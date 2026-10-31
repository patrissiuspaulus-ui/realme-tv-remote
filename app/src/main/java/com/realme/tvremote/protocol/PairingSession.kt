package com.realme.tvremote.protocol

import android.content.Context
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingSession(
    private val context: Context,
    private val identity: ClientIdentity,
    private val host: String,
    private val clientName: String,
    private val timeoutMs: Int = 10_000,
) {
    private var socket: SSLSocket? = null
    private var serverCertificate: X509Certificate? = null
    private var input: BufferedInputStream? = null

    fun start(): String {
        close()
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, PAIRING_PORT), timeoutMs)
            raw.soTimeout = timeoutMs
            val tls = identity.sslContext().socketFactory
                .createSocket(raw, host, PAIRING_PORT, true) as SSLSocket
            socket = tls
            tls.startHandshake()
            serverCertificate = tls.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: error("TV did not present an X.509 certificate")
            input = BufferedInputStream(tls.inputStream)

            send(10, Proto.message(1 to "atvremote", 2 to clientName))
            val acknowledgement = expect(11)
            val encoding = Proto.message(1 to HEXADECIMAL_ENCODING, 2 to PAIRING_CODE_LENGTH)
            send(20, Proto.message(1 to encoding, 3 to INPUT_ROLE))
            expect(20)
            send(30, Proto.message(1 to encoding, 2 to INPUT_ROLE))
            expect(31)
            return acknowledgement.text(1).ifBlank { host }
        } catch (error: Exception) {
            close()
            throw PairingException(error.message ?: "Pairing handshake failed", error)
        }
    }

    fun finish(rawCode: String) {
        val code = rawCode.trim().uppercase()
        require(code.length == PAIRING_CODE_LENGTH && code.all { it in "0123456789ABCDEF" }) {
            "Masukkan 6 karakter kode yang tampil di TV."
        }
        val certificate = serverCertificate ?: throw PairingException("Mulai pairing terlebih dahulu.")
        val serverKey = certificate.publicKey as? RSAPublicKey
            ?: throw PairingException("Kunci sertifikat TV tidak didukung.")
        val digest = pairingDigest(identity.publicKey, serverKey, code)
        require((digest[0].toInt() and 0xff) == code.take(2).toInt(16)) {
            "Kode tidak cocok. Periksa kode pada layar TV."
        }
        try {
            send(40, Proto.message(1 to digest))
            expect(41)
            IdentityPins.saveTvPin(context, host, certificate)
        } catch (error: Exception) {
            throw PairingException(error.message ?: "TV menolak pairing.", error)
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        serverCertificate = null
    }

    private fun send(fieldNumber: Int, payload: ByteArray) {
        val current = socket ?: throw PairingException("Sesi pairing tidak aktif.")
        val frame = Proto.message(1 to PROTOCOL_VERSION, 2 to STATUS_OK, fieldNumber to payload)
        current.outputStream.apply {
            write(Proto.frame(frame))
            flush()
        }
    }

    private fun expect(fieldNumber: Int): Proto.Fields {
        val response = Proto.parse(Proto.readFrame(input ?: throw PairingException("Sesi pairing tidak aktif.")))
        if (response.long(2) != STATUS_OK.toLong()) throw PairingException("TV menolak pairing.")
        if (fieldNumber !in response) throw PairingException("Balasan pairing dari TV tidak sesuai.")
        return response.nested(fieldNumber)
    }

    class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val PAIRING_PORT = 6467
        private const val PROTOCOL_VERSION = 2
        private const val STATUS_OK = 200
        private const val HEXADECIMAL_ENCODING = 3
        private const val INPUT_ROLE = 1
        private const val PAIRING_CODE_LENGTH = 6

        internal fun pairingDigest(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                update(unsigned(client.modulus))
                update(unsigned(client.publicExponent))
                update(unsigned(server.modulus))
                update(unsigned(server.publicExponent))
                update(ByteArray(2) { index -> code.substring(2 + index * 2, 4 + index * 2).toInt(16).toByte() })
                digest()
            }

        private fun unsigned(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.firstOrNull() == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}

object IdentityPins {
    fun saveTvPin(context: Context, host: String, certificate: X509Certificate) =
        ClientIdentity.saveTvPin(context, host, certificate)

    fun tvPin(context: Context, host: String): String? = ClientIdentity.tvPin(context, host)
}