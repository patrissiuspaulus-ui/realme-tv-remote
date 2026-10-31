package com.realme.tvremote.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.realme.tvremote.discovery.TvDevice
import com.realme.tvremote.protocol.TvKey

data class RemoteScreenState(
    val status: String,
    val isError: Boolean,
    val connected: Boolean,
    val paired: Boolean,
    val busy: Boolean,
    val searching: Boolean,
    val selectedDevice: TvDevice?,
    val devices: List<TvDevice>,
)

class RemoteScreen(
    private val context: Context,
    private val onScan: () -> Unit,
    private val onConnect: () -> Unit,
    private val onSelectDevice: (TvDevice) -> Unit,
    private val onRemoteKey: (TvKey) -> Unit,
    private val onKeyboard: () -> Unit,
    private val onSettings: () -> Unit,
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
    }

    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(BACKGROUND)
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(24))
    }
    private val statusDot = View(context).apply { background = circle(MUTED_DOT) }
    private val statusLabel = label("Belum terhubung", 12, TEXT, bold = true).apply {
        maxLines = 2
    }
    private val deviceName = label("Belum ada TV", 17, TEXT, bold = true).apply {
        maxLines = 2
    }
    private val deviceAddress = label("Pindai jaringan Wi-Fi untuk menemukan TV", 11, MUTED).apply {
        maxLines = 2
    }
    private val deviceStatus = label("SIAP DIHUBUNGKAN", 9, GOLD, bold = true, spacing = 0.08f)
    private val deviceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scanLabel = label("CARI TV", 9, GOLD, bold = true, spacing = 0.06f)
    private val scanProgress = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(GOLD)
    }
    private val scanButton: View
    private val devicesConnectLabel: TextView
    private val remotePage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val devicesPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val settingsPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val pageHost = FrameLayout(context)
    private val pages = listOf(remotePage, devicesPage, settingsPage)
    private val navItems = mutableListOf<NavItem>()
    private val remoteControls = mutableListOf<View>()
    private var currentPage = Page.REMOTE

    init {
        val header = buildHeader()
        val devicesButton = primaryButton("PAIR DENGAN TV", onConnect)
        devicesConnectLabel = devicesButton.second
        scanButton = buildScanButton()

        scrollView.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        content.addView(header, fullWidthParams())
        content.addView(buildStatusBar(), fullWidthTopMargin(16))
        devicesPage.visibility = View.GONE
        settingsPage.visibility = View.GONE
        val pageParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        pageHost.addView(remotePage, pageParams)
        pageHost.addView(devicesPage, FrameLayout.LayoutParams(pageParams))
        pageHost.addView(settingsPage, FrameLayout.LayoutParams(pageParams))
        buildRemotePage()
        buildDevicesPage(devicesButton.first)
        buildSettingsPage()
        content.addView(pageHost, fullWidthTopMargin(10))
        root.addView(buildBottomNavigation(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(72),
        ))
        updateNavigation()
    }

    fun render(state: RemoteScreenState) {
        statusLabel.text = state.status
        statusLabel.setTextColor(if (state.isError) ERROR else TEXT)
        statusDot.background = circle(
            when {
                state.connected -> CONNECTED
                state.busy || state.searching -> GOLD
                else -> MUTED_DOT
            },
        )
        deviceName.text = state.selectedDevice?.name ?: "Belum ada TV"
        deviceAddress.text = state.selectedDevice?.host
            ?: "Pindai jaringan Wi-Fi untuk menemukan TV"
        deviceStatus.text = when {
            state.connected -> "TERHUBUNG"
            state.busy -> "MENGHUBUNGKAN"
            state.paired -> "SUDAH DIPASANGKAN"
            else -> "SIAP DIHUBUNGKAN"
        }
        deviceStatus.setTextColor(if (state.connected) CONNECTED else GOLD)
        scanLabel.text = if (state.searching) "MENCARI" else "CARI TV"
        scanProgress.visibility = if (state.searching) View.VISIBLE else View.GONE
        scanButton.isEnabled = !state.searching

        val connectionText = when {
            state.busy -> "MENGHUBUNGKAN..."
            state.connected -> "PUTUSKAN KONEKSI"
            state.paired -> "HUBUNGKAN KE TV"
            else -> "PAIR DENGAN TV"
        }
        devicesConnectLabel.text = connectionText
        val canConnect = !state.busy && state.selectedDevice != null
        devicesConnectLabel.parentAsView().isEnabled = canConnect

        renderDevices(state)
        remoteControls.forEach { control ->
            control.alpha = if (state.connected) 1f else 0.82f
        }
    }

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = FrameLayout(context).apply {
            background = rounded(GOLD, 16, GOLD)
            addView(label("R", 22, BACKGROUND, bold = true), FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.CENTER,
            ))
        }
        header.addView(mark, params(48, 48))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        title.addView(label("REALME", 10, GOLD, bold = true, spacing = 0.2f))
        title.addView(label("TV REMOTE", 18, TEXT, bold = true), topMargin(2))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(Icon.SETTINGS, "Buka pengaturan", SURFACE, 46) {
            selectPage(Page.SETTINGS)
        })
        return header
    }

    private fun buildStatusBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = rounded(SURFACE, 14, EDGE)
        addView(statusDot, params(8, 8))
        addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(9)
            marginEnd = dp(8)
        })
        addView(label("ANDROID TV  ·  REMOTE V2", 8, MUTED, bold = true, spacing = 0.04f))
    }

    private fun buildRemotePage() {
        remotePage.addView(sectionHeading("NAVIGASI", "REMOTE CONTROL"), fullWidthTopMargin(10))
        remotePage.addView(buildDirectionalPad(), fullWidthTopMargin(12))
        remotePage.addView(sectionHeading("AKSES CEPAT", "KONTROL UTAMA"), fullWidthTopMargin(22))
        remotePage.addView(buildQuickControls(), fullWidthTopMargin(3))
        remotePage.addView(sectionHeading("MEDIA", "PEMUTARAN"), fullWidthTopMargin(22))
        remotePage.addView(buildMediaControls(), fullWidthTopMargin(3))
        remotePage.addView(sectionHeading("KONTROL TV", "INFO & SUMBER"), fullWidthTopMargin(22))
        remotePage.addView(buildTvControls(), fullWidthTopMargin(3))
        remotePage.addView(sectionHeading("LAINNYA", "KONTROL TV"), fullWidthTopMargin(22))
        remotePage.addView(buildSecondaryControls(), fullWidthTopMargin(3))
        remotePage.addView(
            label("Ketuk Keyboard untuk mengirim teks ke TV.", 10, MUTED),
            fullWidthTopMargin(12),
        )
    }

    private fun buildDeviceCard(connectButton: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = roundedGradient(
            intArrayOf(0xff24262b.toInt(), SURFACE),
            20,
            EDGE_GOLD,
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(buildTvArtwork(), params(62, 62))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(7), 0)
        }
        labels.addView(label("PERANGKAT", 8, MUTED, bold = true, spacing = 0.12f))
        labels.addView(deviceName, topMargin(5))
        labels.addView(deviceAddress, topMargin(3))
        labels.addView(deviceStatus, topMargin(7))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(scanButton)
        addView(row, fullWidthParams())
        addView(View(context).apply { setBackgroundColor(EDGE) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
        ).apply {
            topMargin = dp(14)
            bottomMargin = dp(13)
        })
        addView(connectButton, fullWidthParams())
    }

    private fun buildTvArtwork(): View = FrameLayout(context).apply {
        background = roundedGradient(intArrayOf(0xff393221.toInt(), 0xff24221d.toInt()), 17, EDGE_GOLD)
        addView(IconView(Icon.TV, GOLD), FrameLayout.LayoutParams(dp(35), dp(35), Gravity.CENTER))
        addView(View(context).apply { background = circle(GOLD) }, FrameLayout.LayoutParams(
            dp(4), dp(4), Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = dp(9)
            marginEnd = dp(9)
        })
    }

    private fun buildScanButton(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(7), dp(9), dp(7))
        background = ripple(SURFACE_RAISED, 12)
        isClickable = true
        isFocusable = true
        contentDescription = "Cari perangkat TV"
        setOnClickListener { onScan() }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        addView(scanProgress, params(15, 15))
        addView(IconView(Icon.SCAN, GOLD).apply {
            visibility = View.VISIBLE
        }, params(20, 20).apply { topMargin = dp(2) })
        addView(scanLabel, topMargin(2))
    }

    private fun buildDirectionalPad(): View {
        val size = (context.resources.displayMetrics.widthPixels /
            context.resources.displayMetrics.density).toInt() - 68
        val diameter = size.coerceIn(238, 286)
        val pad = FrameLayout(context).apply {
            contentDescription = "Directional pad"
            addView(DpadSurface(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val grid = GridLayout(context).apply {
            rowCount = 3
            columnCount = 3
            useDefaultMargins = false
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val directions = mapOf(
            1 to (Icon.UP to TvKey.UP),
            3 to (Icon.LEFT to TvKey.LEFT),
            5 to (Icon.RIGHT to TvKey.RIGHT),
            7 to (Icon.DOWN to TvKey.DOWN),
        )
        for (index in 0 until 9) {
            val control = when {
                index == 4 -> centerOkButton()
                directions.containsKey(index) -> {
                    val (icon, key) = directions.getValue(index)
                    directionalButton(icon, key)
                }
                else -> View(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
            grid.addView(control, GridLayout.LayoutParams(
                GridLayout.spec(index / 3, 1, 1f),
                GridLayout.spec(index % 3, 1, 1f),
            ).apply {
                width = 0
                height = 0
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        pad.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return FrameLayout(context).apply {
            addView(pad, FrameLayout.LayoutParams(dp(diameter), dp(diameter), Gravity.CENTER))
            layoutParams = fullWidthParams(dp(diameter))
        }
    }

    private fun directionalButton(icon: Icon, key: TvKey): View = FrameLayout(context).apply {
        background = ripple(SURFACE_RAISED, 48)
        isClickable = true
        isFocusable = true
        contentDescription = when (key) {
            TvKey.UP -> "Atas"
            TvKey.DOWN -> "Bawah"
            TvKey.LEFT -> "Kiri"
            else -> "Kanan"
        }
        setOnClickListener { onRemoteKey(key) }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        addView(IconView(icon, TEXT), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        remoteControls.add(this)
    }

    private fun centerOkButton(): View = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        contentDescription = "OK"
        setOnClickListener { onRemoteKey(TvKey.OK) }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        val button = TextView(context).apply {
            text = "OK"
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(BACKGROUND)
            gravity = Gravity.CENTER
            background = ripple(GOLD, 28)
        }
        addView(button, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        remoteControls.add(this)
    }

    private fun buildQuickControls(): View {
        val grid = GridLayout(context).apply {
            columnCount = 3
            rowCount = 2
            useDefaultMargins = false
        }
        val controls = listOf(
            Triple(Icon.POWER, "POWER", TvKey.POWER),
            Triple(Icon.VOLUME_DOWN, "VOLUME −", TvKey.VOLUME_DOWN),
            Triple(Icon.MUTE, "MUTE", TvKey.MUTE),
            Triple(Icon.VOLUME_UP, "VOLUME +", TvKey.VOLUME_UP),
            Triple(Icon.CHANNEL_DOWN, "CHANNEL −", TvKey.CHANNEL_DOWN),
            Triple(Icon.CHANNEL_UP, "CHANNEL +", TvKey.CHANNEL_UP),
        )
        controls.forEachIndexed { index, (icon, title, key) ->
            val tile = controlTile(icon, title, { onRemoteKey(key) })
            grid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(index / 3, 1, 1f),
                GridLayout.spec(index % 3, 1, 1f),
            ).apply {
                width = 0
                height = dp(78)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
            remoteControls.add(tile)
        }
        grid.layoutParams = fullWidthParams()
        return grid
    }

    private fun buildMediaControls(): View = controlGrid(
        listOf(
            Triple(Icon.REWIND, "REWIND", TvKey.MEDIA_REWIND),
            Triple(Icon.PLAY_PAUSE, "PLAY / PAUSE", TvKey.MEDIA_PLAY_PAUSE),
            Triple(Icon.FAST_FORWARD, "FAST FORWARD", TvKey.MEDIA_FAST_FORWARD),
        ),
    )

    private fun buildTvControls(): View = controlGrid(
        listOf(
            Triple(Icon.GUIDE, "GUIDE", TvKey.GUIDE),
            Triple(Icon.INFO, "INFO", TvKey.INFO),
            Triple(Icon.INPUT, "INPUT", TvKey.TV_INPUT),
        ),
    )

    private fun controlGrid(controls: List<Triple<Icon, String, TvKey>>): View =
        GridLayout(context).apply {
            columnCount = controls.size
            rowCount = 1
            useDefaultMargins = false
            controls.forEachIndexed { index, (icon, title, key) ->
                val tile = controlTile(icon, title, { onRemoteKey(key) })
                addView(tile, GridLayout.LayoutParams(
                    GridLayout.spec(0, 1, 1f),
                    GridLayout.spec(index, 1, 1f),
                ).apply {
                    width = 0
                    height = dp(78)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
                remoteControls.add(tile)
            }
            layoutParams = fullWidthParams()
        }

    private fun buildSecondaryControls(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val controls = listOf(
            Triple(Icon.HOME, "HOME", { onRemoteKey(TvKey.HOME) }),
            Triple(Icon.BACK, "BACK", { onRemoteKey(TvKey.BACK) }),
            Triple(Icon.MENU, "MENU", { onRemoteKey(TvKey.MENU) }),
            Triple(Icon.KEYBOARD, "KEYBOARD", onKeyboard),
        )
        controls.forEach { (icon, title, action) ->
            val tile = controlTile(icon, title, action, compact = true)
            row.addView(tile, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                marginEnd = dp(5)
            })
            remoteControls.add(tile)
        }
        row.layoutParams = fullWidthParams()
        return row
    }

    private fun controlTile(
        icon: Icon,
        title: String,
        onClick: () -> Unit,
        compact: Boolean = false,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(7), dp(4), dp(7))
        background = ripple(SURFACE, 15)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setOnClickListener { onClick() }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        addView(IconView(icon, if (icon == Icon.POWER) GOLD else SOFT_GOLD),
            params(if (compact) 19 else 21, if (compact) 19 else 21))
        addView(label(title, if (compact) 8 else 9, TEXT, bold = true, spacing = 0.01f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }, topMargin(if (compact) 5 else 7))
    }

    private fun buildDevicesPage(connectButton: View) {
        devicesPage.addView(label("PERANGKAT", 9, GOLD, bold = true, spacing = 0.16f), topMargin(15))
        devicesPage.addView(label("Pilih layar Anda.", 25, TEXT, bold = true), topMargin(5))
        devicesPage.addView(
            label("Temukan TV yang terhubung ke jaringan Wi-Fi yang sama.", 12, MUTED),
            topMargin(5),
        )
        devicesPage.addView(sectionHeading("PERANGKAT AKTIF", "STATUS KONEKSI"), fullWidthTopMargin(20))
        devicesPage.addView(buildDeviceCard(connectButton), fullWidthTopMargin(9))
        devicesPage.addView(sectionHeading("TERDETEKSI", "PILIH PERANGKAT"), fullWidthTopMargin(21))
        val listCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(SURFACE, 18, EDGE)
            addView(deviceList, fullWidthParams())
        }
        devicesPage.addView(listCard, fullWidthParams())
        devicesPage.addView(
            label("Pastikan ponsel dan TV berada di jaringan lokal yang sama.", 10, MUTED),
            fullWidthTopMargin(10),
        )
    }

    private fun buildSettingsPage() {
        settingsPage.addView(label("PREFERENSI", 9, GOLD, bold = true, spacing = 0.16f), topMargin(15))
        settingsPage.addView(label("Pengaturan.", 25, TEXT, bold = true), topMargin(5))
        settingsPage.addView(
            label("Kelola pairing dan informasi aplikasi.", 12, MUTED),
            topMargin(5),
        )
        settingsPage.addView(sectionHeading("AKUN PERANGKAT", "PRIVASI"), fullWidthTopMargin(22))
        settingsPage.addView(
            settingsRow(Icon.TV, "Pairing TV", "Lupakan perangkat tersimpan atau lihat detail.") {
                onSettings()
            },
            fullWidthTopMargin(3),
        )
        settingsPage.addView(sectionHeading("TENTANG", "REALME TV REMOTE"), fullWidthTopMargin(22))
        settingsPage.addView(
            settingsRow(Icon.INFO, "Tentang aplikasi", "Android TV Remote Protocol versi 2.") {
                onSettings()
            },
            fullWidthTopMargin(3),
        )
        settingsPage.addView(
            label("Koneksi dan kredensial pairing disimpan secara lokal di perangkat ini.", 10, MUTED),
            fullWidthTopMargin(14),
        )
    }

    private fun settingsRow(icon: Icon, title: String, description: String, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = ripple(SURFACE, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { view, event -> pressFeedback(view, event) }
            addView(FrameLayout(context).apply {
                background = rounded(SURFACE_RAISED, 12, EDGE)
                addView(IconView(icon, GOLD), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            }, params(44, 44))
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            textColumn.addView(label(title, 13, TEXT, bold = true))
            textColumn.addView(label(description, 10, MUTED).apply { maxLines = 2 }, topMargin(4))
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(IconView(Icon.CHEVRON, MUTED), params(18, 18))
        }

    private fun buildBottomNavigation(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = roundedGradient(
            intArrayOf(0xff1a1c21.toInt(), 0xff14161a.toInt()),
            20,
            EDGE,
        )
        listOf(
            Triple(Page.REMOTE, Icon.REMOTE, "Remote"),
            Triple(Page.DEVICES, Icon.DEVICES, "Devices"),
            Triple(Page.SETTINGS, Icon.SETTINGS, "Settings"),
        ).forEach { (page, icon, title) ->
            val iconView = IconView(icon, MUTED)
            val textView = label(title, 9, MUTED, bold = true)
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                isFocusable = true
                contentDescription = title
                setOnClickListener { selectPage(page) }
                setOnTouchListener { view, event -> pressFeedback(view, event) }
                addView(iconView, params(20, 20))
                addView(textView, topMargin(3))
            }
            navItems.add(NavItem(page, item, iconView, textView))
            addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun selectPage(page: Page) {
        if (page == currentPage) return
        pages[currentPage.ordinal].visibility = View.GONE
        currentPage = page
        val destination = pages[page.ordinal]
        destination.alpha = 0f
        destination.translationY = dp(6).toFloat()
        destination.visibility = View.VISIBLE
        destination.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .start()
        scrollView.smoothScrollTo(0, 0)
        updateNavigation()
    }

    private fun updateNavigation() {
        navItems.forEach { item ->
            val selected = item.page == currentPage
            item.container.background = if (selected) rounded(SELECTED, 13, SELECTED) else null
            item.icon.setTint(if (selected) GOLD else MUTED)
            item.label.setTextColor(if (selected) SOFT_GOLD else MUTED)
        }
    }

    private fun renderDevices(state: RemoteScreenState) {
        deviceList.removeAllViews()
        if (state.devices.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(15), dp(13), dp(15))
                background = rounded(SURFACE_RAISED, 13, EDGE)
            }
            val indicator = if (state.searching) ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(GOLD)
            } else IconView(Icon.WIFI, MUTED)
            empty.addView(indicator, params(20, 20))
            empty.addView(
                label(
                    if (state.searching) "Mencari TV di jaringan Wi-Fi..."
                    else "Belum ada perangkat. Mulai pencarian untuk menemukan TV.",
                    11,
                    MUTED,
                ).apply { maxLines = 3 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(11)
                },
            )
            deviceList.addView(empty)
            return
        }

        state.devices.forEach { device ->
            val selected = state.selectedDevice?.host == device.host
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = ripple(if (selected) SELECTED else SURFACE_RAISED, 13)
                isClickable = true
                isFocusable = true
                contentDescription = "${device.name}, ${device.host}"
                setOnClickListener { onSelectDevice(device) }
                setOnTouchListener { view, event -> pressFeedback(view, event) }
            }
            row.addView(FrameLayout(context).apply {
                background = rounded(if (selected) 0xff393221.toInt() else SURFACE, 12, EDGE)
                addView(IconView(Icon.TV, if (selected) GOLD else MUTED),
                    FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            }, params(42, 42))
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(5), 0)
            }
            textColumn.addView(label(device.name, 12, TEXT, bold = true).apply { maxLines = 2 })
            textColumn.addView(label(device.host, 10, MUTED).apply { maxLines = 1 }, topMargin(3))
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) row.addView(View(context).apply { background = circle(GOLD) }, params(7, 7))
            deviceList.addView(row, if (deviceList.childCount > 0) topMargin(7) else paramsMatchWrap())
        }
    }

    private fun sectionHeading(title: String, detail: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 9, SOFT_GOLD, bold = true, spacing = 0.12f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(detail, 8, MUTED, bold = true, spacing = 0.05f))
        layoutParams = fullWidthParams()
    }

    private fun primaryButton(title: String, onClick: () -> Unit): Pair<View, TextView> {
        val titleView = label(title, 10, BACKGROUND, bold = true, spacing = 0.06f)
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            minimumHeight = dp(50)
            background = ripple(GOLD, 14)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { onClick() }
            setOnTouchListener { view, event -> pressFeedback(view, event) }
            addView(IconView(Icon.PAIR, BACKGROUND), params(17, 17).apply {
                marginEnd = dp(8)
            })
            addView(titleView)
        }
        return button to titleView
    }

    private fun iconButton(icon: Icon, description: String, color: Int, size: Int, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            background = ripple(color, size / 2)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
            setOnTouchListener { view, event -> pressFeedback(view, event) }
            addView(IconView(icon, GOLD), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            layoutParams = params(size, size)
        }

    private fun label(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        spacing: Float = 0f,
    ) = TextView(context).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = spacing
        includeFontPadding = true
    }

    private fun ripple(color: Int, radius: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        rounded(color, radius, color),
        null,
    )

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != color) setStroke(dp(1), stroke)
    }

    private fun roundedGradient(colors: IntArray, radius: Int, stroke: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        colors,
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun params(width: Int, height: Int) =
        LinearLayout.LayoutParams(dp(width), dp(height))

    private fun fullWidthParams(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun fullWidthTopMargin(margin: Int) = fullWidthParams().apply {
        topMargin = dp(margin)
    }

    private fun paramsMatchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun topMargin(margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(margin)
    }

    private fun topMarginHorizontal(margin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(margin)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun TextView.parentAsView(): View = parent as View

    private data class NavItem(
        val page: Page,
        val container: View,
        val icon: IconView,
        val label: TextView,
    )

    private enum class Page { REMOTE, DEVICES, SETTINGS }

    private enum class Icon {
        SETTINGS, TV, SCAN, POWER, UP, DOWN, LEFT, RIGHT, HOME, BACK, MENU,
        VOLUME_UP, VOLUME_DOWN, MUTE, KEYBOARD, CHANNEL_UP, CHANNEL_DOWN,
        REMOTE, DEVICES, WIFI, PAIR, INFO, CHEVRON, REWIND, PLAY_PAUSE,
        FAST_FORWARD, GUIDE, INPUT,
    }

    private inner class DpadSurface : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = EDGE
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = 0x665d5138
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val diameter = minOf(width, height).toFloat() - dp(2)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = diameter / 2f
            fillPaint.shader = RadialGradient(
                centerX,
                centerY,
                radius,
                intArrayOf(SURFACE_RAISED, 0xff14161b.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            fillPaint.shader = null
            canvas.drawCircle(centerX, centerY, radius, strokePaint)
            canvas.drawCircle(centerX, centerY, radius * 0.72f, accentPaint)
            canvas.drawCircle(centerX, centerY, radius * 0.32f, accentPaint)
        }
    }

    private inner class IconView(
        private val icon: Icon,
        tint: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.FILL
        }

        fun setTint(color: Int) {
            paint.color = color
            fill.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 24f
            canvas.save()
            canvas.translate((width - 24 * scale) / 2f, (height - 24 * scale) / 2f)
            canvas.scale(scale, scale)
            when (icon) {
                Icon.POWER -> {
                    canvas.drawArc(RectF(5f, 5f, 19f, 19f), 42f, 276f, false, paint)
                    canvas.drawLine(12f, 3f, 12f, 12f, paint)
                }
                Icon.UP, Icon.CHANNEL_UP -> arrow(canvas, 12f, 19f, 12f, 5f)
                Icon.DOWN, Icon.CHANNEL_DOWN -> arrow(canvas, 12f, 5f, 12f, 19f)
                Icon.LEFT -> arrow(canvas, 19f, 12f, 5f, 12f)
                Icon.RIGHT -> arrow(canvas, 5f, 12f, 19f, 12f)
                Icon.BACK -> {
                    canvas.drawLine(19f, 12f, 5f, 12f, paint)
                    canvas.drawLine(5f, 12f, 11f, 6f, paint)
                    canvas.drawLine(5f, 12f, 11f, 18f, paint)
                }
                Icon.HOME -> {
                    val path = Path().apply {
                        moveTo(3f, 11f); lineTo(12f, 4f); lineTo(21f, 11f)
                        moveTo(6f, 10f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 10f)
                        moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
                    }
                    canvas.drawPath(path, paint)
                }
                Icon.MENU -> for (y in listOf(6f, 12f, 18f)) canvas.drawLine(5f, y, 19f, y, paint)
                Icon.REWIND -> {
                    triangle(canvas, 13f, 5f, 5f, 12f, 13f, 19f)
                    triangle(canvas, 21f, 5f, 13f, 12f, 21f, 19f)
                }
                Icon.FAST_FORWARD -> {
                    triangle(canvas, 3f, 5f, 11f, 12f, 3f, 19f)
                    triangle(canvas, 11f, 5f, 19f, 12f, 11f, 19f)
                }
                Icon.PLAY_PAUSE -> {
                    canvas.drawLine(5f, 5f, 5f, 19f, paint)
                    canvas.drawLine(10f, 5f, 10f, 19f, paint)
                    triangle(canvas, 13f, 5f, 21f, 12f, 13f, 19f)
                }
                Icon.VOLUME_UP, Icon.VOLUME_DOWN, Icon.MUTE -> drawSpeaker(canvas, icon)
                Icon.KEYBOARD -> {
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2f, 2f, paint)
                    for (x in listOf(7f, 12f, 17f)) canvas.drawCircle(x, 9f, 0.8f, fill)
                    canvas.drawLine(6f, 13f, 18f, 13f, paint)
                    canvas.drawLine(9f, 16f, 15f, 16f, paint)
                }
                Icon.SCAN -> {
                    canvas.drawArc(RectF(4f, 4f, 20f, 20f), 45f, 285f, false, paint)
                    canvas.drawLine(16f, 4f, 20f, 4f, paint)
                    canvas.drawLine(20f, 4f, 20f, 8f, paint)
                }
                Icon.SETTINGS -> {
                    canvas.drawCircle(12f, 12f, 4f, paint)
                    canvas.drawCircle(12f, 12f, 8f, paint)
                    for (angle in 0..7) {
                        val radians = Math.toRadians((angle * 45).toDouble())
                        val x1 = 12f + (8f * kotlin.math.cos(radians)).toFloat()
                        val y1 = 12f + (8f * kotlin.math.sin(radians)).toFloat()
                        val x2 = 12f + (10f * kotlin.math.cos(radians)).toFloat()
                        val y2 = 12f + (10f * kotlin.math.sin(radians)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
                Icon.TV -> {
                    canvas.drawRoundRect(RectF(2f, 4f, 22f, 18f), 2.5f, 2.5f, paint)
                    canvas.drawLine(9f, 21f, 15f, 21f, paint)
                    canvas.drawLine(12f, 18f, 12f, 21f, paint)
                    canvas.drawLine(8f, 2f, 12f, 4f, paint)
                    canvas.drawLine(16f, 2f, 12f, 4f, paint)
                }
                Icon.REMOTE -> {
                    canvas.drawRoundRect(RectF(7f, 2f, 17f, 22f), 4f, 4f, paint)
                    canvas.drawCircle(12f, 7f, 1.2f, fill)
                    canvas.drawCircle(12f, 12f, 1f, fill)
                    canvas.drawLine(10f, 18f, 14f, 18f, paint)
                }
                Icon.DEVICES -> {
                    canvas.drawRoundRect(RectF(2f, 4f, 17f, 18f), 2f, 2f, paint)
                    canvas.drawLine(6f, 21f, 13f, 21f, paint)
                    canvas.drawRoundRect(RectF(17f, 9f, 22f, 20f), 1.5f, 1.5f, paint)
                }
                Icon.WIFI -> {
                    canvas.drawArc(RectF(3f, 4f, 21f, 21f), 225f, 90f, false, paint)
                    canvas.drawArc(RectF(7f, 8f, 17f, 18f), 225f, 90f, false, paint)
                    canvas.drawCircle(12f, 19f, 1f, fill)
                }
                Icon.PAIR -> {
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2f, 2f, paint)
                    canvas.drawLine(7f, 10f, 17f, 10f, paint)
                    canvas.drawLine(7f, 14f, 14f, 14f, paint)
                    canvas.drawLine(16f, 16f, 18f, 18f, paint)
                    canvas.drawLine(18f, 18f, 22f, 13f, paint)
                }
                Icon.INFO -> {
                    canvas.drawCircle(12f, 12f, 9f, paint)
                    canvas.drawCircle(12f, 7f, 0.8f, fill)
                    canvas.drawLine(12f, 11f, 12f, 17f, paint)
                }
                Icon.CHEVRON -> arrow(canvas, 8f, 5f, 16f, 12f)
                Icon.GUIDE -> {
                    canvas.drawRoundRect(RectF(3f, 4f, 21f, 20f), 2f, 2f, paint)
                    canvas.drawLine(3f, 9f, 21f, 9f, paint)
                    canvas.drawLine(9f, 9f, 9f, 20f, paint)
                    canvas.drawLine(15f, 9f, 15f, 20f, paint)
                }
                Icon.INPUT -> {
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2f, 2f, paint)
                    canvas.drawLine(12f, 15f, 12f, 7f, paint)
                    canvas.drawLine(8f, 11f, 12f, 7f, paint)
                    canvas.drawLine(16f, 11f, 12f, 7f, paint)
                }
            }
            canvas.restore()
        }

        private fun drawSpeaker(canvas: Canvas, icon: Icon) {
            val speaker = Path().apply {
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
                lineTo(7f, 15f); lineTo(3f, 15f); close()
            }

            canvas.drawPath(speaker, paint)
            when (icon) {
                Icon.MUTE -> {
                    canvas.drawLine(16f, 9f, 21f, 15f, paint)
                    canvas.drawLine(21f, 9f, 16f, 15f, paint)
                }
                Icon.VOLUME_UP -> {
                    canvas.drawArc(RectF(11f, 6f, 20f, 18f), -55f, 110f, false, paint)
                    canvas.drawArc(RectF(10f, 2f, 24f, 22f), -55f, 110f, false, paint)
                }
                else -> canvas.drawArc(RectF(11f, 6f, 20f, 18f), -55f, 110f, false, paint)
            }
        }

        private fun triangle(
            canvas: Canvas,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            x3: Float,
            y3: Float,
        ) {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
                lineTo(x3, y3)
                close()
            }
            canvas.drawPath(path, fill)
        }

        private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            canvas.drawLine(x1, y1, x2, y2, paint)
            val path = Path()
            when {
                x1 == x2 && y2 < y1 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 + 5f)
                    path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 + 5f)
                }
                x1 == x2 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 - 5f)
                    path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 - 5f)
                }
                x2 < x1 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 - 5f)
                    path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 + 5f)
                }
                else -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 - 5f)
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 + 5f)
                }
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun pressFeedback(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
        }
        return false
    }

    private companion object {
        const val BACKGROUND = 0xff090a0d.toInt()
        const val SURFACE = 0xff17191f.toInt()
        const val SURFACE_RAISED = 0xff22252d.toInt()
        const val EDGE = 0xff30333b.toInt()
        const val EDGE_GOLD = 0xff514832.toInt()
        const val SELECTED = 0xff393321.toInt()
        const val GOLD = 0xffd6b477.toInt()
        const val SOFT_GOLD = 0xffe9d5a3.toInt()
        const val TEXT = 0xfff4f1e9.toInt()
        const val MUTED = 0xff9699a1.toInt()
        const val MUTED_DOT = 0xff60636b.toInt()
        const val CONNECTED = 0xff9ec6a6.toInt()
        const val ERROR = 0xfff08c83.toInt()
    }
}
