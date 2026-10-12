package com.golda.patchertiktok;

import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional device spoof. Installed only when enabled with a model selected at startup,
 * so turning it off adds no hooks at all.
 *
 * TikTok learns the device in two ways, and both are replaced:
 * <ul>
 *   <li>{@code android.os.Build} static fields (MODEL, MANUFACTURER, BRAND, DEVICE,
 *   PRODUCT) — read directly by TikTok's code in this process.</li>
 *   <li>The common request parameters TikTok sends to its server:
 *   {@code f_global_device_brand}, {@code f_global_device_manufacturer} and
 *   {@code f_global_device_model} from
 *   {@code com.ss.ugc.clientai.core.api.FeatureProducer#getStringFeature}
 *   (verified present in TikTok 47.0.3 dex; same pattern as {@link RegionSpoof}).</li>
 * </ul>
 *
 * Only real flagship Android profiles are offered. An "iPhone" option is deliberately
 * absent: TikTok's Android build sends Android-shaped device parameters, and an iPhone
 * model string there would be inconsistent with everything else the app reports
 * (Android version, ABI, package), so it would look fake rather than flagship.
 *
 * Honest limit: this changes what the app reports as the device. Whether TikTok's
 * server treats any model differently (for example for upload quality) is TikTok's
 * decision and is not guaranteed by this toggle.
 */
final class DeviceSpoof {
    /** TikTok's feature store that supplies the common request parameters. */
    private static final String FEATURE_PRODUCER = "com.ss.ugc.clientai.core.api.FeatureProducer";

    /** A flagship profile with real, documented Build values. */
    static final class Profile {
        final String id;
        final String name;
        final String model;
        final String manufacturer;
        final String brand;
        final String device;
        final String product;

        Profile(String id, String name, String model, String manufacturer,
                String brand, String device, String product) {
            this.id = id;
            this.name = name;
            this.model = model;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.device = device;
            this.product = product;
        }
    }

    private static final List<Profile> PROFILES = new ArrayList<>();

    static {
        PROFILES.add(new Profile("s24ultra", "Samsung Galaxy S24 Ultra",
                "SM-S928B", "samsung", "samsung", "dm3q", "dm3qx"));
        PROFILES.add(new Profile("pixel8pro", "Google Pixel 8 Pro",
                "Pixel 8 Pro", "Google", "google", "husky", "husky"));
        PROFILES.add(new Profile("pixel9proxl", "Google Pixel 9 Pro XL",
                "Pixel 9 Pro XL", "Google", "google", "komodo", "komodo"));
    }

    private DeviceSpoof() { }

    static List<Profile> profiles() { return PROFILES; }

    static Profile find(String id) {
        for (Profile profile : PROFILES) {
            if (profile.id.equals(id)) return profile;
        }
        return null;
    }

    /** The selected profile, or null when the feature is off or nothing is selected. */
    static Profile selected() {
        if (!Prefs.on(Prefs.DEVICE_SPOOF)) return null;
        return find(Prefs.text(Prefs.DEVICE_SPOOF_MODEL));
    }

    static boolean active() { return selected() != null; }

    static void install(ClassLoader loader) {
        Profile profile = selected();
        if (profile == null) return;
        setBuildFields(profile);
        hookRequestParams(loader, profile);
        RuntimeLog.log("device spoof: " + profile.name);
    }

    /** Build fields are static final; the final bit is cleared so they can be replaced. */
    private static void setBuildFields(Profile profile) {
        setBuildField("MODEL", profile.model);
        setBuildField("MANUFACTURER", profile.manufacturer);
        setBuildField("BRAND", profile.brand);
        setBuildField("DEVICE", profile.device);
        setBuildField("PRODUCT", profile.product);
    }

    private static void setBuildField(String name, String value) {
        try {
            Field field = Build.class.getDeclaredField(name);
            field.setAccessible(true);
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, value);
        } catch (Throwable error) {
            RuntimeLog.log("device spoof: Build." + name + " unavailable");
        }
    }

    /** Same pattern as {@link RegionSpoof}: override the device request parameters. */
    private static void hookRequestParams(ClassLoader loader, Profile profile) {
        Class<?> producer = Hooks.findClass(FEATURE_PRODUCER, loader);
        if (producer == null) {
            RuntimeLog.log("device spoof: FeatureProducer not found");
            return;
        }
        int hooks = 0;
        for (Method method : producer.getDeclaredMethods()) {
            if (!method.getName().startsWith("getStringFeature") || method.getReturnType() != String.class) continue;
            int nameIndex = -1;
            Class<?>[] params = method.getParameterTypes();
            for (int index = 0; index < params.length; index++) {
                if (params[index] == String.class) { nameIndex = index; break; }
            }
            if (nameIndex < 0) continue;
            int name = nameIndex;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    Profile current = selected();
                    if (current == null || !(param.args[name] instanceof String)) return;
                    switch ((String) param.args[name]) {
                        case "f_global_device_brand":
                            param.setResult(current.brand);
                            break;
                        case "f_global_device_manufacturer":
                            param.setResult(current.manufacturer);
                            break;
                        case "f_global_device_model":
                            param.setResult(current.model);
                            break;
                        default:
                            break;
                    }
                }
            });
            hooks++;
        }
        RuntimeLog.log("device spoof: request params hooks=" + hooks);
    }
}
