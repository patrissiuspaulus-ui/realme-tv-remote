package com.realme.tvremote.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
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
    val root: ScrollView = ScrollView(context).apply {
        setBackgroundColor(BACKGROUND)
        isFillViewport = true
        clipToPadding = false
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 28))
    }
    private val statusDot = View(context).apply { background = circle(context, MUTED) }
    private val statusLabel = label(context, "Belum terhubung", 13, TEXT, bold = true)
    private val deviceName = label(context, "Belum ada TV", 17, TEXT, bold = true)
    private val deviceAddress = label(context, "Pindai jaringan Wi-Fi untuk menemukan TV", 12, MUTED)
    private val deviceList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scanLabel = label(context, "CARI TV", 10, GOLD, bold = true)
    private val scanProgress = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(GOLD)
    }
    private val connectionButton = actionButton(context, "PAIR DENGAN TV", primary = true, onClick = onConnect)
    private val remoteControls = mutableListOf<View>()

    init {
        connectionButton.minimumHeight = dp(context, 52)
        root.addView(content)
        buildHeader(context)
        buildConnectionSection(context)
        buildRemoteSection(context)
        buildQuickControls(context)
        buildKeyboard(context)
    }

    fun render(state: RemoteScreenState) {
        statusLabel.text = state.status
        statusLabel.setTextColor(if (state.isError) ERROR else TEXT)
        statusDot.background = circle(
            statusDot.context,
            when {
                state.connected -> CONNECTED
                state.busy || state.searching -> GOLD
                else -> MUTED_DOT
            },
        )
        deviceName.text = state.selectedDevice?.name ?: "Belum ada TV"
        deviceAddress.text = state.selectedDevice?.host ?: "Pindai jaringan Wi-Fi untuk menemukan TV"
        scanLabel.text = if (state.searching) "MENCARI" else "CARI TV"
        scanProgress.visibility = if (state.searching) View.VISIBLE else View.GONE
        (connectionButton.getChildAt(connectionButton.childCount - 1) as TextView).text = when {
            state.busy -> "SEDANG MENGHUBUNGKAN..."
            state.connected -> "PUTUSKAN KONEKSI"
            state.paired -> "HUBUNGKAN"
            else -> "PAIR DENGAN TV"
        }
        connectionButton.isEnabled = !state.busy && state.selectedDevice != null
        renderDevices(state)
        remoteControls.forEach { control ->
            control.alpha = if (state.connected) 1f else 0.78f
        }
    }

    private fun buildHeader(context: Context) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(context).apply {
            text = "R"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(BACKGROUND)
            background = rounded(context, GOLD, 15, GOLD)
        }
        header.addView(mark, params(context, 44, 44))
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), 0, 0, 0)
        }
        title.addView(label(context, "REALME", 11, GOLD, bold = true, spacing = 0.16f))
        title.addView(label(context, "TV REMOTE", 18, TEXT, bold = true), topMargin(context, 3))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton(context, Icon.SETTINGS, "Pengaturan", SURFACE, 44, onSettings))
        content.addView(header)

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            background = rounded(context, SURFACE, 8, SURFACE)
        }
        statusRow.addView(statusDot, params(context, 8, 8))
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(context, 9)
        })
        val protocol = label(context, "REMOTE V2", 9, MUTED, bold = true, spacing = 0.1f)
        statusRow.addView(protocol)
        content.addView(statusRow, topMargin(context, 17))
    }

    private fun buildConnectionSection(context: Context) {
        sectionTitle(context, "PERANGKAT TV", "JARINGAN LOKAL")
        val card = panel(context, outlined = true)
        val deviceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvMark = iconBadge(context, Icon.TV)
        deviceRow.addView(tvMark, params(context, 42, 42))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 11), 0, dp(context, 5), 0)
        }
        labels.addView(deviceName)
        labels.addView(deviceAddress, topMargin(context, 3))
        deviceRow.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scan = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 9), dp(context, 8), dp(context, 9), dp(context, 8))
            background = ripple(context, SURFACE_RAISED, 9)
            isClickable = true
            isFocusable = true
            contentDescription = "Cari perangkat TV"
            setOnClickListener { onScan() }
        }
        scan.addView(scanProgress, params(context, 16, 16))
        scan.addView(IconView(context, Icon.SCAN, GOLD), params(context, 16, 16).apply {
            marginStart = dp(context, 4)
        })
        scan.addView(scanLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(context, 6)
        })
        deviceRow.addView(scan)
        card.addView(deviceRow)

        deviceList.setPadding(0, dp(context, 11), 0, 0)
        card.addView(deviceList)
        card.addView(connectionButton, topMargin(context, 14))
        content.addView(card)
    }

    private fun buildRemoteSection(context: Context) {
        sectionTitle(context, "REMOTE", "KONTROL TV")
        val card = panel(context)
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(label(context, "NAVIGASI", 10, MUTED, bold = true, spacing = 0.12f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val power = actionButton(context, "POWER", primary = true, onClick = { onRemoteKey(TvKey.POWER) }, icon = Icon.POWER)
        top.addView(power, params(context, 105, 40))
        remoteControls.add(power)
        card.addView(top)

        val dial = FrameLayout(context).apply {
            background = rounded(context, DIAL, 124, EDGE)
        }
        val grid = GridLayout(context).apply {
            rowCount = 3
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val icons = listOf(
            null, Icon.UP, null,
            Icon.LEFT, null, Icon.RIGHT,
            null, Icon.DOWN, null,
        )
        val keys = listOf(
            null, TvKey.UP, null,
            TvKey.LEFT, null, TvKey.RIGHT,
            null, TvKey.DOWN, null,
        )
        icons.forEachIndexed { index, icon ->
            val key = keys[index]
            val control = when {
                index == 4 -> actionButton(context, "OK", primary = true, onClick = { onRemoteKey(TvKey.OK) }, circle = true)
                icon != null && key != null -> iconButton(context, icon, key.name, DIAL_BUTTON, 62) { onRemoteKey(key) }
                else -> View(context)
            }
            if (key != null || index == 4) remoteControls.add(control)
            grid.addView(control, GridLayout.LayoutParams().apply {
                width = dp(context, 66)
                height = dp(context, 66)
                rowSpec = GridLayout.spec(index / 3)
                columnSpec = GridLayout.spec(index % 3)
                setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            })
        }
        dial.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        card.addView(dial, centered(context, 220, 220, top = 13))

        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple(Icon.HOME, "HOME", TvKey.HOME),
            Triple(Icon.BACK, "BACK", TvKey.BACK),
            Triple(Icon.MENU, "MENU", TvKey.MENU),
        ).forEach { (icon, text, key) ->
            val control = actionButton(context, text, primary = false, onClick = { onRemoteKey(key) }, icon = icon)
            remoteControls.add(control)
            nav.addView(control, LinearLayout.LayoutParams(0, dp(context, 46), 1f).apply {
                marginStart = dp(context, 3)
                marginEnd = dp(context, 3)
            })
        }
        card.addView(nav, topMargin(context, 13))
        content.addView(card)
    }

    private fun buildQuickControls(context: Context) {
        sectionTitle(context, "AKSES CEPAT", "VOLUME & CHANNEL")
        val card = panel(context)
        val volume = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple(Icon.MINUS, "VOL −", TvKey.VOLUME_DOWN),
            Triple(Icon.MUTE, "MUTE", TvKey.MUTE),
            Triple(Icon.PLUS, "VOL +", TvKey.VOLUME_UP),
        ).forEach { (icon, text, key) ->
            val control = actionButton(context, text, primary = false, onClick = { onRemoteKey(key) }, icon = icon)
            remoteControls.add(control)
            volume.addView(control, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                marginStart = dp(context, 3)
                marginEnd = dp(context, 3)
            })
        }
        card.addView(volume)

        val channels = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple(Icon.DOWN, "CHANNEL −", TvKey.CHANNEL_DOWN),
            Triple(Icon.UP, "CHANNEL +", TvKey.CHANNEL_UP),
        ).forEach { (icon, text, key) ->
            val control = actionButton(context, text, primary = false, onClick = { onRemoteKey(key) }, icon = icon)
            remoteControls.add(control)
            channels.addView(control, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                marginStart = dp(context, 3)
                marginEnd = dp(context, 3)
            })
        }
        card.addView(channels, topMargin(context, 8))
        content.addView(card)
    }

    private fun buildKeyboard(context: Context) {
        val keyboard = actionButton(context, "BUKA KEYBOARD HP", primary = true, onClick = onKeyboard, icon = Icon.KEYBOARD)
        keyboard.minimumHeight = dp(context, 54)
        remoteControls.add(keyboard)
        content.addView(keyboard, topMargin(context, 14))
        content.addView(label(context, "Teks dikirim melalui Android TV Remote IME.", 11, MUTED), topMargin(context, 8))
    }

    private fun renderDevices(state: RemoteScreenState) {
        deviceList.removeAllViews()
        if (state.devices.isEmpty()) {
            val empty = LinearLayout(deviceList.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(context, 11), dp(context, 10), dp(context, 11), dp(context, 10))
                background = rounded(context, SURFACE_RAISED, 8, SURFACE_RAISED)
            }
            val indicator = if (state.searching) ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(GOLD)
            } else IconView(context, Icon.TV, MUTED)
            empty.addView(indicator, params(context, 17, 17))
            empty.addView(label(
                context,
                if (state.searching) "Sedang mencari TV di Wi-Fi..." else "Belum ada perangkat. Pastikan TV dan HP memakai Wi-Fi yang sama.",
                11,
                MUTED,
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 9)
            })
            deviceList.addView(empty)
            return
        }

        state.devices.forEach { device ->
            val selected = state.selectedDevice?.host == device.host
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(context, 10), dp(context, 9), dp(context, 10), dp(context, 9))
                background = ripple(context, if (selected) SELECTED else SURFACE_RAISED, 8)
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelectDevice(device) }
            }
            row.addView(IconView(context, Icon.TV, if (selected) GOLD else MUTED), params(context, 17, 17))
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 9), 0, 0, 0)
            }
            textColumn.addView(label(context, device.name, 12, TEXT, bold = true))
            textColumn.addView(label(context, device.host, 10, MUTED), topMargin(context, 2))
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) row.addView(View(context).apply { background = circle(context, GOLD) }, params(context, 7, 7))
            deviceList.addView(row, topMargin(context, 5))
        }
    }

    private fun sectionTitle(context: Context, title: String, detail: String) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(label(context, title, 10, GOLD, bold = true, spacing = 0.12f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(context, detail, 9, MUTED, bold = true, spacing = 0.08f))
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 20)
            bottomMargin = dp(context, 9)
        })
    }

    private fun panel(context: Context, outlined: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13))
        background = rounded(context, SURFACE, 14, if (outlined) EDGE_GOLD else SURFACE)
    }

    private fun iconBadge(context: Context, icon: Icon) = FrameLayout(context).apply {
        background = rounded(context, SURFACE_RAISED, 12, EDGE)
        addView(IconView(context, icon, GOLD), FrameLayout.LayoutParams(dp(context, 21), dp(context, 21), Gravity.CENTER))
    }

    private fun iconButton(
        context: Context,
        icon: Icon,
        description: String,
        backgroundColor: Int,
        size: Int,
        onClick: () -> Unit,
    ) = FrameLayout(context).apply {
        background = ripple(context, backgroundColor, size / 2)
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener { onClick() }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        addView(IconView(context, icon, if (icon == Icon.POWER || icon == Icon.SETTINGS) GOLD else TEXT),
            FrameLayout.LayoutParams(dp(context, 22), dp(context, 22), Gravity.CENTER))
    }

    private fun actionButton(
        context: Context,
        title: String,
        primary: Boolean,
        onClick: () -> Unit,
        icon: Icon? = null,
        circle: Boolean = false,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
        background = ripple(context, if (primary) GOLD else SURFACE_RAISED, if (circle) 40 else 10)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setOnClickListener { onClick() }
        setOnTouchListener { view, event -> pressFeedback(view, event) }
        if (icon != null) addView(IconView(context, icon, if (primary) BACKGROUND else GOLD), params(context, 18, 18).apply {
            marginEnd = dp(context, 7)
        })
        addView(label(context, title, if (circle) 13 else 10, if (primary) BACKGROUND else TEXT, bold = true, spacing = 0.035f))
    }

    private fun ripple(context: Context, color: Int, radius: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        rounded(context, color, radius, color),
        null,
    )

    private fun rounded(context: Context, color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (stroke != color) setStroke(dp(context, 1), stroke)
    }

    private fun circle(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun label(context: Context, value: String, size: Int, color: Int, bold: Boolean = false, spacing: Float = 0f) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = spacing
        includeFontPadding = true
    }

    private fun centered(context: Context, width: Int, height: Int, top: Int = 0) = LinearLayout.LayoutParams(dp(context, width), dp(context, height)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(context, top)
    }

    private fun params(context: Context, width: Int, height: Int) = LinearLayout.LayoutParams(dp(context, width), dp(context, height))

    private fun topMargin(context: Context, margin: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(context, margin)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private enum class Icon { SETTINGS, TV, SCAN, POWER, UP, DOWN, LEFT, RIGHT, HOME, BACK, MENU, MINUS, PLUS, MUTE, KEYBOARD }

    private class IconView(context: Context, private val icon: Icon, private val tint: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 24f
            canvas.save()
            canvas.translate((width - 24 * scale) / 2f, (height - 24 * scale) / 2f)
            canvas.scale(scale, scale)
            when (icon) {
                Icon.POWER -> {
                    canvas.drawArc(RectF(5f, 5f, 19f, 19f), 40f, 280f, false, paint)
                    canvas.drawLine(12f, 3f, 12f, 12f, paint)
                }
                Icon.UP -> arrow(canvas, 12f, 19f, 12f, 5f, paint)
                Icon.DOWN -> arrow(canvas, 12f, 5f, 12f, 19f, paint)
                Icon.LEFT -> arrow(canvas, 19f, 12f, 5f, 12f, paint)
                Icon.RIGHT -> arrow(canvas, 5f, 12f, 19f, 12f, paint)
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
                Icon.PLUS -> {
                    canvas.drawLine(12f, 5f, 12f, 19f, paint)
                    canvas.drawLine(5f, 12f, 19f, 12f, paint)
                }
                Icon.MINUS -> canvas.drawLine(5f, 12f, 19f, 12f, paint)
                Icon.MUTE -> {
                    val speaker = Path().apply {
                        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
                    }
                    canvas.drawPath(speaker, paint)
                    canvas.drawLine(17f, 9f, 21f, 15f, paint)
                    canvas.drawLine(21f, 9f, 17f, 15f, paint)
                }
                Icon.KEYBOARD -> {
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2f, 2f, paint)
                    for (x in listOf(7f, 12f, 17f)) canvas.drawLine(x, 9f, x, 9f, paint)
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
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 18f), 2f, 2f, paint)
                    canvas.drawLine(9f, 21f, 15f, 21f, paint)
                    canvas.drawLine(12f, 18f, 12f, 21f, paint)
                    canvas.drawLine(8f, 3f, 12f, 5f, paint)
                    canvas.drawLine(16f, 3f, 12f, 5f, paint)
                }
            }
            canvas.restore()
        }

        private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
            canvas.drawLine(x1, y1, x2, y2, paint)
            val path = Path()
            when {
                x1 == x2 && y2 < y1 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 + 5f); path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 + 5f)
                }
                x1 == x2 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 - 5f); path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 - 5f)
                }
                x2 < x1 -> {
                    path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 - 5f); path.moveTo(x2, y2); path.lineTo(x2 + 5f, y2 + 5f)
                }
                else -> {
                    path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 - 5f); path.moveTo(x2, y2); path.lineTo(x2 - 5f, y2 + 5f)
                }
            }
            canvas.drawPath(path, paint)
        }

        companion object {
            private const val BACKGROUND = 0xff090a0b.toInt()
            private const val SURFACE = 0xff151719.toInt()
            private const val SURFACE_RAISED = 0xff202326.toInt()
            private const val DIAL = 0xff101214.toInt()
            private const val DIAL_BUTTON = 0xff282b2e.toInt()
            private const val SELECTED = 0xff302a1d.toInt()
            private const val EDGE = 0xff2b2e30.toInt()
            private const val EDGE_GOLD = 0xff4a402b.toInt()
            private const val GOLD = 0xffd9bd7a.toInt()
            private const val TEXT = 0xfff3f0e8.toInt()
            private const val MUTED = 0xff909397.toInt()
            private const val MUTED_DOT = 0xff62666a.toInt()
            private const val CONNECTED = 0xff9dbb9e.toInt()
            private const val ERROR = 0xffe08a80.toInt()
        }
    }

    companion object {
        private const val BACKGROUND = 0xff090a0b.toInt()
        private const val SURFACE = 0xff151719.toInt()
        private const val SURFACE_RAISED = 0xff202326.toInt()
        private const val DIAL = 0xff101214.toInt()
        private const val DIAL_BUTTON = 0xff282b2e.toInt()
        private const val SELECTED = 0xff302a1d.toInt()
        private const val EDGE = 0xff2b2e30.toInt()
        private const val EDGE_GOLD = 0xff4a402b.toInt()
        private const val GOLD = 0xffd9bd7a.toInt()
        private const val TEXT = 0xfff3f0e8.toInt()
        private const val MUTED = 0xff909397.toInt()
        private const val MUTED_DOT = 0xff62666a.toInt()
        private const val CONNECTED = 0xff9dbb9e.toInt()
        private const val ERROR = 0xffe08a80.toInt()

        private fun pressFeedback(view: View, event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            return false
        }
    }
}