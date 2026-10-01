package com.arionacc.snipbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export dan import data snippet dalam format .json.
 *
 * Bentuk file:
 * {
 *   "format": "snipbox-backup",
 *   "version": 1,
 *   "exportedAt": "...",
 *   "categories": ["Code", "Function", ...],
 *   "snippets": [ { "id": 1, "title": "...", "content": "...", "type": "Code" } ]
 * }
 */
object Backup {
    const val FORMAT = "snipbox-backup"
    const val VERSION = 1

    /** File bukan backup SnipBox yang valid. */
    class InvalidBackupException : Exception()

    data class ImportResult(val added: Int, val skipped: Int)

    /** Nama file bawaan saat export, misalnya snipbox-backup-20261001-1530.json */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "snipbox-backup-$stamp.json"
    }

    fun export(ctx: Context, snippets: List<Snippet>): String {
        val cats = JSONArray()
        Categories.all(ctx, snippets).forEach { cats.put(it) }

        val items = JSONArray()
        snippets.forEach { s ->
            items.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("content", s.content)
                    .put("type", s.type)
            )
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put("categories", cats)
            .put("snippets", items)
            .toString(2)
    }

    /**
     * Gabungkan isi file ke daftar [current] (tidak ada data yang dihapus).
     * Snippet yang sama persis (judul, isi, kategori) dilewati.
     * Kategori baru dari file otomatis ditambahkan.
     */
    fun import(ctx: Context, raw: String, current: MutableList<Snippet>): ImportResult {
        val text = raw.trim().removePrefix("\uFEFF")

        val root: Any = try {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: Exception) {
            throw InvalidBackupException()
        }

        val arr: JSONArray
        val fileCats: JSONArray?
        if (root is JSONArray) {
            arr = root
            fileCats = null
        } else {
            val obj = root as JSONObject
            arr = obj.optJSONArray("snippets") ?: throw InvalidBackupException()
            fileCats = obj.optJSONArray("categories")
        }

        val catList = Categories.all(ctx, current)

        if (fileCats != null) {
            for (i in 0 until fileCats.length()) {
                val name = fileCats.optString(i, "").trim()
                if (name.isNotEmpty() && catList.none { it.equals(name, true) }) catList.add(name)
            }
        }

        val usedIds = current.map { it.id }.toMutableSet()
        var nextId = System.currentTimeMillis()
        var added = 0
        var skipped = 0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            val rawTitle = o.optString("title", "").trim()
            val content = o.optString("content", "")
            if (rawTitle.isEmpty() && content.isEmpty()) {
                skipped++
                continue
            }
            val title = rawTitle.ifEmpty { ctx.getString(R.string.untitled) }

            // Samakan nama kategori dengan yang sudah ada (tanpa peduli huruf besar/kecil)
            val rawType = o.optString("type", "").trim().ifEmpty { "Code" }
            val type = catList.firstOrNull { it.equals(rawType, true) }
                ?: rawType.also { catList.add(it) }

            val duplicate = current.any {
                it.title == title && it.content == content && it.type.equals(type, true)
            }
            if (duplicate) {
                skipped++
                continue
            }

            var id = o.optLong("id", 0L)
            if (id == 0L || id in usedIds) {
                do {
                    nextId++
                } while (nextId in usedIds)
                id = nextId
            }
            usedIds.add(id)

            current.add(Snippet(id, title, content, type))
            added++
        }

        Categories.save(ctx, catList)
        return ImportResult(added, skipped)
    }
}
