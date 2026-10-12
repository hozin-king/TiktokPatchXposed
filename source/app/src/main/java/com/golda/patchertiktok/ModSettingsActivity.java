package com.golda.patchertiktok;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Environment;import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.golda.patchertiktok.I18n.S;

/**
 * The module's settings screen. TikTok starts its own settings activity with
 * {@link #EXTRA}; {@link SettingsEntry} swaps in this class, so the screen lives in
 * TikTok's task and theme without a manifest entry of its own.
 */
public final class ModSettingsActivity extends Activity {
    static final String EXTRA = "com.golda.patchertiktok.SETTINGS";
    static final String EXTRA_DARK = "com.golda.patchertiktok.DARK";
    static final String SOURCE_URL = "https://github.com/hik0w/TiktokPatchXposed";

    private final ArrayDeque<View> pages = new ArrayDeque<>();
    private final Runnable prefsListener = this::onPrefsChanged;
    private Palette palette;
    private Locale locale;
    private FrameLayout stack;
    private View restartBar;
    private Object backCallback;
    private int topInset;
    private int bottomInset;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.load(getApplicationContext());
        locale = getResources().getConfiguration().getLocales().get(0);
        I18n.use(locale);
        boolean dark = getIntent().hasExtra(EXTRA_DARK)
                ? getIntent().getBooleanExtra(EXTRA_DARK, true)
                : (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        palette = Palette.of(dark);
        setupWindow();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.background);
        stack = new FrameLayout(this);
        root.addView(stack, new FrameLayout.LayoutParams(-1, -1));
        View statusScrim = new View(this);
        statusScrim.setBackgroundColor(palette.statusBar);
        root.addView(statusScrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        restartBar = restartBar();
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(restartBar, barParams);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                topInset = bars.top;
                bottomInset = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            for (int index = 0; index < stack.getChildCount(); index++) applyInsets(stack.getChildAt(index));
            statusScrim.getLayoutParams().height = topInset;
            statusScrim.requestLayout();
            restartBar.setPadding(dp(12), dp(8), dp(12), dp(12) + bottomInset);
            return insets;
        });
        setContentView(root);
        push(mainPage(), false);
        updateRestartBar(false);
    }

    /**
     * TikTok serves part of its strings through its own Resources subclass, and its code asks the top
     * activity for them, e.g. for in-app message banners. With the plain activity resources such a
     * banner crashed TikTok while this screen was open.
     */
    @Override
    public Resources getResources() {
        try {
            Context app = getApplicationContext();
            if (app != null) return app.getResources();
        } catch (RuntimeException ignored) {
            // Not attached yet.
        }
        return super.getResources();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.listen(prefsListener);
    }

    @Override
    protected void onStop() {
        Prefs.unlisten(prefsListener);
        super.onStop();
    }

    /** Used when TikTok has not opted into predictive back; otherwise {@link #syncBackCallback()} applies. */
    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (pages.size() > 1) pop();
        else super.onBackPressed();
    }

    // ---- window ----------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(palette.background));
        window.setStatusBarColor(0);
        window.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(palette.dark ? 0 : light, light);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!palette.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    // ---- navigation ------------------------------------------------------------------------

    private void push(View page, boolean animate) {
        View previous = pages.peek();
        pages.push(page);
        stack.addView(page, new FrameLayout.LayoutParams(-1, -1));
        applyInsets(page);
        reveal(page);
        if (animate && previous != null) {
            float width = getResources().getDisplayMetrics().widthPixels;
            page.setTranslationX(width);
            page.animate().translationX(0).setDuration(280)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            previous.animate().translationX(-width * 0.3f).setDuration(280)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction(() -> previous.setVisibility(View.INVISIBLE)).start();
        }
        syncBackCallback();
    }

    /** Cards slide up one after another, as the page appears. */
    private void reveal(View page) {
        Object holder = page.getTag(R_ID_CONTENT);
        if (!(holder instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) holder;
        int shown = 0;
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.setAlpha(0f);
            child.setTranslationY(dp(18));
            child.animate().alpha(1f).translationY(0).setStartDelay(60L + 40L * Math.min(shown++, 8))
                    .setDuration(340).setInterpolator(EASE).start();
        }
    }

    private void pop() {
        View page = pages.pop();
        View previous = pages.peek();
        hideKeyboard(page);
        float width = getResources().getDisplayMetrics().widthPixels;
        if (previous != null) {
            previous.setVisibility(View.VISIBLE);
            previous.animate().translationX(0).setDuration(250).start();
        }
        page.animate().translationX(width).setDuration(250).withEndAction(() -> stack.removeView(page)).start();
        if (previous != null && previous.getTag() instanceof Runnable) ((Runnable) previous.getTag()).run();
        syncBackCallback();
    }

    /** API 33+ dispatches back through callbacks when the host app opts into predictive back. */
    private void syncBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (pages.size() > 1 && backCallback == null) {
            android.window.OnBackInvokedCallback callback = this::pop;
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        } else if (pages.size() <= 1 && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    private void applyInsets(View page) {
        Object holder = page.getTag(R_ID_INSETS);
        if (holder instanceof Insettable) ((Insettable) holder).apply(topInset, bottomInset);
    }

    private interface Insettable { void apply(int top, int bottom); }

    private static final int R_ID_INSETS = 0x7f0b0001;
    private static final int R_ID_CONTENT = 0x7f0b0002;
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int[] AGE_DAYS = {7, 30, 90, 180, 365, 730};
    private static final S[] AGE_NAMES = {S.AGE_WEEK, S.AGE_MONTH, S.AGE_3_MONTHS, S.AGE_6_MONTHS, S.AGE_YEAR, S.AGE_2_YEARS};

    // ---- pages -----------------------------------------------------------------------------

    /** A group of settings with its own page; the main page lists the groups. */
    private enum Category {
        FEED(S.SECTION_FEED, Glyph.Kind.FEED, 0xFFFE2C55),
        PLAYBACK(S.SECTION_PLAYBACK, Glyph.Kind.PLAY, 0xFF8B5CF6),
        INTERFACE(S.SECTION_INTERFACE, Glyph.Kind.PALETTE, 0xFF14B8A6),
        SOCIAL(S.SECTION_SOCIAL, Glyph.Kind.COMMENT, 0xFFFF9F0A),
        SHARING(S.SECTION_SHARING, Glyph.Kind.DOWNLOAD, 0xFF2BB673),
        REGION(S.SECTION_REGION, Glyph.Kind.GLOBE, 0xFF1FA2FF),
        GHOST(S.SECTION_GHOST, Glyph.Kind.GHOST, 0xFF6B7280),
        STREAKS(S.SECTION_STREAKS, Glyph.Kind.FLAME, 0xFFFF6A2B);

        final S title;
        final Glyph.Kind icon;
        final int color;

        Category(S title, Glyph.Kind icon, int color) {
            this.title = title;
            this.icon = icon;
            this.color = color;
        }
    }

    /** A plain switch; listed on its group's page and found by the search on the main page. */
    private static final class Setting {
        final Category category;
        final S title;
        final S description;
        final String key;

        Setting(Category category, S title, S description, String key) {
            this.category = category;
            this.title = title;
            this.description = description;
            this.key = key;
        }
    }

    private static final Setting[] SETTINGS = {
            new Setting(Category.FEED, S.ADS, S.ADS_DESC, Prefs.ADS),
            new Setting(Category.FEED, S.LIVE, S.LIVE_DESC, Prefs.LIVE),
            new Setting(Category.FEED, S.ACQUAINTANCES, S.ACQUAINTANCES_DESC, Prefs.ACQUAINTANCES),
            new Setting(Category.FEED, S.SEEKBAR, S.SEEKBAR_DESC, Prefs.SEEKBAR),
            new Setting(Category.FEED, S.PROMO, S.PROMO_DESC, Prefs.PROMO),
            new Setting(Category.FEED, S.AI_CONTENT, S.AI_CONTENT_DESC, Prefs.AI_CONTENT),
            new Setting(Category.FEED, S.CUSTOM_FILTER, S.CUSTOM_FILTER_DESC, Prefs.CUSTOM_FILTER),
            new Setting(Category.FEED, S.LIVE_STREAM_URL, S.LIVE_STREAM_URL_DESC, Prefs.LIVE_STREAM_URL),
            new Setting(Category.PLAYBACK, S.REMEMBER_CLEAN, S.REMEMBER_CLEAN_DESC, Prefs.REMEMBER_CLEAN),
            new Setting(Category.PLAYBACK, S.RESUME_POSITION, S.RESUME_POSITION_DESC, Prefs.RESUME_POSITION),
            new Setting(Category.PLAYBACK, S.AUTO_SCROLL, S.AUTO_SCROLL_DESC, Prefs.AUTO_SCROLL),
            new Setting(Category.PLAYBACK, S.REMEMBER_SPEED, S.REMEMBER_SPEED_DESC, Prefs.REMEMBER_SPEED),
            new Setting(Category.PLAYBACK, S.WATCH_HISTORY, S.WATCH_HISTORY_DESC, Prefs.WATCH_HISTORY),
            new Setting(Category.INTERFACE, S.HIDE_INBOX_STORIES, S.HIDE_INBOX_STORIES_DESC, Prefs.HIDE_INBOX_STORIES),
            new Setting(Category.INTERFACE, S.BURN_IN, S.BURN_IN_DESC, Prefs.BURN_IN),
            new Setting(Category.INTERFACE, S.ACCENT, S.ACCENT_DESC, Prefs.ACCENT),
            new Setting(Category.INTERFACE, S.AMOLED, S.AMOLED_DESC, Prefs.AMOLED),
            new Setting(Category.INTERFACE, S.NO_LOGIN_WALL, S.NO_LOGIN_WALL_DESC, Prefs.NO_LOGIN_WALL),
            new Setting(Category.SOCIAL, S.COMMENT_REPOSTS, S.COMMENT_REPOSTS_DESC, Prefs.COMMENT_REPOSTS),
            new Setting(Category.SOCIAL, S.FAKE_VERIFIED, S.FAKE_VERIFIED_DESC, Prefs.FAKE_VERIFIED),
            new Setting(Category.SOCIAL, S.FAKE_LIVE, S.FAKE_LIVE_DESC, Prefs.FAKE_LIVE),
            new Setting(Category.SOCIAL, S.FAKE_GIFT, S.FAKE_GIFT_DESC, Prefs.FAKE_GIFT),
            new Setting(Category.SOCIAL, S.FAKE_STREAK, S.FAKE_STREAK_DESC, Prefs.FAKE_STREAK),
            new Setting(Category.SOCIAL, S.LIVE_VIDEO, S.LIVE_VIDEO_DESC, Prefs.LIVE_VIDEO),
            new Setting(Category.SOCIAL, S.BIO_LIMIT, S.BIO_LIMIT_DESC, Prefs.BIO_LIMIT),
            new Setting(Category.SOCIAL, S.NEW_PROFILE, S.NEW_PROFILE_DESC, Prefs.NEW_PROFILE),
            new Setting(Category.SOCIAL, S.EXTRA_FEATURES, S.EXTRA_FEATURES_DESC, Prefs.EXTRA_FEATURES),
            new Setting(Category.SOCIAL, S.COPY_COMMENT, S.COPY_COMMENT_DESC, Prefs.COPY_COMMENT_TEXT),
            new Setting(Category.SOCIAL, S.AUTHOR_REGION, S.AUTHOR_REGION_DESC, Prefs.AUTHOR_REGION),
            new Setting(Category.SOCIAL, S.AUTO_TRANSLATE, S.AUTO_TRANSLATE_DESC, Prefs.AUTO_TRANSLATE),
            new Setting(Category.SHARING, S.NO_WATERMARK, null, Prefs.NO_WATERMARK),
            new Setting(Category.SHARING, S.DOWNLOAD_ANY, S.DOWNLOAD_ANY_DESC, Prefs.DOWNLOAD_ANY),
            new Setting(Category.SHARING, S.QUALITY_PICKER, S.QUALITY_PICKER_DESC, Prefs.QUALITY_PICKER),
            new Setting(Category.SHARING, S.SAVE_ALL_PHOTOS, S.SAVE_ALL_PHOTOS_DESC, Prefs.SAVE_ALL_PHOTOS),
            new Setting(Category.SHARING, S.COMMENT_MEDIA, S.COMMENT_MEDIA_DESC, Prefs.COMMENT_MEDIA),
            new Setting(Category.SHARING, S.SCREENSHOTS, S.SCREENSHOTS_DESC, Prefs.SCREENSHOTS),
            new Setting(Category.SHARING, S.CLEAN_LINKS, S.CLEAN_LINKS_DESC, Prefs.CLEAN_LINKS),
            new Setting(Category.SHARING, S.EXTERNAL_BROWSER, S.EXTERNAL_BROWSER_DESC, Prefs.EXTERNAL_BROWSER),
            new Setting(Category.REGION, S.REGION_LANGUAGE, S.REGION_LANGUAGE_DESC, Prefs.REGION_LANGUAGE),
            new Setting(Category.REGION, S.OLD_VIDEOS, S.OLD_VIDEOS_DESC, Prefs.OLD_VIDEOS),
            new Setting(Category.GHOST, S.GHOST_STORIES, S.GHOST_STORIES_DESC, Prefs.GHOST_STORIES),
            new Setting(Category.GHOST, S.GHOST_PROFILE, S.GHOST_PROFILE_DESC, Prefs.GHOST_PROFILE),
            new Setting(Category.GHOST, S.GHOST_POSTS, S.GHOST_POSTS_DESC, Prefs.GHOST_POSTS),
            new Setting(Category.GHOST, S.BLOCK_LOCATION, S.BLOCK_LOCATION_DESC, Prefs.BLOCK_LOCATION),
            new Setting(Category.GHOST, S.HIDE_VPN, S.HIDE_VPN_DESC, Prefs.HIDE_VPN),
            new Setting(Category.GHOST, S.SPOOF_ANDROID_ID, S.SPOOF_ANDROID_ID_DESC, Prefs.SPOOF_ANDROID_ID),
            new Setting(Category.GHOST, S.DEVICE_SPOOF, S.DEVICE_SPOOF_DESC, Prefs.DEVICE_SPOOF),
            new Setting(Category.STREAKS, S.AUTO_STREAK, S.AUTO_STREAK_DESC, Prefs.AUTO_STREAK),
            new Setting(Category.STREAKS, S.STREAK_REMINDER, S.STREAK_REMINDER_DESC, Prefs.STREAK_REMINDER),
    };

    private View mainPage() {
        Page page = new Page(I18n.get(S.APP_NAME), true);
        TextView greeting = new TextView(this);
        greeting.setText(I18n.get(S.GREETING));
        greeting.setTextColor(palette.textSecondary);
        greeting.setTextSize(16);
        greeting.setTypeface(Fonts.regular(this));
        greeting.setPadding(dp(32), 0, dp(32), dp(16));
        page.content.addView(greeting);
        LinearLayout status = page.card();
        TextView statusDetail = statusRow(status);

        EditText search = searchBox(page.content);
        LinearLayout groups = new LinearLayout(this);
        groups.setOrientation(LinearLayout.VERTICAL);
        page.content.addView(groups, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        page.content.addView(results, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, dp(12));
        LinearLayout list = card(groups);
        List<TextView> counters = new ArrayList<>();
        for (Category category : Category.values()) counters.add(categoryRow(list, category));
        groups.addView(new View(this), gap);
        LinearLayout about = card(groups);
        navigation(about, I18n.get(S.VERSION), BuildConfig.VERSION_NAME, null);
        navigation(about, I18n.get(S.BACKUP_EXPORT), null, view -> exportSettings());
        navigation(about, I18n.get(S.BACKUP_IMPORT), null, view -> push(importPage(), true));
        navigation(about, I18n.get(S.SOURCE_CODE), null, view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (RuntimeException ignored) { }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(locale);
                View shown = query.isEmpty() ? groups : results;
                View hidden = query.isEmpty() ? results : groups;
                if (!query.isEmpty()) showResults(results, query);
                if (shown.getVisibility() != View.VISIBLE) {
                    hidden.setVisibility(View.GONE);
                    shown.setVisibility(View.VISIBLE);
                    shown.setAlpha(0f);
                    shown.setTranslationY(dp(8));
                    shown.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(EASE).start();
                }
            }
        });
        page.root.setTag((Runnable) () -> {
            statusDetail.setText(statusText());
            for (int index = 0; index < counters.size(); index++) {
                counters.get(index).setText(categorySummary(Category.values()[index]));
            }
        });
        return page.root;
    }

    /** First card on the main page: the module is running, with the TikTok build and region it runs with. */
    private TextView statusRow(LinearLayout card) {
        LinearLayout row = row(card);
        row.setOnClickListener(view -> {
            hideKeyboard(view);
            push(statusPage(), true);
        });
        View dot = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(FeatureStatus.problems() == 0 ? 0xFF2BB673 : 0xFFFF9F0A);
        dot.setBackground(circle);
        ObjectAnimator breathe = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f));
        breathe.setDuration(1300);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        dot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { breathe.start(); }
            @Override public void onViewDetachedFromWindow(View view) { breathe.cancel(); }
        });
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMarginEnd(dp(14));
        row.addView(dot, dotParams);
        LinearLayout texts = texts(row, I18n.get(S.STATUS_ACTIVE), statusText());
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(20)));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return (TextView) texts.getChildAt(1);
    }

    /** Feature names as Module installs them, shown with the setting they belong to. */
    private static final String[][] FEATURE_TITLES = {
            {"feed filter", "ADS"}, {"search ads", "PROMO"}, {"rendered ads", "ADS"}, {"startup ads", "ADS"},
            {"LIVE button", "LIVE"}, {"seekbar", "SEEKBAR"}, {"remember clear display", "REMEMBER_CLEAN"},
            {"resume position", "RESUME_POSITION"}, {"playback speed", "REMEMBER_SPEED"}, {"downloads plus", "QUALITY_PICKER"}, {"auto-scroll", "AUTO_SCROLL"}, {"comment media", "COMMENT_MEDIA"},
            {"comment copy", "COPY_COMMENT"}, {"external browser", "EXTERNAL_BROWSER"},
            {"interface", "HIDE_INBOX_STORIES"}, {"overlay", "OVERLAY_OPACITY"}, {"accent color", "ACCENT"},
            {"author region", "AUTHOR_REGION"}, {"comment translation", "AUTO_TRANSLATE"}, {"downloads", "NO_WATERMARK"}, {"clean links", "CLEAN_LINKS"},
            {"invisible mode", "SECTION_GHOST"}, {"region", "REGION"}, {"recommendation", "REGION_LANGUAGE"},
            {"A/B overrides", "EXTRA_FEATURES"}, {"auto streaks", "AUTO_STREAK"},
            {"device model", "DEVICE_SPOOF"}, {"longer bio", "BIO_LIMIT"},
            {"live video", "LIVE_VIDEO"}};

    private View statusPage() {
        Page page = new Page(I18n.get(S.STATUS_TITLE), true);
        LinearLayout card = page.card();
        for (String feature : FeatureStatus.features()) {
            String title = null;
            for (String[] entry : FEATURE_TITLES) {
                if (entry[0].equals(feature)) title = I18n.get(S.valueOf(entry[1]));
            }
            if (title == null) title = feature;
            String problem = FeatureStatus.problem(feature);
            LinearLayout row = row(card);
            row.setBackground(null);
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(problem == null ? 0xFF2BB673 : 0xFFFF9F0A);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.setMarginEnd(dp(14));
            row.addView(dot, dotParams);
            texts(row, title, (problem == null ? I18n.get(S.STATUS_OK) : I18n.get(S.STATUS_PROBLEM)) + " · " + feature);
        }
        page.footer(I18n.get(S.STATUS_FOOTER));
        return page.root;
    }

    private String statusText() {
        String tiktok;
        try {
            tiktok = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            tiktok = "?";
        }
        int problems = FeatureStatus.problems();
        String features = problems == 0 ? I18n.get(S.STATUS_ALL_OK) : String.format(locale, I18n.get(S.STATUS_SOME), problems);
        return "TikTok " + tiktok + " · " + features;
    }

    private EditText searchBox(LinearLayout parent) {
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(rounded(palette.field, dp(14)));
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Glyph(Glyph.Kind.SEARCH, palette.textSecondary, dp(18)));
        searchBox.addView(glass, new LinearLayout.LayoutParams(dp(18), dp(18)));
        EditText search = new EditText(this);
        search.setBackground(null);
        search.setHint(I18n.get(S.SEARCH_SETTINGS));
        search.setHintTextColor(palette.textSecondary);
        search.setTextColor(palette.textPrimary);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(8), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(42), 1));
        search.setOnFocusChangeListener((view, focused) -> searchBox.animate()
                .scaleX(focused ? 1.015f : 1f).scaleY(focused ? 1.015f : 1f).setDuration(160).start());
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(42));
        boxParams.setMargins(dp(8), dp(12), dp(8), dp(12));
        parent.addView(searchBox, boxParams);
        return search;
    }

    /** A group row: colored icon tile, title, how many of its switches are on. */
    private TextView categoryRow(LinearLayout card, Category category) {
        LinearLayout row = row(card);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new Glyph(category.icon, 0xFFFFFFFF, dp(20)));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(rounded(category.color, dp(9)));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.setMarginEnd(dp(14));
        row.addView(icon, iconParams);
        LinearLayout texts = texts(row, I18n.get(category.title), categorySummary(category));
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(20)));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        chevronParams.setMarginStart(dp(8));
        row.addView(chevron, chevronParams);
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    icon.animate().scaleX(0.86f).scaleY(0.86f).setDuration(110).setInterpolator(EASE).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(420)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        row.setOnClickListener(view -> {
            hideKeyboard(view);
            push(categoryPage(category), true);
        });
        return (TextView) texts.getChildAt(1);
    }

    private String categorySummary(Category category) {
        if (category == Category.REGION) return regionLabel();
        int total = 0;
        int enabled = 0;
        for (Setting setting : SETTINGS) {
            if (setting.category != category) continue;
            total++;
            if (Prefs.on(setting.key)) enabled++;
        }
        return String.format(locale, I18n.get(S.ENABLED_COUNT), enabled, total);
    }

    private void showResults(LinearLayout results, String query) {
        results.removeAllViews();
        LinearLayout card = null;
        for (Setting setting : SETTINGS) {
            String title = I18n.get(setting.title);
            String description = setting.description == null ? "" : I18n.get(setting.description);
            String group = I18n.get(setting.category.title);
            if (!(title + " " + description + " " + group).toLowerCase(locale).contains(query)) continue;
            if (card == null) card = card(results);
            toggle(card, setting.title, setting.description, setting.key);
        }
        if (card == null) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(S.NOTHING_FOUND));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(32), dp(24), dp(32));
            results.addView(empty);
        }
    }

    private View categoryPage(Category category) {
        Page page = new Page(I18n.get(category.title), true);
        LinearLayout card = page.card();
        View dim = null;
        if (category == Category.REGION) {
            buildRegion(page, card);
        } else {
            for (Setting setting : SETTINGS) {
                if (setting.category != category) continue;
                toggle(card, setting.title, setting.description, setting.key);
                // Burn-in protection: dimming is optional, shown right under its switch.
                if (Prefs.BURN_IN.equals(setting.key)) dim = toggle(card, S.BURN_IN_DIM, S.BURN_IN_DIM_DESC, Prefs.BURN_IN_DIM);
            }
        }
        if (category == Category.PLAYBACK) {
            TextView value = navigation(card, I18n.get(S.WATCH_HISTORY_TITLE), null,
                    view -> push(historyPage(), true));
            View row = (View) value.getParent();
            Runnable sync = () -> row.setVisibility(Prefs.on(Prefs.WATCH_HISTORY) ? View.VISIBLE : View.GONE);
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.SOCIAL) {
            TextView value = navigation(card, I18n.get(S.TRANSLATE_EXCLUDE), Prefs.text(Prefs.TRANSLATE_EXCLUDE),
                    view -> push(excludePage(), true));
            View row = (View) value.getParent();
            TextView streakValue = navigation(card, I18n.get(S.FAKE_STREAK_DAYS), streakDaysLabel(),
                    view -> push(streakDaysPage(), true));
            View streakRow = (View) streakValue.getParent();
            TextView liveListValue = navigation(card, I18n.get(S.LIVE_VIDEO_LIST), liveVideoListLabel(),
                    view -> push(liveVideoListPage(), true));
            View liveListRow = (View) liveListValue.getParent();
            TextView liveModeValue = navigation(card, I18n.get(S.LIVE_VIDEO_MODE), liveVideoModeLabel(),
                    view -> push(liveVideoModePage(), true));
            View liveModeRow = (View) liveModeValue.getParent();
            Runnable sync = () -> {
                value.setText(Prefs.text(Prefs.TRANSLATE_EXCLUDE));
                row.setVisibility(Prefs.on(Prefs.AUTO_TRANSLATE) ? View.VISIBLE : View.GONE);
                streakValue.setText(streakDaysLabel());
                streakRow.setVisibility(Prefs.on(Prefs.FAKE_STREAK) ? View.VISIBLE : View.GONE);
                liveListValue.setText(liveVideoListLabel());
                liveListRow.setVisibility(Prefs.on(Prefs.LIVE_VIDEO) ? View.VISIBLE : View.GONE);
                liveModeValue.setText(liveVideoModeLabel());
                liveModeRow.setVisibility(Prefs.on(Prefs.LIVE_VIDEO) ? View.VISIBLE : View.GONE);
            };
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.STREAKS) {
            TextView value = navigation(card, I18n.get(S.STREAK_SEND), stickerLabel(), view -> push(stickerPage(), true));
            View row = (View) value.getParent();
            Runnable sync = () -> {
                value.setText(stickerLabel());
                row.setVisibility(Prefs.on(Prefs.AUTO_STREAK) ? View.VISIBLE : View.GONE);
            };
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.INTERFACE) {
            navigation(card, I18n.get(S.HIDE_PARTS), null, view -> push(hidePage(), true));
            opacityRow(page);
            TextView value = navigation(card, I18n.get(S.ACCENT_PICK), null, view -> push(colorPage(), true));
            View row = (View) value.getParent();
            View dimRow = dim;
            Runnable sync = () -> {
                value.setCompoundDrawablesRelative(swatch((int) Prefs.number(Prefs.ACCENT_COLOR), dp(22)), null, null, null);
                row.setVisibility(Prefs.on(Prefs.ACCENT) ? View.VISIBLE : View.GONE);
                if (dimRow != null) dimRow.setVisibility(Prefs.on(Prefs.BURN_IN) ? View.VISIBLE : View.GONE);
            };
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.PLAYBACK) {
            TextView value = navigation(card, I18n.get(S.SPEEDS), speedsLabel(), view -> push(speedsPage(), true));
            // Chain with the history-row sync above instead of overwriting its tag:
            // otherwise toggling WATCH_HISTORY never updates the history row visibility.
            Object previous = page.root.getTag();
            Runnable prev = previous instanceof Runnable ? (Runnable) previous : null;
            Runnable sync = () -> {
                if (prev != null) prev.run();
                value.setText(speedsLabel());
            };
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.FEED) {
            View setup = (View) navigation(card, I18n.get(S.FILTER_SETUP), null, view -> push(filterPage(), true)).getParent();
            TextView urlValue = navigation(card, I18n.get(S.LIVE_STREAM_URL), null,
                    view -> push(liveUrlPage(), true));
            View urlRow = (View) urlValue.getParent();
            Runnable sync = () -> {
                setup.setVisibility(Prefs.on(Prefs.CUSTOM_FILTER) ? View.VISIBLE : View.GONE);
                urlRow.setVisibility(Prefs.on(Prefs.LIVE_STREAM_URL) ? View.VISIBLE : View.GONE);
            };
            sync.run();
            page.root.setTag(sync);
        }
        if (category == Category.GHOST) {
            TextView deviceValue = navigation(card, I18n.get(S.DEVICE_SPOOF_MODEL), deviceLabel(),
                    view -> push(devicePage(), true));
            View deviceRow = (View) deviceValue.getParent();
            Runnable sync = () -> {
                deviceValue.setText(deviceLabel());
                deviceRow.setVisibility(Prefs.on(Prefs.DEVICE_SPOOF) ? View.VISIBLE : View.GONE);
            };
            sync.run();
            page.root.setTag(sync);
        }
        return page.root;
    }

    /** A slider for the opacity of buttons and captions over videos. */
    private void opacityRow(Page page) {
        page.section(I18n.get(S.OVERLAY_OPACITY));
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.START);
        TextView value = new TextView(this);
        value.setTextColor(palette.textPrimary);
        value.setTextSize(17);
        value.setTypeface(Fonts.semibold(this));
        row.addView(value);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(90);
        long current = Prefs.number(Prefs.OVERLAY_OPACITY);
        int percent = current <= 0 ? 100 : (int) Math.max(10, Math.min(100, current));
        slider.setProgress(percent - 10);
        slider.setProgressTintList(ColorStateList.valueOf(palette.trackOn));
        slider.setThumbTintList(ColorStateList.valueOf(palette.trackOn));
        value.setText(percent + "%");
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                value.setText((progress + 10) + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                Prefs.setNumber(Prefs.OVERLAY_OPACITY, bar.getProgress() + 10);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        row.addView(slider, params);
        page.footer(I18n.get(S.OVERLAY_OPACITY_DESC));
    }

    private static final int[] ACCENTS = {
            0x25F4EE, 0x1FA2FF, 0x8B5CF6, 0xFF4FA3, 0xFF8A00, 0x22C55E, 0xFACC15, 0x14B8A6};

    private GradientDrawable swatch(int rgb, int size) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF000000 | rgb);
        circle.setStroke(dp(1), palette.dark ? 0x33FFFFFF : 0x22000000);
        circle.setSize(size, size);
        circle.setBounds(0, 0, size, size);
        return circle;
    }

    /** Ready colors to tap, plus any hex value. */
    private View colorPage() {
        Page page = new Page(I18n.get(S.ACCENT_PICK), true);
        LinearLayout card = page.card();
        int current = (int) Prefs.number(Prefs.ACCENT_COLOR) & 0xFFFFFF;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(12), dp(16), dp(12), dp(16));
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout line = null;
        for (int index = 0; index < ACCENTS.length; index++) {
            if (index % 4 == 0) {
                line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER);
                grid.addView(line, new LinearLayout.LayoutParams(-1, dp(64)));
            }
            int rgb = ACCENTS[index];
            FrameLayout cell = new FrameLayout(this);
            ImageView dot = new ImageView(this);
            dot.setImageDrawable(swatch(rgb, dp(44)));
            cell.addView(dot, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
            if (rgb == current) {
                ImageView check = new ImageView(this);
                check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, 0xFFFFFFFF, dp(22)));
                cell.addView(check, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            }
            cell.setOnClickListener(view -> {
                view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction(() -> {
                    Prefs.setNumber(Prefs.ACCENT_COLOR, rgb);
                    pop();
                }).start();
            });
            line.addView(cell, new LinearLayout.LayoutParams(0, dp(56), 1));
        }

        page.section(I18n.get(S.ACCENT_HEX));
        LinearLayout hexCard = page.card();
        LinearLayout row = row(hexCard);
        row.setBackground(null);
        ImageView preview = new ImageView(this);
        preview.setImageDrawable(swatch(current, dp(26)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        previewParams.setMarginEnd(dp(12));
        row.addView(preview, previewParams);
        EditText hex = new EditText(this);
        hex.setBackground(null);
        hex.setPadding(0, 0, 0, 0);
        hex.setText(String.format(Locale.ROOT, "#%06X", current));
        hex.setTextColor(palette.textPrimary);
        hex.setTextSize(17);
        hex.setTypeface(Fonts.semibold(this));
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        row.addView(hex, new LinearLayout.LayoutParams(0, -2, 1));
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                Integer rgb = parseHex(s.toString());
                if (rgb != null) preview.setImageDrawable(swatch(rgb, dp(26)));
            }
        });
        hex.setOnEditorActionListener((view, action, event) -> {
            Integer rgb = parseHex(hex.getText().toString());
            if (rgb != null) {
                Prefs.setNumber(Prefs.ACCENT_COLOR, rgb);
                pop();
            }
            return true;
        });
        page.footer(I18n.get(S.ACCENT_FOOTER));
        return page.root;
    }

    private static Integer parseHex(String value) {
        String hex = value.trim().replace("#", "");
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return Integer.parseInt(hex, 16);
    }

    private void exportSettings() {
        String json = Prefs.export();
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TiktokPatchXposed", json));
        android.widget.Toast.makeText(this, I18n.get(S.BACKUP_COPIED), android.widget.Toast.LENGTH_SHORT).show();
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, json);
        try {
            startActivity(Intent.createChooser(share, I18n.get(S.BACKUP_EXPORT)));
        } catch (RuntimeException ignored) { }
    }

    /** Paste a backup made with "Export settings". */
    private View importPage() {
        Page page = new Page(I18n.get(S.BACKUP_IMPORT), true);
        LinearLayout card = page.card();
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setHint(I18n.get(S.BACKUP_HINT));
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(14);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(4);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
        android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
        if (clipboard != null && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (pasted != null && pasted.toString().contains("TiktokPatchXposed")) field.setText(pasted);
        }
        page.content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(12)));
        LinearLayout actions = page.card();
        navigation(actions, I18n.get(S.BACKUP_APPLY), null, view -> {
            boolean done = Prefs.importJson(field.getText().toString());
            android.widget.Toast.makeText(this, I18n.get(done ? S.BACKUP_DONE : S.BACKUP_INVALID),
                    android.widget.Toast.LENGTH_SHORT).show();
            if (done) {
                hideKeyboard(view);
                pop();
            }
        });
        return page.root;
    }

    private String speedsLabel() {
        String speeds = Prefs.text(Prefs.SPEEDS).trim();
        if (speeds.isEmpty()) return I18n.get(S.SPEEDS_TIKTOK);
        StringBuilder label = new StringBuilder();
        for (Float speed : PlaybackSpeed.parseSpeeds(speeds)) {
            if (label.length() > 0) label.append(", ");
            label.append(speed % 1 == 0 ? String.valueOf(speed.intValue()) : String.valueOf(speed));
        }
        return label.toString();
    }

    /** Parts of the layer over videos to hide. */
    private View hidePage() {
        Page page = new Page(I18n.get(S.HIDE_PARTS), true);
        LinearLayout card = page.card();
        String[] keys = {Prefs.HIDE_BUTTONS, Prefs.HIDE_MUSIC, Prefs.HIDE_CAPTION, Prefs.HIDE_TAKO};
        S[] titles = {S.HIDE_BUTTONS, S.HIDE_MUSIC, S.HIDE_CAPTION, S.HIDE_TAKO};
        S[] descriptions = {S.HIDE_BUTTONS_DESC, null, null, S.HIDE_TAKO_DESC};
        for (int index = 0; index < keys.length; index++) toggle(card, titles[index], descriptions[index], keys[index]);
        page.footer(I18n.get(S.HIDE_PARTS_FOOTER));
        return page.root;
    }

    private String stickerLabel() {
        if (StreakSticker.chosen() == null) return I18n.get(S.STREAK_FLAME);
        String title = Prefs.text(Prefs.STREAK_STICKER_TITLE);
        return title == null || title.isEmpty() ? I18n.get(S.STREAK_STICKER) : title;
    }

    /** What auto streaks send: TikTok's flame, or one of the user's favourite stickers. */
    private View stickerPage() {
        Page page = new Page(I18n.get(S.STREAK_SEND), true);
        choice(page.card(), I18n.get(S.STREAK_FLAME), StreakSticker.chosen() == null, () -> {
            Prefs.setText(Prefs.STREAK_STICKER, "");
            Prefs.setText(Prefs.STREAK_STICKER_TITLE, "");
        });
        page.section(I18n.get(S.STREAK_FAVOURITES));
        LinearLayout card = page.card();
        TextView status = new TextView(this);
        status.setText(I18n.get(S.STREAK_LOADING));
        status.setTextColor(palette.textSecondary);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(24), dp(16), dp(24));
        card.addView(status);
        page.footer(I18n.get(S.STREAK_STICKER_FOOTER));
        new Thread(() -> {
            java.util.List<StreakSticker.Choice> stickers = StreakSticker.favourites();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (stickers == null || stickers.isEmpty()) {
                    status.setText(I18n.get(stickers == null ? S.STREAK_OUTSIDE : S.STREAK_NONE));
                    return;
                }
                card.removeView(status);
                stickerGrid(card, stickers);
            });
        }, "tpx-favourite-stickers").start();
        return page.root;
    }

    private void stickerGrid(LinearLayout card, java.util.List<StreakSticker.Choice> stickers) {
        String chosen = StreakSticker.chosen();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout line = null;
        for (int index = 0; index < stickers.size(); index++) {
            if (index % 4 == 0) {
                line = new LinearLayout(this);
                grid.addView(line, new LinearLayout.LayoutParams(-1, dp(84)));
            }
            StreakSticker.Choice sticker = stickers.get(index);
            FrameLayout cell = new FrameLayout(this);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(14));
            frame.setColor(sticker.id.equals(chosen) ? (palette.accent & 0x33FFFFFF) : 0);
            if (sticker.id.equals(chosen)) frame.setStroke(dp(2), palette.accent);
            cell.setBackground(frame);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(image, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
            loadPicture(image, sticker.id, sticker.previews);
            cell.setOnClickListener(view -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() -> {
                Prefs.setText(Prefs.STREAK_STICKER, sticker.id);
                Prefs.setText(Prefs.STREAK_STICKER_TITLE, sticker.title);
                pop();
            }).start());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            line.addView(cell, params);
        }
        while (line != null && line.getChildCount() < 4) line.addView(new View(this), new LinearLayout.LayoutParams(0, dp(76), 1));
    }

    /** A few at a time, so a long favourites list does not open dozens of connections at once. */
    private static final java.util.concurrent.ExecutorService PICTURES = java.util.concurrent.Executors.newFixedThreadPool(4);
    /** Sticker pictures already fetched in this session, by sticker id. */
    private static final android.util.LruCache<String, byte[]> PICTURE_CACHE = new android.util.LruCache<String, byte[]>(8 << 20) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };

    /**
     * A sticker preview: from memory, then from TikTok's cache folder, then from the first mirror
     * that answers (and kept for next time). Decoded at cell size; animated ones play.
     */
    private void loadPicture(ImageView view, String id, java.util.List<String> urls) {
        java.io.File file = new java.io.File(new java.io.File(getCacheDir(), "tpx_stickers"), id.replaceAll("[^0-9A-Za-z_-]", "_"));
        PICTURES.execute(() -> {
            byte[] bytes = PICTURE_CACHE.get(id);
            if (bytes == null && file.isFile()) {
                try {
                    bytes = java.nio.file.Files.readAllBytes(file.toPath());
                } catch (java.io.IOException ignored) {
                    bytes = null;
                }
            }
            if (bytes == null) {
                bytes = fetch(urls);
                if (bytes == null) return;
                try {
                    java.io.File folder = file.getParentFile();
                    if (folder != null && (folder.isDirectory() || folder.mkdirs())) java.nio.file.Files.write(file.toPath(), bytes);
                } catch (java.io.IOException ignored) {
                    // Shown anyway; fetched again next time.
                }
            }
            PICTURE_CACHE.put(id, bytes);
            try {
                int limit = dp(72);
                android.graphics.drawable.Drawable picture = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                        (decoder, info, source) -> {
                            int width = info.getSize().getWidth();
                            int height = info.getSize().getHeight();
                            float scale = Math.min(1f, (float) limit / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                        });
                runOnUiThread(() -> {
                    view.setImageDrawable(picture);
                    if (picture instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        ((android.graphics.drawable.AnimatedImageDrawable) picture).start();
                    }
                });
            } catch (Exception broken) {
                PICTURE_CACHE.remove(id);
                file.delete();
            }
        });
    }

    private byte[] fetch(java.util.List<String> urls) {
        for (String url : urls) {
            if (isFinishing()) return null;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(12_000);
                if (connection.getResponseCode() != 200) continue;
                try (java.io.InputStream input = connection.getInputStream();
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    for (int read; (read = input.read(buffer)) > 0; ) output.write(buffer, 0, read);
                    if (output.size() > 0) return output.toByteArray();
                }
            } catch (Exception ignored) {
                // Next mirror.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private View excludePage() {
        Page page = new Page(I18n.get(S.TRANSLATE_EXCLUDE), true);
        textField(page.card(), Prefs.TRANSLATE_EXCLUDE, I18n.get(S.TRANSLATE_EXCLUDE_HINT));
        return page.root;
    }

    private static final int HISTORY_PAGE_SIZE = 50;

    private View historyPage() {
        Page page = new Page(I18n.get(S.WATCH_HISTORY_TITLE), true);
        LinearLayout card = page.card();
        if (WatchHistory.count(this) == 0) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText(I18n.get(S.WATCH_HISTORY_EMPTY));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(15);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(dp(24), dp(32), dp(24), dp(32));
            card.addView(empty);
            return page.root;
        }
        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.addView(list);
        final java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale);
        final int[] loaded = {0};
        final boolean[] loading = {false};
        final android.widget.Button moreButton = new android.widget.Button(this);
        moreButton.setText(I18n.get(S.LOAD_MORE));
        Runnable more = new Runnable() {
            @Override public void run() {
                if (loading[0]) return;
                loading[0] = true;
                try {
                    java.util.List<WatchHistory.Entry> batch =
                            WatchHistory.entries(ModSettingsActivity.this, loaded[0], HISTORY_PAGE_SIZE);
                    for (WatchHistory.Entry entry : batch) {
                        android.widget.LinearLayout row = row(list);
                        String title = entry.author.isEmpty() ? entry.aid : entry.author;
                        String subtitle = (entry.desc.isEmpty() ? "" : entry.desc + "\n")
                                + date.format(new java.util.Date(entry.time));
                        texts(row, title, subtitle);
                        row.setOnClickListener(view -> openVideo(entry.aid));
                    }
                    loaded[0] += batch.size();
                    moreButton.setVisibility(
                            loaded[0] < WatchHistory.count(ModSettingsActivity.this)
                                    ? android.view.View.VISIBLE : android.view.View.GONE);
                } finally {
                    loading[0] = false;
                }
            }
        };
        moreButton.setOnClickListener(view -> more.run());
        android.widget.LinearLayout.LayoutParams moreParams =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        moreParams.setMargins(dp(16), dp(8), dp(16), dp(8));
        card.addView(moreButton, moreParams);
        page.onBottom(more);
        more.run();
        android.widget.Button clear = new android.widget.Button(this);
        clear.setText(I18n.get(S.WATCH_HISTORY_CLEAR));
        clear.setOnClickListener(view -> {
            WatchHistory.clear(this);
            push(historyPage(), false);
        });
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), dp(16), dp(16), dp(16));
        page.root.addView(clear, params);
        return page.root;
    }

    private void openVideo(String aid) {
        try {
            // Deep link verified from TikTok 47.0.3: the "tiktok" scheme is declared
            // in the manifest (handled by com.ss.android.ugc.aweme.deeplink.AppLinkHandlerV2)
            // and the route "aweme://aweme/detail/:id" is used in the dex.
            // setPackage forces TikTok itself so this never lands in a browser.
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("tiktok://aweme/detail/" + aid));
            intent.setPackage(getPackageName());
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) { }
    }

    private View liveUrlPage() {
        Page page = new Page(I18n.get(S.LIVE_STREAM_URL), true);
        LinearLayout card = page.card();
        if (LiveStreamUrl.count(this) == 0) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText(I18n.get(S.LIVE_STREAM_URL_EMPTY));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(15);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(dp(24), dp(32), dp(24), dp(32));
            card.addView(empty);
            return page.root;
        }
        // Expiry warning first.
        android.widget.TextView warning = new android.widget.TextView(this);
        warning.setText(I18n.get(S.LIVE_STREAM_URL_EXPIRES));
        warning.setTextColor(palette.textSecondary);
        warning.setTextSize(14);
        warning.setTypeface(Fonts.regular(this));
        warning.setPadding(dp(16), dp(12), dp(16), dp(4));
        card.addView(warning);

        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.addView(list);
        final java.text.DateFormat date = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale);
        final int[] loaded = {0};
        final boolean[] loading = {false};
        final android.widget.Button moreButton = new android.widget.Button(this);
        moreButton.setText(I18n.get(S.LOAD_MORE));
        Runnable more = new Runnable() {
            @Override public void run() {
                if (loading[0]) return;
                loading[0] = true;
                try {
                    java.util.List<LiveStreamUrl.Captured> batch =
                            LiveStreamUrl.page(ModSettingsActivity.this, loaded[0], HISTORY_PAGE_SIZE);
                    for (LiveStreamUrl.Captured captured : batch) {
                        android.widget.TextView capturedAt = new android.widget.TextView(ModSettingsActivity.this);
                        capturedAt.setText(date.format(new java.util.Date(captured.time)));
                        capturedAt.setTextColor(palette.textSecondary);
                        capturedAt.setTextSize(13);
                        capturedAt.setTypeface(Fonts.semibold(ModSettingsActivity.this));
                        capturedAt.setPadding(dp(16), dp(12), dp(16), dp(4));
                        list.addView(capturedAt);
                        if (captured.rtmpUrl != null && !captured.rtmpUrl.isEmpty()) {
                            addUrlRow(list, "RTMP", captured.rtmpUrl);
                        }
                        for (java.util.Map.Entry<String, String> entry : captured.flvUrls.entrySet()) {
                            addUrlRow(list, "FLV " + entry.getKey(), entry.getValue());
                        }
                    }
                    loaded[0] += batch.size();
                    moreButton.setVisibility(
                            loaded[0] < LiveStreamUrl.count(ModSettingsActivity.this)
                                    ? android.view.View.VISIBLE : android.view.View.GONE);
                } finally {
                    loading[0] = false;
                }
            }
        };
        moreButton.setOnClickListener(view -> more.run());
        android.widget.LinearLayout.LayoutParams moreParams =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        moreParams.setMargins(dp(16), dp(8), dp(16), dp(8));
        card.addView(moreButton, moreParams);
        page.onBottom(more);
        more.run();
        return page.root;
    }

    private void addUrlRow(LinearLayout card, String label, final String url) {
        android.widget.LinearLayout row = row(card);
        android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView titleView = new android.widget.TextView(this);
        titleView.setText(label);
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(16);
        titleView.setTypeface(Fonts.semibold(this));
        texts.addView(titleView);
        android.widget.TextView urlView = new android.widget.TextView(this);
        urlView.setText(url);
        urlView.setTextColor(palette.textSecondary);
        urlView.setTextSize(12);
        urlView.setTypeface(Fonts.regular(this));
        urlView.setSingleLine(true);
        urlView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(urlView);
        row.addView(texts, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button copy = new android.widget.Button(this);
        copy.setText(I18n.get(S.LIVE_STREAM_URL_COPY));
        copy.setOnClickListener(view -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("live-url", url));
            }
        });
        row.addView(copy);
        row.setOnClickListener(null);
        row.setClickable(false);
    }

    /** Speeds offered in TikTok's own speed menu. */
    private View speedsPage() {
        Page page = new Page(I18n.get(S.SPEEDS), true);
        textField(page.card(), Prefs.SPEEDS, "0.75, 1, 1.25, 1.5, 2, 3");
        page.footer(I18n.get(S.SPEEDS_FOOTER));
        return page.root;
    }

    /** The user's own filter: words and hashtags, authors, minimum likes and views. */
    private View filterPage() {
        Page page = new Page(I18n.get(S.CUSTOM_FILTER), true);
        page.section(I18n.get(S.FILTER_WORDS));
        textField(page.card(), Prefs.FILTER_WORDS, I18n.get(S.FILTER_WORDS_HINT));
        page.section(I18n.get(S.FILTER_AUTHORS));
        textField(page.card(), Prefs.FILTER_AUTHORS, I18n.get(S.FILTER_AUTHORS_HINT));
        page.section(I18n.get(S.FILTER_MIN));
        LinearLayout minimum = page.card();
        numberRow(minimum, I18n.get(S.FILTER_MIN_LIKES), Prefs.FILTER_MIN_LIKES);
        numberRow(minimum, I18n.get(S.FILTER_MIN_VIEWS), Prefs.FILTER_MIN_VIEWS);
        page.footer(I18n.get(S.FILTER_FOOTER));
        return page.root;
    }

    private void textField(LinearLayout card, String key, String hint) {
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setText(Prefs.text(key));
        field.setHint(hint);
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(16);
        field.setTypeface(Fonts.regular(this));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(2);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { Prefs.setText(key, s.toString()); }
        });
        card.addView(field, new LinearLayout.LayoutParams(-1, -2));
    }

    private void numberRow(LinearLayout card, String title, String key) {
        LinearLayout row = row(card);
        row.setBackground(null);
        texts(row, title, null);
        EditText field = new EditText(this);
        field.setBackground(rounded(palette.field, dp(10)));
        long value = Prefs.number(key);
        if (value > 0) field.setText(String.valueOf(value));
        field.setHint("0");
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(16);
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        field.setPadding(dp(10), dp(6), dp(10), dp(6));
        field.setMinWidth(dp(96));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                long parsed;
                try {
                    parsed = s.length() == 0 ? 0 : Long.parseLong(s.toString());
                } catch (NumberFormatException error) {
                    parsed = 0;
                }
                Prefs.setNumber(key, parsed);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(12));
        row.addView(field, params);
        row.setOnClickListener(view -> field.requestFocus());
    }

    private void buildRegion(Page page, LinearLayout card) {
        TextView regionValue = navigation(card, I18n.get(S.REGION), regionLabel(),
                view -> push(regionPage(), true));
        View languageRow = toggle(card, S.REGION_LANGUAGE, S.REGION_LANGUAGE_DESC, Prefs.REGION_LANGUAGE);
        languageRow.setVisibility(Prefs.region().isEmpty() ? View.GONE : View.VISIBLE);
        page.footer(I18n.get(S.REGION_DESC));
        page.content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(16)));
        LinearLayout fresh = page.card();
        toggle(fresh, S.OLD_VIDEOS, S.OLD_VIDEOS_DESC, Prefs.OLD_VIDEOS);
        TextView ageValue = navigation(fresh, I18n.get(S.OLD_AGE), ageLabel(Prefs.oldVideoDays()),
                view -> push(agePage(), true));
        View ageRow = (View) ageValue.getParent();
        ageRow.setVisibility(Prefs.on(Prefs.OLD_VIDEOS) ? View.VISIBLE : View.GONE);
        page.root.setTag((Runnable) () -> {
            regionValue.setText(regionLabel());
            languageRow.setVisibility(Prefs.region().isEmpty() ? View.GONE : View.VISIBLE);
            ageValue.setText(ageLabel(Prefs.oldVideoDays()));
            ageRow.setVisibility(Prefs.on(Prefs.OLD_VIDEOS) ? View.VISIBLE : View.GONE);
        });
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(4), 0, dp(4));
        card.setBackground(rounded(palette.card, dp(16)));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(8), 0, dp(8), 0);
        parent.addView(card, params);
        return card;
    }

    private View regionPage() {
        Page page = new Page(I18n.get(S.REGION), false);
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(12), 0);
        searchBox.setBackground(rounded(palette.field, dp(8)));
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Glyph(Glyph.Kind.SEARCH, palette.textSecondary, dp(18)));
        searchBox.addView(glass, new LinearLayout.LayoutParams(dp(18), dp(18)));
        EditText search = new EditText(this);
        search.setBackground(null);
        search.setHint(I18n.get(S.SEARCH));
        search.setHintTextColor(palette.textSecondary);
        search.setTextColor(palette.textPrimary);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(8), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, dp(40));
        boxParams.setMargins(dp(16), dp(8), dp(16), dp(12));
        page.content.addView(searchBox, boxParams);

        LinearLayout list = page.card();
        List<View> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String selected = Prefs.region();
        rows.add(choice(list, I18n.get(S.REGION_OFF), selected.isEmpty(), () -> Prefs.setRegion("")));
        names.add("");
        for (Regions.Region region : Regions.sorted(locale)) {
            String name = region.name(locale);
            rows.add(choice(list, name, region.iso.equals(selected), () -> Prefs.setRegion(region.iso)));
            names.add(name.toLowerCase(locale) + " " + region.iso + " " + region.name(Locale.ENGLISH).toLowerCase(Locale.ROOT));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase(locale);
                for (int index = 0; index < rows.size(); index++) {
                    boolean visible = query.isEmpty() ? true : index > 0 && names.get(index).contains(query);
                    rows.get(index).setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
        });
        return page.root;
    }

    /** Preset periods plus a custom number of days. */
    private View agePage() {
        Page page = new Page(I18n.get(S.OLD_AGE), false);
        LinearLayout presets = page.card();
        int current = Prefs.oldVideoDays();
        boolean custom = true;
        for (int index = 0; index < AGE_DAYS.length; index++) {
            int days = AGE_DAYS[index];
            if (days == current) custom = false;
            choice(presets, I18n.get(AGE_NAMES[index]), days == current, () -> Prefs.setOldVideoDays(days));
        }

        page.section(I18n.get(S.AGE_CUSTOM));
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setHint(I18n.get(S.AGE_DAYS_HINT));
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(17);
        field.setTypeface(Fonts.semibold(this));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (custom) field.setText(String.valueOf(current));
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView check = new ImageView(this);
        check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
        check.setVisibility(custom ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(view -> field.requestFocus());
        field.setOnEditorActionListener((view, action, event) -> {
            int days;
            try {
                days = Integer.parseInt(field.getText().toString().trim());
            } catch (NumberFormatException error) {
                days = 0;
            }
            if (days > 0) {
                Prefs.setOldVideoDays(days);
                pop();
            }
            return true;
        });
        return page.root;
    }

    private String ageLabel(int days) {
        for (int index = 0; index < AGE_DAYS.length; index++) {
            if (AGE_DAYS[index] == days) return I18n.get(AGE_NAMES[index]);
        }
        return String.format(locale, I18n.get(S.AGE_DAYS), days);
    }

    private String streakDaysLabel() {
        return String.valueOf(Prefs.number(Prefs.FAKE_STREAK_DAYS));
    }

    /** Number input for the fake streak day count. */
    private View streakDaysPage() {
        Page page = new Page(I18n.get(S.FAKE_STREAK_DAYS), false);
        LinearLayout card = page.card();
        LinearLayout row = row(card);
        row.setBackground(null);
        EditText field = new EditText(this);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setHint(streakDaysLabel());
        field.setHintTextColor(palette.textSecondary);
        field.setTextColor(palette.textPrimary);
        field.setTextSize(17);
        field.setTypeface(Fonts.semibold(this));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setText(streakDaysLabel());
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView check = new ImageView(this);
        check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(view -> field.requestFocus());
        field.setOnEditorActionListener((view, action, event) -> {
            long days;
            try {
                days = Long.parseLong(field.getText().toString().trim());
            } catch (NumberFormatException error) {
                days = 0;
            }
            if (days > 0) {
                Prefs.setNumber(Prefs.FAKE_STREAK_DAYS, days);
                pop();
            }
            return true;
        });
        return page.root;
    }

    private String regionLabel() {
        Regions.Region region = Regions.find(Prefs.region());
        return region == null ? I18n.get(S.OFF) : region.name(locale);
    }

    private static final int REQ_LIVE_VIDEO_PICK = 0x11E;

    private String liveVideoListLabel() {
        int n = LiveVideoSource.playlist().size();
        return n == 0 ? I18n.get(S.LIVE_VIDEO_EMPTY) : n + " video";
    }

    private String liveVideoModeLabel() {
        int mode = (int) Prefs.number(Prefs.LIVE_VIDEO_MODE);
        if (mode == LiveVideoSource.MODE_LOOP_LIST) return I18n.get(S.LIVE_VIDEO_MODE_LOOP_LIST);
        if (mode == LiveVideoSource.MODE_LOOP_ONE) return I18n.get(S.LIVE_VIDEO_MODE_LOOP_ONE);
        return I18n.get(S.LIVE_VIDEO_MODE_ONCE);
    }

    /** Playlist manager for live-from-video: add from gallery, tap to remove. */
    private View liveVideoListPage() {
        Page page = new Page(I18n.get(S.LIVE_VIDEO_LIST), false);
        LinearLayout card = page.card();
        // Risk warning at the top.
        TextView risk = new TextView(this);
        risk.setText(I18n.get(S.LIVE_VIDEO_RISK));
        risk.setTextColor(palette.textSecondary);
        risk.setTextSize(13);
        risk.setPadding(dp(4), dp(4), dp(4), dp(12));
        card.addView(risk);
        // Add button.
        LinearLayout addRow = row(card);
        TextView addLabel = new TextView(this);
        addLabel.setText(I18n.get(S.LIVE_VIDEO_PICK));
        addLabel.setTextColor(palette.accent);
        addLabel.setTextSize(16);
        addLabel.setTypeface(Fonts.semibold(this));
        addRow.addView(addLabel);
        addRow.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent,
                    I18n.get(S.LIVE_VIDEO_PICK)), REQ_LIVE_VIDEO_PICK);
        });
        refreshLiveVideoList(card, addRow);
        return page.root;
    }

    private void refreshLiveVideoList(LinearLayout card, View addRow) {
        // Remove old video rows (everything after the add row).
        int start = card.indexOfChild(addRow) + 1;
        while (card.getChildCount() > start) card.removeViewAt(start);
        List<String> list = LiveVideoSource.playlist();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(S.LIVE_VIDEO_EMPTY));
            empty.setTextColor(palette.textSecondary);
            empty.setTextSize(14);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            card.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final String path = list.get(i);
            final int index = i;
            LinearLayout r = row(card);
            TextView name = new TextView(this);
            name.setText((i + 1) + ". " + new File(path).getName());
            name.setTextColor(palette.textPrimary);
            name.setTextSize(15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextColor(palette.textSecondary);
            remove.setTextSize(16);
            remove.setPadding(dp(12), dp(8), dp(4), dp(8));
            r.addView(remove);
            r.setOnClickListener(v -> {
                List<String> cur = LiveVideoSource.playlist();
                cur.remove(index);
                LiveVideoSource.setPlaylist(cur);
                refreshLiveVideoList(card, addRow);
                onPrefsChanged();
            });
        }
    }

    /** Play mode picker: once / loop playlist / loop current video. */
    private View liveVideoModePage() {
        Page page = new Page(I18n.get(S.LIVE_VIDEO_MODE), false);
        LinearLayout card = page.card();
        int[] modes = {LiveVideoSource.MODE_ONCE, LiveVideoSource.MODE_LOOP_LIST,
                LiveVideoSource.MODE_LOOP_ONE};
        S[] labels = {S.LIVE_VIDEO_MODE_ONCE, S.LIVE_VIDEO_MODE_LOOP_LIST,
                S.LIVE_VIDEO_MODE_LOOP_ONE};
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            LinearLayout r = row(card);
            TextView label = new TextView(this);
            label.setText(I18n.get(labels[i]));
            label.setTextColor(palette.textPrimary);
            label.setTextSize(16);
            r.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if ((int) Prefs.number(Prefs.LIVE_VIDEO_MODE) == mode) {
                ImageView check = new ImageView(this);
                check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
                r.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }
            r.setOnClickListener(v -> {
                Prefs.setNumber(Prefs.LIVE_VIDEO_MODE, mode);
                pop();
                onPrefsChanged();
            });
        }
        return page.root;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_LIVE_VIDEO_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            List<String> added = new ArrayList<>();
            for (Uri uri : uris) {
                String path = copyVideoToPublic(uri);
                if (path != null) added.add(path);
            }
            runOnUiThread(() -> {
                if (!added.isEmpty()) {
                    List<String> cur = LiveVideoSource.playlist();
                    cur.addAll(added);
                    LiveVideoSource.setPlaylist(cur);
                    android.widget.Toast.makeText(this,
                            added.size() + " video", android.widget.Toast.LENGTH_SHORT).show();
                }
                onPrefsChanged();
                // Refresh the list page if it is on top.
                pop();
                push(liveVideoListPage(), true);
            });
        }).start();
    }

    /**
     * Copies a picked video to public Download storage so TikTok's process
     * (a different UID) can read it via an absolute path.
     */
    private String copyVideoToPublic(Uri uri) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "TiktokPatchXposed");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "live_" + System.currentTimeMillis() + "_"
                    + Math.abs(uri.toString().hashCode()) + ".mp4";
            File out = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new java.io.FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            RuntimeLog.log("live video copy failed: " + t);
            return null;
        }
    }

    /** Flagship model picker for the device spoof. */
    private View devicePage() {
        Page page = new Page(I18n.get(S.DEVICE_SPOOF_MODEL), false);
        LinearLayout list = page.card();
        String selected = Prefs.text(Prefs.DEVICE_SPOOF_MODEL);
        for (DeviceSpoof.Profile profile : DeviceSpoof.profiles()) {
            choice(list, profile.name + " (" + profile.model + ")", profile.id.equals(selected),
                    () -> Prefs.setText(Prefs.DEVICE_SPOOF_MODEL, profile.id));
        }
        page.footer(I18n.get(S.DEVICE_SPOOF_DESC));
        return page.root;
    }

    private String deviceLabel() {
        DeviceSpoof.Profile profile = DeviceSpoof.find(Prefs.text(Prefs.DEVICE_SPOOF_MODEL));
        return profile == null ? I18n.get(S.OFF) : profile.name;
    }

    // ---- rows ------------------------------------------------------------------------------

    private View toggle(LinearLayout card, S title, S description, String key) {
        LinearLayout row = row(card);
        LinearLayout texts = texts(row, I18n.get(title), description == null ? null : I18n.get(description));
        Toggle toggle = new Toggle(this, palette);
        toggle.setChecked(Prefs.on(key), false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(12));
        row.addView(toggle, params);
        row.setContentDescription(texts.getContentDescription());
        texts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setOnClickListener(view -> {
            boolean value = !toggle.checked();
            toggle.setChecked(value, true);
            Prefs.set(key, value);
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        });
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(toggle.checked());
            }
        });
        return row;
    }

    private TextView navigation(LinearLayout card, String title, String value, View.OnClickListener click) {
        LinearLayout row = row(card);
        texts(row, title, null);
        TextView end = new TextView(this);
        end.setTextColor(palette.textSecondary);
        end.setTextSize(16);
        end.setTypeface(Fonts.regular(this));
        end.setSingleLine(true);
        end.setEllipsize(TextUtils.TruncateAt.END);
        end.setMaxWidth(dp(180));
        if (value != null) end.setText(value);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-2, -2);
        valueParams.setMarginStart(dp(12));
        row.addView(end, valueParams);
        if (click != null) {
            ImageView chevron = new ImageView(this);
            chevron.setImageDrawable(new Glyph(Glyph.Kind.CHEVRON, palette.chevron, dp(20)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(20), dp(20));
            params.setMarginStart(dp(4));
            row.addView(chevron, params);
            row.setOnClickListener(click);
        } else {
            row.setClickable(false);
            row.setBackground(null);
        }
        return end;
    }

    private View choice(LinearLayout card, String title, boolean selected, Runnable select) {
        LinearLayout row = row(card);
        row.setMinimumHeight(dp(52));
        texts(row, title, null);
        ImageView check = new ImageView(this);
        check.setImageDrawable(new Glyph(Glyph.Kind.CHECK, palette.accent, dp(22)));
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setSelected(selected);
        row.setOnClickListener(view -> {
            select.run();
            hideKeyboard(view);
            pop();
        });
        return row;
    }

    private LinearLayout row(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), null,
                new ColorDrawable(0xFFFFFFFF)));
        row.setClickable(true);
        row.setFocusable(true);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout texts(LinearLayout row, String title, String description) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(palette.textPrimary);
        titleView.setTextSize(17);
        titleView.setTypeface(Fonts.semibold(this));
        texts.addView(titleView);
        if (description != null) {
            TextView descriptionView = new TextView(this);
            descriptionView.setText(description);
            descriptionView.setTextColor(palette.textSecondary);
            descriptionView.setTextSize(14);
            descriptionView.setTypeface(Fonts.regular(this));
            descriptionView.setPadding(0, dp(2), 0, 0);
            texts.addView(descriptionView);
        }
        texts.setContentDescription(description == null ? title : title + ". " + description);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        return texts;
    }

    // ---- restart ---------------------------------------------------------------------------

    private View restartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout inner = new LinearLayout(this);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(16), dp(10), dp(10), dp(10));
        inner.setBackground(rounded(palette.dark ? 0xFF2B2B2B : 0xFF161823, dp(12)));
        inner.setElevation(dp(6));
        TextView hint = new TextView(this);
        hint.setText(I18n.get(S.RESTART_HINT));
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setTypeface(Fonts.regular(this));
        inner.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView button = new TextView(this);
        button.setText(I18n.get(S.RESTART));
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(15);
        button.setTypeface(Fonts.semibold(this));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(40));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                rounded(palette.accent, dp(6)), null));
        button.setOnClickListener(view -> AppRestart.restart(this));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(40));
        buttonParams.setMarginStart(dp(12));
        inner.addView(button, buttonParams);
        bar.addView(inner, new LinearLayout.LayoutParams(-1, -2));
        bar.setVisibility(View.GONE);
        return bar;
    }

    private void onPrefsChanged() {
        runOnUiThread(() -> {
            updateRestartBar(true);
            for (View page : pages) {
                if (page.getTag() instanceof Runnable) ((Runnable) page.getTag()).run();
            }
        });
    }

    private void updateRestartBar(boolean animate) {
        boolean show = Prefs.restartPending();
        if (show == (restartBar.getVisibility() == View.VISIBLE)) return;
        if (!show) {
            restartBar.setVisibility(View.GONE);
            return;
        }
        restartBar.setVisibility(View.VISIBLE);
        if (animate) {
            restartBar.setAlpha(0f);
            restartBar.setTranslationY(dp(24));
            restartBar.animate().alpha(1f).translationY(0).setDuration(220).start();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager manager = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** A scrolling page with TikTok's collapsing large title and a toolbar. */
    private final class Page {
        final FrameLayout root = new FrameLayout(ModSettingsActivity.this);
        final LinearLayout content = new LinearLayout(ModSettingsActivity.this);
        private final ScrollView scroll = new ScrollView(ModSettingsActivity.this);
        private final FrameLayout toolbar = new FrameLayout(ModSettingsActivity.this);
        private Runnable bottomCallback;

        /** Called when the user scrolls near the bottom (for lazy loading). */
        void onBottom(Runnable callback) { bottomCallback = callback; }

        Page(String title, boolean root) {
            this.root.setBackgroundColor(palette.background);
            this.root.setClickable(true);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.setClipToPadding(false);
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            this.root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

            toolbar.setBackgroundColor(palette.background);
            ImageView back = new ImageView(ModSettingsActivity.this);
            back.setImageDrawable(new Glyph(Glyph.Kind.BACK, palette.textPrimary, dp(24)));
            back.setScaleType(ImageView.ScaleType.CENTER);
            back.setContentDescription(I18n.get(S.BACK));
            back.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.pressed), null, null));
            back.setOnClickListener(view -> {
                if (pages.size() > 1) pop();
                else finish();
            });
            FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.BOTTOM);
            backParams.setMarginStart(dp(4));
            backParams.bottomMargin = dp(4);
            toolbar.addView(back, backParams);
            TextView small = new TextView(ModSettingsActivity.this);
            small.setText(title);
            small.setTextColor(palette.textPrimary);
            small.setTextSize(18);
            small.setTypeface(Fonts.bold(ModSettingsActivity.this));
            small.setSingleLine(true);
            small.setEllipsize(TextUtils.TruncateAt.END);
            small.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams smallParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
            smallParams.setMarginStart(dp(60));
            smallParams.setMarginEnd(dp(16));
            toolbar.addView(small, smallParams);
            this.root.addView(toolbar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            TextView large = null;
            if (root) {
                large = new TextView(ModSettingsActivity.this);
                large.setText(title);
                large.setTextColor(palette.textPrimary);
                large.setTextSize(32);
                large.setTypeface(Fonts.bold(ModSettingsActivity.this));
                large.setPadding(dp(32), dp(8), dp(32), dp(12));
                content.addView(large);
                small.setAlpha(0f);
            }
            TextView largeTitle = large;
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
                if (largeTitle != null) {
                    float fade = Math.max(0f, Math.min(1f, (y - largeTitle.getHeight() * 0.5f) / (largeTitle.getHeight() * 0.4f)));
                    small.setAlpha(fade);
                }
                if (bottomCallback != null && scroll.getChildAt(0) != null) {
                    int distance = scroll.getChildAt(0).getHeight() - scroll.getHeight() - y;
                    if (distance < dp(400)) bottomCallback.run();
                }
            });
            this.root.setTag(R_ID_CONTENT, content);
            this.root.setTag(R_ID_INSETS, (Insettable) (top, bottom) -> {
                toolbar.setPadding(0, top, 0, 0);
                toolbar.getLayoutParams().height = top + dp(56);
                toolbar.requestLayout();
                scroll.setPadding(0, top + dp(56), 0, bottom + dp(96));
            });
        }

        void section(String title) {
            TextView header = new TextView(ModSettingsActivity.this);
            header.setText(title);
            header.setTextColor(palette.textSecondary);
            header.setTextSize(15);
            header.setTypeface(Fonts.semibold(ModSettingsActivity.this));
            header.setPadding(dp(32), dp(20), dp(32), dp(10));
            if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
            content.addView(header);
        }

        LinearLayout card() { return ModSettingsActivity.this.card(content); }

        void footer(String text) {
            TextView footer = new TextView(ModSettingsActivity.this);
            footer.setText(text);
            footer.setTextColor(palette.textTertiary);
            footer.setTextSize(13);
            footer.setTypeface(Fonts.regular(ModSettingsActivity.this));
            footer.setPadding(dp(24), dp(8), dp(24), 0);
            content.addView(footer);
        }
    }

    /** Builds the intent that opens this screen from inside TikTok. */
    static Intent intent(Context host, boolean dark) {
        Intent intent = new Intent();
        intent.setClassName(host.getPackageName(), SettingsEntry.HOST_ACTIVITY);
        intent.putExtra(EXTRA, true);
        intent.putExtra(EXTRA_DARK, dark);
        if (!(host instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
