# 📲 Cara Build APK Lewat GitHub (Tanpa PC)

## Langkah 1 — Buat akun GitHub
Buka https://github.com dan daftar gratis (atau login kalau sudah punya).

---

## Langkah 2 — Buat repository baru
1. Tap tombol **"+"** → **"New repository"**
2. Nama repo: `audio-recorder` (bebas)
3. Pilih **Public**
4. Tap **"Create repository"**

---

## Langkah 3 — Upload semua file project

Di halaman repository yang baru dibuat:
1. Tap **"uploading an existing file"**
2. Extract file `.tar.gz` yang sudah didownload di HP/PC
3. Upload **semua isi folder** `android_audio_recorder/` ke GitHub
   - Pastikan struktur foldernya benar (lib/, android/, .github/, dll)
4. Tap **"Commit changes"**

> 💡 **Tips:** Kalau dari HP, pakai aplikasi **Working Copy** (iOS) atau **Pocket Git** (Android) untuk upload lebih mudah.

---

## Langkah 4 — Tunggu build otomatis

Setelah upload selesai:
1. Tap tab **"Actions"** di repository
2. Akan terlihat workflow **"Build APK"** sedang berjalan (lingkaran kuning = proses)
3. Tunggu ~5-10 menit sampai lingkaran berubah jadi ✅ hijau

---

## Langkah 5 — Download APK

Setelah ✅ hijau:
1. Tap workflow **"Build APK"** yang sudah selesai
2. Scroll ke bawah ke bagian **"Artifacts"**
3. Tap **"AudioRecorder-arm64"** → APK otomatis terdownload
   - Untuk HP modern (2018 ke atas) → pakai **arm64**
   - Untuk HP lama → pakai **armeabi**

---

## Langkah 6 — Install APK di HP

1. Buka file APK yang sudah didownload
2. Jika muncul peringatan "Install dari sumber tidak dikenal":
   - Buka **Pengaturan → Keamanan → Sumber tidak dikenal** → Aktifkan
3. Tap **Install**
4. Buka aplikasi **"Audio Recorder"**

---

## ⚠️ Penting saat pertama kali pakai

Saat tap tombol **REKAM**, akan muncul dua dialog:
1. **"Izin Mikrofon"** → Tap **Izinkan**
2. **"Mulai merekam layar?"** → Tap **Mulai sekarang**

Kedua izin ini **wajib** diberikan agar audio internal + mic bisa direkam.

File rekaman tersimpan di: **Penyimpanan Internal → Music → AudioCapture**
