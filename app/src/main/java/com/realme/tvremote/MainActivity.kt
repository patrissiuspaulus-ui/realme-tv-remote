package com.realme.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.realme.tvremote.discovery.TvDevice
import com.realme.tvremote.discovery.TvDiscovery
import com.realme.tvremote.protocol.ClientIdentity
import com.realme.tvremote.protocol.PairingSession
import com.realme.tvremote.protocol.RemoteClient
import com.realme.tvremote.protocol.TvKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val devices = mutableListOf<TvDevice>()
    private var selectedDevice: TvDevice? = null
    private var discovery: TvDiscovery? = null
    @Volatile private var remote: RemoteClient? = null
    private var pairing: PairingSession? = null
    private var identity: ClientIdentity? = null
    private var searching = false
    private var statusText = "Belum terhubung"
    private var statusIsError = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var deviceContainer: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var connectButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        discovery = TvDiscovery(this) { found ->
            runOnUiThread {
                devices.clear()
                devices.addAll(found)
                if (selectedDevice == null || devices.none { it.host == selectedDevice?.host }) {
                    selectedDevice = devices.firstOrNull()
                }
                renderDevices()
                updateConnectionSummary()
            }
        }
        render()
        startDiscovery()
    }

    override fun onDestroy() {
        discovery?.stop()
        pairing?.close()
        remote?.disconnect()
        multicastLock?.let { if (it.isHeld) it.release() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        scroll.addView(content)

        content.addView(text("REALME", 13, COLOR_GOLD, bold = true, spacing = 2f))
        content.addView(text("TV REMOTE", 27, COLOR_TEXT, bold = true), margin(top = 3, bottom = 4))
        val subtitle = text("ANDROID TV CONTROL", 10, COLOR_MUTED, bold = true, spacing = 1.4f)
        content.addView(subtitle, margin(bottom = 20))

        val connectionPanel = verticalPanel()
        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val indicator = View(this).apply {
            background = shape(if (isConnected()) COLOR_GOLD else COLOR_DISCONNECTED, 7)
        }
        statusRow.addView(indicator, LinearLayout.LayoutParams(dp(9), dp(9)))
        statusLabel = text(statusText, 14, COLOR_TEXT, bold = true).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectionPanel.addView(statusRow)
        val deviceSummary = selectedDevice?.let { "${it.name}  ·  ${it.host}" } ?: "Pilih TV di jaringan Wi-Fi"
        connectionPanel.addView(text(deviceSummary, 12, COLOR_MUTED), margin(top = 8))
        content.addView(connectionPanel, margin(bottom = 14))

        val sectionHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        sectionHeader.addView(text("PERANGKAT", 11, COLOR_GOLD, bold = true, spacing = 1f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scanButton = smallButton(if (searching) "MENCARI..." else "CARI TV") {
            startDiscovery()
        }
        sectionHeader.addView(scanButton)
        content.addView(sectionHeader, margin(bottom = 9))

        deviceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(deviceContainer, margin(bottom = 10))
        connectButton = wideButton(connectionActionLabel()) { connectSelectedDevice() }
        content.addView(connectButton, margin(bottom = 24))

        content.addView(text("REMOTE", 11, COLOR_GOLD, bold = true, spacing = 1f), margin(bottom = 10))
        content.addView(buildQuickControls(), margin(bottom = 10))
        content.addView(buildDpad(), margin(bottom = 10))
        content.addView(buildChannelAndKeyboard())
        setContentView(scroll)
        renderDevices()
        updateConnectionSummary()
    }

    private fun buildQuickControls(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(remoteButton("⏻", "Power", TvKey.POWER, isPower = true), weightParams())
        row.addView(remoteButton("−", "Volume", TvKey.VOLUME_DOWN), weightParams())
        row.addView(remoteButton("+", "Volume", TvKey.VOLUME_UP), weightParams())
        row.addView(remoteButton("◖", "Mute", TvKey.MUTE), weightParams())
        return row
    }

    private fun buildDpad(): View {
        val panel = verticalPanel().apply { gravity = Gravity.CENTER }
        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            useDefaultMargins = false
        }
        val empty = View(this)
        grid.addView(empty, gridCell())
        grid.addView(directionButton("↑", TvKey.UP), gridCell())
        grid.addView(emptySpace(), gridCell())
        grid.addView(directionButton("←", TvKey.LEFT), gridCell())
        grid.addView(okButton(), gridCell())
        grid.addView(directionButton("→", TvKey.RIGHT), gridCell())
        grid.addView(emptySpace(), gridCell())
        grid.addView(directionButton("↓", TvKey.DOWN), gridCell())
        grid.addView(emptySpace(), gridCell())
        panel.addView(grid)
        val navRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        navRow.addView(textButton("HOME", TvKey.HOME), weightParams())
        navRow.addView(textButton("BACK", TvKey.BACK), weightParams())
        navRow.addView(textButton("MENU", TvKey.MENU), weightParams())
        panel.addView(navRow, margin(top = 13))
        return panel
    }

    private fun buildChannelAndKeyboard(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val channelRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        channelRow.addView(textButton("CH −", TvKey.CHANNEL_DOWN), weightParams())
        channelRow.addView(text("CHANNEL", 10, COLOR_MUTED, bold = true, spacing = 1f).apply {
            gravity = Gravity.CENTER
        }, weightParams())
        channelRow.addView(textButton("CH +", TvKey.CHANNEL_UP), weightParams())
        column.addView(channelRow)
        val keyboard = wideButton("⌨   KEYBOARD TV") { showKeyboardDialog() }
        column.addView(keyboard, margin(top = 10))
        return column
    }

    private fun renderDevices() {
        val container = deviceContainer ?: return
        container.removeAllViews()
        if (devices.isEmpty()) {
            val hint = if (searching) "Mencari perangkat Android TV di Wi-Fi..." else "Tidak ada TV ditemukan"
            container.addView(text(hint, 13, COLOR_MUTED).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = shape(COLOR_SURFACE, 8)
            })
            return
        }
        devices.forEach { device ->
            val selected = selectedDevice?.host == device.host
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = shape(if (selected) COLOR_SELECTED else COLOR_SURFACE, 8)
                setOnClickListener {
                    selectedDevice = device
                    render()
                }
            }
            row.addView(text(device.name, 14, COLOR_TEXT, bold = true))
            row.addView(text(device.host, 11, COLOR_MUTED), margin(top = 3))
            container.addView(row, margin(bottom = 7))
        }
    }

    private fun startDiscovery() {
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
            render()
            discovery?.start()
        } catch (error: Exception) {
            searching = false
            setStatus("Discovery gagal: ${error.message ?: "periksa izin jaringan"}", error = true)
        }
    }

    private fun connectSelectedDevice() {
        val device = selectedDevice ?: run {
            setStatus("Pilih TV yang ditemukan terlebih dahulu.", error = true)
            return
        }
        val pin = ClientIdentity.tvPin(this, device.host)
        if (pin == null) startPairing(device) else connectToTv(device, pin)
    }

    private fun startPairing(device: TvDevice) {
        setStatus("Memulai pairing dengan ${device.name}...")
        worker.execute {
            try {
                val clientIdentity = identity ?: ClientIdentity.load(this).also { identity = it }
                val session = PairingSession(this, clientIdentity, device.host, "Realme TV Remote")
                pairing = session
                val tvName = session.start()
                runOnUiThread {
                    statusText = "Masukkan kode yang tampil di $tvName"
                    statusIsError = false
                    render()
                    showPairingDialog(device)
                }
            } catch (error: Exception) {
                runOnUiThread { setStatus(error.message ?: "Pairing tidak dapat dimulai.", error = true) }
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
                setStatus("Pairing dibatalkan.")
            }
            .setPositiveButton("HUBUNGKAN", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val code = input.text.toString()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        setStatus("Memverifikasi kode pairing...")
                        worker.execute {
                            try {
                                pairing?.finish(code) ?: error("Sesi pairing sudah berakhir.")
                                pairing = null
                                runOnUiThread {
                                    dialog.dismiss()
                                    val pin = ClientIdentity.tvPin(this, device.host)
                                    if (pin == null) setStatus("Pairing selesai, tetapi pin TV tidak tersimpan.", true)
                                    else connectToTv(device, pin)
                                }
                            } catch (error: Exception) {
                                runOnUiThread {
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
                                    setStatus("Koneksi TV terputus${cause?.message?.let { ": $it" }.orEmpty()}", error = true)
                                }
                            }
                        }
                    },
                )
                remote = createdClient
                createdClient.connect()
                runOnUiThread {
                    statusText = "Terhubung ke ${device.name}"
                    statusIsError = false
                    render()
                }
            } catch (error: Exception) {
                runOnUiThread {
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
        runOnUiThread { updateConnectionSummary() }
    }

    private fun updateConnectionSummary() {
        statusLabel?.text = statusText
        statusLabel?.setTextColor(if (statusIsError) COLOR_ERROR else COLOR_TEXT)
        connectButton?.text = connectionActionLabel()
    }

    private fun connectionActionLabel(): String = when {
        isConnected() -> "PUTUSKAN KONEKSI"
        ClientIdentity.tvPin(this, selectedDevice?.host.orEmpty()) != null -> "HUBUNGKAN"
        else -> "PAIR DENGAN TV"
    }

    private fun isConnected(): Boolean = remote?.isConnected() == true

    private fun remoteButton(symbol: String, description: String, key: TvKey, isPower: Boolean = false): View {
        val button = Button(this).apply {
            text = symbol
            contentDescription = description
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isPower) COLOR_BACKGROUND else COLOR_GOLD)
            background = shape(if (isPower) COLOR_GOLD else COLOR_SURFACE, 26)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(58)
            minimumHeight = dp(58)
            setPadding(0, 0, 0, 0)
            setOnClickListener { sendKey(key) }
        }
        return button
    }

    private fun directionButton(label: String, key: TvKey) = Button(this).apply {
        text = label
        textSize = 23f
        setTextColor(COLOR_TEXT)
        background = shape(COLOR_BUTTON, 30)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(58)
        minimumHeight = dp(58)
        setPadding(0, 0, 0, 0)
        setOnClickListener { sendKey(key) }
    }

    private fun okButton() = Button(this).apply {
        text = "OK"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_BACKGROUND)
        background = shape(COLOR_GOLD, 34)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(58)
        minimumHeight = dp(58)
        setPadding(0, 0, 0, 0)
        setOnClickListener { sendKey(TvKey.OK) }
    }

    private fun textButton(label: String, key: TvKey) = Button(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT)
        background = shape(COLOR_SURFACE, 8)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(46)
        minimumHeight = dp(46)
        setOnClickListener { sendKey(key) }
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_GOLD)
        background = shape(COLOR_SURFACE, 8)
        setPadding(dp(11), dp(4), dp(11), dp(4))
        minHeight = dp(38)
        setOnClickListener { action() }
    }

    private fun wideButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_BACKGROUND)
        background = shape(COLOR_GOLD, 10)
        minHeight = dp(52)
        setOnClickListener {
            if (isConnected()) {
                remote?.disconnect()
                remote = null
                setStatus("Koneksi diputus.")
            } else action()
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false, spacing: Float = 0f) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = spacing
    }

    private fun verticalPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = shape(COLOR_SURFACE, 8)
    }

    private fun emptySpace() = View(this).apply { visibility = View.INVISIBLE }

    private fun shape(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun gridCell() = GridLayout.LayoutParams().apply {
        width = dp(62)
        height = dp(58)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
        setMargins(dp(3), 0, dp(3), 0)
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_BACKGROUND = Color.rgb(11, 12, 14)
        private val COLOR_SURFACE = Color.rgb(27, 29, 32)
        private val COLOR_BUTTON = Color.rgb(40, 42, 44)
        private val COLOR_SELECTED = Color.rgb(49, 45, 34)
        private val COLOR_GOLD = Color.rgb(218, 183, 104)
        private val COLOR_TEXT = Color.rgb(245, 242, 232)
        private val COLOR_MUTED = Color.rgb(153, 153, 148)
        private val COLOR_DISCONNECTED = Color.rgb(111, 111, 108)
        private val COLOR_ERROR = Color.rgb(239, 124, 112)
    }
}