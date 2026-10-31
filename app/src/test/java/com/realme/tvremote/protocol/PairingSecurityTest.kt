package com.realme.tvremote.protocol

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingSecurityTest {
    @Test
    fun clientCertificateIsValidAndSelfSigned() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val certificate = ClientIdentity.createCertificate(pair)

        certificate.verify(pair.public)
        assertEquals(pair.public, certificate.publicKey)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
    }

    @Test
    fun pairingDigestChecksumMatchesTheSixCharacterCode() {
        val clientKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        val serverKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public as RSAPublicKey
        val digest = PairingSession.pairingDigest(clientKey, serverKey, "001234")
        val validCode = "%02X1234".format(digest[0].toInt() and 0xff)
        val verified = PairingSession.pairingDigest(clientKey, serverKey, validCode)

        assertArrayEquals(digest, verified)
        assertFalse(digest.contentEquals(PairingSession.pairingDigest(serverKey, clientKey, validCode)))
    }
}