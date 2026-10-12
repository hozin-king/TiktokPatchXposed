# Pelajaran dari HyperCeiler (hozin-king/HyperCeiler, fork)
Cloned: ~/workspace/hyperceiler-ref (shallow, 12 Okt 2026). Modul Xposed/LSPosed untuk HyperOS/MIUI.

## 1. Build system
- Gradle Kotlin DSL, multi-modul: `app` (aplikasi settings) + `library/libhook` (semua hook) + `library/common`, `library/core`, `library/processor`, `library/provision`, `library/hidden-api`.
- BUKAN YukiHookAPI, BUKAN XposedBridge lama. Pakai **libxposed API 102** (`io.github.libxposed.api.XposedModule`) + **EzHookTool 1.3.0** (`io.github.lingqiqi5211.ezhooktool`) + **DexKit** (`org.luckypray.dexkit`) untuk cari method yang diobfuscate.

## 2. Pola organisasi hook (BAGUS, tiru ini)
- Entry: `library/libhook/.../base/XposedInitEntry.java extends XposedModule` → delegasi ke `BaseLoad`.
- Satu dispatcher per aplikasi target: `library/libhook/.../app/Aod.java` — anotasi `@HookBase(targetPackage="com.miui.aod")`, extends `BaseLoad`, method `onPackageLoaded()` memanggil `initHook(<Rule>, <kondisi>)` per fitur, mis. `initHook(new UnlockAodAon(), PrefsBridge.getBoolean("aod_unlock_aon"))`.
- Registrasi otomatis: annotation processor `library/processor` (`HookBaseProcessor.java`) meng-generate class `DataBase` (map className → targetPackage + batasan minSdk/maxSdk/minOSVersion/deviceType) saat compile — tidak ada daftar manual yang lupa diupdate.
- `ModuleMatcher.shouldLoad()` (`base/ModuleMatcher.java`) menyaring per package: safe-mode, versi SDK/OS, debug mode.
- Satu rule = satu file: `library/libhook/.../rules/<app>/<area>/<Fitur>.kt`, Kotlin `object : BaseHook()` + `override fun init()`, mis. `rules/aod/UnlockShortCuts.kt` memakai DSL EzHookTool: `loadClass("...").findMethod { name("loadWhiteItems") }.createAfterHook { it.result = ... }` (juga ada `createBeforeHook`, `createInterceptHook`).
- Tiap `initHook` dibungkus try/catch → `logHookFailure` — satu hook gagal tidak membunuh modul lain.
- Untuk target obfuscate: `useDexKit()` + `initDexKit()` dengan `requiredMember`/`optionalMember` (contoh: `rules/analytics/FuckMiuiUpload.java`).

## 3. Pola settings/prefs (BAGUS, tiru ini)
- UI settings: `PreferenceFragmentCompat` (custom `fan.preference.PreferenceFragment`), XML per kategori di `app/src/main/res/xml/prefs_*.xml`, Activity dasar `library/core/.../dashboard/SettingsActivity.java`.
- `PrefsBridge` (`library/common/.../utils/PrefsBridge.java`): proses aplikasi `initForApp` (SharedPreferences `hyperceiler_prefs`, device-protected); proses hook `initForHook(getRemotePreferences(...))` — sisi hook READ-ONLY (tulis hanya via `putByApp` dari aplikasi) + cache `sHookCache`.

## 4. Pola proteksi crash (BAGUS, tiru yang sederhana)
- `library/libhook/.../safecrash/CrashMonitor.kt` + `SafeModeHandler`: kalau satu package crash 2x dalam 60 detik, hook untuk package itu dimatikan otomatis. Untuk modul TikTok cukup versi sederhana: try/catch per `initHook` + catat gagal.

## 5. Yang JELEK/berbahaya — hindari
- Terlalu kompleks untuk kebutuhan kita: mesin hot-reload (state EXTRA_MAGIC/VERSION), kode native (CMake, arm64 saja), shim hidden-api — jangan ditiru.
- `setObjectReplacement` resource (contoh `rules/home/title/EnableIconMonetColor.kt`) — rapuh antar versi.
- Dead code dikomentari dibiarkan di file (file yang sama contohnya) — hapus, jangan tiru.
- `settings.gradle.kts` mewajibkan kredensial GitHub Packages saat build (throw kalau tidak ada) — bikin build fork/CI ribet; pilih dependensi dari mavenCentral saja.
- PrefsBridge hanya "memperingatkan" kalau proses hook menulis pref — untuk kita, tegakkan read-only beneran.

## 6. Kesimpulan untuk TiktokPatchXposed
Tiru: 1 rule class per fitur + `initHook(rule, prefs.getBoolean(key))` dengan try/catch per hook; registrasi hook terpusat tanpa daftar manual (bisa versi sederhana tanpa annotation processor); PrefsBridge pola remote-prefs read-only di proses hook; crash guard sederhana. Jangan tiru: libxposed API 102 + EzHookTool (migrasi besar, modul kita sudah jalan di API klasik — pertimbangkan hanya jika butuh DexKit), hot-reload, native, dan kerumitan multi-modul.
