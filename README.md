# EQ Adaptif

AI penyeimbang suara untuk Android (9 ke atas). EQ Adaptif **bukan equalizer**. Aplikasi ini bekerja **di atas EQ pilihan Anda** (Poweramp Equalizer, Wavelet, EQ bawaan HP, dll.) dan menjaga karakter suara tetap sama walau lagu berganti.

## Cara kerja

- `Visualizer` membaca spektrum musik yang sedang diputar di HP.
- AI menghitung keseimbangan bass, mid, dan treble setiap lagu dari rata-rata ±10 detik terakhir, lalu membandingkannya dengan target. Target bisa Netral, atau "dikunci" dari lagu yang suaranya sudah pas dengan EQ Anda.
- Koreksi kecil (dibatasi ±dB dan berubah perlahan) dipasang lewat equalizer dasar Android. Jenis efek ini berbeda dari Poweramp Equalizer, jadi keduanya tidak berebut kendali.

## Build APK

Setiap push ke `main` otomatis membuat APK lewat GitHub Actions. Unduh dari halaman **Releases**:
`https://github.com/Nkhafidz/eq-adaptif/releases/latest/download/EQ-Adaptif.apk`

## Catatan

Izin rekam audio hanya dipakai `Visualizer` untuk membaca spektrum musik yang diputar, bukan untuk merekam mikrofon.
