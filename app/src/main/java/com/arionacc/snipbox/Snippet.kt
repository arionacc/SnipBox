package com.arionacc.snipbox

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Satu snippet. Nama kategori disimpan di field `type`.
 * `sensitive` = isi disamarkan di daftar dan overlay (cocok untuk password / API key).
 */
data class Snippet(
    val id: Long,
    var title: String,
    var content: String,
    var type: String,
    var sensitive: Boolean = false
)

/** Isi yang disalin ke clipboard. Snippet sensitif ditandai supaya Android 13+ menyembunyikan pratinjaunya. */
fun Snippet.toClip(): ClipData {
    val clip = ClipData.newPlainText("snippet", content)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    return clip
}

/** Penyimpanan snippet secara lokal (JSON di SharedPreferences). */
object SnippetStore {
    private const val FILE = "snipbox_data"
    private const val KEY = "snippets"

    fun load(ctx: Context): MutableList<Snippet> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Snippet>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Snippet(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        title = o.optString("title", ""),
                        content = o.optString("content", ""),
                        type = o.optString("type", "Code"),
                        sensitive = o.optBoolean("sensitive", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Snippet>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("content", s.content)
                    .put("type", s.type)
                    .put("sensitive", s.sensitive)
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
