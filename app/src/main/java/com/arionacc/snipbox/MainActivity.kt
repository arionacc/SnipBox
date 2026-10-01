package com.arionacc.snipbox

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var snippets: MutableList<Snippet> = mutableListOf()
    private var shown: List<Snippet> = emptyList()
    private var filter: String? = null
    private lateinit var adapter: SnippetAdapter
    private lateinit var countLabel: TextView
    private lateinit var catBar: CategoryBar
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        snippets = SnippetStore.load(this)
        adapter = SnippetAdapter(this) { shown }

        // Header
        val titleView = TextView(this)
        titleView.text = "SnipBox"
        titleView.textSize = 28f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.setTextColor(Ui.TEXT)

        val subtitle = TextView(this)
        subtitle.text = getString(R.string.app_tagline)
        subtitle.textSize = 13f
        subtitle.setTextColor(Ui.TEXT_DIM)

        // Tombol overlay: tinggi sama, lebar sama
        val btnOverlay = Ui.pill(this, getString(R.string.btn_enable_overlay), Ui.ACCENT, Ui.BG) { startOverlay() }
        val btnStop = Ui.pill(this, getString(R.string.btn_disable), Ui.CARD, Ui.TEXT) {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_STOP)
            )
        }
        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.addView(btnOverlay, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(btnStop, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(10)
        })

        // Tombol menu titik tiga
        val btnMore = android.widget.ImageView(this)
        btnMore.setImageResource(R.drawable.ic_more)
        btnMore.scaleType = android.widget.ImageView.ScaleType.CENTER
        btnMore.background = Ui.pressable(Color.TRANSPARENT, dp(20).toFloat())
        btnMore.isClickable = true
        btnMore.setOnClickListener { showMenu(btnMore) }

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(titleView)
        titles.addView(subtitle)

        val headerRow = LinearLayout(this)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        headerRow.addView(btnMore, LinearLayout.LayoutParams(dp(40), dp(40)))

        countLabel = Ui.label(this, getString(R.string.label_snippets))

        // Kategori
        catBar = CategoryBar(this)

        // Daftar snippet
        val lv = ListView(this)
        lv.adapter = adapter
        lv.divider = null
        lv.dividerHeight = 0
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        lv.overScrollMode = View.OVER_SCROLL_NEVER
        lv.isVerticalScrollBarEnabled = false
        lv.clipToPadding = false
        lv.setPadding(0, dp(12), 0, dp(96))
        lv.setOnItemClickListener { _, v, pos, _ ->
            Ui.feedback(v)
            copy(shown[pos].content)
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val target = shown[pos]
            AlertDialog.Builder(this)
                .setTitle(target.title)
                .setItems(arrayOf(getString(R.string.menu_edit), getString(R.string.menu_delete))) { _, which ->
                    if (which == 0) {
                        showEditor(target)
                    } else {
                        snippets.remove(target)
                        persist()
                    }
                }.show()
            true
        }

        empty = TextView(this)
        empty.textSize = 14f
        empty.gravity = Gravity.CENTER
        empty.setTextColor(Ui.TEXT_DIM)
        empty.setPadding(dp(24), dp(24), dp(24), dp(24))
        lv.emptyView = empty

        val listHolder = FrameLayout(this)
        listHolder.addView(lv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        listHolder.addView(
            empty,
            FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER)
        )

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(16), dp(20), 0)
        column.addView(headerRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        column.addView(controls, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            topMargin = dp(20)
        })
        column.addView(countLabel)
        column.addView(catBar.view, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        column.addView(listHolder, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        // Tombol tambah mengambang
        val fab = Ui.pill(this, getString(R.string.btn_add), Ui.ACCENT, Ui.BG) { showEditor(null) }
        fab.textSize = 15f
        fab.elevation = dp(8).toFloat()
        val fabParams = FrameLayout.LayoutParams(dp(132), dp(48), Gravity.BOTTOM or Gravity.END)
        fabParams.setMargins(0, 0, dp(20), dp(24))

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        root.addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(fab, fabParams)

        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val cats = Categories.all(this, snippets)
        if (filter != null && cats.none { it.equals(filter, true) }) filter = null
        val f = filter
        shown = if (f == null) snippets else snippets.filter { it.type.equals(f, true) }

        catBar.set(
            cats, f,
            onSelect = { filter = it; refresh() },
            onLongPress = { showCategoryMenu(it) },
            onAdd = { promptCategory(getString(R.string.dlg_new_category), "") { name -> addCategory(name) } }
        )
        empty.text = if (snippets.isEmpty()) {
            getString(R.string.empty_no_snippets)
        } else {
            getString(R.string.empty_no_snippets_cat)
        }
        adapter.notifyDataSetChanged()
        countLabel.text = if (f == null) getString(R.string.label_snippets_count, shown.size) else "${f.uppercase()} (${shown.size})"
    }

    private fun persist() {
        SnippetStore.save(this, snippets)
        refresh()
        refreshOverlay()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("snippet", text))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    // ---------- Kategori ----------

    private fun promptCategory(title: String, initial: String, onOk: (String) -> Unit) {
        val et = Ui.field(this, getString(R.string.hint_category_name), false)
        et.setText(initial)
        et.setSelection(et.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun addCategory(name: String): Boolean {
        val cats = Categories.all(this, snippets)
        if (cats.any { it.equals(name, true) }) {
            Toast.makeText(this, getString(R.string.toast_category_exists), Toast.LENGTH_SHORT).show()
            return false
        }
        cats.add(name)
        Categories.save(this, cats)
        refresh()
        refreshOverlay()
        return true
    }

    private fun showCategoryMenu(name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.menu_rename), getString(R.string.menu_delete))) { _, which ->
                if (which == 0) {
                    promptCategory(getString(R.string.dlg_rename_category), name) { newName -> renameCategory(name, newName) }
                } else {
                    deleteCategory(name)
                }
            }.show()
    }

    private fun renameCategory(old: String, new: String) {
        val cats = Categories.all(this, snippets)
        if (!old.equals(new, true) && cats.any { it.equals(new, true) }) {
            Toast.makeText(this, getString(R.string.toast_category_exists), Toast.LENGTH_SHORT).show()
            return
        }
        val idx = cats.indexOfFirst { it.equals(old, true) }
        if (idx >= 0) cats[idx] = new
        Categories.save(this, cats)
        snippets.filter { it.type.equals(old, true) }.forEach { it.type = new }
        if (filter.equals(old, true)) filter = new
        SnippetStore.save(this, snippets)
        refresh()
        refreshOverlay()
    }

    private fun deleteCategory(name: String) {
        if (snippets.any { it.type.equals(name, true) }) {
            Toast.makeText(this, getString(R.string.toast_category_not_empty), Toast.LENGTH_LONG).show()
            return
        }
        val cats = Categories.all(this, snippets)
        cats.removeAll { it.equals(name, true) }
        Categories.save(this, cats)
        refresh()
        refreshOverlay()
    }

    // ---------- Editor snippet ----------

    private fun showEditor(existing: Snippet?) {
        val cats = Categories.all(this, snippets)
        var selected = existing?.type ?: filter ?: cats.firstOrNull() ?: "Code"

        val etTitle = Ui.field(this, getString(R.string.hint_title), false)
        etTitle.setText(existing?.title ?: "")

        val etContent = Ui.field(this, getString(R.string.hint_content), true)
        etContent.setText(existing?.content ?: "")

        val bar = CategoryBar(this)
        fun renderBar() {
            bar.set(
                cats, selected,
                onSelect = { if (it != null) { selected = it; renderBar() } },
                onAdd = {
                    promptCategory(getString(R.string.dlg_new_category), "") { name ->
                        if (cats.none { c -> c.equals(name, true) }) {
                            cats.add(name)
                            Categories.save(this, cats)
                        }
                        selected = name
                        renderBar()
                    }
                },
                showAll = false
            )
        }
        renderBar()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(etTitle)
        box.addView(Ui.label(this, getString(R.string.label_category)))
        box.addView(bar.view)
        box.addView(Ui.label(this, getString(R.string.label_content)))
        box.addView(etContent)

        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.dlg_new_snippet) else getString(R.string.dlg_edit_snippet))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val t = etTitle.text.toString().ifBlank { getString(R.string.untitled) }
                val c = etContent.text.toString()
                if (existing == null) {
                    snippets.add(0, Snippet(System.currentTimeMillis(), t, c, selected))
                } else {
                    existing.title = t
                    existing.content = c
                    existing.type = selected
                }
                persist()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ---------- Menu pengaturan ----------

    private fun showMenu(anchor: View) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor, Gravity.END)
        menu.menu.add(0, 0, 0, getString(R.string.menu_overlay_size))
        menu.menu.add(0, 1, 1, getString(R.string.menu_qs_tile))
        menu.menu.add(0, 2, 2, getString(R.string.menu_allow_background))
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> showSizeDialog()
                1 -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.menu_qs_tile))
                    .setMessage(getString(R.string.qs_tile_help))
                    .setPositiveButton(getString(R.string.btn_got_it), null)
                    .show()
                2 -> requestBatteryExemption()
            }
            true
        }
        menu.show()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, getString(R.string.toast_already_allowed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- Ukuran overlay ----------

    private fun showSizeDialog() {
        var w = Prefs.panelW(this)
        var h = Prefs.panelH(this)
        var b = Prefs.bubbleSize(this)

        fun slider(
            title: String, min: Int, max: Int, start: Int, unit: String, onChange: (Int) -> Unit
        ): View {
            val label = TextView(this)
            label.textSize = 13f
            label.setTextColor(Ui.TEXT)
            label.text = "$title: $start$unit"
            val sb = SeekBar(this)
            sb.max = max - min
            sb.progress = start - min
            sb.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            sb.thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            sb.progressBackgroundTintList = ColorStateList.valueOf(Ui.STROKE)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p
                    label.text = "$title: $v$unit"
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(0, dp(12), 0, 0)
            col.addView(label)
            col.addView(sb)
            return col
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(slider(getString(R.string.slider_panel_width), Prefs.PANEL_W_MIN, Prefs.PANEL_W_MAX, w, "%") { w = it })
        box.addView(slider(getString(R.string.slider_panel_height), Prefs.PANEL_H_MIN, Prefs.PANEL_H_MAX, h, "%") { h = it })
        box.addView(slider(getString(R.string.slider_icon_size), Prefs.BUBBLE_MIN, Prefs.BUBBLE_MAX, b, "dp") { b = it })
        val tip = TextView(this)
        tip.text = getString(R.string.size_tip)
        tip.textSize = 12f
        tip.setTextColor(Ui.TEXT_DIM)
        tip.setPadding(0, dp(16), 0, 0)
        box.addView(tip)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_overlay_size))
            .setView(box)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                Prefs.setSizes(this, w, h, b)
                refreshOverlay()
            }
            .setNeutralButton(getString(R.string.btn_reset)) { _, _ ->
                Prefs.setSizes(this, 88, 42, 52)
                Prefs.resetPositions(this)
                refreshOverlay()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun refreshOverlay() {
        if (OverlayService.running) {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH)
            )
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_allow_overlay_first), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        )
    }

    override fun onResume() {
        super.onResume()
        snippets = SnippetStore.load(this)
        refresh()
        CrashLog.consume(this)?.let { showCrash(it) }
    }

    /** Tampilkan laporan crash terakhir supaya bisa disalin dan dikirim. */
    private fun showCrash(report: String) {
        val tv = TextView(this)
        tv.text = report
        tv.textSize = 11f
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(Ui.TEXT)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(20), dp(8), dp(20), dp(8))
        val scroll = ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_crash_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_copy)) { _, _ -> copy(report) }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }
}
