package com.realme.tvremote.protocol

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoTest {
    @Test
    fun framesAndParsesNestedRemoteFields() {
        val payload = Proto.message(10 to Proto.message(1 to 22, 2 to 3), 2 to "remote")
        val parsed = Proto.parse(Proto.readFrame(ByteArrayInputStream(Proto.frame(payload))))

        assertEquals(22L, parsed.nested(10).long(1))
        assertEquals(3L, parsed.nested(10).long(2))
        assertEquals("remote", parsed.text(2))
    }

    @Test
    fun encodesNegativeIntAsProtobufInt32Varint() {
        val payload = Proto.message(1 to -1)

        assertArrayEquals(byteArrayOf(0x08, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01), payload)
    }
}