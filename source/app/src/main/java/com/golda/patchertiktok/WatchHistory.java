package com.golda.patchertiktok;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Records videos played in the feed — TikTok has no watch history of its own.
 * Entries live in a local SQLite database (see {@link HistoryDb}), newest first.
 */
final class WatchHistory {
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
        HistoryDb.migrateIfNeeded(context);
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
        HistoryDb.recordVideo(context, aid, author, desc);
    }

    /** Newest-first page of entries. */
    static List<Entry> entries(Context context, int offset, int limit) {
        return HistoryDb.videoPage(context, offset, limit);
    }

    static int count(Context context) {
        return HistoryDb.videoCount(context);
    }

    static void clear(Context context) {
        HistoryDb.clearVideos(context);
    }
}
