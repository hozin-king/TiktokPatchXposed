package com.golda.patchertiktok;

import java.lang.reflect.Method;

/**
 * Fake verified badge (local only): forces the verification getters on
 * {@code com.ss.android.ugc.aweme.profile.model.User} to report a verified
 * account, but ONLY for the logged-in account (checked via {@link SelfUid}),
 * so TikTok draws the blue checkmark on the user's own profile on this device.
 * Other people's profiles are never affected.
 *
 * Verified from TikTok 47.0.3 (androguard): the class has no boolean
 * {@code isVerified} field; verification is the int fields
 * {@code verificationType} ({@code getVerificationType()I}) and
 * {@code verificationBadgeType} ({@code getVerificationBadgeType()I}).
 * Returning a non-zero value (1) marks the account verified for the UI.
 * The server still treats the account as unverified — this changes nothing
 * outside this device.
 */
final class FakeVerified {
    private static final String USER = "com.ss.android.ugc.aweme.profile.model.User";
    private static final int VERIFIED = 1;

    private FakeVerified() { }

    static void install(ClassLoader loader) {
        Class<?> user = Hooks.findClass(USER, loader);
        if (user == null) {
            RuntimeLog.log("fake verified unavailable: user model not found");
            return;
        }
        int hooks = 0;
        for (Method method : user.getDeclaredMethods()) {
            String name = method.getName();
            boolean target = "getVerificationType".equals(name) || "getVerificationBadgeType".equals(name);
            if (!target || method.getParameterTypes().length != 0) continue;
            if (method.getReturnType() != int.class && method.getReturnType() != Integer.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.FAKE_VERIFIED) && SelfUid.isSelf(param.thisObject, loader)) {
                        param.setResult(VERIFIED);
                    }
                }
            });
            hooks++;
        }
        RuntimeLog.log("fake verified: hooks=" + hooks);
    }
}
