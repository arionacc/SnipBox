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
import android.provider.DocumentsContract
import android.text.InputType
import android.provider.Settings
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var snippets: MutableList<Snippet> = mutableListOf()
    private var shown: List<Snippet> = emptyList()
    private var filter: String? = null
    private lateinit var adapter: SnippetAdapter
    private lateinit var countLabel: TextView
    private lateinit var catBar: CategoryBar
    private lateinit var empty: TextView

    // Izin notifikasi (Android 13+). Layanan overlay dijalankan setelah pengguna memilih.
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        launchOverlayService()
        if (!granted) showNotificationsOffDialog()
    }

    // Pemilih file bawaan Android (Storage Access Framework): bisa dari folder mana pun,
    // tanpa izin penyimpanan tambahan.
    // Kata sandi export disimpan sementara sampai pengguna memilih lokasi file.
    private var pendingExportPassword: CharArray? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            doExport(uri)
        } else {
            pendingExportPassword?.fill('\u0000')
            pendingExportPassword = null
        }
    }

    // Pilihan sementara di dialog backup otomatis saat pengguna pergi memilih folder
    private var draftMode: Int? = null
    private var draftEncrypt: Boolean? = null

    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                val old = Prefs.autoTree(this)
                if (old != null && old != uri.toString()) {
                    try { contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) } catch (_: Exception) {}
                }
                Prefs.setAutoTree(this, uri.toString())
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_folder_failed), Toast.LENGTH_LONG).show()
            }
        }
        showAutoBackupDialog()
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) doImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)

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
            copySnippet(shown[pos])
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val target = shown[pos]
            AlertDialog.Builder(this)
                .setTitle(target.title)
                .setItems(
                    arrayOf(
                        getString(R.string.menu_edit),
                        getString(if (target.pinned) R.string.menu_unpin else R.string.menu_pin),
                        getString(R.string.menu_delete)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> showEditor(target)
                        1 -> {
                            target.pinned = !target.pinned
                            persist()
                        }
                        else -> {
                            snippets.remove(target)
                            persist()
                        }
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
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, root)
        bars.isAppearanceLightStatusBars = !Ui.isDark(this)
        bars.isAppearanceLightNavigationBars = !Ui.isDark(this)
        refresh()
    }

    private fun refresh() {
        val cats = Categories.all(this, snippets)
        if (filter != null && cats.none { it.equals(filter, true) }) filter = null
        val f = filter
        shown = (if (f == null) snippets else snippets.filter { it.type.equals(f, true) }).pinnedFirst()

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

    private fun copySnippet(snippet: Snippet) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Template.variables(snippet.content).isEmpty()) {
            cm.setPrimaryClip(snippet.toClip())
            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            return
        }
        // Ada variabel {{...}}: minta pengguna mengisi dulu
        showFillDialog(this, snippet) { text ->
            if (text != null) {
                cm.setPrimaryClip(snippet.toClip(text))
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
        }
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

        val varHint = TextView(this)
        varHint.text = getString(R.string.hint_variables)
        varHint.textSize = 12f
        varHint.setTextColor(Ui.TEXT_DIM)
        varHint.setPadding(dp(4), dp(6), 0, 0)

        val cbSensitive = CheckBox(this)
        cbSensitive.text = getString(R.string.cb_sensitive)
        cbSensitive.textSize = 14f
        cbSensitive.setTextColor(Ui.TEXT)
        cbSensitive.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
        cbSensitive.isChecked = existing?.sensitive ?: false
        val sensitiveHint = TextView(this)
        sensitiveHint.text = getString(R.string.cb_sensitive_hint)
        sensitiveHint.textSize = 12f
        sensitiveHint.setTextColor(Ui.TEXT_DIM)
        sensitiveHint.setPadding(dp(4), 0, 0, 0)

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
        box.addView(varHint)
        box.addView(cbSensitive, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(12) })
        box.addView(sensitiveHint)

        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) getString(R.string.dlg_new_snippet) else getString(R.string.dlg_edit_snippet))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val t = etTitle.text.toString().ifBlank { getString(R.string.untitled) }
                val c = etContent.text.toString()
                if (existing == null) {
                    snippets.add(0, Snippet(System.currentTimeMillis(), t, c, selected, cbSensitive.isChecked))
                } else {
                    existing.title = t
                    existing.content = c
                    existing.type = selected
                    existing.sensitive = cbSensitive.isChecked
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
        menu.menu.add(0, 6, 1, getString(R.string.menu_theme))
        menu.menu.add(0, 7, 2, getString(R.string.menu_auto_backup))
        menu.menu.add(0, 3, 3, getString(R.string.menu_export))
        menu.menu.add(0, 4, 4, getString(R.string.menu_import))
        menu.menu.add(0, 1, 5, getString(R.string.menu_qs_tile))
        menu.menu.add(0, 2, 6, getString(R.string.menu_allow_background))
        menu.menu.add(0, 5, 7, getString(R.string.menu_about))
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> showSizeDialog()
                1 -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.menu_qs_tile))
                    .setMessage(getString(R.string.qs_tile_help))
                    .setPositiveButton(getString(R.string.btn_got_it), null)
                    .show()
                2 -> requestBatteryExemption()
                3 -> showExportDialog()
                4 -> importLauncher.launch(arrayOf("*/*"))
                5 -> showAbout()
                6 -> showThemeDialog()
                7 -> showAutoBackupDialog()
            }
            true
        }
        menu.show()
    }

    // ---------- Export / Import ----------

    private fun passwordField(hint: String): EditText {
        val et = Ui.field(this, hint, false)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return et
    }

    private fun CharSequence.toChars(): CharArray = CharArray(length) { this[it] }

    /** Dialog pilihan export: biasa atau terenkripsi dengan kata sandi. */
    private fun showExportDialog() {
        val cb = CheckBox(this)
        cb.text = getString(R.string.cb_encrypt)
        cb.textSize = 14f
        cb.setTextColor(Ui.TEXT)
        cb.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
        cb.isChecked = snippets.any { it.sensitive }

        val etPass = passwordField(getString(R.string.hint_password))
        val etPass2 = passwordField(getString(R.string.hint_password_confirm))

        val note = TextView(this)
        note.textSize = 12f
        note.setTextColor(Ui.TEXT_DIM)
        note.setPadding(dp(4), dp(8), 0, 0)

        val passBox = LinearLayout(this)
        passBox.orientation = LinearLayout.VERTICAL
        passBox.addView(etPass, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        passBox.addView(etPass2, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })

        fun sync() {
            passBox.visibility = if (cb.isChecked) View.VISIBLE else View.GONE
            note.text = getString(
                if (cb.isChecked) R.string.export_note_encrypted else R.string.export_note_plain
            )
        }
        cb.setOnCheckedChangeListener { _, _ -> sync() }
        sync()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(cb)
        box.addView(passBox)
        box.addView(note)

        val scroll = ScrollView(this)
        scroll.addView(box)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_export))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_continue), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        dialog.setOnShowListener {
            // Listener manual supaya dialog tidak tertutup saat input belum valid
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!cb.isChecked) {
                    pendingExportPassword = null
                    dialog.dismiss()
                    exportLauncher.launch(Backup.fileName(false))
                    return@setOnClickListener
                }
                val p1 = etPass.text.toChars()
                val p2 = etPass2.text.toChars()
                val ok = p1.contentEquals(p2)
                when {
                    p1.size < Backup.PASSWORD_MIN -> {
                        Toast.makeText(
                            this, getString(R.string.toast_password_short, Backup.PASSWORD_MIN), Toast.LENGTH_LONG
                        ).show()
                        p1.fill('\u0000'); p2.fill('\u0000')
                    }
                    !ok -> {
                        Toast.makeText(this, getString(R.string.toast_password_mismatch), Toast.LENGTH_LONG).show()
                        p1.fill('\u0000'); p2.fill('\u0000')
                    }
                    else -> {
                        p2.fill('\u0000')
                        pendingExportPassword = p1
                        dialog.dismiss()
                        exportLauncher.launch(Backup.fileName(true))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun doExport(uri: Uri) {
        val password = pendingExportPassword
        pendingExportPassword = null
        val count = snippets.size
        val plain = Backup.export(this, snippets)

        fun write(text: String) {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("Cannot open file")
        }

        if (password == null) {
            try {
                write(plain)
                Toast.makeText(this, getString(R.string.toast_export_ok, count), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.toast_export_failed, e.message ?: ""), Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // Derivasi kunci butuh beberapa ratus milidetik, jadi dijalankan di luar thread utama.
        Toast.makeText(this, getString(R.string.toast_encrypting), Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                write(Backup.encrypt(plain, password))
                null
            } catch (e: Exception) {
                e.message ?: ""
            } finally {
                password.fill('\u0000')
            }
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(
                        this, getString(R.string.toast_export_ok_encrypted, count), Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.toast_export_failed, result), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun doImport(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw java.io.IOException("Cannot open file")
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.toast_import_failed, e.message ?: ""), Toast.LENGTH_LONG
            ).show()
            return
        }
        if (Backup.isEncrypted(raw)) {
            askPasswordThenImport(raw, false)
        } else {
            finishImport(raw)
        }
    }

    private fun askPasswordThenImport(raw: String, retry: Boolean) {
        val et = passwordField(getString(R.string.hint_password))
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        AlertDialog.Builder(this)
            .setTitle(getString(if (retry) R.string.dlg_password_wrong else R.string.dlg_password_needed))
            .setMessage(getString(R.string.dlg_password_message))
            .setView(box)
            .setPositiveButton(getString(R.string.btn_open)) { _, _ ->
                val pass = et.text.toChars()
                Toast.makeText(this, getString(R.string.toast_decrypting), Toast.LENGTH_SHORT).show()
                Thread {
                    var plain: String? = null
                    var error: Exception? = null
                    try {
                        plain = Backup.decrypt(raw, pass)
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        pass.fill('\u0000')
                    }
                    val decrypted = plain
                    val failure = error
                    runOnUiThread {
                        when {
                            decrypted != null -> finishImport(decrypted)
                            failure is Backup.WrongPasswordException -> askPasswordThenImport(raw, true)
                            else -> Toast.makeText(
                                this, getString(R.string.toast_import_invalid), Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun finishImport(json: String) {
        try {
            val result = Backup.import(this, json, snippets)
            persist()
            Toast.makeText(
                this,
                getString(R.string.toast_import_ok, result.added, result.skipped),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Backup.InvalidBackupException) {
            Toast.makeText(this, getString(R.string.toast_import_invalid), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.toast_import_failed, e.message ?: ""), Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- Tema ----------

    private fun showThemeDialog() {
        val names = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_theme))
            .setSingleChoiceItems(names, Prefs.theme(this)) { d, which ->
                d.dismiss()
                if (which != Prefs.theme(this)) {
                    Prefs.setTheme(this, which)
                    applyNightMode(this) // activity dibuat ulang otomatis kalau tampilan berubah
                    refreshOverlay()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ---------- Backup otomatis ----------

    private fun folderLabel(): String {
        val t = Prefs.autoTree(this) ?: return getString(R.string.auto_no_folder)
        return try {
            DocumentsContract.getTreeDocumentId(Uri.parse(t)).substringAfter(':').ifEmpty { "/" }
        } catch (e: Exception) {
            t
        }
    }

    private fun showAutoBackupDialog() {
        val mode0 = draftMode ?: Prefs.autoMode(this)
        val enc0 = draftEncrypt ?: Prefs.autoEncrypt(this)
        draftMode = null
        draftEncrypt = null

        var dialogRef: AlertDialog? = null

        // Frekuensi
        val group = RadioGroup(this)
        intArrayOf(R.string.auto_off, R.string.auto_daily, R.string.auto_weekly).forEachIndexed { i, res ->
            val rb = RadioButton(this)
            rb.id = 1000 + i
            rb.text = getString(res)
            rb.textSize = 14f
            rb.setTextColor(Ui.TEXT)
            rb.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            group.addView(rb)
        }
        group.check(1000 + mode0)
        fun selectedMode(): Int = (group.checkedRadioButtonId - 1000).coerceIn(0, 2)

        // Enkripsi
        val cb = CheckBox(this)
        cb.text = getString(R.string.cb_encrypt)
        cb.textSize = 14f
        cb.setTextColor(Ui.TEXT)
        cb.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
        cb.isChecked = enc0

        val hasOldPass = Prefs.autoPass(this) != null
        val etPass = passwordField(
            getString(if (hasOldPass) R.string.hint_password_keep else R.string.hint_password)
        )

        val note = TextView(this)
        note.textSize = 12f
        note.setTextColor(Ui.TEXT_DIM)
        note.setPadding(dp(4), dp(8), 0, 0)

        // Folder tujuan
        val folderText = TextView(this)
        folderText.text = folderLabel()
        folderText.textSize = 13f
        folderText.setTextColor(Ui.TEXT)
        folderText.setPadding(dp(4), 0, 0, dp(8))
        val pick = Ui.pill(this, getString(R.string.btn_choose_folder), Ui.CARD, Ui.TEXT) {
            draftMode = selectedMode()
            draftEncrypt = cb.isChecked
            dialogRef?.dismiss()
            treeLauncher.launch(null)
        }

        // Status backup terakhir
        val status = TextView(this)
        status.textSize = 12f
        status.setTextColor(Ui.TEXT_DIM)
        status.setPadding(dp(4), dp(12), 0, 0)
        val last = Prefs.autoLastTime(this)
        val err = Prefs.autoLastError(this)
        status.text = when {
            last == 0L -> getString(R.string.auto_last_never)
            err == null -> getString(R.string.auto_last_ok, formatTime(last))
            else -> getString(R.string.auto_last_failed, formatTime(last), err)
        }

        fun sync() {
            etPass.visibility = if (cb.isChecked) View.VISIBLE else View.GONE
            note.text = getString(
                if (cb.isChecked) R.string.auto_note_encrypted else R.string.auto_note_plain,
                AutoBackup.KEEP
            )
        }
        cb.setOnCheckedChangeListener { _, _ -> sync() }
        sync()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(Ui.label(this, getString(R.string.label_auto_frequency)))
        box.addView(group)
        box.addView(Ui.label(this, getString(R.string.label_auto_folder)))
        box.addView(folderText)
        box.addView(pick, LinearLayout.LayoutParams(Ui.MATCH, dp(44)))
        box.addView(cb, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(16) })
        box.addView(etPass, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        box.addView(note)
        box.addView(status)

        val scroll = ScrollView(this)
        scroll.addView(box)

        // Simpan pengaturan. Mengembalikan false kalau ada isian yang belum valid.
        fun save(): Boolean {
            val mode = selectedMode()
            if (mode != 0 && Prefs.autoTree(this) == null) {
                Toast.makeText(this, getString(R.string.toast_choose_folder_first), Toast.LENGTH_LONG).show()
                return false
            }
            if (cb.isChecked) {
                val pass = etPass.text.toChars()
                if (!(pass.isEmpty() && hasOldPass)) {
                    if (pass.size < Backup.PASSWORD_MIN) {
                        Toast.makeText(
                            this, getString(R.string.toast_password_short, Backup.PASSWORD_MIN), Toast.LENGTH_LONG
                        ).show()
                        pass.fill('\u0000')
                        return false
                    }
                    try {
                        Prefs.setAutoPass(this, SecretBox.wrap(pass))
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.toast_keystore_failed), Toast.LENGTH_LONG).show()
                        return false
                    } finally {
                        pass.fill('\u0000')
                    }
                }
            } else {
                Prefs.setAutoPass(this, null)
            }
            Prefs.setAutoMode(this, mode)
            Prefs.setAutoEncrypt(this, cb.isChecked)
            AutoBackup.schedule(this)
            return true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_auto_backup))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_save), null)
            .setNeutralButton(getString(R.string.btn_backup_now), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()
        dialogRef = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (save()) {
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.toast_auto_saved), Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (Prefs.autoTree(this) == null) {
                    Toast.makeText(this, getString(R.string.toast_choose_folder_first), Toast.LENGTH_LONG).show()
                } else if (save()) {
                    dialog.dismiss()
                    runBackupNow()
                }
            }
        }
        dialog.show()
    }

    private fun runBackupNow() {
        Toast.makeText(this, getString(R.string.toast_backup_running), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val error = AutoBackup.run(app)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (error == null) getString(R.string.toast_backup_ok)
                    else getString(R.string.toast_backup_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun formatTime(millis: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    // ---------- Tentang ----------

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        fun body(text: String, size: Float = 14f, color: Int = Ui.TEXT): TextView {
            val tv = TextView(this)
            tv.text = text
            tv.textSize = size
            tv.setTextColor(color)
            tv.setLineSpacing(0f, 1.15f)
            return tv
        }

        val name = body("SnipBox", 24f)
        name.typeface = Typeface.DEFAULT_BOLD

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), dp(8))
        box.addView(name)
        box.addView(body(getString(R.string.about_version, version), 13f, Ui.TEXT_DIM))
        box.addView(body(getString(R.string.about_description)).apply { setPadding(0, dp(12), 0, 0) })
        box.addView(Ui.label(this, getString(R.string.about_made_by_label)))
        box.addView(body(getString(R.string.about_made_by)))
        box.addView(Ui.label(this, getString(R.string.about_license_label)))
        box.addView(body(getString(R.string.about_license)))
        box.addView(Ui.label(this, getString(R.string.about_source_label)))
        box.addView(body(getString(R.string.about_source)).apply {
            autoLinkMask = Linkify.WEB_URLS
            setLinkTextColor(Ui.TEXT)
        })

        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_about))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
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
            != PackageManager.PERMISSION_GRANTED &&
            !notifPermAsked()
        ) {
            // Jalankan layanan setelah dialog izin ditutup (lihat notifPermLauncher)
            markNotifPermAsked()
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchOverlayService()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            showNotificationsOffDialog()
        }
    }

    private fun launchOverlayService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        )
    }

    private fun notifPermAsked() =
        getSharedPreferences("snipbox_prefs", Context.MODE_PRIVATE).getBoolean("notif_asked", false)

    private fun markNotifPermAsked() {
        getSharedPreferences("snipbox_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("notif_asked", true).apply()
    }

    /** Notifikasi dimatikan: ikon melayang tetap jalan, tapi notifikasi tidak akan tampil. */
    private fun showNotificationsOffDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_notif_off_title))
            .setMessage(getString(R.string.dlg_notif_off_message))
            .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } catch (e: Exception) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setNegativeButton(getString(R.string.btn_later), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        snippets = SnippetStore.load(this)
        refresh()
        // Kalau notifikasi baru diizinkan lewat Pengaturan, tampilkan lagi tanpa mengganggu ikon
        if (OverlayService.running && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REPOST)
            )
        }
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
