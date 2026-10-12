# Backlog Ide Fitur (ditampung 2026-10-12, belum dibangun)

## 1. Fake LIVE profile ring
- Keinginan: ring bulat "LIVE" di foto profil, terlihat oleh orang biasa juga.
- Kendala: status live ditentukan server TikTok; hook client hanya bisa fake lokal (diri sendiri yang lihat).

## 2. Fake streak manager
- Set jumlah hari streak (tampilan).
- Memunculkan popup perayaan streak + pesan yang bisa diposting ke story.
- Pet streak & sticker streak fake.
- Kendala: data streak (hari, flame, pet) bersifat server-side; fake hanya tampil lokal.

## 3. (sudah dibahas) Upload HD maksimal via override eksperimen
## 4. (sudah dibahas) Centang verifikasi fake (lokal; antar-pengguna modul via daftar ID)

## 5. Riwayat tontonan (disetujui user 2026-10-12)
- Catat video yang sudah ditonton + UI untuk membuka lagi.

## 6. Pengingat streak (disetujui user 2026-10-12)
- Notifikasi lokal saat ada streak yang mau habis.

## 7. Mini drama unlock (disetujui user 2026-10-12)
- Buka episode mini drama yang terkunci (paywall).

## 8. Download playlist drama (disetujui user 2026-10-12)
- Download semua episode per playlist; simpan ke Video offline atau galeri di folder video/mini drama/<judul playlist>.

## 9. Live dari video galeri (ide, tahap riset — disetujui user 2026-10-12)
- Suntik video galeri sebagai sumber live (fake live): opsi berhenti saat video selesai,
  loop 1x / loop tanpa batas. PERINGATAN: TikTok mendeteksi live palsu (risiko batasi akun);
  butuh hook native dalam di live SDK. "Sambung eksternal" tidak bisa (TikTok mobile tidak
  memberi stream key). JANGAN build sebelum riset membuktikan titik hook yang valid.
- HASIL RISET (2026-10-12, APK TikTok v47.x):
  - Pusher live = `com.ss.pusher` (VeLivePusher, ByteDance). Java-nya tipis; capture kamera +
    encoder + RTMP (`LibRTMPTransport`) jalan di native. Native lib TIDAK ada di APK (diunduh
    dinamis via TTClassLoad) — hook native = target bergerak, rapuh tiap update.
  - API `pushVideoFrame` / `ExternalVideoCapturer` HANYA ada di `com.ss.bytertc` (mesin RTC untuk
    co-host/video-call), TIDAK di `com.ss.pusher` (siaran live utama). Jadi tidak ada titik Java
    resmi untuk menyuntik frame ke siaran live.
  - Jalur non-kamera yang ADA: mode GAME_LIVE/MOBILE_GAME pakai screen capture via VirtualDisplay
    (`game_live_cast_create_virtual_display`). Satu-satunya cara "tanpa native hook" = putar video
    galeri fullscreen lalu live via mode game — tapi itu hack UX (notifikasi/system UI bisa bocor,
    tidak bisa pilih file dari dalam fitur).
  - Sinyal deteksi: field API `preRecorded`/`preRecordedBadgeText` (server bisa melabeli live sebagai
    rekaman), `livesdk_hit_risk_control` (risk control live). TikTok melarang live palsu di Community
    Guidelines — risiko pembatasan akun nyata.
  - KESIMPULAN: secara teknis SANGAT SULIT dari modul Xposed (butuh hook native ke .so yang diunduh
    dinamis, tanpa API Java). Risiko akun tinggi. REKOMENDASI: jangan build; kalaupun dipaksa, satu-
    satunya jalur realistis adalah mode screen-capture + pemutar video fullscreen, bukan injeksi frame.

## 10. Lihat URL stream live (disetujui user 2026-10-12)
- Saat menonton live, tampilkan/salin URL stream langsung (bukan link share vt.tiktok.com)
  agar bisa dibuka di pemutar eksternal. Catatan: URL biasanya bertanda tangan & kedaluwarsa.
