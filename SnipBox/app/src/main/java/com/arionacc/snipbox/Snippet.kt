package com.arionacc.snipbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Satu snippet. Nama kategori disimpan di field `type`. */
data class Snippet(
    val id: Long,
    var title: String,
    var content: String,
    var type: String
)

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
                        type = o.optString("type", "Code")
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
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
