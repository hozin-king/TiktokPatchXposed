package com.golda.patchertiktok;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.List;
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
    private static final String PREVIEW_TAG = "live_video_preview";
    /** Overlay preview video per activity (weak, ikut GC saat activity hancur). */
    private static final WeakHashMap<Activity, View> PREVIEWS = new WeakHashMap<>();
    /** View kamera asli yang disembunyikan saat diganti VideoView, per activity. */
    private static final WeakHashMap<Activity, View> REPLACED_CAMERAS = new WeakHashMap<>();
    /** VideoView pengganti kamera, per activity. */
    private static final WeakHashMap<Activity, VideoView> REPLACEMENT_VIDEOS = new WeakHashMap<>();

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
            if (nowOn) {
                showPreview(activity);
            } else {
                hidePreview(activity);
            }
            String diag = nowOn
                    ? "Live dari video galeri AKTIF (" + LiveVideoSource.diagStatus() + ")"
                    : "Kembali ke kamera biasa";
            Toast.makeText(activity, diag, Toast.LENGTH_LONG).show();
        });
        parent.addView(toggle, Math.max(0, index));
        markInstalled(activity);
        // Jika pref sudah on (mis. dari pengaturan modul), tampilkan preview juga.
        if (Prefs.on(Prefs.LIVE_VIDEO)) showPreview(activity);
        RuntimeLog.log("live toggle button added");
    }

    /**
     * Ganti view kamera dengan VideoView: cari view kamera (TextureView /
     * SurfaceView terbesar di hierarki layar live), sembunyikan view itu
     * (INVISIBLE, bukan GONE, supaya layout tidak bergeser), lalu sisipkan
     * VideoView di parent yang sama, tepat setelah view kamera, dengan
     * LayoutParams yang sama — jadi video menempati persis area kamera,
     * tombol dan UI live lainnya tetap bisa ditekan.
     * Video yang diputar adalah playlist yang sama dengan yang dikirim
     * ke stream, dan loop-nya mengikuti setelan mode di pengaturan modul
     * (sekali / loop daftar / loop satu video). Preview dibisukan total.
     * Kalau view kamera tidak ketemu (belum ter-layout / bukan
     * TextureView-SurfaceView), jatuh ke overlay full-screen lama supaya
     * fitur tetap jalan, bukan gagal total.
     */
    private static void showPreview(Activity activity) {
        hidePreview(activity); // buang yang lama dulu, kembalikan kamera
        final List<String> list = LiveVideoSource.playlist();
        if (list.isEmpty()) return;
        final int mode = (int) Prefs.number(Prefs.LIVE_VIDEO_MODE);
        try {
            final VideoView vv = new VideoView(activity);
            vv.setTag(PREVIEW_TAG);
            final int[] idx = {0};
            final Runnable[] playAt = new Runnable[1];
            playAt[0] = () -> {
                if (idx[0] < 0 || idx[0] >= list.size()) return;
                vv.setVideoPath(list.get(idx[0]));
                vv.start();
            };
            vv.setOnPreparedListener((MediaPlayer mp) -> {
                // LOOP_ONE saja yang looping di level MediaPlayer; mode lain
                // maju lewat OnCompletionListener di bawah.
                mp.setLooping(mode == LiveVideoSource.MODE_LOOP_ONE);
                // Preview visual saja: bisukan total supaya tidak berebut
                // audio focus dengan pemutar video TikTok (FYP jadi hening).
                mp.setVolume(0f, 0f);
                vv.start();
            });
            vv.setOnCompletionListener((MediaPlayer mp) -> {
                if (mode == LiveVideoSource.MODE_LOOP_ONE) return;
                idx[0]++;
                if (idx[0] >= list.size()) {
                    if (mode == LiveVideoSource.MODE_LOOP_LIST) {
                        idx[0] = 0;
                    } else {
                        return; // MODE_ONCE: berhenti di video terakhir
                    }
                }
                playAt[0].run();
            });
            vv.setOnErrorListener((mp, what, extra) -> {
                RuntimeLog.log("live preview error: " + what + "," + extra);
                return true;
            });
            // Jalur utama: ganti view kamera di tempatnya.
            View decor = activity.getWindow().getDecorView();
            View camera = decor != null ? findCameraView(decor) : null;
            if (camera != null && camera.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) camera.getParent();
                int index = parent.indexOfChild(camera);
                camera.setVisibility(View.INVISIBLE);
                parent.addView(vv, index + 1, camera.getLayoutParams());
                synchronized (REPLACED_CAMERAS) {
                    REPLACED_CAMERAS.put(activity, camera);
                    REPLACEMENT_VIDEOS.put(activity, vv);
                }
                playAt[0].run();
                RuntimeLog.log("live preview: camera view replaced ("
                        + camera.getClass().getSimpleName() + " "
                        + camera.getWidth() + "x" + camera.getHeight()
                        + ", " + list.size() + " video, mode=" + mode + ")");
                return;
            }
            // Fallback: overlay full-screen (perilaku lama).
            ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
            if (root == null) return;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag(PREVIEW_TAG);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            overlay.addView(vv, lp);
            // Ketuk overlay untuk menutup preview (tetap mode video aktif untuk stream).
            overlay.setOnClickListener(v -> hidePreview(activity));
            root.addView(overlay);
            synchronized (PREVIEWS) {
                PREVIEWS.put(activity, overlay);
            }
            playAt[0].run();
            RuntimeLog.log("live preview shown (fallback overlay, camera view not found): "
                    + list.size() + " video, mode=" + mode);
        } catch (Throwable t) {
            RuntimeLog.log("live preview failed: " + t);
        }
    }

    /**
     * Cari view kamera: TextureView / SurfaceView (VideoView adalah
     * subclass SurfaceView, tapi preview lama sudah dibuang oleh
     * hidePreview() sebelum pencarian, jadi yang ketemu adalah view
     * TikTok sendiri) dengan luas terbesar yang sudah ter-layout.
     * Tidak bergantung pada nama kelas TikTok yang diobfuscate.
     */
    private static View findCameraView(View root) {
        View best = null;
        long bestArea = 0;
        if (root instanceof TextureView || root instanceof SurfaceView) {
            // Jangan ambil VideoView pengganti kita sendiri bila masih ada.
            if (PREVIEW_TAG.equals(root.getTag())) return null;
            if (root.getWidth() > 0 && root.getHeight() > 0) {
                best = root;
                bestArea = (long) root.getWidth() * root.getHeight();
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCameraView(group.getChildAt(i));
                if (found != null) {
                    long area = (long) found.getWidth() * found.getHeight();
                    if (area > bestArea) {
                        best = found;
                        bestArea = area;
                    }
                }
            }
        }
        return best;
    }

    /** Sembunyikan preview video galeri dan kembalikan view kamera asli. */
    private static void hidePreview(Activity activity) {
        // Jalur pengganti kamera: lepas VideoView, tampilkan lagi kamera.
        VideoView replacement;
        View camera;
        synchronized (REPLACED_CAMERAS) {
            replacement = REPLACEMENT_VIDEOS.remove(activity);
            camera = REPLACED_CAMERAS.remove(activity);
        }
        if (replacement != null) {
            try {
                replacement.stopPlayback();
                ViewGroup parent = (ViewGroup) replacement.getParent();
                if (parent != null) parent.removeView(replacement);
            } catch (Throwable ignored) { }
        }
        if (camera != null) {
            try {
                camera.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) { }
        }
        // Jalur overlay fallback lama.
        View old;
        synchronized (PREVIEWS) {
            old = PREVIEWS.remove(activity);
        }
        if (old == null) {
            // Fallback: cari by tag bila map sudah ke-GC.
            try {
                ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
                if (root != null) old = root.findViewWithTag(PREVIEW_TAG);
            } catch (Throwable ignored) { }
        }
        if (old != null) {
            try {
                // Matikan total: stop MediaPlayer di dalam overlay supaya tidak
                // jalan hantu di background dan mengganggu audio TikTok.
                if (old instanceof ViewGroup) stopVideoViews((ViewGroup) old);
                ViewGroup parent = (ViewGroup) old.getParent();
                if (parent != null) parent.removeView(old);
            } catch (Throwable ignored) { }
        }
    }

    /** Stop semua VideoView di dalam view group (matikan MediaPlayer hantu). */
    private static void stopVideoViews(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c instanceof VideoView) {
                try {
                    ((VideoView) c).stopPlayback();
                } catch (Throwable ignored) { }
            } else if (c instanceof ViewGroup) {
                stopVideoViews((ViewGroup) c);
            }
        }
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
