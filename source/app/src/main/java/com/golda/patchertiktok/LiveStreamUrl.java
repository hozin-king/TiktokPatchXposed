package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures direct live stream URLs (RTMP/FLV) while watching a live.
 * Hooks the verified getters on TikTok's stream URL models:
 *   com.ss.android.ugc.aweme.feed.model.StreamUrlModel#getRtmpPullUrl()
 *   com.ss.android.ugc.aweme.feed.model.live.LiveStreamUrl#getRtmpPullUrl()
 *   com.ss.android.ugc.aweme.feed.model.live.LiveStreamUrl#getFlvPullUrl()
 * URLs are signed by TikTok and expire; they are shown for opening in an
 * external player, not stored long-term.
 */
final class LiveStreamUrl {
    private static final String STREAM_URL_MODEL = "com.ss.android.ugc.aweme.feed.model.StreamUrlModel";
    private static final String LIVE_STREAM_URL = "com.ss.android.ugc.aweme.feed.model.live.LiveStreamUrl";

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

    private static volatile Captured latest;
    private static final Object LOCK = new Object();

    private LiveStreamUrl() { }

    static void install(ClassLoader loader) {
        hookGetter(loader, STREAM_URL_MODEL, "getRtmpPullUrl");
        hookGetter(loader, LIVE_STREAM_URL, "getRtmpPullUrl");
        hookFlvGetter(loader);
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
                        if (!url.isEmpty()) store(url, null);
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
                        Map<String, String> urls = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) result).entrySet()) {
                            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                                urls.put((String) entry.getKey(), (String) entry.getValue());
                            }
                        }
                        if (!urls.isEmpty()) store(null, urls);
                    }
                }
            });
        }
    }

    private static void store(String rtmpUrl, Map<String, String> flvUrls) {
        synchronized (LOCK) {
            Captured current = latest;
            String rtmp = rtmpUrl != null ? rtmpUrl : (current != null ? current.rtmpUrl : null);
            Map<String, String> flv = flvUrls != null ? flvUrls
                    : (current != null ? current.flvUrls : new LinkedHashMap<String, String>());
            latest = new Captured(rtmp, flv, System.currentTimeMillis());
        }
    }

    static Captured latest() {
        return latest;
    }

    static List<String> allUrls(Captured captured) {
        List<String> out = new ArrayList<>();
        if (captured.rtmpUrl != null && !captured.rtmpUrl.isEmpty()) out.add(captured.rtmpUrl);
        out.addAll(captured.flvUrls.values());
        return out;
    }
}
