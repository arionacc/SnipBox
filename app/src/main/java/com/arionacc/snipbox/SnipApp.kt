package com.arionacc.snipbox

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class SnipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        applyNightMode(this)
    }
}

/** Terapkan pilihan tema (ikuti sistem / terang / gelap) ke seluruh aplikasi. */
fun applyNightMode(ctx: Context) {
    AppCompatDelegate.setDefaultNightMode(
        when (Prefs.theme(ctx)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}

/** Menyimpan stack trace crash terakhir agar bisa ditampilkan saat aplikasi dibuka lagi. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(app).writeText(
                    "Thread: ${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Baca lalu hapus, supaya dialog hanya muncul sekali. */
    fun consume(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = try { f.readText() } catch (_: Throwable) { null }
        f.delete()
        return text
    }
}
