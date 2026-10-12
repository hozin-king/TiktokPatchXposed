package com.golda.patchertiktok;

import java.lang.reflect.Method;

/**
 * Fake streak day count (local only): overrides the day number shown on
 * streaks in chats with a user-chosen value.
 *
 * Verified from TikTok 47.0.3: the streak model is the Kotlin data class
 * {@code com.ss.android.ugc.aweme.im.streak.api.StreakData} (loaded
 * dynamically, so it is resolved defensively at runtime like
 * {@link StreakApi} does). The day count is the {@code streak} field,
 * read by TikTok's UI through {@code getStreak()}. Hooking the getter and
 * replacing its result changes the displayed number everywhere the model
 * is rendered, for every conversation.
 *
 * The server still holds the real number — the other person keeps seeing
 * the true day count. This changes nothing outside this device.
 */
final class FakeStreak {
    private static final String DATA = "com.ss.android.ugc.aweme.im.streak.api.StreakData";

    private FakeStreak() { }

    static void install(ClassLoader loader) {
        Class<?> data = Hooks.findClass(DATA, loader);
        if (data == null) {
            RuntimeLog.log("fake streak unavailable: StreakData not found");
            return;
        }
        int hooks = 0;
        for (Method method : data.getDeclaredMethods()) {
            if (!"getStreak".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 0) continue;
            Class<?> ret = method.getReturnType();
            if (ret != int.class && ret != Integer.class
                    && ret != long.class && ret != Long.class
                    && ret != Number.class) continue;
            final boolean wide = ret == long.class || ret == Long.class;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.FAKE_STREAK)) return;
                    long days = Prefs.number(Prefs.FAKE_STREAK_DAYS);
                    if (days <= 0) return;
                    param.setResult(wide ? days : (int) Math.min(days, Integer.MAX_VALUE));
                }
            });
            hooks++;
        }
        RuntimeLog.log("fake streak: hooks=" + hooks);
    }
}
