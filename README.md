# EQ Adaptif

Equalizer 10 band untuk Android (9 ke atas) yang bekerja di **YouTube Music**, Spotify, dan aplikasi musik lain, tanpa root. Ada fitur **Auto-seimbang**: karakter suara tetap sama walau lagu berganti, tanpa mengubah preset.

## Cara kerja singkat

- **EQ**: aplikasi musik mengumumkan "sesi audio"-nya ke sistem. EQ Adaptif memasang efek `DynamicsProcessing` (EQ 10 band + limiter) ke sesi itu. Cara ini sama dengan yang dipakai Wavelet.
- **Auto-seimbang**: `Visualizer` membaca spektrum musik yang sedang diputar. Aplikasi menghitung keseimbangan bass, mid, dan treble rata-rata selama ±10 detik terakhir, membandingkannya dengan target, lalu menambahkan koreksi kecil per band (dibatasi ±dB, berubah perlahan). Preset Anda tetap berlaku di atasnya.
- Analisis memakai rata-rata bergeser ±10 detik, jadi koreksi ikut menyesuaikan sendiri saat lagu berganti.

## Build APK

Cara paling mudah adalah lewat GitHub:

1. Buat repo baru di GitHub, lalu unggah seluruh isi folder ini, termasuk folder `.github` dan `keystore`.
2. Buka tab **Actions** dan tunggu workflow **Build APK** selesai (±5 menit).
3. Buka **Releases** dan unduh `EQ-Adaptif.apk` dari HP.

Alternatif: buka folder ini dengan Android Studio, lalu jalankan **Build → Build APK(s)**.

## Pemasangan di HP

1. Pasang APK. Jika diminta, izinkan "Instal aplikasi tidak dikenal".
2. Buka EQ Adaptif, nyalakan **Equalizer aktif**, dan izinkan *rekam audio* serta *notifikasi*.
3. Buka YouTube Music dan putar lagu. Jika status belum "Terhubung ke: YouTube Music", hentikan lalu putar ulang. Bisa juga tutup paksa YouTube Music lalu buka lagi.
4. Kecualikan EQ Adaptif dari optimasi baterai supaya tidak dimatikan sistem.

## Catatan

- Izin rekam audio hanya dipakai `Visualizer` untuk membaca spektrum musik yang diputar, bukan untuk merekam mikrofon.
- Beberapa HP (terutama dengan efek audio bawaan seperti Dolby/Sound Assistant) bisa bentrok. Kalau suara tidak berubah, coba matikan efek bawaan HP.
- Kunci di folder `keystore/` hanya untuk menandatangani APK pribadi, supaya update bisa dipasang di atas versi lama. Jangan dipakai untuk aplikasi yang dirilis ke Play Store.

## Kredit

Preset headset berasal dari [AutoEq](https://github.com/jaakkopasanen/AutoEq) oleh Jaakko Pasanen (lisensi MIT), dikonversi ke 31 band.
