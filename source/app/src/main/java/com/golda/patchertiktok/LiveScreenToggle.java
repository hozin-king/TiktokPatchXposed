package com.golda.patchertiktok;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.WeakHashMap;

/**
 * Toggle button "Video galeri / Kamera" langsung di layar pembuatan LIVE TikTok.
 *
 * <p>Cara kerja: hook {@code Activity.onResume}, lalu telusuri hierarki view mencari
 * tombol "Siarkan LIVE" / "Go LIVE". Kalau ketemu, sisipkan tombol toggle di parent yang
 * sama. Tidak bergantung pada nama kelas TikTok yang diobfuscate — hanya pada teks
 * tombol yang terlihat, sehingga lebih tahan terhadap update aplikasi.
 *
 * <p>Menekan tombol membalik {@link Prefs#LIVE_VIDEO}; {@link LiveVideoSource} membaca
 * flag itu setiap frame kamera, jadi efeknya langsung tanpa restart.
 */
final class LiveScreenToggle {
    private static final String[] LIVE_BUTTON_TEXTS = {"Siarkan LIVE", "Go LIVE"};
    private static final long CHECK_DELAY_MS = 600;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Activity yang sudah dipasangi tombol (weak, ikut GC saat activity hancur). */
    private static final WeakHashMap<Activity, Boolean> INSTALLED = new WeakHashMap<>();
    private static final String TAG = "live_toggle_btn";

    private LiveScreenToggle() { }

    static void install(ClassLoader loader) {
        Class<?> activity = Hooks.findClass("android.app.Activity", loader);
        if (activity == null) {
            RuntimeLog.log("live toggle unavailable: Activity not found");
            return;
        }
        int n = Hooks.hookAll(activity, "onResume", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object o = param.thisObject;
                if (!(o instanceof Activity)) return;
                final Activity act = (Activity) o;
                MAIN.postDelayed(() -> {
                    try {
                        maybeInstall(act);
                    } catch (Throwable t) {
                        RuntimeLog.log("live toggle check failed: " + t);
                    }
                }, CHECK_DELAY_MS);
            }
        });
        RuntimeLog.log("live toggle installed (onResume hooks=" + n + ")");
    }

    private static void maybeInstall(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(activity)) return;
        }
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        View liveButton = findLiveButton(decor);
        if (liveButton == null) return;
        ViewGroup parent = (ViewGroup) liveButton.getParent();
        if (parent == null) return;
        // Jangan pasang dua kali (mis. hierarki dirombak lalu onResume lagi).
        if (parent.findViewWithTag(TAG) != null) {
            markInstalled(activity);
            return;
        }
        final Button toggle = new Button(activity);
        toggle.setTag(TAG);
        toggle.setAllCaps(false);
        refreshLabel(toggle);
        // Gaya sederhana: tombol kecil di atas tombol Siarkan LIVE.
        ViewGroup.LayoutParams lp = liveButton.getLayoutParams();
        ViewGroup.LayoutParams mine;
        if (lp != null) {
            mine = new ViewGroup.LayoutParams(lp.width, lp.height);
        } else {
            mine = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        toggle.setLayoutParams(mine);
        final int index = parent.indexOfChild(liveButton);
        toggle.setOnClickListener(v -> {
            boolean nowOn = !Prefs.on(Prefs.LIVE_VIDEO);
            if (nowOn && LiveVideoSource.playlist().isEmpty()) {
                Toast.makeText(activity,
                        "Playlist video kosong — tambah video dulu di pengaturan modul",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.set(Prefs.LIVE_VIDEO, nowOn);
            refreshLabel(toggle);
            Toast.makeText(activity,
                    nowOn ? "Live dari video galeri AKTIF" : "Kembali ke kamera biasa",
                    Toast.LENGTH_SHORT).show();
        });
        parent.addView(toggle, Math.max(0, index));
        markInstalled(activity);
        RuntimeLog.log("live toggle button added");
    }

    private static void markInstalled(Activity activity) {
        synchronized (INSTALLED) {
            INSTALLED.put(activity, Boolean.TRUE);
        }
    }

    private static void refreshLabel(Button toggle) {
        boolean on = Prefs.on(Prefs.LIVE_VIDEO);
        toggle.setText(on ? "🎥 Video galeri: AKTIF (ketuk = kamera biasa)"
                          : "🎥 Live dari video galeri (ketuk untuk aktif)");
    }

    /** Cari view teks tombol "Siarkan LIVE" / "Go LIVE" secara rekursif. */
    private static View findLiveButton(View root) {
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null) {
                String s = text.toString();
                for (String want : LIVE_BUTTON_TEXTS) {
                    if (s.contains(want)) return root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findLiveButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
