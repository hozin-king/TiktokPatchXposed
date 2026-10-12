package com.golda.patchertiktok;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake live gifts (local animation only): plays a gift animation on this device
 * without sending a real gift (no coins, no payment API touched).
 *
 * <p>Verified from TikTok 47.0.3 (androguard):
 * <ul>
 *   <li>{@code com.bytedance.android.livesdk.gift.base.platform.business.effect.LiveGiftEffectWidget#onGiftMessage}
 *       plays the animation from a {@code GiftMessage} purely locally (no network in the path).</li>
 *   <li>{@code com.bytedance.android.livesdk.model.message.GiftMessage} is a plain data class with a
 *       public no-arg constructor and public fields ({@code giftId}, {@code mGift}, {@code fromUser},
 *       {@code comboCount}, ...).</li>
 *   <li>{@code com.bytedance.android.livesdk.gift.base.platform.core.manager.GiftManager} provides
 *       {@code findGiftById(long)} and {@code getCurrentGiftPanelList()} from a local cache.</li>
 * </ul>
 *
 * <p>Trigger buttons are injected into the live gift panel, the live comment panel and the
 * regular video gift panel (all verified fragment classes, see below). Tapping one opens a
 * picker with the real gift list; picking a gift builds a fake {@code GiftMessage} and feeds
 * it straight to the captured effect widget.
 *
 * <p>For regular videos, the animation uses {@code GiftAnimationFragment} (verified in 47.0.3):
 * it plays from a {@code Comment} carrying {@code fakeGiftId} + {@code giftEffectPath}.
 * The effect path is cached by hooking {@code Comment.setGiftEffectPath} during normal
 * gift browsing — a gift's animation can be faked once its assets have been resolved
 * at least once by the app.
 *
 * <p>Honest limits: only this device shows the animation — the streamer and other viewers see
 * nothing, no gift is actually sent and no coins are spent. The gift list needs the gift
 * panel to have been opened once (cache). NEVER calls any real gift-sending or payment API.
 */
final class FakeGift {
    private static final String GIFT_MANAGER =
            "com.bytedance.android.livesdk.gift.base.platform.core.manager.GiftManager";
    private static final String EFFECT_WIDGET =
            "com.bytedance.android.livesdk.gift.base.platform.business.effect.LiveGiftEffectWidget";
    private static final String GIFT_MESSAGE =
            "com.bytedance.android.livesdk.model.message.GiftMessage";
    /** Live gift panel (bottom sheet). Verified: onViewCreated(View, Bundle). */
    private static final String GIFT_PANEL =
            "com.bytedance.android.livesdk.gift.base.platform.business.panel.anchor.GiftAnchorPanelSheetFragment";
    /** Live comment sheet. Verified: onCreateView(LayoutInflater, ViewGroup, Bundle) as obfuscated FT. */
    private static final String COMMENT_PANEL =
            "com.bytedance.android.livesdk.commonactionpanel.LiveCommentSheetPanel";
    /**
     * Regular video gift panel (comment keyboard assembly).
     * Verified in 47.0.3 (dex_base/classes9.dex): onViewCreated(View).
     */
    private static final String VIDEO_GIFT_PANEL =
            "com.ss.android.ugc.aweme.comment.keyboard.keyboardv2.refactor.CommentGiftSelectAssem";
    /**
     * Video gift animation player (DialogFragment).
     * Verified in 47.0.3 (dex_adex/classes24.dex): reads Comment from
     * getArguments().getSerializable("comment"), plays via PlayerController
     * from comment.getGiftEffectPath() when comment.getFakeGiftId() != 0.
     */
    private static final String GIFT_ANIMATION_FRAGMENT =
            "com.ss.android.ugc.aweme.comment.gift.ui.GiftAnimationFragment";
    /** Comment model carrying fake gift data. Verified: setFakeGiftId(J), setGiftEffectPath(String). */
    private static final String COMMENT_MODEL =
            "com.ss.android.ugc.aweme.comment.model.Comment";

    private static volatile Object giftManager;
    private static volatile Object effectWidget;
    private static volatile ClassLoader savedLoader;
    /** giftId -> local effect asset path, captured from normal gift browsing. */
    private static final java.util.Map<Long, String> effectPathCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Gift entry for the picker: id + display name. */
    static final class GiftInfo {
        final long id;
        final String name;
        GiftInfo(long id, String name) { this.id = id; this.name = name; }
    }

    private FakeGift() { }

    static void install(ClassLoader loader) {
        savedLoader = loader;
        Class<?> manager = Hooks.findClass(GIFT_MANAGER, loader);
        if (manager != null) {
            Hooks.hookConstructors(manager, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    giftManager = param.thisObject;
                }
            });
        } else {
            RuntimeLog.log("fake gift unavailable: GiftManager not found");
        }
        Class<?> widget = Hooks.findClass(EFFECT_WIDGET, loader);
        if (widget != null) {
            Hooks.hookConstructors(widget, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    effectWidget = param.thisObject;
                }
            });
        } else {
            RuntimeLog.log("fake gift unavailable: LiveGiftEffectWidget not found");
        }
        injectGiftPanelButton(loader);
        injectCommentPanelButton(loader);
        injectVideoGiftPanelButton(loader);
        hookEffectPathCache(loader);
        RuntimeLog.log("fake gift installed");
    }

    // ---- public API ----------------------------------------------------------

    /** Gifts from TikTok's own cached panel list. Empty when the panel was never opened. */
    static List<GiftInfo> gifts() {
        List<GiftInfo> out = new ArrayList<>();
        Object manager = giftManager;
        if (manager == null) return out;
        try {
            Method list = manager.getClass().getMethod("getCurrentGiftPanelList");
            Object raw = list.invoke(manager);
            if (!(raw instanceof List)) return out;
            for (Object gift : (List<?>) raw) {
                if (gift == null) continue;
                long id = readLongField(gift, "id", -1);
                String name = readStringField(gift, "name");
                if (id >= 0) out.add(new GiftInfo(id, name == null || name.isEmpty() ? "Gift " + id : name));
            }
        } catch (Throwable t) {
            RuntimeLog.log("fake gift list failed: " + t);
        }
        return out;
    }

    /**
     * Plays the gift animation locally. Returns false when the effect widget or
     * gift is unavailable. Never touches payment APIs.
     */
    static boolean play(long giftId) {
        Object manager = giftManager;
        Object widget = effectWidget;
        ClassLoader loader = savedLoader;
        if (manager == null || widget == null || loader == null) {
            RuntimeLog.log("fake gift play: no widget/manager captured yet");
            return false;
        }
        try {
            Method find = manager.getClass().getMethod("findGiftById", long.class);
            Object gift = find.invoke(manager, giftId);
            if (gift == null) {
                RuntimeLog.log("fake gift play: gift not found: " + giftId);
                return false;
            }
            Class<?> msgClass = Class.forName(GIFT_MESSAGE, false, loader);
            Object msg = msgClass.newInstance();
            setField(msg, "giftId", giftId);
            setField(msg, "mGift", gift);
            setField(msg, "comboCount", 1);
            // fromUser left null: the animation path resolves assets from giftId locally.
            Method target = null;
            for (Method m : widget.getClass().getMethods()) {
                if ("onGiftMessage".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                RuntimeLog.log("fake gift play: onGiftMessage not found");
                return false;
            }
            target.invoke(widget, msg);
            RuntimeLog.log("fake gift played: " + giftId);
            return true;
        } catch (Throwable t) {
            RuntimeLog.log("fake gift play failed: " + t);
            return false;
        }
    }

    // ---- trigger buttons -----------------------------------------------------

    /** Small trigger button inside the live gift panel (top area). */
    private static void injectGiftPanelButton(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(GIFT_PANEL, loader);
        if (panel == null) {
            RuntimeLog.log("fake gift: gift panel not found");
            return;
        }
        Hooks.Hook add = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.FAKE_GIFT)) return;
                if (param.args.length > 0 && param.args[0] instanceof View) {
                    addTriggerButton((View) param.args[0], Gravity.TOP | Gravity.END);
                }
            }
        };
        // onViewCreated(View, Bundle) — verified signature in 47.0.3.
        for (Method m : panel.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("onViewCreated".equals(m.getName()) && p.length == 2
                    && View.class.isAssignableFrom(p[0]) && p[1] == Bundle.class) {
                Hooks.hook(m, add);
                return;
            }
        }
        RuntimeLog.log("fake gift: gift panel onViewCreated not found");
    }

    /** Small trigger button inside the live comment sheet (top area). */
    private static void injectCommentPanelButton(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(COMMENT_PANEL, loader);
        if (panel == null) {
            RuntimeLog.log("fake gift: comment panel not found");
            return;
        }
        Hooks.Hook add = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.FAKE_GIFT)) return;
                Object view = param.getResult();
                if (view instanceof View) addTriggerButton((View) view, Gravity.TOP | Gravity.END);
            }
        };
        // onCreateView(LayoutInflater, ViewGroup, Bundle):View — obfuscated name in 47.0.3,
        // matched by signature instead.
        for (Method m : panel.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == LayoutInflater.class && p[1] == ViewGroup.class
                    && p[2] == Bundle.class && View.class.isAssignableFrom(m.getReturnType())) {
                Hooks.hook(m, add);
                return;
            }
        }
        RuntimeLog.log("fake gift: comment panel onCreateView not found");
    }

    /**
     * Trigger button inside the regular video gift panel (comment keyboard).
     * Verified in 47.0.3: CommentGiftSelectAssem.onViewCreated(View).
     * The button is placed at the bottom, next to the coin count.
     */
    private static void injectVideoGiftPanelButton(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(VIDEO_GIFT_PANEL, loader);
        if (panel == null) {
            RuntimeLog.log("fake gift: video gift panel not found");
            return;
        }
        Hooks.Hook add = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.FAKE_GIFT)) return;
                if (param.args.length > 0 && param.args[0] instanceof View) {
                    addTriggerButton((View) param.args[0], Gravity.BOTTOM | Gravity.END, true);
                }
            }
        };
        for (Method m : panel.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("onViewCreated".equals(m.getName()) && p.length == 1
                    && View.class.isAssignableFrom(p[0])) {
                Hooks.hook(m, add);
                RuntimeLog.log("fake gift: video gift panel hooked");
                return;
            }
        }
        RuntimeLog.log("fake gift: video gift panel onViewCreated not found");
    }

    /**
     * Caches giftId -> effect path whenever the app resolves one during normal
     * gift browsing. Verified in 47.0.3: CommentPublishViewModel.U63 calls
     * Comment.setFakeGiftId(J) before Comment.setGiftEffectPath(String).
     */
    private static void hookEffectPathCache(ClassLoader loader) {
        Class<?> comment = Hooks.findClass(COMMENT_MODEL, loader);
        if (comment == null) {
            RuntimeLog.log("fake gift: Comment model not found for path cache");
            return;
        }
        for (Method m : comment.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("setGiftEffectPath".equals(m.getName()) && p.length == 1 && p[0] == String.class) {
                Hooks.hook(m, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        try {
                            Object path = param.args.length > 0 ? param.args[0] : null;
                            if (!(path instanceof String) || ((String) path).isEmpty()) return;
                            Method getId = param.thisObject.getClass().getMethod("getFakeGiftId");
                            Object id = getId.invoke(param.thisObject);
                            if (id instanceof Number) {
                                long giftId = ((Number) id).longValue();
                                if (giftId != 0) {
                                    effectPathCache.put(giftId, (String) path);
                                }
                            }
                        } catch (Throwable ignored) { }
                    }
                });
                RuntimeLog.log("fake gift: effect path cache hooked");
                return;
            }
        }
        RuntimeLog.log("fake gift: setGiftEffectPath not found");
    }

    /**
     * Plays a fake gift animation for regular videos via GiftAnimationFragment.
     * Returns false when the effect path is not cached yet (the gift's assets
     * must have been resolved at least once by normal browsing).
     */
    static boolean playVideoGift(long giftId, Activity activity) {
        ClassLoader loader = savedLoader;
        if (loader == null || activity == null) return false;
        String path = effectPathCache.get(giftId);
        if (path == null || path.isEmpty()) {
            RuntimeLog.log("fake gift video: no cached effect path for " + giftId);
            return false;
        }
        try {
            Class<?> commentClass = Class.forName(COMMENT_MODEL, false, loader);
            Object comment = commentClass.newInstance();
            commentClass.getMethod("setFakeGiftId", long.class).invoke(comment, giftId);
            commentClass.getMethod("setGiftEffectPath", String.class).invoke(comment, path);

            Bundle args = new Bundle();
            args.putSerializable("comment", (java.io.Serializable) comment);

            Class<?> fragmentClass = Class.forName(GIFT_ANIMATION_FRAGMENT, false, loader);
            Object fragment = fragmentClass.newInstance();
            fragmentClass.getMethod("setArguments", Bundle.class).invoke(fragment, args);

            Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
            fragmentClass.getMethod("show", Class.forName("androidx.fragment.app.FragmentManager", false, loader), String.class)
                    .invoke(fragment, fm, "GiftAnimationFragment");
            RuntimeLog.log("fake gift video played: " + giftId);
            return true;
        } catch (Throwable t) {
            RuntimeLog.log("fake gift video play failed: " + t);
            return false;
        }
    }

    private static void addTriggerButton(View root, int gravity) {
        addTriggerButton(root, gravity, false);
    }

    private static void addTriggerButton(View root, int gravity, boolean isVideo) {
        if (!(root instanceof ViewGroup)) return;
        // Avoid duplicates when the view is recreated.
        ViewGroup parent = (ViewGroup) root;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i).getTag() == TAG) return;
        }
        Activity activity = activityOf(root);
        if (activity == null) return;
        Button button = new Button(activity);
        button.setTag(TAG);
        button.setText("\uD83C\uDF81");
        button.setTextSize(18);
        button.setContentDescription("Fake gift");
        float density = activity.getResources().getDisplayMetrics().density;
        int size = (int) (48 * density);
        ViewGroup.LayoutParams lp;
        if (parent instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size, gravity);
            int m = (int) (8 * density);
            flp.setMargins(m, m, m, m);
            lp = flp;
        } else {
            lp = new ViewGroup.LayoutParams(size, size);
        }
        button.setOnClickListener(v -> showPicker(activity, isVideo));
        parent.addView(button, lp);
    }

    private static final Object TAG = new Object();

    /** Gift picker reusing the module's bottom-sheet style. */
    private static void showPicker(Activity activity) {
        showPicker(activity, false);
    }

    private static void showPicker(Activity activity, boolean isVideo) {
        List<GiftInfo> list = gifts();
        if (list.isEmpty()) {
            Toast.makeText(activity, I18n.get(I18n.S.FAKE_GIFT_EMPTY), Toast.LENGTH_SHORT).show();
            return;
        }
        List<DownloadSheet.Row> rows = new ArrayList<>(list.size());
        for (GiftInfo g : list) {
            rows.add(new DownloadSheet.Row(g.name, "id " + g.id, Glyph.Kind.FLAME, 0xFFFF9F0A, false));
        }
        DownloadSheet.show(activity, I18n.get(I18n.S.FAKE_GIFT_PICK),
                I18n.get(I18n.S.FAKE_GIFT_DESC), rows,
                index -> {
                    GiftInfo g = list.get(index);
                    boolean ok = isVideo ? playVideoGift(g.id, activity) : play(g.id);
                    if (!ok) {
                        Toast.makeText(activity, I18n.get(I18n.S.FAKE_GIFT_EMPTY),
                                Toast.LENGTH_SHORT).show();
                    }
                }, null);
    }

    // ---- reflection helpers --------------------------------------------------

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = fieldOf(target.getClass(), name);
        if (value instanceof Long) f.setLong(target, (Long) value);
        else if (value instanceof Integer) f.setInt(target, (Integer) value);
        else f.set(target, value);
    }

    private static Field fieldOf(Class<?> type, String name) throws Exception {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException e) {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }

    private static long readLongField(Object target, String name, long fallback) {
        try {
            Field f = fieldOf(target.getClass(), name);
            Object v = f.get(target);
            return v instanceof Number ? ((Number) v).longValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String readStringField(Object target, String name) {
        try {
            Field f = fieldOf(target.getClass(), name);
            Object v = f.get(target);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Activity activityOf(View view) {
        Context ctx = view.getContext();
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    /** Run on the main thread (view hooks already are, but play() may be called later). */
    @SuppressWarnings("unused")
    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new android.os.Handler(Looper.getMainLooper()).post(r);
    }
}
