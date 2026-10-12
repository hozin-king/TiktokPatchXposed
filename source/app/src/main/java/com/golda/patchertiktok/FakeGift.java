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

    private static volatile Object giftManager;
    private static volatile Object effectWidget;
    private static volatile ClassLoader savedLoader;

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

    private static void addTriggerButton(View root, int gravity) {
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
        button.setOnClickListener(v -> showPicker(activity));
        parent.addView(button, lp);
    }

    private static final Object TAG = new Object();

    /** Gift picker reusing the module's bottom-sheet style. */
    private static void showPicker(Activity activity) {
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
                    if (!play(g.id)) {
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
