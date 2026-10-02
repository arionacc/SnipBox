package com.arionacc.snipbox

import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Variabel isian di dalam snippet.
 *
 *   {{nama}}            -> diisi saat disalin
 *   {{host|localhost}}  -> sama, tapi kolomnya sudah terisi nilai bawaan "localhost"
 *
 * Nama yang sama di beberapa tempat cukup diisi sekali.
 */
object Template {
    data class Var(val name: String, val default: String)

    private val REGEX = Regex("""\{\{\s*([^{}|\n]{1,40}?)\s*(?:\|([^{}\n]*))?\}\}""")

    fun variables(content: String): List<Var> {
        val seen = LinkedHashMap<String, Var>()
        REGEX.findAll(content).forEach { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty() && !seen.containsKey(name)) {
                seen[name] = Var(name, m.groupValues[2])
            }
        }
        return seen.values.toList()
    }

    fun fill(content: String, values: Map<String, String>): String =
        REGEX.replace(content) { m -> values[m.groupValues[1].trim()] ?: m.value }
}

/**
 * Dialog pengisian variabel. [onResult] dipanggil tepat sekali:
 * teks akhir yang siap disalin, atau null kalau dibatalkan.
 */
fun showFillDialog(activity: AppCompatActivity, snippet: Snippet, onResult: (String?) -> Unit) {
    val vars = Template.variables(snippet.content)
    var done = false
    fun finish(text: String?) {
        if (done) return
        done = true
        onResult(text)
    }

    val fields = LinkedHashMap<String, EditText>()
    val box = LinearLayout(activity)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
    vars.forEach { v ->
        box.addView(Ui.label(activity, v.name))
        val et = Ui.field(activity, v.name, false)
        et.setText(v.default)
        et.setSelection(et.text.length)
        fields[v.name] = et
        box.addView(et, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    val scroll = ScrollView(activity)
    scroll.addView(box)

    val dialog = AlertDialog.Builder(activity)
        .setTitle(snippet.title)
        .setView(scroll)
        .setPositiveButton(activity.getString(R.string.btn_copy)) { _, _ ->
            val values = fields.mapValues { it.value.text.toString() }
            finish(Template.fill(snippet.content, values))
        }
        // Untuk snippet yang memang berisi {{ }} sungguhan (misalnya template Helm atau GitHub Actions)
        .setNeutralButton(activity.getString(R.string.btn_copy_as_is)) { _, _ -> finish(snippet.content) }
        .setNegativeButton(activity.getString(R.string.btn_cancel)) { _, _ -> finish(null) }
        .setOnCancelListener { finish(null) }
        .create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
    fields.values.firstOrNull()?.requestFocus()
}

/** Activity transparan kecil: dipakai overlay untuk menampilkan dialog isian di atas aplikasi lain. */
class FillActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "snippet_id"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val snippet = SnippetStore.load(this).firstOrNull { it.id == id }
        if (snippet == null) {
            finish()
            return
        }
        showFillDialog(this, snippet) { text ->
            if (text != null) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(snippet.toClip(text))
                android.widget.Toast.makeText(
                    this, getString(R.string.toast_copied), android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
