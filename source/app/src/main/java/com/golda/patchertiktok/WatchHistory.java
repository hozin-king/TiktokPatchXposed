package com.golda.patchertiktok;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Records videos played in the feed — TikTok has no watch history of its own.
 * Entries live in TikTok's own data directory, newest first, capped at 200.
 */
final class WatchHistory {
    private static final String FILE = "tiktokpatchxposed_history";
    private static final String KEY = "entries";
    private static final int MAX = 200;
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";

    static final class Entry {
        final String aid;
        final String author;
        final String desc;
        final long time;

        Entry(String aid, String author, String desc, long time) {
            this.aid = aid;
            this.author = author;
            this.desc = desc;
            this.time = time;
        }
    }

    private WatchHistory() { }

    static void install(Context context, ClassLoader loader) {
        Class<?> panel = Hooks.findClass(PANEL, loader);
        Method current = panel == null ? null : RenderedAdSkip.currentAweme(panel);
        if (panel == null || current == null) {
            RuntimeLog.log("watch history unavailable: feed panel not found");
            return;
        }
        current.setAccessible(true);
        final String[] lastAid = {null};
        int hooks = 0;
        for (Method method : panel.getDeclaredMethods()) {
            if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.WATCH_HISTORY)) return;
                    try {
                        Object aweme = current.invoke(param.thisObject);
                        if (aweme == null) return;
                        Object aid = Reflect.get(aweme, "aid");
                        if (!(aid instanceof String) || ((String) aid).isEmpty()) return;
                        if (aid.equals(lastAid[0])) return;
                        lastAid[0] = (String) aid;
                        record(context, aweme, (String) aid);
                    } catch (Throwable ignored) { }
                }
            });
            hooks++;
        }
        RuntimeLog.log("watch history: hooks=" + hooks);
    }

    private static void record(Context context, Object aweme, String aid) {
        String author = "";
        String desc = "";
        try {
            Object user = Reflect.call(aweme, "getAuthor");
            if (user != null) {
                Object nickname = Reflect.call(user, "getNickname");
                Object uniqueId = Reflect.call(user, "getUniqueId");
                if (nickname instanceof String) author = (String) nickname;
                if (uniqueId instanceof String && !((String) uniqueId).isEmpty()) {
                    author = author.isEmpty() ? "@" + uniqueId : author + " (@" + uniqueId + ")";
                }
            }
            Object text = Reflect.call(aweme, "getDesc");
            if (text instanceof String) desc = (String) text;
        } catch (Throwable ignored) { }
        try {
            SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            // Drop any older entry for the same video, then prepend.
            JSONArray fresh = new JSONArray();
            fresh.put(entryJson(aid, author, desc, System.currentTimeMillis()));
            for (int i = 0; i < array.length() && fresh.length() < MAX; i++) {
                JSONObject old = array.optJSONObject(i);
                if (old != null && aid.equals(old.optString("aid"))) continue;
                fresh.put(old);
            }
            prefs.edit().putString(KEY, fresh.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static JSONObject entryJson(String aid, String author, String desc, long time) {
        JSONObject json = new JSONObject();
        try {
            json.put("aid", aid);
            json.put("author", author);
            json.put("desc", desc);
            json.put("time", time);
        } catch (org.json.JSONException ignored) { }
        return json;
    }

    static List<Entry> entries(Context context) {
        List<Entry> out = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                out.add(new Entry(json.optString("aid"), json.optString("author"),
                        json.optString("desc"), json.optLong("time")));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void clear(Context context) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        } catch (Throwable ignored) { }
    }
}
