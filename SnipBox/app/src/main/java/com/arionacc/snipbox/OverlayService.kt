package com.arionacc.snipbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.widget.doOnTextChanged
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "snipbox.START"
        const val ACTION_TOGGLE = "snipbox.TOGGLE"
        const val ACTION_STOP = "snipbox.STOP"
        const val ACTION_REFRESH = "snipbox.REFRESH"
        private const val CHANNEL_ID = "snipbox_overlay_v2"
        private const val OLD_CHANNEL_ID = "snipbox_channel"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }

    private val wm: WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideStatus: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Ukuran layar terkini (berubah saat rotasi). */
    private fun screen(): Pair<Int, Int> {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                return b.width() to b.height()
            }
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            return metrics.widthPixels to metrics.heightPixels
        } catch (e: Exception) {
            val m = resources.displayMetrics
            return m.widthPixels to m.heightPixels
        }
    }

    /** Area layar yang aman dipakai: di luar status bar, bilah navigasi, dan cutout. */
    private fun usable(): android.graphics.Rect {
        val (sw, sh) = screen()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                if (i.left + i.top + i.right + i.bottom > 0) {
                    return android.graphics.Rect(i.left, i.top, sw - i.right, sh - i.bottom)
                }
            } catch (e: Exception) {
                // lanjut ke cara cadangan di bawah
            }
        }
        fun dimen(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        val status = dimen("status_bar_height")
        val nav = dimen("navigation_bar_height")
        return if (sh >= sw) android.graphics.Rect(0, status, sw, sh - nav)
        else android.graphics.Rect(0, status, sw - nav, sh)
    }

    private fun minPanelH(sh: Int): Int = minOf(dp(240), (sh * 0.9f).toInt())

    private fun notifyTile() {
        try {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, OverlayTileService::class.java)
            )
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        notifyTile()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.deleteNotificationChannel(OLD_CHANNEL_ID) } catch (_: Exception) {}
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay SnipBox", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun rebuild() {
        if (panel != null) {
            removePanel()
            showPanel()
        } else if (bubble != null) {
            removeBubble()
            showBubble()
        }
    }

    // Bangun ulang ikon/panel saat layar diputar supaya ukuran & posisi menyesuaikan
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ rebuild() }, 200)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil cepat setelah startForegroundService
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        when (intent?.action) {
            ACTION_TOGGLE -> if (panel == null) openPanel() else closePanel()
            ACTION_REFRESH -> rebuild()
            ACTION_STOP -> {
                removePanel()
                removeBubble()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (panel == null && bubble == null) showBubble()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        fun action(act: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, OverlayService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_snip)
            .setContentTitle("SnipBox aktif")
            .setContentText("Ketuk ikon melayang untuk membuka snippet")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_snip, "Buka Overlay", action(ACTION_TOGGLE, 1))
            .addAction(R.drawable.ic_close, "Matikan", action(ACTION_STOP, 2))
            .build()
    }

    // ---------- Ikon melayang ----------

    private fun openPanel() {
        removeBubble()
        showPanel()
    }

    private fun closePanel() {
        removePanel()
        showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val u = usable()
        val size = dp(Prefs.bubbleSize(this))

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_notification)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        icon.setColorFilter(Ui.TEXT)
        val pad = size / 4
        icon.setPadding(pad, pad, pad, pad)
        icon.background = Ui.roundRect(Ui.SURFACE, (size / 2).toFloat(), Ui.STROKE, dp(1))
        icon.elevation = dp(6).toFloat()

        val rx = maxOf(0, u.width() - size)
        val ry = maxOf(0, u.height() - size)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = u.left + (rx * Prefs.bubbleXFrac(this@OverlayService)).toInt()
            y = u.top + (ry * Prefs.bubbleYFrac(this@OverlayService)).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        icon.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cu = usable()
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                            .coerceIn(cu.left, maxOf(cu.left, cu.right - size))
                        params.y = (startY + dy).toInt()
                            .coerceIn(cu.top, maxOf(cu.top, cu.bottom - size))
                        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        // Bebas di mana saja, tanpa menempel ke tepi
                        val cu = usable()
                        val rangeX = maxOf(1, cu.width() - size)
                        val rangeY = maxOf(1, cu.height() - size)
                        Prefs.setBubblePos(
                            this,
                            (params.x - cu.left).toFloat() / rangeX,
                            (params.y - cu.top).toFloat() / rangeY
                        )
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        openPanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(icon, params)
            bubble = icon
            bubbleParams = params
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Gagal menampilkan ikon: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun removeBubble() {
        bubble?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        bubble = null
        bubbleParams = null
    }

    // ---------- Panel snippet ----------

    private fun showPanel() {
        val (sw, sh) = screen()
        val all = SnippetStore.load(this)
        val cats = Categories.all(this, all)
        var category: String? = null
        var query = ""
        var shown: List<Snippet> = all
        val snipAdapter = SnippetAdapter(this) { shown }

        // Garis tarik di atas panel: seret ke mana saja untuk memindahkan panel
        val handleBar = View(this)
        handleBar.background = Ui.roundRect(Ui.STROKE, dp(2).toFloat())
        val handleArea = FrameLayout(this)
        handleArea.addView(handleBar, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))

        // Label kecil "Tersalin"
        val status = TextView(this)
        status.textSize = 12f
        status.setTextColor(Ui.TEXT_DIM)
        status.visibility = View.GONE
        status.setPadding(dp(4), dp(8), dp(4), 0)

        fun flash(title: String) {
            status.text = "✓ Tersalin: $title"
            status.visibility = View.VISIBLE
            hideStatus?.let { handler.removeCallbacks(it) }
            val r = Runnable { status.visibility = View.GONE }
            hideStatus = r
            handler.postDelayed(r, 1600)
        }

        // Tampilan kosong
        val emptyTv = TextView(this)
        emptyTv.textSize = 14f
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setTextColor(Ui.TEXT_DIM)
        emptyTv.setPadding(dp(16), dp(24), dp(16), dp(24))

        fun applyFilter() {
            val q = query.trim().lowercase()
            shown = all.filter { s ->
                (category == null || s.type.equals(category, true)) &&
                    (q.isEmpty() || s.title.lowercase().contains(q) ||
                        s.content.lowercase().contains(q))
            }
            emptyTv.text = when {
                all.isEmpty() -> "Belum ada snippet.\nTambahkan lewat aplikasi SnipBox."
                q.isNotEmpty() -> "Tidak ada hasil untuk \"$q\""
                else -> "Belum ada snippet di kategori ini."
            }
            snipAdapter.notifyDataSetChanged()
        }

        // Kolom pencarian
        val search = EditText(this)
        search.hint = "Cari snippet..."
        search.setTextColor(Ui.TEXT)
        search.setHintTextColor(Ui.TEXT_DIM)
        search.textSize = 14f
        search.isSingleLine = true
        search.background = Ui.roundRect(Ui.FIELD, dp(22).toFloat())
        search.setPadding(dp(14), 0, dp(14), 0)
        search.compoundDrawablePadding = dp(10)
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        search.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        search.doOnTextChanged { text, _, _, _ ->
            query = text.toString()
            applyFilter()
        }

        // Tombol tutup
        val close = ImageView(this)
        close.setImageResource(R.drawable.ic_close)
        close.scaleType = ImageView.ScaleType.CENTER
        close.background = Ui.pressable(Ui.FIELD, dp(22).toFloat())
        close.isClickable = true
        close.setOnClickListener { closePanel() }

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(search)
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(10)
        })

        // Chip kategori
        val catBar = CategoryBar(this)
        fun renderCats() {
            catBar.set(cats, category, onSelect = {
                category = it
                renderCats()
                applyFilter()
            })
        }
        renderCats()

        // Daftar kartu
        val lv = ListView(this)
        lv.adapter = snipAdapter
        lv.divider = null
        lv.dividerHeight = 0
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        lv.overScrollMode = View.OVER_SCROLL_NEVER
        lv.isVerticalScrollBarEnabled = false
        lv.setPadding(0, dp(8), 0, 0)
        lv.clipToPadding = false
        lv.emptyView = emptyTv
        lv.setOnItemClickListener { _, v, pos, _ ->
            val s = shown[pos]
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("snippet", s.content))
            Ui.feedback(v)
            flash(s.title)
        }

        val listHolder = FrameLayout(this)
        listHolder.addView(lv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        listHolder.addView(
            emptyTv,
            FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER)
        )

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.background = Ui.roundRect(Ui.SURFACE, dp(24).toFloat(), Ui.STROKE, dp(1))
        container.setPadding(dp(14), dp(2), dp(14), dp(24))
        container.addView(handleArea, LinearLayout.LayoutParams(Ui.MATCH, dp(24)))
        container.addView(header)
        container.addView(catBar.view, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            topMargin = dp(10)
        })
        container.addView(status)
        container.addView(listHolder, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        applyFilter()

        // Pegangan ubah ukuran di sudut kanan bawah
        val grip = ImageView(this)
        grip.setImageResource(R.drawable.ic_grip)
        grip.scaleType = ImageView.ScaleType.CENTER

        val root = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(
            grip,
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END)
        )

        val u = usable()
        val w = minOf(
            minOf((sw * Prefs.panelW(this) / 100f).toInt(), dp(560)),
            maxOf(dp(240), u.width() - dp(16))
        )
        val h = minOf(
            maxOf((sh * Prefs.panelH(this) / 100f).toInt(), minPanelH(sh)),
            maxOf(minPanelH(sh), u.height() - dp(16))
        )
        val yFrac = Prefs.panelYFrac(this)
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = u.left + (maxOf(0, u.width() - w) * Prefs.panelXFrac(this@OverlayService)).toInt()
            y = if (yFrac < 0f) {
                u.bottom - h - dp(12)
            } else {
                u.top + (maxOf(0, u.height() - h) * yFrac).toInt()
            }
            y = y.coerceIn(u.top, maxOf(u.top, u.bottom - h))
        }

        fun savePanelPos() {
            val cu = usable()
            val rx = maxOf(1, cu.width() - params.width)
            val ry = maxOf(1, cu.height() - params.height)
            Prefs.setPanelPos(
                this,
                (params.x - cu.left).toFloat() / rx,
                (params.y - cu.top).toFloat() / ry
            )
        }

        // Saat keyboard muncul, naikkan panel kalau posisinya terlalu ke bawah
        search.setOnFocusChangeListener { _, has ->
            if (has) {
                val (_, ch) = screen()
                val limit = (ch * 0.5f).toInt()
                if (params.y + params.height > limit) {
                    params.y = maxOf(usable().top, limit - params.height)
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                }
            }
        }

        // Seret garis di atas: pindahkan panel ke mana saja
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handleArea.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cu = usable()
                    params.x = (startX + (e.rawX - downX).toInt())
                        .coerceIn(cu.left, maxOf(cu.left, cu.right - params.width))
                    params.y = (startY + (e.rawY - downY).toInt())
                        .coerceIn(cu.top, maxOf(cu.top, cu.bottom - params.height))
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelPos()
                    true
                }
                else -> false
            }
        }

        // Seret sudut kanan bawah: ubah lebar & tinggi
        var startW = 0
        var startH = 0
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startW = params.width
                    startH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val cu = usable()
                    val minW = dp(240)
                    val minH = minPanelH(cu.height())
                    params.width = (startW + (e.rawX - downX).toInt())
                        .coerceIn(minW, maxOf(minW, cu.right - params.x))
                    params.height = (startH + (e.rawY - downY).toInt())
                        .coerceIn(minH, maxOf(minH, cu.bottom - params.y))
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val (cw, ch) = screen()
                    // Simpan ukuran hanya dari portrait supaya tidak merusak ukuran portrait
                    if (ch > cw) {
                        Prefs.setPanelSize(
                            this,
                            (params.width * 100f / cw).toInt(),
                            (params.height * 100f / ch).toInt()
                        )
                    }
                    savePanelPos()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, params)
            panel = root
            panelParams = params
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "Gagal menampilkan panel: ${e.message}", android.widget.Toast.LENGTH_LONG
            ).show()
            showBubble()
        }
    }

    private fun removePanel() {
        handler.removeCallbacksAndMessages(null)
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        panel = null
        panelParams = null
    }

    override fun onDestroy() {
        running = false
        notifyTile()
        removePanel()
        removeBubble()
        super.onDestroy()
    }
}
