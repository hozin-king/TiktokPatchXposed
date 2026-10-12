package com.golda.patchertiktok;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Identifies the logged-in TikTok account, so fake-identity features
 * (fake LIVE ring, fake verified badge) only affect the user's own
 * profile and never leak onto other people's avatars.
 *
 * <p>Uses the same service pattern as {@link StreakApi}:
 * {@code ServiceManager -> IUserService.getCurrentUserID()}.
 * Everything is resolved defensively at runtime; if the account cannot
 * be determined the features stay inert instead of affecting everyone.
 */
final class SelfUid {
    private static final String USER_SERVICE =
            "com.ss.android.ugc.aweme.framework.services.IUserService";

    private static volatile Object service;
    private static volatile Method loginMethod;
    private static volatile Method idMethod;
    private static volatile boolean resolved;

    private SelfUid() { }

    private static synchronized boolean resolve(ClassLoader loader) {
        if (resolved) return service != null;
        resolved = true;
        try {
            service = StreakApi.service(loader, USER_SERVICE);
            loginMethod = StreakApi.method(service.getClass(), "isLogin");
            idMethod = StreakApi.method(service.getClass(), "getCurrentUserID");
        } catch (Throwable ignored) {
            service = null;
        }
        return service != null;
    }

    /** UID of the logged-in account as a decimal string, or null if unknown. */
    static String current(ClassLoader loader) {
        if (!resolve(loader)) return null;
        try {
            if (!Boolean.TRUE.equals(loginMethod.invoke(service))) return null;
            Object id = idMethod.invoke(service);
            if (id == null) return null;
            String s = id instanceof Number
                    ? Long.toString(((Number) id).longValue())
                    : String.valueOf(id);
            return s.matches("[1-9][0-9]{0,18}") ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * True when the given TikTok {@code User} model object belongs to the
     * logged-in account. Compares the model's own UID (via {@code getUid()}
     * or the {@code uid} field) against the current account UID.
     */
    static boolean isSelf(Object user, ClassLoader loader) {
        if (user == null) return false;
        String self = current(loader);
        if (self == null) return false;
        try {
            Object uid = uidOf(user);
            if (uid == null) return false;
            String s = uid instanceof Number
                    ? Long.toString(((Number) uid).longValue())
                    : String.valueOf(uid);
            return self.equals(s);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object uidOf(Object user) throws Exception {
        Class<?> type = user.getClass();
        try {
            return type.getMethod("getUid").invoke(user);
        } catch (NoSuchMethodException e) {
            Field field = type.getDeclaredField("uid");
            field.setAccessible(true);
            return field.get(user);
        }
    }
}
