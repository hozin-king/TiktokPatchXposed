# Backlog Ide Fitur (ditampung 2026-10-12, belum dibangun)

## 1. Fake LIVE profile ring
- Keinginan: ring bulat "LIVE" di foto profil, terlihat oleh orang biasa juga.
- Kendala: status live ditentukan server TikTok; hook client hanya bisa fake lokal (diri sendiri yang lihat).

### Hasil riset hook (2026-10-12, APK com.ss.android.ugc.trill v47.0.3, via androguard — MUNGKIN)
- **Kandidat hook VALID, rantai render terverifikasi sampai ke pemanggil:**
- `com.ss.android.ugc.aweme.profile.model.User.isLive()Z` — bytecode: `return roomId != 0`
  (iget-wide `roomId J`, cmp-long dengan 0).
- Renderer ring: `LX/1GqV;->LIZ(Lcom/ss/android/ugc/aweme/profile/model/LiveCircleParam;)Z`
  (dex_split/classes21.dex, class ter-obfuscate) — di dalam method ini ADA pemanggilan
  eksplisit `invoke-virtual User.isLive()Z`, lalu memakai `ListLiveCircleItemVM`
  ("ListItemLiveCircleView") untuk menampilkan ring di `avatarView`.
- Param: `LiveCircleParam` (field `user`, `avatarView`, `forceSkipLive`, `liveCircleViewType`,
  `needLiveBreathAnim`, `sceneType`).
- **Strategi hook:** hook `User.isLive()` → return `true`. Maka `LIZ()` melihat user "live"
  dan me-render ring LIVE di avatar. Alternatif: set field `User.roomId` ke non-nol.
- Batasan jujur:
  - `LIZ()` juga membaca `forceSkipLive` dari param — jika caller mengesetnya, ring tetap
    tidak muncul (untuk profil sendiri seharusnya tidak diset).
  - Mengetuk ring akan mencoba masuk room live yang tidak ada (roomId=0) → kemungkinan
    error/gagal masuk. Visual ring-nya yang fake, bukan room-nya.
  - Fake tetap **lokal saja** — orang lain tidak melihat ring ini (server tidak tahu).
  - Perlu verifikasi runtime di HP: aktifkan hook, buka profil sendiri, lihat apakah ring muncul.

## 2. Fake streak manager
- Set jumlah hari streak (tampilan).
- Memunculkan popup perayaan streak + pesan yang bisa diposting ke story.
- Pet streak & sticker streak fake.
- Kendala: data streak (hari, flame, pet) bersifat server-side; fake hanya tampil lokal.

### Hasil riset hook (2026-10-12, APK com.ss.android.ugc.trill v47.0.3)
- **MUNGKIN** — kandidat hook VALID, terbukti dari kode modul existing
  (`source/app/src/main/java/com/golda/patchertiktok/StreakApi.java`) yang SUDAH
  membaca data streak asli di runtime:
- Class: `com.ss.android.ugc.aweme.im.streak.api.StreakData`
  (loadable saat runtime via `Class.forName(..., loader)` — class ada di dex
  dinamis/terpisah, tidak di base.apk/split yang terekstrak statis).
- Field angka hari: `streak` (Number/int) — dipakai modul existing via
  `number(item, "streak")`; inilah angka 🔥 yang tampil di UI chat.
- Field lain: `convType` (1 = one-to-one), `convId` (String),
  `userStreak` (List, tiap item punya `uid`), `activeStart`/`activeBefore`/`endAt`
  (long, jendela waktu streak).
- Provider ditemukan via fingerprint log `"StreakServerDataSource triggerStreakCompare convId: "`,
  method `List<StreakData> x(List<RecordType>)` (RecordType enum: USER, CONVERSATION).
- **Strategi hook:** hook getter `getStreak()` (Kotlin data class) atau method list
  provider lalu set field `streak` via reflection ke angka pilihan user.
- **Batasan jujur:**
  - Nilai asli datang dari server (`StreakServerDataSource`, API
    `/tiktok/v1/im/streaks/get|info|compare`). Fake hanya mengubah TAMPILAN
    di perangkat ini; lawan chat tetap melihat angka asli.
  - Kalau belum ada streak asli (tidak ada record), angka fake murni kosmetik.
  - Perlu verifikasi runtime di HP.
- Terkait (belum diriset hook-nya): popup perayaan
  (`StreakUnlockAnimTrigger`, `StreakMilestoneUnlockAnimationConfig`),
  pet streak (`StreakPetData`, API `/tiktok/v1/im/streaks/pet/*`),
  streak sticker (modul sudah punya `StreakSticker.java`).

## 3. (sudah dibahas) Upload HD maksimal via override eksperimen

### Hasil riset lanjutan (2026-10-12, APK com.ss.android.ugc.trill v47.0.3 — BLOCKED untuk upload)
- **Komentar foto:** ditemukan experiment `comment_photo_quality_optimization_v2`
  (key terverifikasi di `LX/0IQj;->invoke()`, classes9.dex) dengan class
  `com.ss.android.ugc.aweme.comment.experiment.CommentPhotoQualityOptimizationV2`
  + config `CommentPhotoQualityOptV2Config` (field: `enableDetailArgb8888`,
  `enableDetailDownsample`, `enableThumbnailArgb8888`, `enableThumbnailDownsample`,
  `detailSrLevel`, `thumbnailSrLevel`, `lowEndSrPolicy`, `midEndSrPolicy`).
  TAPI ini eksperimen **kualitas pemrosesan/tampilan** (downsampling, color depth,
  super-resolution saat render), BUKAN kontrol kualitas upload yang terverifikasi.
  Tidak dibangun agar tidak jadi toggle palsu.
- **Video upload:** tidak ditemukan experiment key sederhana; pengaturan encoder
  berupa JSON compile config kompleks (bitrate 5-6 Mbps, crf, gop) yang berisiko
  diubah tanpa pemahaman penuh. Toggle `UPLOAD_HD` yang ada di Prefs/I18n saat ini
  MATI (tidak ada implementasi) — jangan aktifkan ke user sebelum ada hook valid.
- Kesimpulan: tetap BLOCKED sampai ditemukan titik kontrol upload yang terverifikasi.
## 4. (sudah dibahas) Centang verifikasi fake (lokal; antar-pengguna modul via daftar ID)

### Hasil riset hook (2026-10-12, APK com.ss.android.ugc.trill v47.0.3, base.apk/classes2.dex)
- **MUNGKIN** — kandidat hook VALID ditemukan via androguard (bukan karangan):
- Model: `com.ss.android.ugc.aweme.profile.model.User` (367 field, 673 method;
  `Serializable`+`Cloneable` — model Gson dari server).
- **TIDAK ADA field boolean `isVerified`** di versi ini (pendekatan lama tidak berlaku).
- Field verifikasi yang ada:
  - `verificationType: int` → `getVerificationType()I` + `setVerificationType(I)V` (public, ada setter)
  - `verificationBadgeType: int` → `getVerificationBadgeType()I` (hanya getter, tanpa setter)
  - `verifyInfo: String` → getter + setter
  - `customVerify: String` → getter + setter
  - `enterpriseVerifyReason: String` → getter + setter
  - (`isVerifiedTako: int` = terkait asisten AI Tako, BUKAN badge profil — jangan dipakai)
- `User$VerificationType` adalah annotation interface gaya `@IntDef` (hanya konstanta `DEFAULT`).
- Kandidat hook: `User.getVerificationType()` (kembalikan nilai verified non-nol) dan/atau
  `User.getVerificationBadgeType()`. Field bersifat mutable, tidak ada indikasi signing kriptografis.
- Batasan jujur: rantai render UI persisnya (View mana yang membaca field ini) belum dipetakan
  (XREF lintas-dex mahal); verifikasi runtime via mekanisme Discovery modul — hook getter lalu
  amati badge di profil. Fake tetap lokal saja (server tetap menganggap unverified).

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
  - KESIMPULAN AWAL: secara teknis SANGAT SULIT dari modul Xposed (butuh hook native ke .so yang diunduh
    dinamis, tanpa API Java). Risiko akun tinggi. REKOMENDASI AWAL: jangan build; kalaupun dipaksa, satu-
    satunya jalur realistis adalah mode screen-capture + pemutar video fullscreen, bukan injeksi frame.
- HASIL RISET VIRTUAL CAMERA (2026-10-12, APK com.ss.android.ugc.trill v47.0.3, via androguard 4.1.4 — MUNGKIN):
  - Riset awal melewatkan stack capture yang sebenarnya: siaran live v47.0.3 memakai
    `com.ss.ttlivestreamer` (livestreamer v2), BUKAN cuma `com.ss.pusher`.
  - Kamera live = `com.ss.android.ttvecamera.TECameraCapture` (abstraksi kamera milik TikTok di atas
    android.hardware.camera2: CameraDevice, CameraCaptureSession). TIDAK pakai CameraX.
  - Pompa frame = `com.ss.ttlivestreamer.core.buffer.SurfaceTextureHelper` (gaya WebRTC):
    `setOnFrameAvailableListener`, `startListening`/`stopListening`, `tryDeliverTextureFrame`,
    `textureToYuv` — semua di level Java, bisa di-hook (dex_split/classes4.dex).
  - TITIK INJEKSI (terverifikasi): `com.ss.ttlivestreamer.livestreamv2.capture.LiveStreamVideoCapture`,
    method `LLJJI(Lcom/ss/ttlivestreamer/core/buffer/VideoFrame$Buffer; I I I J)I`
    (nama obfuscate; cari via reflection berdasarkan signature: (Buffer, width, height, rotation,
    timestampNs) -> int). Ini pintu masuk frame ke pipeline live.
  - Buffer bisa dibuat murni di Java: `NV21Buffer(byte[], width, height, Runnable)` atau
    `NV12Buffer(int,int,int,int,ByteBuffer,Runnable)` (konstruktor publik). Interface
    `VideoFrame$I420Buffer` (getDataY/U/V + stride) juga bisa diimplementasi sendiri.
    Decode file video pakai `android.media.MediaCodec` + `MediaExtractor` (keduanya direferensi di dex).
  - Jalur audio: `com.ss.ttlivestreamer.core.capture.audio.AudioCapturer`, `AudioDeviceModule`,
    `pushAudioFrame` — titik injeksi audio kemungkinan ada tapi BELUM dipetakan penuh
    (audio dari file video harus ikut disiarkan).
  - Yang BELUM terbukti (butuh verifikasi runtime): cara mendapatkan instance LiveStreamVideoCapture
    yang aktif (kandidat: hook konstruktornya); apakah frame kamera juga masuk via LLJJI yang sama
    (kalau ya, frame kamera harus disupresi — kandidat: `SurfaceTextureHelper.stopListening()` atau
    `LiveStreamVideoCapture.pause()`); apakah timestamp harus realtime-monotonik.
  - Sinyal deteksi (terverifikasi): `com.bytedance.android.livesdkapi.depend.model.live.RoomPrivateInfo.preRecorded:int`
    dan `com.bytedance.android.livesdk.model.TopFrameSummary.preRecordedBadgeText:String`
    (dex_base/classes13.dex) — server bisa menandai live sebagai rekaman + menampilkan badge.
    Risiko pembatasan akun TETAP TINGGI.
  - KESIMPULAN: MUNGKIN secara teknis — titik injeksi Java terverifikasi (tidak butuh hook native),
    tapi butuh verifikasi runtime + pemetaan audio + penanganan supresi kamera. Risiko akun tinggi.
- SPEK UI (permintaan user 2026-10-12, bila titik injeksi terbukti):
  - MODE pemutaran: sekali putar lalu berhenti | loop 1x | loop tanpa batas.
  - Playlist: pilih/tambah BEBERAPA video, diputar berurutan.
  - Kontrol mudah diakses dari dalam aplikasi: tombol di area inbox/live + pengaturan pilih video.
  - Saat user menekan LIVE, yang disiarkan = video pilihan (bukan kamera, bukan tangkapan layar).

## 10. Lihat URL stream live (disetujui user 2026-10-12)
- Saat menonton live, tampilkan/salin URL stream langsung (bukan link share vt.tiktok.com)
  agar bisa dibuka di pemutar eksternal. Catatan: URL biasanya bertanda tangan & kedaluwarsa.
