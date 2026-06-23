# 📱 Panduan Build APK — Internal Audio Recorder

## Struktur Project

```
audio_capture/
├── lib/
│   └── main.dart                    ← UI Flutter (tombol rekam, status, log)
├── android/
│   ├── AndroidManifest.xml          ← Permissions + Service declaration
│   ├── app/
│   │   ├── build.gradle             ← minSdk=29 (Android 10 wajib!)
│   │   └── src/main/kotlin/com/audiocapture/recorder/
│   │       ├── MainActivity.kt      ← MethodChannel + MediaProjection dialog
│   │       └── AudioCaptureService.kt ← Service inti (AudioPlaybackCapture + mic)
├── pubspec.yaml                     ← Flutter dependencies
└── BUILD_GUIDE.md                   ← File ini
```

---

## Prasyarat

| Tool | Versi | Cara Install |
|------|-------|-------------|
| Flutter SDK | 3.10+ | https://docs.flutter.dev/get-started/install |
| Android Studio | Hedgehog+ | https://developer.android.com/studio |
| Android SDK | API 34 | Lewat Android Studio SDK Manager |
| Java/JDK | 17 | Sudah bundled di Android Studio |

---

## Langkah Build APK

### 1. Setup `local.properties`

Buka file `android/local.properties` dan isi path yang benar:

**Windows:**
```properties
flutter.sdk=C:\Users\NamaKamu\flutter
sdk.dir=C:\Users\NamaKamu\AppData\Local\Android\Sdk
```

**Mac / Linux:**
```properties
flutter.sdk=/Users/NamaKamu/flutter
sdk.dir=/Users/NamaKamu/Library/Android/sdk
```

### 2. Install Dependencies

```bash
cd audio_capture
flutter pub get
```

### 3. Build APK Debug (untuk tes)

```bash
flutter build apk --debug
```

APK tersimpan di: `build/app/outputs/flutter-apk/app-debug.apk`

### 4. Build APK Release (untuk distribusi)

```bash
flutter build apk --release --split-per-abi
```

APK tersimpan di:
- `build/app/outputs/flutter-apk/app-arm64-v8a-release.apk` ← Untuk HP modern (64-bit)
- `build/app/outputs/flutter-apk/app-armeabi-v7a-release.apk` ← Untuk HP lama (32-bit)

### 5. Install langsung ke HP (opsional)

```bash
# Sambungkan HP via USB, aktifkan USB Debugging
flutter run --release
```

---

## Cara Pakai Aplikasi

1. **Buka aplikasi** di HP
2. **Tap tombol REKAM** (lingkaran biru)
3. Akan muncul **dialog sistem "Mulai merekam layar?"** — ini adalah dialog MediaProjection yang WAJIB disetujui agar audio internal bisa direkam. Tap **"Mulai sekarang"**
4. Aplikasi akan meminta izin **Mikrofon** — tap **Izinkan**
5. Status berubah menjadi merah dan timer mulai berjalan
6. Buka Discord, mulai voice call, mainkan game → semua audio direkam
7. **Tap tombol BERHENTI** untuk mengakhiri rekaman
8. File tersimpan di: **Music/AudioCapture/** di storage HP

---

## Penjelasan Teknis (Kenapa Kode Ini Bekerja)

### Masalah: Kenapa audio internal tidak bisa direkam dengan cara biasa?

Android secara sengaja memblokir akses langsung ke audio internal untuk melindungi privasi. Aplikasi biasa hanya bisa akses `AudioSource.MIC`.

### Solusi: MediaProjection + AudioPlaybackCaptureConfiguration

```
MediaProjection API
    └── AudioPlaybackCaptureConfiguration  ← Kunci utama (API 29+)
            └── AudioRecord                ← Menangkap audio dari semua app
                    ├── USAGE_MEDIA        ← Spotify, YouTube, dll
                    ├── USAGE_GAME         ← Game audio
                    └── USAGE_VOICE_COMMUNICATION ← Discord voice
```

### Kenapa harus Foreground Service?

Android OS akan mematikan proses yang berjalan di background untuk menghemat baterai. Foreground Service + Notifikasi persisten = OS tidak akan mematikan proses ini.

### Mixing Mic + Internal Audio

```
Thread 1: AudioRecord (Mic)      → Buffer Mic     ─┐
                                                    ├→ Mixer Thread → WAV File
Thread 2: AudioRecord (Internal) → Buffer Internal ─┘
```

Formula mixing: `output[i] = clamp(mic[i] + internal[i], -32768, 32767)`

---

## Troubleshooting

| Problem | Solusi |
|---------|--------|
| Build error "minSdk too low" | Pastikan `minSdk = 29` di `build.gradle` |
| "AudioPlaybackCapture gagal" | HP harus Android 10+ |
| Audio Discord tidak terekam | Cek apakah Discord menggunakan `allowAudioPlaybackCapture=false` (jarang) |
| File tidak muncul di galeri | Cek folder **Music/AudioCapture** di file manager |
| Rekaman berhenti sendiri | Pastikan izin "Rekam di background" diaktifkan untuk app ini di pengaturan HP |
| Error "FOREGROUND_SERVICE_TYPE" | Sudah ditangani — service dideklarasikan dengan `foregroundServiceType="mediaProjection"` |

---

## Batasan yang Perlu Diketahui

- ✅ Discord voice call audio → **BISA direkam**
- ✅ Game audio → **BISA direkam**  
- ✅ Suara mic pengguna → **BISA direkam**
- ⚠️ Spotify / YouTube Music → **TIDAK BISA** (mereka menyetel `allowAudioPlaybackCapture=false`)
- ⚠️ Perlu Android 10 (API 29) minimum
- ⚠️ Dialog izin MediaProjection **HARUS** disetujui user setiap kali rekaman dimulai (ini by design Android)
