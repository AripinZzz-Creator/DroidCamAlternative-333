# DroidCam Alternative (`com.aripin.webcam`) - Build, Installation & PC Webcam Guide

## Cara Tercepat Mendapatkan File `.APK` (Tanpa Instal Android Studio)
Proyek ini sudah dilengkapi workflow **GitHub Actions** otomatis (`.github/workflows/build-apk.yml`):
1. Buat repository baru (gratis) di **GitHub**.
2. Upload seluruh isi folder `DroidCamAlternative` (termasuk folder `.github`) ke repository tersebut.
3. Buka tab **Actions** pada repository GitHub Anda -> klik workflow **Build Android APK (Debug & Release)** -> klik **Run workflow**.
4. Setelah selesai (~3 menit), unduh file **`DroidCamAlternative-release-apk`** atau **`DroidCamAlternative-debug-apk`** langsung dari bagian **Artifacts** di halaman tersebut dan instal ke HP Android Anda.

## Cara Build Lokal dengan Android Studio
1. Buka **Android Studio** -> **Open** -> pilih folder `DroidCamAlternative`.
2. Tunggu Gradle Sync selesai, lalu klik **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
