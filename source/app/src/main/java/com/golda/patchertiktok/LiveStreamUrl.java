package com.golda.patchertiktok;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captures direct live stream URLs (RTMP/FLV) while watching a live.
 *
 * Hook targets (verified from APKs):
 * - Older builds: com.ss.android.ugc.aweme.feed.model.StreamUrlModel#getRtmpPullUrl(),
 *   com.ss.android.ugc.aweme.feed.model.live.LiveStreamUrl#getRtmpPullUrl()/getFlvPullUrl().
 *   Kept for backward compatibility; they no-op when the class is absent.
 * - TikTok 47.0.3: the aweme models above are gone (StreamUrlModel is an empty
 *   stub). The real data lives in the live SDK:
 *   com.bytedance.android.livesdkapi.depend.model.live.StreamUrl
 *   (verified present in classes10.dex of 47.0.3). Its getters are obfuscated,
 *   so every no-arg method returning String (or JSONObject) is hooked and the
 *   result is kept only when it looks like a stream URL.
 *
 * Captures are stored in SQLite (see {@link HistoryDb}), newest first.
 * URLs are signed by TikTok and expire; they are shown for opening in an
 * external player, not stored long-term.
 */
final class LiveStreamUrl {
    private static final String STREAM_URL_MODEL = "com.ss.android.ugc.aweme.feed.model.StreamUrlModel";
    private static final String LIVE_STREAM_URL = "com.ss.android.ugc.aweme.feed.model.live.LiveStreamUrl";
    private static final String LIVESDK_STREAM_URL =
            "com.bytedance.android.livesdkapi.depend.model.live.StreamUrl";

    static final class Captured {
        final String rtmpUrl;
        final Map<String, String> flvUrls;
        final long time;

        Captured(String rtmpUrl, Map<String, String> flvUrls, long time) {
            this.rtmpUrl = rtmpUrl;
            this.flvUrls = flvUrls;
            this.time = time;
        }
    }

    private static final Object LOCK = new Object();
    private static String pendingRtmp;
    private static final Map<String, String> pendingFlv = new LinkedHashMap<>();
    private static Context appContext;

    private LiveStreamUrl() { }

    static void install(Context context, ClassLoader loader) {
        appContext = context.getApplicationContext();
        // Legacy targets (pre-47 builds).
        hookGetter(loader, STREAM_URL_MODEL, "getRtmpPullUrl");
        hookGetter(loader, LIVE_STREAM_URL, "getRtmpPullUrl");
        hookFlvGetter(loader);
        // TikTok 47.x live SDK (obfuscated getters, discovered by signature).
        hookLivesdkStringGetters(loader);
        hookLivesdkJsonGetter(loader);
        RuntimeLog.log("live stream url: installed");
    }

    private static void hookGetter(ClassLoader loader, String className, String methodName) {
        Class<?> cls = Hooks.findClass(className, loader);
        if (cls == null) {
            RuntimeLog.log("live stream url unavailable: " + className);
            return;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getParameterTypes().length != 0) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.LIVE_STREAM_URL)) return;
                    Object result = param.getResult();
                    if (result instanceof String) {
                        String url = (String) result;
                        if (looksLikeStreamUrl(url)) addUrl(url);
                    }
                }
            });
        }
    }

    private static void hookFlvGetter(ClassLoader loader) {
        Class<?> cls = Hooks.findClass(LIVE_STREAM_URL, loader);
        if (cls == null) return;
        for (Method method : cls.getDeclaredMethods()) {
            if (!"getFlvPullUrl".equals(method.getName()) || method.getParameterTypes().length != 0) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.LIVE_STREAM_URL)) return;
                    Object result = param.getResult();
                    if (result instanceof Map) {
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) result).entrySet()) {
                            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                                String url = (String) entry.getValue();
                                if (looksLikeStreamUrl(url)) addFlv((String) entry.getKey(), url);
                            }
                        }
                        flush();
                    }
                }
            });
        }
    }

    /** 47.x: hook every no-arg String getter; keep only real stream URLs. */
    private static void hookLivesdkStringGetters(ClassLoader loader) {
        Class<?> cls = Hooks.findClass(LIVESDK_STREAM_URL, loader);
        if (cls == null) {
            RuntimeLog.log("live stream url unavailable: " + LIVESDK_STREAM_URL);
            return;
        }
        int hooks = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != String.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.LIVE_STREAM_URL)) return;
                    Object result = param.getResult();
                    if (result instanceof String) {
                        String url = (String) result;
                        if (looksLikeStreamUrl(url)) {
                            addUrl(url);
                            flush();
                        }
                    }
                }
            });
            hooks++;
        }
        RuntimeLog.log("live stream url: livesdk string getters hooked=" + hooks);
    }

    /** 47.x: the JSONObject getter may carry the URLs; scan its values. */
    private static void hookLivesdkJsonGetter(ClassLoader loader) {
        Class<?> cls = Hooks.findClass(LIVESDK_STREAM_URL, loader);
        if (cls == null) return;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != org.json.JSONObject.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.LIVE_STREAM_URL)) return;
                    Object result = param.getResult();
                    if (!(result instanceof org.json.JSONObject)) return;
                    org.json.JSONObject json = (org.json.JSONObject) result;
                    java.util.Iterator<String> keys = json.keys();
                    boolean found = false;
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = json.optString(key, null);
                        if (looksLikeStreamUrl(value)) {
                            addFlv(key, value);
                            found = true;
                        }
                    }
                    if (found) flush();
                }
            });
        }
    }

    private static boolean looksLikeStreamUrl(String url) {
        if (url == null || url.length() < 12) return false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("rtmp://") || lower.startsWith("rtmps://")) return true;
        return lower.startsWith("http") &&
                (lower.contains(".flv") || lower.contains(".m3u8") || lower.contains("pull"));
    }

    private static void addUrl(String url) {
        synchronized (LOCK) {
            if (url.toLowerCase(Locale.US).startsWith("rtmp")) {
                pendingRtmp = url;
            } else {
                addFlv("auto", url);
            }
        }
    }

    private static void addFlv(String key, String url) {
        synchronized (LOCK) {
            // Avoid duplicates under different keys.
            for (Map.Entry<String, String> e : pendingFlv.entrySet()) {
                if (e.getValue().equals(url)) return;
            }
            String useKey = key;
            int n = 2;
            while (pendingFlv.containsKey(useKey)) useKey = key + "-" + (n++);
            pendingFlv.put(useKey, url);
        }
    }

    private static void flush() {
        String rtmp;
        Map<String, String> flv;
        synchronized (LOCK) {
            if (pendingRtmp == null && pendingFlv.isEmpty()) return;
            rtmp = pendingRtmp;
            flv = new LinkedHashMap<>(pendingFlv);
            pendingRtmp = null;
            pendingFlv.clear();
        }
        Context ctx = appContext;
        if (ctx != null) HistoryDb.recordLive(ctx, rtmp, flv);
    }

    /** Newest-first page of captures. */
    static List<Captured> page(Context context, int offset, int limit) {
        return HistoryDb.livePage(context, offset, limit);
    }

    static int count(Context context) {
        return HistoryDb.liveCount(context);
    }

    static List<String> allUrls(Captured captured) {
        List<String> out = new ArrayList<>();
        if (captured.rtmpUrl != null && !captured.rtmpUrl.isEmpty()) out.add(captured.rtmpUrl);
        out.addAll(captured.flvUrls.values());
        return out;
    }
}
