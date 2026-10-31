# Realme TV Remote

Aplikasi remote Android native untuk TV yang menjalankan Android TV Remote Service v2, termasuk Realme Android TV dan Google TV yang menyediakan service tersebut.

## Fitur

- Discovery TV melalui mDNS/DNS-SD pada jaringan Wi-Fi lokal (`_androidtvremote2._tcp`).
- Pairing Android TV Remote v2 dengan kode enam karakter yang diverifikasi sebelum secret dikirim.
- TLS dengan identitas client tersimpan terenkripsi memakai Android Keystore; sertifikat TV dipin setelah pairing berhasil.
- Kontrol power, volume, channel, D-pad, OK, Home, Back, Menu, dan Mute.
- Input teks melalui remote IME protocol.
- Status koneksi hanya aktif setelah handshake remote selesai; koneksi yang putus ditampilkan sebagai terputus.

TV dan ponsel harus berada pada jaringan lokal yang sama, dan TV harus menjalankan Android TV Remote Service. Perintah power melalui jaringan bergantung pada service remote TV yang masih tersedia; TV yang benar-benar mati mungkin tidak dapat menerima perintah.

## Build

Prasyarat: JDK 17 dan Android SDK Platform 35 serta Build Tools 35.0.0.

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK Debug dibuat di `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions menjalankan tes dan mengunggah APK Debug sebagai artifact pada push, pull request, atau workflow dispatch.

## Batas pengujian

Tes otomatis mencakup framing protobuf, sertifikat client self-signed, serta checksum kode pairing. Koneksi mDNS, pairing dengan TV, dan pengiriman perintah membutuhkan Android TV fisik pada jaringan lokal; ketiganya belum dapat diverifikasi dari build container.
