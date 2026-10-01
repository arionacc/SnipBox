# SnipBox

🌐 [English](README.md) | **Bahasa Indonesia**

Aplikasi Android untuk menyimpan **code, function, dan command favorit** dan menyalinnya dengan satu ketukan, dari mana saja, lewat ikon melayang yang muncul di atas aplikasi lain.

Semua data tersimpan lokal di perangkat. Aplikasi ini tidak meminta izin internet.

## Fitur

- **Simpan snippet:** judul, isi, dan kategori. Ketuk snippet untuk menyalin ke clipboard.
- **Kategori buatan sendiri:** bawaan Code, Function, dan Command. Tambah, ganti nama, atau hapus sesuai kebutuhan (tekan lama pada kategori). Kategori yang masih berisi snippet tidak bisa dihapus.
- **Ikon melayang (bubble):** muncul di atas aplikasi lain. Ketuk untuk membuka panel snippet, geser ke mana saja di layar.
- **Panel overlay yang fleksibel:**
  - geser lewat garis kecil di bagian atas panel,
  - ubah ukuran dengan menarik sudut kanan bawah,
  - cari snippet dan saring per kategori.
- **Ukuran bisa diatur:** menu titik tiga, lalu "Ukuran overlay & ikon" (lebar panel, tinggi panel, ukuran ikon). Tombol Reset mengembalikan ukuran dan posisi ke default.
- **Tile Quick Settings:** nyalakan atau matikan ikon melayang dari panel cepat tanpa membuka aplikasi.
- **Mendukung landscape**, dan ikon tidak akan masuk ke bawah bilah navigasi.
- **Tampilan monochrome** (hitam, abu-abu, putih).

## Cara pakai

1. Buka SnipBox, ketuk **+ Tambah**, isi judul, pilih kategori, tulis isi snippet, lalu **Simpan**.
2. Ketuk **Aktifkan overlay**. Pertama kali, kamu akan diminta mengizinkan **Tampil di atas aplikasi lain**.
3. Ikon melayang muncul. Ketuk untuk membuka panel, ketuk snippet untuk menyalin, lalu tempel di aplikasi mana pun.
4. Ketuk **Matikan** (atau tombol Matikan di notifikasi) untuk menghentikan overlay.

### Menambahkan tile Quick Settings

Tarik panel notifikasi ke bawah, ketuk ikon pensil atau tombol tambah, lalu seret tile **SnipBox** ke panel cepat.

### Tips untuk HP Samsung (One UI) dan perangkat lain

Beberapa perangkat mematikan aplikasi di latar belakang untuk menghemat baterai. Kalau ikon melayang hilang sendiri, buka menu titik tiga, pilih **Izinkan jalan di latar belakang**, dan izinkan SnipBox.

## Izin yang dipakai

| Izin | Kegunaan |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Menampilkan ikon dan panel di atas aplikasi lain |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Menjaga overlay tetap hidup |
| `POST_NOTIFICATIONS` | Notifikasi overlay aktif (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opsional, agar overlay tidak dimatikan sistem |

## Unduh

Ambil APK terbaru dari halaman [Releases](../../releases), atau dari bagian **Artifacts** pada run yang berhasil di tab [Actions](../../actions). Untuk menginstalnya, izinkan **Instal aplikasi tidak dikenal** di perangkatmu.

## Build

Proyek ini memakai Gradle dan Kotlin tanpa library UI tambahan di luar AppCompat dan Material. Dibutuhkan JDK 17 dan Gradle 8.7 atau lebih baru (atau buka foldernya di Android Studio).

```bash
gradle assembleDebug
```

APK hasil build ada di `app/build/outputs/apk/debug/`.

### Build dengan GitHub Actions

Workflow di `.github/workflows/build.yml` membangun APK debug setiap kali ada push, lalu mengunggahnya sebagai artifact **SnipBox-debug-apk**. Kamu juga bisa menjalankannya manual dari tab Actions lewat **Run workflow**.

## Struktur kode

```
app/src/main/java/com/arionacc/snipbox/
├── MainActivity.kt        Layar utama, editor snippet, kategori, menu pengaturan
├── OverlayService.kt      Ikon melayang dan panel overlay (geser, ubah ukuran)
├── OverlayTileService.kt  Tile Quick Settings
├── Ui.kt                  Warna, komponen UI, adapter kartu snippet, chip kategori
├── Prefs.kt               Pengaturan ukuran/posisi dan penyimpanan kategori
├── Snippet.kt             Data class snippet dan penyimpanan lokal (SnippetStore)
└── SnipApp.kt             Application class dan pencatat crash terakhir
```

Kalau aplikasi berhenti tiba-tiba, saat dibuka lagi akan muncul dialog berisi detail error yang bisa disalin. Ini berguna untuk melaporkan bug.

## Kontribusi

Issue dan pull request dipersilakan. Untuk perubahan besar, buka issue dulu agar bisa didiskusikan.

## Lisensi

Dirilis di bawah [Lisensi MIT](LICENSE).
