# Cara bikin APK (untuk pemula, tanpa install Android Studio)

GitHub yang akan membuatkan APK-nya di server mereka (gratis). Kamu cuma upload folder ini.

1. **Buat akun GitHub** di https://github.com/signup (lewati kalau sudah punya).
2. Klik **+** (kanan atas) -> **New repository**. Nama: `yt-downloader-android`. Pilih **Private**. Klik **Create repository**.
3. **Upload project**
   - Di Mac, unzip file `yt-downloader-android.zip`.
   - Di Finder tekan **Cmd + Shift + .** supaya folder tersembunyi `.github` terlihat.
   - Di halaman repo klik **uploading an existing file**, lalu drag **semua isi** folder `yt-downloader-android` (termasuk `.github`, `app`, `build.gradle.kts`, dst) ke browser.
   - Tunggu selesai, klik **Commit changes**.
   - Cek: di repo harus ada folder `.github/workflows/build-apk.yml`. Kalau tidak ada: **Add file -> Create new file**, ketik nama `.github/workflows/build-apk.yml`, paste isi file itu, Commit.
4. Buka tab **Actions**. Kalau ada tombol "I understand my workflows, enable them", klik. Tunggu job **Build APK** sampai centang hijau (sekitar 5-10 menit). Kalau tidak jalan sendiri: klik **Build APK** di kiri -> **Run workflow**.
5. **Ambil APK**: tab **Code** -> kolom kanan **Releases** -> `YT Downloader build 1` -> klik `YT-Downloader.apk`. (Cadangan: Actions -> klik run -> bagian Artifacts.)
6. **Install di HP Android**: buka GitHub di browser HP (login), download `YT-Downloader.apk`, buka file-nya. Izinkan "Install dari sumber ini". Kalau muncul peringatan Play Protect, pilih **Install anyway**.
7. Buka app, izinkan notifikasi. Pertama kali tunggu 10-30 detik (menyiapkan yt-dlp), perlu internet.

Hasil unduhan: folder **Download/YT** di HP.

## Kalau build gagal (X merah)
Actions -> klik run yang merah -> klik job `build` -> copy bagian error (teks merah) -> kirim ke Claude.
