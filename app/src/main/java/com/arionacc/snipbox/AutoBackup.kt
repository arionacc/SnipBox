package com.arionacc.snipbox

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.nio.CharBuffer
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Backup otomatis berkala ke folder pilihan pengguna (lewat Storage Access Framework).
 *
 * - Dijadwalkan dengan WorkManager (harian atau mingguan), tetap jalan setelah HP restart.
 * - Kalau enkripsi aktif, kata sandi disimpan terbungkus kunci Android Keystore.
 * - Kalau enkripsi mati, snippet sensitif TIDAK ikut dicadangkan (file berupa teks biasa).
 * - Hanya [KEEP] file terbaru berawalan [PREFIX] yang disimpan, yang lama dihapus otomatis.
 */
object AutoBackup {
    private const val WORK_NAME = "snipbox_auto_backup"
    const val PREFIX = "snipbox-auto-"
    const val KEEP = 5

    /** Pasang atau batalkan jadwal sesuai pengaturan tersimpan. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        val mode = Prefs.autoMode(ctx)
        if (mode == 0 || Prefs.autoTree(ctx) == null) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val days = if (mode == 1) 1L else 7L
        val request = PeriodicWorkRequest.Builder(AutoBackupWorker::class.java, days, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Jalankan satu kali backup sekarang. Mengembalikan null kalau berhasil, atau pesan galat. */
    fun run(ctx: Context): String? {
        val error: String? = try {
            doBackup(ctx)
            null
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
        Prefs.setAutoResult(ctx, System.currentTimeMillis(), error)
        return error
    }

    private fun doBackup(ctx: Context) {
        val treeStr = Prefs.autoTree(ctx) ?: throw IOException("No folder")
        val tree = Uri.parse(treeStr)
        val resolver = ctx.contentResolver
        val encrypt = Prefs.autoEncrypt(ctx)

        val all = SnippetStore.load(ctx)
        val list = if (encrypt) all else all.filter { !it.sensitive }
        val plain = Backup.export(ctx, list)

        val text = if (encrypt) {
            val wrapped = Prefs.autoPass(ctx) ?: throw IOException("Password missing")
            val pass = SecretBox.unwrap(wrapped)
            try {
                Backup.encrypt(plain, pass)
            } finally {
                pass.fill('\u0000')
            }
        } else {
            plain
        }

        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = "$PREFIX$stamp${if (encrypt) "-enc" else ""}.json"
        val doc = DocumentsContract.createDocument(resolver, parent, "application/json", name)
            ?: throw IOException("Cannot create file")
        resolver.openOutputStream(doc, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Cannot open file")

        prune(ctx, tree, treeId)
    }

    /** Hapus backup otomatis lama; file lain di folder itu tidak disentuh. */
    private fun prune(ctx: Context, tree: Uri, treeId: String) {
        try {
            val resolver = ctx.contentResolver
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val found = mutableListOf<Pair<String, String>>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && n.endsWith(".json")) found.add(n to id)
                }
            }
            found.sortedByDescending { it.first }.drop(KEEP).forEach { (_, id) ->
                try {
                    DocumentsContract.deleteDocument(
                        resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        // Selalu success: kegagalan dicatat di pengaturan, dan jadwal berikutnya mencoba lagi.
        AutoBackup.run(applicationContext)
        return Result.success()
    }
}

/** Membungkus kata sandi dengan kunci AES yang tersimpan di Android Keystore. */
object SecretBox {
    private const val ALIAS = "snipbox_autobackup_key"
    private const val PROVIDER = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun wrap(password: CharArray): String {
        val buf = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(bytes)
            return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    fun unwrap(wrapped: String): CharArray {
        val parts = wrapped.split(":")
        if (parts.size != 2) throw IOException("Password missing")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val bytes = cipher.doFinal(ct)
        try {
            val chars = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
            return CharArray(chars.remaining()).also { chars.get(it) }
        } finally {
            bytes.fill(0)
        }
    }
}
