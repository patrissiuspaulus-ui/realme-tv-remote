package com.realme.tvremote.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

class ClientIdentity private constructor(
    private val privateKey: java.security.PrivateKey,
    val certificate: X509Certificate,
) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun sslContext(pinnedFingerprint: String? = null): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(ClientKeyManager()), arrayOf(PinningTrustManager(pinnedFingerprint)), SecureRandom())
    }

    private inner class ClientKeyManager : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(CLIENT_ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = CLIENT_ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = CLIENT_ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = null
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
    }

    private class PinningTrustManager(private val expected: String?) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            require(!chain.isNullOrEmpty()) { "TV did not present a certificate" }
            if (expected != null) {
                val actual = fingerprint(chain[0])
                require(MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                    "TV certificate changed; pair with this TV again"
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val KEY_ALIAS = "realme-tv-remote-storage-key"
        private const val IDENTITY_PREFS = "remote_identity"
        private const val PIN_PREFS = "tv_certificate_pins"
        private const val IDENTITY_KEY = "encrypted_identity"
        private const val CLIENT_ALIAS = "realme-tv-remote-client"

        fun load(context: Context): ClientIdentity {
            val preferences = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            val stored = preferences.getString(IDENTITY_KEY, null)
            if (stored != null) {
                runCatching { decryptIdentity(stored, storageKey()).let(::decodeIdentity) }
                    .getOrNull()?.let { return it }
            }
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certificate = createCertificate(keyPair)
            val identity = ClientIdentity(keyPair.private, certificate)
            val encrypted = encryptIdentity(encodeIdentity(identity), storageKey())
            check(preferences.edit().putString(IDENTITY_KEY, encrypted).commit()) {
                "Unable to save the encrypted TV remote identity"
            }
            return identity
        }

        fun saveTvPin(context: Context, host: String, certificate: X509Certificate) {
            val key = pinKey(host)
            val success = context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, fingerprint(certificate)).commit()
            check(success) { "Unable to save TV certificate pin" }
        }

        fun tvPin(context: Context, host: String): String? =
            context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE).getString(pinKey(host), null)

        fun forgetTv(context: Context, host: String) {
            context.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)
                .edit().remove(pinKey(host)).apply()
        }

        private fun pinKey(host: String) = "tv_${Base64.encodeToString(host.toByteArray(), Base64.NO_WRAP)}"

        private fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }

        private fun storageKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }

        private fun encryptIdentity(clear: ByteArray, key: SecretKey): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(clear)
            val packed = cipher.iv + encrypted
            return Base64.encodeToString(packed, Base64.NO_WRAP)
        }

        private fun decryptIdentity(encoded: String, key: SecretKey): ByteArray {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_NONCE_BYTES) { "Invalid encrypted identity" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(0, GCM_NONCE_BYTES)))
            return cipher.doFinal(packed, GCM_NONCE_BYTES, packed.size - GCM_NONCE_BYTES)
        }

        private fun encodeIdentity(identity: ClientIdentity): ByteArray = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                val key = identity.privateKey.encoded
                val certificate = identity.certificate.encoded
                output.writeInt(key.size)
                output.write(key)
                output.writeInt(certificate.size)
                output.write(certificate)
            }
            buffer.toByteArray()
        }

        private fun decodeIdentity(bytes: ByteArray): ClientIdentity = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val privateSize = input.readInt().also { require(it in 1..MAX_IDENTITY_BYTES) }
            val privateBytes = ByteArray(privateSize).also(input::readFully)
            val certificateSize = input.readInt().also { require(it in 1..MAX_IDENTITY_BYTES) }
            val certificateBytes = ByteArray(certificateSize).also(input::readFully)
            require(input.available() == 0) { "Unexpected data in encrypted identity" }
            val privateKey = java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
            ClientIdentity(privateKey, certificate)
        }

        internal fun createCertificate(keyPair: KeyPair): X509Certificate {
            val algorithm = sequence(oid(1, 2, 840, 113549, 1, 1, 11), nullValue())
            val name = sequence(set(sequence(oid(2, 5, 4, 3), utf8("Realme TV Remote"))))
            val validity = sequence(utcTime(Date(System.currentTimeMillis() - DAY_MS)), utcTime(Date(System.currentTimeMillis() + CERTIFICATE_LIFETIME_MS)))
            val tbs = sequence(
                tagged(0xa0, integer(BigInteger.valueOf(2))),
                integer(BigInteger.ONE),
                algorithm,
                name,
                validity,
                name,
                keyPair.public.encoded,
            )
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(keyPair.private)
            signer.update(tbs)
            val signed = sequence(tbs, algorithm, bitString(signer.sign()))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(signed)) as X509Certificate
        }

        private fun sequence(vararg values: ByteArray) = der(0x30, values.fold(ByteArrayOutputStream()) { out, bytes -> out.apply { write(bytes) } }.toByteArray())
        private fun set(value: ByteArray) = der(0x31, value)
        private fun integer(value: BigInteger) = der(0x02, value.toByteArray())
        private fun utf8(value: String) = der(0x0c, value.toByteArray(Charsets.UTF_8))
        private fun nullValue() = byteArrayOf(0x05, 0x00)
        private fun bitString(value: ByteArray) = der(0x03, byteArrayOf(0) + value)
        private fun tagged(tag: Int, value: ByteArray) = der(tag, value)
        private fun oid(vararg arcs: Int): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(arcs[0] * 40 + arcs[1])
            arcs.drop(2).forEach { arc ->
                var value = arc
                val bytes = ArrayList<Int>()
                bytes.add(value and 0x7f)
                value = value ushr 7
                while (value > 0) {
                    bytes.add((value and 0x7f) or 0x80)
                    value = value ushr 7
                }
                bytes.asReversed().forEach(output::write)
            }
            return der(0x06, output.toByteArray())
        }

        private fun utcTime(date: Date): ByteArray {
            val formatted = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(date)
            return der(0x17, formatted.toByteArray(Charsets.US_ASCII))
        }

        private fun der(tag: Int, content: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(tag)
            if (content.size < 128) {
                output.write(content.size)
            } else {
                var length = content.size
                val encodedLength = ArrayList<Int>()
                while (length > 0) {
                    encodedLength.add(length and 0xff)
                    length = length ushr 8
                }
                output.write(0x80 or encodedLength.size)
                encodedLength.asReversed().forEach(output::write)
            }
            output.write(content)
            return output.toByteArray()
        }

        private const val GCM_NONCE_BYTES = 12
        private const val MAX_IDENTITY_BYTES = 16_384
        private const val DAY_MS = 86_400_000L
        private const val CERTIFICATE_LIFETIME_MS = 365L * DAY_MS * 20
    }
}