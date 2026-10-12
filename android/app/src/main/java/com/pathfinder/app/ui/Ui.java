package com.pathfinder.app.ui;

import android.content.Context;
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

    static final int PRIMARY = 0xFF14506E;
    static final int ON_PRIMARY = 0xFFFFFFFF;
    static final int SURFACE = 0xFFFFFFFF;
    static final int TEXT = 0xFF1B1F23;
    static final int MUTED = 0xFF5B6770;
    static final int ERROR = 0xFFB3261E;
    static final int OUTLINE = 0xFFC3CCD2;
    static final int BG = 0xFFE8EEF1;

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
