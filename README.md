# TiktokPatchXposed

**Indonesia** | [Русский](README-RU.md) | [English](README-EN.md)

> Teruji pada **TikTok 47.1.4** dari Google Play dan **Android 16**. Sebagian besar fitur
> menemukan sendiri lokasi yang dibutuhkan di dalam TikTok, sehingga tetap berfungsi setelah
> TikTok diperbarui. Jika setelah pembaruan TikTok ada yang berhenti bekerja, lihat
> «Status fitur» di pengaturan modul lalu tulis ke
> [issues](https://github.com/hik0w/TiktokPatchXposed/issues).

Modul LSPosed untuk TikTok. Menghilangkan iklan, memungkinkan pengunduhan apa pun dengan
kualitas pilihan, mengaktifkan fitur yang tidak dibuka TikTok untuk semua orang, dan menambah
banyak hal kecil: gulir otomatis di mana saja, warna kustom dan tema AMOLED, penyembunyian
elemen, bendera negara, mode tak terlihat, dan stiker pengganti api streak. Semua pengaturan
berada langsung di dalam TikTok dan tampak seperti bawaan aplikasi.

Versi saat ini — **4.1.0**.

### Fitur uji coba 🧪

Fitur bertanda 🧪 sudah dibuat dan bisa dinyalakan, tetapi belum teruji sepenuhnya. Fitur
tersebut bisa saja tidak berfungsi atau bekerja tidak sesuai deskripsi. Semuanya mati secara
bawaan.

- **Tanpa wajib masuk** — penggunaan tanpa akun TikTok belum diuji di perangkat nyata.
- **Mode tak terlihat** (tontonan story, profil, dan video) — sudah terverifikasi bahwa TikTok
  tidak mengirim tanda tontonan, tetapi tampilannya di sisi orang lain belum terkonfirmasi.
- **Blokir lokasi, sembunyikan VPN, ID perangkat acak** — modul memalsukan jawaban Android
  untuk TikTok, tetapi tidak semua cara TikTok mengetahuinya bisa diperiksa.

Jika fitur uji coba berfungsi atau tidak berfungsi di perangkat Anda — tulis ke issues, itu
sangat membantu.

## Tampilan

<p align="center">
  <img src="docs/media/main.png" width="250" alt="Layar utama pengaturan modul">
  <img src="docs/media/status.png" width="250" alt="Status fitur">
  <img src="docs/media/interface.png" width="250" alt="Bagian «Antarmuka»">
</p>
<p align="center"><sub>Pengaturan di dalam TikTok · status fitur di versi Anda · bagian «Antarmuka»</sub></p>

<p align="center">
  <img src="docs/media/hide.png" width="250" alt="Penyembunyian elemen">
  <img src="docs/media/color.png" width="250" alt="Warna aksen kustom">
  <img src="docs/media/downloads.png" width="250" alt="Unduhan dan tautan">
</p>
<p align="center"><sub>Penyembunyian elemen · warna aksen kustom · unduhan dan tautan</sub></p>

## Pemasangan

**Syarat:** Android 8.1 atau lebih baru, root, dan [LSPosed](https://github.com/JingMatrix/LSPosed)
(API libxposed modern 101–102 atau LSPosed lawas 1.9.x), TikTok dari Google Play
(`com.zhiliaoapp.musically`; `com.ss.android.ugc.trill` juga didukung tetapi belum diuji).

1. Unduh APK dari [rilis](https://github.com/hik0w/TiktokPatchXposed/releases/latest) terbaru lalu pasang.
2. Di LSPosed, aktifkan modul dan centang TikTok di daftar aplikasinya.
3. Hentikan paksa TikTok (Pengaturan Android → Aplikasi → TikTok → Hentikan) lalu buka kembali.
4. Buka pengaturan modul (lihat di bawah) — di bagian atas akan tertulis «Modul berjalan».

### Letak pengaturan

TikTok → Profil → ☰ → Pengaturan dan privasi → **TiktokPatchXposed** (baris paling atas).

Modul tidak punya aplikasi tersendiri: tombol pengaturan di LSPosed membuka layar yang sama di
dalam TikTok. Layar tersebut meniru tampilan TikTok serta mengikuti tema dan bahasanya.
Diterjemahkan ke bahasa Rusia, Inggris, Ukraina, Jerman, Spanyol, Portugis, Prancis, Italia,
Polandia, Turki, dan Indonesia; untuk bahasa Belarusia dan Kazakh digunakan bahasa Rusia,
untuk bahasa lainnya digunakan bahasa Inggris.

Sebagian pengaturan baru berlaku setelah TikTok dimulai ulang (wilayah, warna aksen, tema
AMOLED, ID acak, dan eksperimen server) — saat itu tombol «Mulai ulang» akan muncul di
bagian bawah layar.

### Pembaruan

APK baru dipasang menimpa yang lama, pengaturan tetap tersimpan. Setelah pemasangan, mulai
ulang TikTok. Daftar perubahan ada di deskripsi tiap
[rilis](https://github.com/hik0w/TiktokPatchXposed/releases). Pengaturan bisa disimpan dan
dipindahkan: *Status fitur → Ekspor / Impor pengaturan*.

### Jika ada yang tidak berfungsi

- **Modul tidak ada di pengaturan TikTok** — pastikan modul aktif di LSPosed dan TikTok
  dicentang di daftarnya, lalu hentikan paksa TikTok.
- **Fitur tidak berfungsi setelah TikTok diperbarui** — buka «Status fitur» (kartu
  «Modul berjalan» di atas). Jika tertulis «Tidak ditemukan di versi TikTok ini», modul
  melewati fitur tersebut. Tulis ke issues beserta versi TikTok Anda.
- **Pengaturan tidak diterapkan** — jika ada tombol «Mulai ulang» di bawah, tekan tombol itu.
- **TikTok menampilkan «Pulihkan aplikasi»** — itu mode perlindungan TikTok setelah aplikasi
  macet. Tekan **«Batal»**, bukan «Pulihkan»: pemulihan akan menghapus data TikTok.
- **Hal lain** — buka [issue](https://github.com/hik0w/TiktokPatchXposed/issues): versi
  TikTok, versi modul, tangkapan layar «Status fitur», dan jika memungkinkan, log LSPosed.

## Fitur

### Beranda

| Fitur | Cara kerja |
| --- | --- |
| Tanpa iklan | Menghilangkan iklan dari beranda, layar pembuka saat aplikasi dibuka, sisipan di dalam video, dan iklan di daftar video lainnya |
| Sembunyikan konten berbayar dan produk | Konten berbayar, video berisi produk toko, dan iklan di pencarian |
| Sembunyikan video AI | Video bertanda «Dibuat dengan AI» dari pembuat maupun TikTok |
| Filter kustom | Menyembunyikan rekomendasi berdasarkan kata dan tagar, pembuat, jumlah suka minimum, dan jumlah tontonan |
| Sembunyikan LIVE | Siaran langsung di beranda dan tombol LIVE di kiri atas |
| Sembunyikan «Orang yang mungkin Anda kenal» | Video yang dipilih TikTok berdasarkan kontak Anda |
| Bilah gulir di mana saja | Termasuk video pendek |

### Menonton

| Fitur | Cara kerja |
| --- | --- |
| Gulir otomatis di mana saja | Saat video selesai, video berikutnya terbuka: di profil, pencarian, langganan, dan chat. Postingan foto berganti mengikuti panjang musik, sekitar 3 detik per foto. Sakelarnya juga ada di menu tekan lama pada video. Di «Rekomendasi» memakai gulir otomatis bawaan TikTok |
| Kecepatan kustom | Kecepatan apa pun dari 0,25 hingga 3 di menu kecepatan TikTok |
| Ingat kecepatan | Kecepatan yang dipilih tetap berlaku untuk video berikutnya |
| Jangan atur ulang «Bersihkan layar» | Layar bersih tetap bertahan saat bergeser |
| Lanjutkan dari posisi terakhir | Video yang Anda buka kembali dilanjutkan, bukan diulang dari awal |

### Antarmuka

| Fitur | Cara kerja |
| --- | --- |
| Penyembunyian elemen | Tombol di kanan, piringan musik, pembuat dan keterangan, tombol TikTok Tako (asisten AI). Modul menyembunyikan komponen TikTok itu sendiri, sehingga tiap sakelar hanya menghilangkan bagiannya dan langsung mengembalikannya saat dimatikan. Ketuk dua kali tetap memberi suka |
| Transparansi antarmuka | Tombol, pembuat, dan keterangan di atas video menjadi semi-transparan |
| Anti burn-in | Untuk OLED: tombol bergeser sedikit tiap menit dan, jika «Redupkan tombol» dinyalakan, meredup selama layar tidak disentuh |
| Warna aksen kustom | Mengganti warna merah TikTok: tombol «Ikuti», «+» di bawah avatar, ikon, penghitung belum dibaca, komentar yang disukai. 8 warna siap pakai atau HEX sendiri |
| Tema AMOLED | Latar hitam pekat sebagai pengganti abu-abu gelap pada tema gelap TikTok |
| Sembunyikan story di «Kotak Masuk» | Deretan story di atas chat |
| 🧪 Tanpa wajib masuk | Tanpa akun TikTok, aplikasi tidak ditutup oleh jendela masuk |

### Komentar dan profil

| Fitur | Cara kerja |
| --- | --- |
| Terjemahan komentar otomatis | Komentar berbahasa lain diterjemahkan memakai penerjemah bawaan TikTok. Bahasa yang tidak diterjemahkan bisa ditentukan |
| Negara pembuat | Bendera di samping tanggal video dan di samping nama di profil (lihat catatan di bawah) |
| Salin teks saja | «Salin» pada komentar hanya menyalin teks tanpa nama pembuat |
| Tautan di peramban | Tautan dari profil dan story dibuka di peramban Anda, bukan di dalam TikTok |
| Repost berkomentar, profil baru | Eksperimen server TikTok, modul mengaktifkannya secara lokal |
| Fitur tersembunyi TikTok | Percepatan dengan tahan, komentar favorit, komentar audio dan foto, pemutaran latar, dan lainnya |

> **Bendera bukan tempat tinggal.** Modul menampilkan wilayah yang disimpan TikTok pada akun
> pembuat (jika tidak ada — wilayah video). TikTok menentukannya sendiri, biasanya saat
> pendaftaran, berdasarkan kartu SIM, alamat IP, dan bahasa perangkat. Karena itu bendera
> belum tentu sama dengan tempat orang tersebut tinggal saat ini: ia bisa saja pindah,
> memakai VPN atau pemalsu wilayah, atau mendaftar di negara lain.

### Unduhan dan tautan

| Fitur | Cara kerja |
| --- | --- |
| Tanpa tanda air | Mengunduh video tanpa tanda air |
| Unduh video apa pun | Bahkan jika pembuat melarang pengunduhan |
| Pilih kualitas | «Unduh» membuka panel: pilihan kualitas beserta ukuran berkas, «Audio saja», dan penyimpanan biasa TikTok |
| Semua foto dari korsel | Pada postingan foto: semua foto sekaligus dalam ukuran penuh tanpa tanda air, atau pilih foto tertentu |
| Stiker dan foto dari komentar | Tekan lama komentar → «Unduh stiker» (dan GIF) atau «Unduh foto» |
| Tangkapan layar di mana saja | Tangkapan layar dan perekaman layar berfungsi juga di tempat yang dilarang TikTok, termasuk di percakapan |
| Tautan bersih | Tautan pendek `vt.tiktok.com/…` (melaluinya TikTok tahu siapa yang membagikan) sedetik setelah disalin diganti tautan video biasa; dari tautan panjang dibuang `_r`, `_t`, `u_code`, `share_link_id`, dan penanda lainnya |

### Wilayah

| Fitur | Cara kerja |
| --- | --- |
| Negara | Pemalsuan negara kartu SIM dan operator, sekitar 190 negara. Bawaan Jerman, bisa diganti atau dimatikan. Nama zona waktu juga berubah mengikuti zona waktu negara tersebut dengan waktu yang sama, sehingga jam tetap benar |
| Rekomendasi dalam bahasa saya | Rekomendasi dalam bahasa yang dipilih di TikTok. Jika dimatikan, TikTok memilih video sesuai negara yang dipilih |
| Hanya video terbaru | Menyembunyikan rekomendasi yang lebih lama dari jangka waktu pilihan (dari seminggu hingga 2 tahun, atau kustom) |

> **Wilayah tidak dijamin.** Modul hanya mengubah yang diketahui TikTok tentang perangkat:
> negara kartu SIM, operator, dan zona waktu. Wilayah akun itu sendiri (yang diingat TikTok
> saat pendaftaran), alamat IP, dan sinyal lainnya tidak diubah modul. Karena itu rekomendasi,
> fitur yang tersedia, dan wilayah akun belum tentu persis sesuai negara yang dipilih.

### Mode tak terlihat

Semuanya mati secara bawaan, semua fitur bersifat uji coba (lihat [Fitur uji coba](#fitur-uji-coba-)).

| Fitur | Cara kerja |
| --- | --- |
| 🧪 Sembunyikan tontonan story | Pembuat tidak akan melihat Anda di antara penonton story |
| 🧪 Sembunyikan tontonan profil | Anda bisa menyalakan «Tontonan profil» di TikTok dan melihat pengunjung tanpa terlihat oleh orang lain |
| 🧪 Sembunyikan tontonan video dari langganan | Dengan riwayat tontonan menyala, orang yang Anda ikuti tidak melihat video mereka mana yang Anda tonton |
| 🧪 Blokir lokasi | TikTok melihat geolokasi dalam keadaan mati dan tidak menerima koordinat baik dari GPS maupun jaringan |
| 🧪 Sembunyikan VPN | TikTok tidak melihat bahwa koneksi berjalan melalui VPN |
| 🧪 ID perangkat acak | TikTok menerima Android ID acak sebagai pengganti yang asli. Berlaku setelah mulai ulang; TikTok bisa meminta masuk kembali |

### Streak

| Fitur | Cara kerja |
| --- | --- |
| Streak otomatis | Memperpanjang streak yang sudah ada, selengkapnya di bawah |
| Yang dikirim | Api streak atau stiker dari favorit Anda. Jika stiker dihapus dari favorit, yang terkirim adalah api |

### Layar pengaturan

Pencarian di semua pengaturan, «Status fitur» (apa yang ditemukan di versi TikTok Anda),
ekspor dan impor pengaturan dalam satu baris.

## Streak otomatis

Dinyalakan manual. Modul hanya memperpanjang streak yang sudah ada di chat pribadi berdua
dan hanya saat streak hampir hangus. Pengiriman memakai interaksi normal TikTok `spark_v1`
(atau stiker jika dipilih), bukan pesan teks. Berfungsi selama TikTok terbuka: tanpa alarm
dan tanpa layanan latar. Setiap pengiriman dicatat dulu, sehingga streak yang sama tidak
diperpanjang dua kali dalam sehari. Antar chat modul memberi jeda beberapa detik, seolah
Anda berpindah manual. Jika TikTok menolak streak, akan muncul notifikasi: periksa chat
tersebut sendiri.

## Kompatibilitas

Dulu setiap pembaruan TikTok membuat modul harus disesuaikan dengan nama kelas yang baru.
Kini modul hanya mengaitkan nama kelas dan metode TikTok yang terbaca, sedangkan bagian
yang dikaburkan ditemukan sendiri saat pertama kali versi baru dijalankan (berdasarkan
string dan signature) — hanya butuh sepersekian detik dan hasilnya diingat. Karena itu
versi TikTok yang baru kemungkinan besar langsung berfungsi tanpa pembaruan modul. Hal ini
tidak bisa dijamin: jika suatu fitur tidak ditemukan, modul melewatinya begitu saja dan
menampilkannya di «Status fitur».

Modul bekerja melalui API LSPosed modern (libxposed 101–102), sehingga di versi LSPosed
baru tidak ada label «legacy». LSPosed lama 1.9.x juga didukung: cara pemuatan lama
disertakan di APK.

## Batasan

- Ikon TikTok di layar utama tidak bisa diganti: modul tidak bisa mengubah manifes TikTok.
- Sebagian tampilan TikTok digambar sendiri dari gambar dan Jetpack Compose — beberapa
  elemen kecil tetap merah walau warna aksen sudah diganti.
- Wilayah mengubah yang diketahui TikTok tentang kartu SIM, operator, dan zona waktu, tetapi
  bukan wilayah akun dan bukan alamat IP, sehingga hasilnya tidak dijamin.
- Fitur uji coba 🧪 belum teruji sepenuhnya (lihat di atas).

## Membangun

Butuh Java 17 dan Android SDK 36.

Seluruh proyek ada di folder `source/`:

```shell
cd source
./gradlew testDebugUnitTest assembleRelease
```

APK akan muncul di `source/app/build/outputs/apk/release/`.

### Struktur proyek

```text
source/                  proyek Gradle modul
docs/media/              tangkapan layar untuk README
.github/workflows/       build dan pengujian di GitHub Actions

source/app/src/main/java/com/golda/patchertiktok/
  ModuleMain, MainHook   titik masuk untuk LSPosed baru dan lama
  Module                 menjalankan semua fitur, masing-masing terisolasi dan tidak menjatuhkan yang lain
  Hooks, Reflect         pembungkus API LSPosed dan refleksi
  Discovery, DexFinder   pencarian kelas TikTok yang dikaburkan berdasarkan string dan signature
  Prefs, I18n            pengaturan dan terjemahan ke 11 bahasa
  ModSettingsActivity    layar pengaturan di dalam TikTok
  FeedFilter, ContentFilter, ...   beranda dan filter
  AutoScroll, PlaybackSpeed, ...   menonton
  Overlay, AccentColor, ...        antarmuka
  DownloadsPlus, CommentMedia, ... unduhan
  RegionSpoof, Ghost, Privacy      wilayah dan mode tak terlihat
  AutoStreakManager, StreakSticker streak
```

## Peringatan

Modifikasi klien dan tindakan otomatis dapat berujung pada pembatasan akun. Gunakan dengan
risiko sendiri. Ini proyek tidak resmi, tidak terafiliasi dengan TikTok maupun ByteDance.

## Lisensi

Lisensi **MIT**, lihat [LICENSE](LICENSE).
