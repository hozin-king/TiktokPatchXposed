package com.golda.patchertiktok;

import java.lang.reflect.Method;

/**
 * Fake LIVE profile ring (local only): forces {@code isLive()} on
 * {@code com.ss.android.ugc.aweme.profile.model.User} to return true,
 * but ONLY for the logged-in account (checked via {@link SelfUid}),
 * so TikTok draws the LIVE ring around the user's own avatar on this device.
 * Other people's avatars are never affected.
 *
 * Verified from TikTok 47.0.3 (androguard): {@code isLive()Z} bytecode is
 * {@code return roomId != 0}, and the ring renderer calls
 * {@code User.isLive()} explicitly before drawing the ring via
 * ListLiveCircleItemVM on the avatar view. No client-side cryptographic
 * signing involved.
 *
 * Honest limits: tapping the ring tries to enter a room that does not exist
 * (roomId is 0), so it will likely fail. Other people do not see the ring —
 * the server still knows the account is not live.
 */
final class FakeLiveRing {
    private static final String USER = "com.ss.android.ugc.aweme.profile.model.User";

    private FakeLiveRing() { }

    static void install(ClassLoader loader) {
        Class<?> user = Hooks.findClass(USER, loader);
        if (user == null) {
            RuntimeLog.log("fake live ring unavailable: user model not found");
            return;
        }
        int hooks = 0;
        for (Method method : user.getDeclaredMethods()) {
            if (!"isLive".equals(method.getName())) continue;
            if (method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.FAKE_LIVE) && SelfUid.isSelf(param.thisObject, loader)) {
                        param.setResult(true);
                    }
                }
            });
            hooks++;
        }
        RuntimeLog.log("fake live ring: hooks=" + hooks);
    }
}
