package com.pathfinder.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors and small view builders (the app has no XML layouts or image files). */
final class Ui {
    private Ui() {}

    // Light is the default. applyTheme() switches these to the dark set when the phone is in dark mode.
    static int PRIMARY = 0xFF14506E;
    static int ON_PRIMARY = 0xFFFFFFFF;
    static int SURFACE = 0xFFFFFFFF;
    static int TEXT = 0xFF1B1F23;
    static int MUTED = 0xFF5B6770;
    static int ERROR = 0xFFB3261E;
    static int OUTLINE = 0xFFC3CCD2;
    static int BG = 0xFFE8EEF1;
    static boolean dark;

    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    static int themeMode(Context c) {
        return c.getSharedPreferences("pathfinder_ui", Context.MODE_PRIVATE).getInt("theme", THEME_SYSTEM);
    }

    static void setThemeMode(Context c, int mode) {
        c.getSharedPreferences("pathfinder_ui", Context.MODE_PRIVATE).edit().putInt("theme", mode).apply();
    }

    /** Dark when the user chose Dark, or chose System and the phone is in dark mode. */
    static boolean isNight(Context c) {
        int mode = themeMode(c);
        if (mode == THEME_DARK) return true;
        if (mode == THEME_LIGHT) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Call before building any view. Follows the phone's dark mode setting. */
    static void applyTheme(Context c) {
        dark = isNight(c);
        if (dark) {
            PRIMARY = 0xFF6CB4D9;
            ON_PRIMARY = 0xFF0B2230;
            SURFACE = 0xFF1E252A;
            TEXT = 0xFFE6EAED;
            MUTED = 0xFF9AA7B0;
            ERROR = 0xFFF2B8B5;
            OUTLINE = 0xFF3B464D;
            BG = 0xFF12171A;
        } else {
            PRIMARY = 0xFF14506E;
            ON_PRIMARY = 0xFFFFFFFF;
            SURFACE = 0xFFFFFFFF;
            TEXT = 0xFF1B1F23;
            MUTED = 0xFF5B6770;
            ERROR = 0xFFB3261E;
            OUTLINE = 0xFFC3CCD2;
            BG = 0xFFE8EEF1;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable outlined(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String label, boolean filled, View.OnClickListener l) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(filled ? ON_PRIMARY : PRIMARY);
        b.setBackground(filled ? rounded(c, PRIMARY, 20) : outlined(c, SURFACE, PRIMARY, 20));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    /** White rounded panel with a shadow. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(c, SURFACE, 12));
        l.setElevation(dp(c, 4));
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static View spacer(Context c, float dpSize) {
        View v = new View(c);
        v.setLayoutParams(lp(dp(c, dpSize), dp(c, dpSize)));
        return v;
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
