package com.realme.tvremote.protocol

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

class RemoteClient(
    private val identity: ClientIdentity,
    private val host: String,
    private val certificatePin: String,
    private val clientName: String,
    private val listener: Listener,
    private val timeoutMs: Int = 8_000,
) {
    interface Listener {
        fun onDisconnected(cause: Throwable?)
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val closing = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    @Volatile private var connected = false
    @Volatile private var imeCounter = 0L
    @Volatile private var imeFieldCounter = 0L

    fun connect() {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, REMOTE_PORT), timeoutMs)
        raw.soTimeout = timeoutMs
        val tls = identity.sslContext(certificatePin).socketFactory
            .createSocket(raw, host, REMOTE_PORT, true) as SSLSocket
        socket = tls
        tls.startHandshake()
        val input = BufferedInputStream(tls.inputStream)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!connected && System.currentTimeMillis() < deadline) {
                handle(Proto.parse(Proto.readFrame(input)))
            }
            check(connected) { "TV tidak menyelesaikan handshake remote." }
        } catch (error: Exception) {
            disconnect()
            throw error
        }
        tls.soTimeout = 0
        Thread({ readLoop(tls, input) }, "tv-remote-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun isConnected(): Boolean = connected && socket?.isClosed == false

    fun sendKey(key: TvKey) {
        sendMessage(Proto.message(10 to Proto.message(1 to key.androidCode, 2 to SHORT_PRESS)))
    }

    fun sendText(text: String) {
        require(text.length <= MAX_TEXT_LENGTH) { "Teks terlalu panjang untuk dikirim." }
        val cursor = text.length - 1
        val field = Proto.message(1 to cursor, 2 to cursor, 3 to text)
        val edit = Proto.message(1 to 1, 2 to field)
        sendMessage(Proto.message(21 to Proto.message(1 to imeCounter, 2 to imeFieldCounter, 3 to edit)))
    }

    fun disconnect() {
        closing.set(true)
        connected = false
        runCatching { socket?.close() }
        socket = null
        writer.shutdownNow()
    }

    private fun readLoop(current: SSLSocket, input: BufferedInputStream) {
        var failure: Throwable? = null
        try {
            while (!closing.get()) handle(Proto.parse(Proto.readFrame(input)))
        } catch (error: Throwable) {
            if (!closing.get()) failure = error
        } finally {
            connected = false
            runCatching { current.close() }
            if (!closing.get()) listener.onDisconnected(failure)
        }
    }

    private fun handle(message: Proto.Fields) {
        when {
            1 in message -> {
                val info = Proto.message(
                    1 to clientName,
                    2 to "Android",
                    3 to 1,
                    4 to "1",
                    5 to "atvremote",
                    6 to "1.0.0",
                )
                sendHandshakeMessage(Proto.message(1 to Proto.message(1 to SUPPORTED_FEATURES, 2 to info)))
            }
            2 in message -> {
                sendHandshakeMessage(Proto.message(2 to Proto.message(1 to SUPPORTED_FEATURES)))
                connected = true
            }
            8 in message -> sendMessage(Proto.message(9 to Proto.message(1 to message.nested(8).long(1))))
            21 in message -> {
                val edit = message.nested(21)
                imeCounter = edit.long(1, imeCounter)
                imeFieldCounter = edit.long(2, imeFieldCounter)
            }
            3 in message -> throw IOException("TV melaporkan error protokol remote.")
        }
    }

    private fun sendMessage(payload: ByteArray) {
        val current = socket ?: throw IOException("Belum terhubung ke TV.")
        writer.execute {
            if (!closing.get()) runCatching {
                synchronized(current) {
                    current.outputStream.apply {
                        write(Proto.frame(payload))
                        flush()
                    }
                }
            }.onFailure { error ->
                connected = false
                runCatching { current.close() }
                if (!closing.get()) listener.onDisconnected(error)
            }
        }
    }

    private fun sendHandshakeMessage(payload: ByteArray) {
        val current = socket ?: throw IOException("Belum terhubung ke TV.")
        synchronized(current) {
            current.outputStream.apply {
                write(Proto.frame(payload))
                flush()
            }
        }
    }

    companion object {
        private const val REMOTE_PORT = 6466
        private const val SHORT_PRESS = 3
        private const val SUPPORTED_FEATURES = 1 or 2 or 4 or 32 or 64 or 512
        private const val MAX_TEXT_LENGTH = 2_000
    }
}