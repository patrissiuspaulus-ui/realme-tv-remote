package com.realme.tvremote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import com.realme.tvremote.discovery.TvDevice
import com.realme.tvremote.discovery.TvDiscovery
import com.realme.tvremote.protocol.ClientIdentity
import com.realme.tvremote.protocol.PairingSession
import com.realme.tvremote.protocol.RemoteClient
import com.realme.tvremote.protocol.TvKey
import com.realme.tvremote.ui.RemoteScreen
import com.realme.tvremote.ui.RemoteScreenState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = mutableListOf<TvDevice>()
    private var selectedDevice: TvDevice? = null
    private var discovery: TvDiscovery? = null
    @Volatile private var remote: RemoteClient? = null
    private var pairing: PairingSession? = null
    private var identity: ClientIdentity? = null
    private var searching = false
    private var busy = false
    private var statusText = "Belum terhubung"
    private var statusIsError = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryTimeout: Runnable? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListening = false
    private var pendingVoiceLanguage = "id-ID"
    private lateinit var screen: RemoteScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        screen = RemoteScreen(
            context = this,
            onScan = { startDiscovery() },
            onConnect = {
                if (isConnected()) disconnectFromTv() else connectSelectedDevice()
            },
            onSelectDevice = { device ->
                selectedDevice = device
                render()
            },
            onRemoteKey = { key -> sendKey(key) },
            onKeyboard = { showKeyboardDialog() },
            onSettings = { showSettingsDialog() },
            onVoiceSearch = { language -> startVoiceSearch(language) },
            onSendSearch = { query -> sendVoiceSearch(query) },
        )
        setContentView(screen.root)
        discovery = TvDiscovery(this) { found ->
            runOnUiThread {
                devices.clear()
                devices.addAll(found)
                if (selectedDevice == null || devices.none { it.host == selectedDevice?.host }) {
                    selectedDevice = devices.firstOrNull()
                }
                render()
            }
        }
        render()
        startDiscovery()
    }

    override fun onDestroy() {
        discoveryTimeout?.let(mainHandler::removeCallbacks)
        discovery?.stop()
        pairing?.close()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        remote?.disconnect()
        multicastLock?.let { if (it.isHeld) it.release() }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition(pendingVoiceLanguage)
        } else {
            voiceListening = false
            screen.setVoiceListening(false)
            screen.setVoiceMessage("Izin mikrofon ditolak. Izinkan mikrofon untuk memakai pencarian suara.")
            setStatus("Izin mikrofon ditolak. Aktifkan izin di Pengaturan aplikasi.", error = true)
        }
    }

    private fun startVoiceSearch(language: String) {
        if (voiceListening) {
            speechRecognizer?.stopListening()
            return
        }
        pendingVoiceLanguage = language
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            screen.setVoiceMessage("Izinkan akses mikrofon untuk mulai mencari dengan suara.")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startVoiceRecognition(language)
    }

    private fun startVoiceRecognition(language: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            screen.setVoiceMessage("Layanan pengenalan suara tidak tersedia di perangkat ini.")
            setStatus("Speech Recognizer tidak tersedia.", error = true)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (language == "id-ID") "Ucapkan yang ingin dicari di TV" else "Say what you want to search on TV",
            )
        }
        screen.setVoiceSearchResult("")
        screen.setVoiceMessage(if (language == "id-ID") "Menyiapkan mikrofon..." else "Preparing microphone...")
        try {
            val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                speechRecognizer = it
                it.setRecognitionListener(createRecognitionListener())
            }
            recognizer.startListening(intent)
        } catch (error: Exception) {
            voiceListening = false
            screen.setVoiceListening(false)
            screen.setVoiceMessage("Mikrofon tidak dapat dimulai. Coba lagi.")
            setStatus(error.message ?: "Pengenalan suara gagal dimulai.", error = true)
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            voiceListening = true
            screen.setVoiceListening(true)
            setStatus(if (pendingVoiceLanguage == "id-ID") "Mendengarkan pencarian..." else "Listening for your search...")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            voiceListening = false
            screen.setVoiceListening(false)
            screen.setVoiceMessage("Memproses suara...")
        }

        override fun onError(error: Int) {
            voiceListening = false
            screen.setVoiceListening(false)
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Suara tidak terdeteksi. Dekatkan ponsel dan coba bicara lagi."
                SpeechRecognizer.ERROR_AUDIO ->
                    "Mikrofon tidak menangkap suara. Periksa mikrofon lalu coba lagi."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Izin mikrofon ditolak. Izinkan mikrofon di Pengaturan aplikasi."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Layanan suara bermasalah. Periksa koneksi internet lalu coba lagi."
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "Bahasa ini tidak tersedia pada layanan suara perangkat."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Pengenalan suara sedang sibuk. Coba beberapa saat lagi."
                else -> "Pengenalan suara gagal (kode $error). Ketuk mikrofon untuk mencoba lagi."
            }
            screen.setVoiceMessage(message)
            setStatus(message, error = true)
        }

        override fun onResults(results: Bundle?) {
            voiceListening = false
            screen.setVoiceListening(false)
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (transcript.isEmpty()) {
                val message = "Suara tidak terdeteksi. Coba bicara lebih jelas."
                screen.setVoiceMessage(message)
                setStatus(message, error = true)
            } else {
                screen.setVoiceSearchResult(transcript)
                screen.setVoiceMessage("Hasil siap. Periksa teks, lalu ketuk KIRIM KE TV.")
                setStatus("Pencarian suara siap dikirim.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) screen.setVoiceSearchResult(partial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun sendVoiceSearch(query: String) {
        val text = query.trim()
        if (text.isEmpty()) {
            screen.setVoiceMessage("Ucapkan atau ketik kata yang ingin dicari terlebih dahulu.")
            setStatus("Kata pencarian masih kosong.", error = true)
            return
        }
        val client = remote
        if (client == null || !client.isConnected()) {
            screen.setVoiceMessage("Hubungkan TV sebelum mengirim pencarian.")
            setStatus("Hubungkan TV sebelum mengirim pencarian.", error = true)
            return
        }
        runCatching {
            client.sendText(text)
            client.sendKey(TvKey.SEARCH)
        }.onSuccess {
            screen.setVoiceMessage("Perintah dikirim. Buka kolom pencarian di TV jika belum aktif.")
            setStatus("Pencarian dikirim ke TV.")
        }.onFailure { error ->
            val message = error.message ?: "Pencarian tidak dapat dikirim ke TV."
            screen.setVoiceMessage(message)
            setStatus(message, error = true)
        }
    }

    private fun render() {
        if (!::screen.isInitialized) return
        screen.render(
            RemoteScreenState(
                status = statusText,
                isError = statusIsError,
                connected = isConnected(),
                paired = selectedDevice?.let { ClientIdentity.tvPin(this, it.host) != null } == true,
                busy = busy,
                searching = searching,
                selectedDevice = selectedDevice,
                devices = devices.toList(),
            ),
        )
    }

    private fun startDiscovery() {
        if (searching) return
        try {
            if (multicastLock?.isHeld != true) {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("realme-tv-remote-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            searching = true
            statusText = if (isConnected()) statusText else "Mencari TV di Wi-Fi..."
            statusIsError = false
            render()
            discovery?.start()
            discoveryTimeout?.let(mainHandler::removeCallbacks)
            discoveryTimeout = Runnable {
                searching = false
                discovery?.stop()
                releaseMulticastLock()
                if (!isConnected()) {
                    setStatus(
                        if (devices.isEmpty()) "TV tidak ditemukan. Pastikan TV dan HP memakai Wi-Fi yang sama."
                        else "Pemindaian selesai. Pilih TV untuk melanjutkan.",
                    )
                } else render()
            }.also { mainHandler.postDelayed(it, DISCOVERY_DURATION_MS) }
        } catch (error: Exception) {
            searching = false
            releaseMulticastLock()
            setStatus("Discovery gagal: ${error.message ?: "periksa izin jaringan"}", error = true)
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun connectSelectedDevice() {
        val device = selectedDevice ?: run {
            setStatus("Pilih TV yang ditemukan terlebih dahulu.", error = true)
            return
        }
        val pin = ClientIdentity.tvPin(this, device.host)
        if (pin == null) startPairing(device) else connectToTv(device, pin)
    }

    private fun disconnectFromTv() {
        remote?.disconnect()
        remote = null
        setStatus("Koneksi diputus.")
    }

    private fun showSettingsDialog() {
        val device = selectedDevice
        val actions = if (device == null) arrayOf("TENTANG APLIKASI") else arrayOf("LUPAKAN PAIRING TV", "TENTANG APLIKASI")
        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setItems(actions) { _, selected ->
                if (device != null && selected == 0) {
                    disconnectFromTv()
                    ClientIdentity.forgetTv(this, device.host)
                    setStatus("Pairing lokal dihapus. Pairing ulang diperlukan untuk menyambungkan TV.")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Realme TV Remote")
                        .setMessage("Android TV Remote Protocol v2 · API minimum 23")
                        .setPositiveButton("TUTUP", null)
                        .show()
                }
            }
            .setNegativeButton("TUTUP", null)
            .show()
    }

    private fun startPairing(device: TvDevice) {
        busy = true
        setStatus("Memulai pairing dengan ${device.name}...")
        worker.execute {
            try {
                val clientIdentity = identity ?: ClientIdentity.load(this).also { identity = it }
                val session = PairingSession(this, clientIdentity, device.host, "Realme TV Remote")
                pairing = session
                val tvName = session.start()
                runOnUiThread {
                    busy = false
                    statusText = "Masukkan kode yang tampil di $tvName"
                    statusIsError = false
                    render()
                    showPairingDialog(device)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus(error.message ?: "Pairing tidak dapat dimulai.", error = true)
                }
            }
        }
    }

    private fun showPairingDialog(device: TvDevice) {
        val input = EditText(this).apply {
            hint = "XXXXXX"
            filters = arrayOf(InputFilter.LengthFilter(6))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            gravity = Gravity.CENTER
            textSize = 23f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Pair dengan ${device.name}")
            .setMessage("Izinkan pairing pada TV, lalu masukkan kode 6 karakter yang muncul di layar.")
            .setView(input)
            .setNegativeButton("BATAL") { _, _ ->
                pairing?.close()
                pairing = null
                busy = false
                setStatus("Pairing dibatalkan.")
            }
            .setPositiveButton("HUBUNGKAN", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val code = input.text.toString()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        busy = true
                        setStatus("Memverifikasi kode pairing...")
                        worker.execute {
                            try {
                                pairing?.finish(code) ?: error("Sesi pairing sudah berakhir.")
                                pairing = null
                                runOnUiThread {
                                    busy = false
                                    dialog.dismiss()
                                    val pin = ClientIdentity.tvPin(this, device.host)
                                    if (pin == null) setStatus("Pairing selesai, tetapi pin TV tidak tersimpan.", true)
                                    else connectToTv(device, pin)
                                }
                            } catch (error: Exception) {
                                runOnUiThread {
                                    busy = false
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    setStatus(error.message ?: "Kode pairing ditolak.", error = true)
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun connectToTv(device: TvDevice, pin: String) {
        remote?.disconnect()
        remote = null
        busy = true
        setStatus("Menghubungkan ke ${device.name}...")
        worker.execute {
            try {
                val clientIdentity = identity ?: ClientIdentity.load(this).also { identity = it }
                lateinit var createdClient: RemoteClient
                createdClient = RemoteClient(
                    identity = clientIdentity,
                    host = device.host,
                    certificatePin = pin,
                    clientName = "Realme TV Remote",
                    listener = object : RemoteClient.Listener {
                        override fun onDisconnected(cause: Throwable?) {
                            runOnUiThread {
                                if (remote === createdClient) {
                                    remote = null
                                    busy = false
                                    setStatus("Koneksi TV terputus${cause?.message?.let { ": $it" }.orEmpty()}", error = true)
                                }
                            }
                        }
                    },
                )
                remote = createdClient
                createdClient.connect()
                runOnUiThread {
                    busy = false
                    statusText = "Terhubung ke ${device.name}"
                    statusIsError = false
                    render()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    remote = null
                    val message = if (error is javax.net.ssl.SSLException) {
                        "Sertifikat TV berubah. Pair ulang untuk memverifikasi perangkat."
                    } else error.message ?: "Koneksi TV gagal."
                    setStatus(message, error = true)
                }
            }
        }
    }

    private fun showKeyboardDialog() {
        if (!requireRemote()) return
        val input = EditText(this).apply {
            hint = "Ketik teks untuk TV"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
        }
        AlertDialog.Builder(this)
            .setTitle("Keyboard TV")
            .setView(input)
            .setNegativeButton("BATAL", null)
            .setPositiveButton("KIRIM") { _, _ ->
                val text = input.text.toString()
                runCatching { remote?.sendText(text) }
                    .onFailure { setStatus(it.message ?: "Teks tidak dapat dikirim.", error = true) }
            }
            .show()
    }

    private fun sendKey(key: TvKey) {
        val client = remote
        if (client == null || !client.isConnected()) {
            setStatus("Hubungkan TV sebelum mengirim perintah.", error = true)
            return
        }
        runCatching { client.sendKey(key) }
            .onFailure { setStatus(it.message ?: "Perintah tidak dapat dikirim.", error = true) }
    }

    private fun requireRemote(): Boolean {
        if (remote?.isConnected() == true) return true
        setStatus("Hubungkan TV sebelum menggunakan remote.", error = true)
        return false
    }

    private fun setStatus(message: String, error: Boolean = false) {
        statusText = message
        statusIsError = error
        runOnUiThread { render() }
    }

    private fun isConnected(): Boolean = remote?.isConnected() == true
    companion object {
        private const val REQUEST_RECORD_AUDIO = 410
        private const val COLOR_BACKGROUND = 0xff090a0d.toInt()
        private const val COLOR_TEXT = 0xfff4f1e9.toInt()
        private const val COLOR_MUTED = 0xff9699a1.toInt()
        private const val DISCOVERY_DURATION_MS = 12_000L
    }
}