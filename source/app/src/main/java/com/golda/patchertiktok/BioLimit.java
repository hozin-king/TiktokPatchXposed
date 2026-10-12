package com.golda.patchertiktok;

import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Raises TikTok's bio character limit from 160 to 2000.
 *
 * The bio field's 160 cap is applied client-side as an
 * {@code InputFilter.LengthFilter(160)} on the bio EditText (the edit screen
 * shows a "45/160" counter). This hooks {@code TextView.setFilters} and swaps
 * that filter for a 2000 limit, but only on the bio field — identified by
 * "bio" in the view's class name or resource entry name — so other fields
 * are never affected.
 *
 * Honest limit: the server may still reject a bio longer than 160 characters
 * when saving. This only removes the cap inside the app.
 */
final class BioLimit {
    private static final int STOCK_LIMIT = 160;
    private static final int RAISED_LIMIT = 2000;

    private BioLimit() { }

    static void install() {
        Hooks.hookAll(TextView.class, "setFilters", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.BIO_LIMIT)) return;
                if (!(param.thisObject instanceof EditText)) return;
                if (!(param.args[0] instanceof InputFilter[])) return;
                if (!isBioField((View) param.thisObject)) return;
                InputFilter[] filters = (InputFilter[]) param.args[0];
                boolean changed = false;
                for (int index = 0; index < filters.length; index++) {
                    if (filters[index] instanceof InputFilter.LengthFilter
                            && maxOf((InputFilter.LengthFilter) filters[index]) == STOCK_LIMIT) {
                        filters[index] = new InputFilter.LengthFilter(RAISED_LIMIT);
                        changed = true;
                    }
                }
                if (changed) {
                    param.args[0] = filters;
                    RuntimeLog.log("bio limit raised to " + RAISED_LIMIT);
                }
            }
        });
        RuntimeLog.log("bio limit: installed");
    }

    private static boolean isBioField(View view) {
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        if (cls.contains("bio")) return true;
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String name = view.getResources().getResourceEntryName(id).toLowerCase(Locale.ROOT);
                if (name.contains("bio")) return true;
            }
        } catch (Throwable ignored) {
            // Unknown ID or missing resources: not the bio field.
        }
        return false;
    }

    private static int maxOf(InputFilter.LengthFilter filter) {
        try {
            Field field = InputFilter.LengthFilter.class.getDeclaredField("mMax");
            field.setAccessible(true);
            return field.getInt(filter);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
