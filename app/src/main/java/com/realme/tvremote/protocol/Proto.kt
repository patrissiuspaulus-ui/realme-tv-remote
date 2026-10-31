package com.realme.tvremote.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

internal object Proto {
    private const val MAX_FRAME_SIZE = 1_048_576

    fun message(vararg fields: Pair<Int, Any?>): ByteArray {
        val output = ByteArrayOutputStream()
        fields.forEach { (number, value) ->
            require(number > 0) { "Protobuf field numbers must be positive" }
            when (value) {
                null -> Unit
                is Boolean -> {
                    output.write(varint((number shl 3).toLong()))
                    output.write(varint(if (value) 1 else 0))
                }
                is Int -> {
                    output.write(varint((number shl 3).toLong()))
                    output.write(varint(value.toLong()))
                }
                is Long -> {
                    output.write(varint((number shl 3).toLong()))
                    output.write(varint(value))
                }
                is String -> output.writeBytesField(number, value.toByteArray(Charsets.UTF_8))
                is ByteArray -> output.writeBytesField(number, value)
                else -> error("Unsupported protobuf value: ${value::class.java.name}")
            }
        }
        return output.toByteArray()
    }

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) { "Protobuf frame is too large" }
        return varint(payload.size.toLong()) + payload
    }

    fun readFrame(input: InputStream): ByteArray {
        val size = readVarint(input).toInt()
        require(size in 1..MAX_FRAME_SIZE) { "Invalid protobuf frame size" }
        val payload = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(payload, offset, size - offset)
            if (count < 0) throw EOFException("Remote closed the connection")
            offset += count
        }
        return payload
    }

    fun parse(payload: ByteArray): Fields {
        val parsed = mutableMapOf<Int, MutableList<Any>>()
        var offset = 0
        while (offset < payload.size) {
            val key = readVarint(payload, offset).also { offset = it.second }.first
            val number = (key ushr 3).toInt()
            require(number > 0) { "Invalid protobuf field number" }
            val value: Any = when ((key and 7).toInt()) {
                0 -> readVarint(payload, offset).also { offset = it.second }.first
                2 -> {
                    val length = readVarint(payload, offset).also { offset = it.second }.first.toInt()
                    require(length >= 0 && length <= payload.size - offset) { "Invalid protobuf field length" }
                    payload.copyOfRange(offset, offset + length).also { offset += length }
                }
                1 -> payload.copyOfRange(offset, offset + 8).also {
                    require(offset + 8 <= payload.size) { "Truncated fixed64 protobuf field" }
                    offset += 8
                }
                5 -> payload.copyOfRange(offset, offset + 4).also {
                    require(offset + 4 <= payload.size) { "Truncated fixed32 protobuf field" }
                    offset += 4
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
            parsed.getOrPut(number) { mutableListOf() }.add(value)
        }
        return Fields(parsed)
    }

    private fun ByteArrayOutputStream.writeBytesField(number: Int, bytes: ByteArray) {
        write(varint(((number shl 3) or 2).toLong()))
        write(varint(bytes.size.toLong()))
        write(bytes)
    }

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val result = ByteArrayOutputStream()
        while (remaining and -128L != 0L) {
            result.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        result.write(remaining.toInt())
        return result.toByteArray()
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        repeat(10) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Incomplete protobuf frame length")
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint")
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = start
        repeat(10) {
            require(index < bytes.size) { "Truncated protobuf varint" }
            val byte = bytes[index++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to index
            shift += 7
        }
        throw IllegalArgumentException("Malformed protobuf varint")
    }

    class Fields internal constructor(private val values: Map<Int, List<Any>>) {
        operator fun contains(number: Int): Boolean = number in values

        fun long(number: Int, default: Long = 0): Long = values[number]?.firstOrNull() as? Long ?: default

        fun text(number: Int): String =
            (values[number]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()

        fun bytes(number: Int): ByteArray? = values[number]?.firstOrNull() as? ByteArray

        fun nested(number: Int): Fields = bytes(number)?.let(::parse) ?: Fields(emptyMap())
    }
}