package com.arionacc.snipbox

import android.content.Context
import org.json.JSONArray

/** Pengaturan ukuran overlay + posisi ikon melayang. */
object Prefs {
    const val PANEL_W_MIN = 40
    const val PANEL_W_MAX = 100
    const val PANEL_H_MIN = 20
    const val PANEL_H_MAX = 85
    const val BUBBLE_MIN = 40
    const val BUBBLE_MAX = 72

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences("snipbox_prefs", Context.MODE_PRIVATE)

    fun panelW(ctx: Context): Int = sp(ctx).getInt("panel_w", 88)
    fun panelH(ctx: Context): Int = sp(ctx).getInt("panel_h", 42)
    fun bubbleSize(ctx: Context): Int = sp(ctx).getInt("bubble_size", 52)

    fun setSizes(ctx: Context, w: Int, h: Int, bubble: Int) {
        sp(ctx).edit()
            .putInt("panel_w", w.coerceIn(PANEL_W_MIN, PANEL_W_MAX))
            .putInt("panel_h", h.coerceIn(PANEL_H_MIN, PANEL_H_MAX))
            .putInt("bubble_size", bubble.coerceIn(BUBBLE_MIN, BUBBLE_MAX))
            .apply()
    }

    fun setPanelH(ctx: Context, h: Int) {
        sp(ctx).edit().putInt("panel_h", h.coerceIn(PANEL_H_MIN, PANEL_H_MAX)).apply()
    }

    /** Posisi ikon & panel disimpan sebagai pecahan area layar yang bisa dipakai (0..1). */
    fun bubbleXFrac(ctx: Context): Float = sp(ctx).getFloat("bubble_xf", 1f)
    fun bubbleYFrac(ctx: Context): Float = sp(ctx).getFloat("bubble_yf2", 0.35f)

    fun setBubblePos(ctx: Context, xFrac: Float, yFrac: Float) {
        sp(ctx).edit()
            .putFloat("bubble_xf", xFrac.coerceIn(0f, 1f))
            .putFloat("bubble_yf2", yFrac.coerceIn(0f, 1f))
            .apply()
    }

    fun panelXFrac(ctx: Context): Float = sp(ctx).getFloat("panel_xf", 0.5f)

    /** Negatif = belum pernah digeser (default: menempel di bawah). */
    fun panelYFrac(ctx: Context): Float = sp(ctx).getFloat("panel_yf", -1f)

    fun setPanelPos(ctx: Context, xFrac: Float, yFrac: Float) {
        sp(ctx).edit()
            .putFloat("panel_xf", xFrac.coerceIn(0f, 1f))
            .putFloat("panel_yf", yFrac.coerceIn(0f, 1f))
            .apply()
    }

    fun setPanelSize(ctx: Context, w: Int, h: Int) {
        sp(ctx).edit()
            .putInt("panel_w", w.coerceIn(PANEL_W_MIN, PANEL_W_MAX))
            .putInt("panel_h", h.coerceIn(PANEL_H_MIN, PANEL_H_MAX))
            .apply()
    }

    // ---- Tema: 0 = ikuti sistem, 1 = terang, 2 = gelap ----
    fun theme(ctx: Context): Int = sp(ctx).getInt("theme", 0).coerceIn(0, 2)
    fun setTheme(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("theme", mode.coerceIn(0, 2)).apply()
    }

    // ---- Backup otomatis ----
    /** 0 = mati, 1 = harian, 2 = mingguan */
    fun autoMode(ctx: Context): Int = sp(ctx).getInt("auto_mode", 0).coerceIn(0, 2)
    fun setAutoMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("auto_mode", mode.coerceIn(0, 2)).apply()
    }

    fun autoTree(ctx: Context): String? = sp(ctx).getString("auto_tree", null)
    fun setAutoTree(ctx: Context, uri: String?) {
        sp(ctx).edit().putString("auto_tree", uri).apply()
    }

    fun autoEncrypt(ctx: Context): Boolean = sp(ctx).getBoolean("auto_encrypt", false)
    fun setAutoEncrypt(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("auto_encrypt", on).apply()
    }

    /** Kata sandi backup otomatis, sudah dibungkus kunci Android Keystore (bukan teks asli). */
    fun autoPass(ctx: Context): String? = sp(ctx).getString("auto_pass", null)
    fun setAutoPass(ctx: Context, wrapped: String?) {
        sp(ctx).edit().putString("auto_pass", wrapped).apply()
    }

    fun autoLastTime(ctx: Context): Long = sp(ctx).getLong("auto_last_time", 0L)
    fun autoLastError(ctx: Context): String? = sp(ctx).getString("auto_last_error", null)
    fun setAutoResult(ctx: Context, time: Long, error: String?) {
        sp(ctx).edit().putLong("auto_last_time", time).putString("auto_last_error", error).apply()
    }

    fun resetPositions(ctx: Context) {
        sp(ctx).edit()
            .remove("bubble_xf").remove("bubble_yf2")
            .remove("panel_xf").remove("panel_yf")
            .apply()
    }
}

/**
 * Kategori buatan sendiri. Nama kategori disimpan di field `type` pada Snippet,
 * jadi tidak perlu mengubah Snippet / SnippetStore.
 */
object Categories {
    private val DEFAULTS = listOf("Code", "Function", "Command")

    private fun stored(ctx: Context): MutableList<String> {
        val raw = ctx.getSharedPreferences("snipbox_prefs", Context.MODE_PRIVATE)
            .getString("categories", null) ?: return DEFAULTS.toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            DEFAULTS.toMutableList()
        }
    }

    /** Daftar kategori + kategori lama yang masih dipakai snippet. */
    fun all(ctx: Context, snippets: List<Snippet>): MutableList<String> {
        val list = stored(ctx)
        snippets.map { it.type }
            .filter { it.isNotBlank() && list.none { c -> c.equals(it, true) } }
            .distinct()
            .forEach { list.add(it) }
        return list
    }

    fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences("snipbox_prefs", Context.MODE_PRIVATE).edit()
            .putString("categories", JSONArray(list).toString())
            .apply()
    }
}
