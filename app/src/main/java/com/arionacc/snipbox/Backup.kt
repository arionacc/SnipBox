package com.arionacc.snipbox

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
    const val FORMAT_ENCRYPTED = "snipbox-backup-encrypted"
    const val VERSION = 1

    // Parameter enkripsi: AES-256-GCM, kunci diturunkan dari kata sandi lewat PBKDF2-HMAC-SHA256.
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KDF_ITERATIONS = 200_000
    private const val ITER_MIN = 50_000
    private const val ITER_MAX = 2_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    const val PASSWORD_MIN = 8

    /** File bukan backup SnipBox yang valid. */
    class InvalidBackupException : Exception()

    /** Kata sandi salah, atau file terenkripsi sudah rusak. */
    class WrongPasswordException : Exception()

    data class ImportResult(val added: Int, val skipped: Int)

    /** Nama file bawaan saat export, misalnya snipbox-backup-20261001-1530.json */
    fun fileName(encrypted: Boolean = false): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return if (encrypted) "snipbox-backup-$stamp-enc.json" else "snipbox-backup-$stamp.json"
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
                    .put("sensitive", s.sensitive)
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

            val sensitive = o.optBoolean("sensitive", false)

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

            current.add(Snippet(id, title, content, type, sensitive))
            added++
        }

        Categories.save(ctx, catList)
        return ImportResult(added, skipped)
    }

    // ---------- Enkripsi ----------

    /** True kalau [raw] adalah file backup terenkripsi SnipBox. */
    fun isEncrypted(raw: String): Boolean = try {
        JSONObject(raw.trim().removePrefix("\uFEFF")).optString("format") == FORMAT_ENCRYPTED
    } catch (e: Exception) {
        false
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Bungkus JSON backup [plain] menjadi JSON terenkripsi. Hasil tetap berupa file .json. */
    fun encrypt(plain: String, password: CharArray): String {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt, KDF_ITERATIONS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        return JSONObject()
            .put("format", FORMAT_ENCRYPTED)
            .put("version", VERSION)
            .put("cipher", "AES-256-GCM")
            .put("kdf", KDF)
            .put("iterations", KDF_ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString(2)
    }

    /** Buka file terenkripsi menjadi JSON backup biasa. */
    fun decrypt(raw: String, password: CharArray): String {
        val o = try {
            JSONObject(raw.trim().removePrefix("\uFEFF"))
        } catch (e: Exception) {
            throw InvalidBackupException()
        }
        if (o.optString("format") != FORMAT_ENCRYPTED) throw InvalidBackupException()

        val iterations = o.optInt("iterations", 0)
        if (o.optString("kdf") != KDF || iterations !in ITER_MIN..ITER_MAX) throw InvalidBackupException()

        val salt: ByteArray
        val iv: ByteArray
        val data: ByteArray
        try {
            salt = Base64.decode(o.getString("salt"), Base64.DEFAULT)
            iv = Base64.decode(o.getString("iv"), Base64.DEFAULT)
            data = Base64.decode(o.getString("data"), Base64.DEFAULT)
        } catch (e: Exception) {
            throw InvalidBackupException()
        }
        if (salt.isEmpty() || iv.size != IV_BYTES || data.size < GCM_TAG_BITS / 8) throw InvalidBackupException()

        return try {
            val key = deriveKey(password, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: BadPaddingException) {
            throw WrongPasswordException()
        }
    }
}
