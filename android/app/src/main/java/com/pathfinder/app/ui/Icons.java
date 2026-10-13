package com.pathfinder.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.pathfinder.app.core.TurnType;

/** Small icons drawn with code (no image files). */
final class Icons {
    private Icons() {}

    enum Kind { LOCATE, VOICE_ON, VOICE_OFF, NORTH_UP, DIRECTION_UP, SETTINGS, BACK, CLOSE, SEARCH, SPARKLE, MIC, TURN_ARROW, FLAG }

    static final class Icon extends Drawable {
        private final Kind kind;
        private final int color;
        private final float turnAngle; // degrees clockwise from "up", for TURN_ARROW
        private final boolean uTurn;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Kind kind, int color, float turnAngle, boolean uTurn) {
            this.kind = kind;
            this.color = color;
            this.turnAngle = turnAngle;
            this.uTurn = uTurn;
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(color);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height(), s = Math.min(w, h);
            c.save();
            c.translate(getBounds().left + w / 2f, getBounds().top + h / 2f);
            float u = s / 24f; // design on a 24 x 24 grid centered at 0,0
            stroke.setStrokeWidth(2f * u);
            switch (kind) {
                case LOCATE:
                    c.drawCircle(0, 0, 6 * u, stroke);
                    c.drawCircle(0, 0, 2.5f * u, fill);
                    for (int i = 0; i < 4; i++) {
                        c.save();
                        c.rotate(90 * i);
                        c.drawLine(0, -8.5f * u, 0, -11.5f * u, stroke);
                        c.restore();
                    }
                    break;
                case VOICE_ON:
                case VOICE_OFF:
                    speaker(c, u);
                    if (kind == Kind.VOICE_ON) {
                        RectF r1 = new RectF(-3 * u, -4.5f * u, 6 * u, 4.5f * u);
                        c.drawArc(r1, -50, 100, false, stroke);
                        RectF r2 = new RectF(-4 * u, -8.5f * u, 10 * u, 8.5f * u);
                        c.drawArc(r2, -50, 100, false, stroke);
                    } else {
                        c.drawLine(-10 * u, 9 * u, 10 * u, -9 * u, stroke);
                    }
                    break;
                case NORTH_UP:
                    c.drawCircle(0, 0, 10 * u, stroke);
                    arrowHead(c, u, 0);
                    break;
                case DIRECTION_UP:
                    arrowHead(c, u, 0);
                    c.drawLine(0, 2 * u, 0, 10 * u, stroke);
                    break;
                case SETTINGS:
                    for (int i = -1; i <= 1; i++) c.drawLine(-9 * u, i * 6 * u, 9 * u, i * 6 * u, stroke);
                    c.drawCircle(-4 * u, -6 * u, 2.2f * u, fill);
                    c.drawCircle(4 * u, 0, 2.2f * u, fill);
                    c.drawCircle(-2 * u, 6 * u, 2.2f * u, fill);
                    break;
                case BACK:
                    c.drawLine(9 * u, 0, -9 * u, 0, stroke);
                    c.drawLine(-9 * u, 0, -2 * u, -7 * u, stroke);
                    c.drawLine(-9 * u, 0, -2 * u, 7 * u, stroke);
                    break;
                case CLOSE:
                    c.drawLine(-7 * u, -7 * u, 7 * u, 7 * u, stroke);
                    c.drawLine(-7 * u, 7 * u, 7 * u, -7 * u, stroke);
                    break;
                case SEARCH:
                    c.drawCircle(-2 * u, -2 * u, 6.5f * u, stroke);
                    c.drawLine(3 * u, 3 * u, 10 * u, 10 * u, stroke);
                    break;
                case SPARKLE:
                    sparkle(c, 0, 0, 10 * u);
                    sparkle(c, 7 * u, -7 * u, 4 * u);
                    break;
                case MIC:
                    c.drawRoundRect(new android.graphics.RectF(-3.5f * u, -11 * u, 3.5f * u, 2 * u), 3.5f * u, 3.5f * u, fill);
                    c.drawArc(new android.graphics.RectF(-7 * u, -6 * u, 7 * u, 7 * u), 0, 180, false, stroke);
                    c.drawLine(0, 7 * u, 0, 11 * u, stroke);
                    c.drawLine(-4 * u, 11 * u, 4 * u, 11 * u, stroke);
                    break;
                case FLAG:
                    c.drawLine(-6 * u, -10 * u, -6 * u, 10 * u, stroke);
                    Path f = new Path();
                    f.moveTo(-6 * u, -10 * u);
                    f.lineTo(9 * u, -5 * u);
                    f.lineTo(-6 * u, 0);
                    f.close();
                    c.drawPath(f, fill);
                    break;
                case TURN_ARROW:
                    turnArrow(c, u);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        private void speaker(Canvas c, float u) {
            Path p = new Path();
            p.moveTo(-10 * u, -3.5f * u);
            p.lineTo(-6 * u, -3.5f * u);
            p.lineTo(-1 * u, -8 * u);
            p.lineTo(-1 * u, 8 * u);
            p.lineTo(-6 * u, 3.5f * u);
            p.lineTo(-10 * u, 3.5f * u);
            p.close();
            c.drawPath(p, fill);
        }

        private void arrowHead(Canvas c, float u, float rotate) {
            c.save();
            c.rotate(rotate);
            Path p = new Path();
            p.moveTo(0, -9 * u);
            p.lineTo(5.5f * u, 4 * u);
            p.lineTo(0, 1 * u);
            p.lineTo(-5.5f * u, 4 * u);
            p.close();
            c.drawPath(p, fill);
            c.restore();
        }

        private void sparkle(Canvas c, float cx, float cy, float r) {
            Path p = new Path();
            p.moveTo(cx, cy - r);
            p.quadTo(cx, cy, cx + r, cy);
            p.quadTo(cx, cy, cx, cy + r);
            p.quadTo(cx, cy, cx - r, cy);
            p.quadTo(cx, cy, cx, cy - r);
            p.close();
            c.drawPath(p, fill);
        }

        private void turnArrow(Canvas c, float u) {
            stroke.setStrokeWidth(3.2f * u);
            if (uTurn) {
                Path p = new Path();
                p.moveTo(-6 * u, 10 * u);
                p.lineTo(-6 * u, -2 * u);
                p.arcTo(new RectF(-6 * u, -10 * u, 6 * u, 2 * u), 180, 180);
                p.lineTo(6 * u, 4 * u);
                c.drawPath(p, stroke);
                Path head = new Path();
                head.moveTo(6 * u, 11 * u);
                head.lineTo(1.5f * u, 4 * u);
                head.lineTo(10.5f * u, 4 * u);
                head.close();
                c.drawPath(head, fill);
                return;
            }
            // stem up from the bottom, then bend by turnAngle and end in an arrow head
            float len = 10 * u;
            double a = Math.toRadians(turnAngle);
            float ex = (float) (Math.sin(a) * len), ey = (float) (-Math.cos(a) * len);
            Path p = new Path();
            p.moveTo(0, 11 * u);
            p.lineTo(0, 1 * u);
            p.lineTo(ex, 1 * u + ey);
            c.drawPath(p, stroke);
            c.save();
            c.translate(ex, 1 * u + ey);
            c.rotate(turnAngle);
            Path head = new Path();
            head.moveTo(0, -5 * u);
            head.lineTo(5 * u, 2 * u);
            head.lineTo(-5 * u, 2 * u);
            head.close();
            c.drawPath(head, fill);
            c.restore();
        }

        @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static Drawable of(Kind kind, int color) {
        return new Icon(kind, color, 0, false);
    }

    static Drawable forTurn(TurnType type, int color) {
        switch (type) {
            case DEPART: return new Icon(Kind.DIRECTION_UP, color, 0, false);
            case ARRIVE: return new Icon(Kind.FLAG, color, 0, false);
            case STRAIGHT: return new Icon(Kind.TURN_ARROW, color, 0, false);
            case SLIGHT_LEFT: return new Icon(Kind.TURN_ARROW, color, -45, false);
            case LEFT: return new Icon(Kind.TURN_ARROW, color, -90, false);
            case SHARP_LEFT: return new Icon(Kind.TURN_ARROW, color, -135, false);
            case SLIGHT_RIGHT: return new Icon(Kind.TURN_ARROW, color, 45, false);
            case RIGHT: return new Icon(Kind.TURN_ARROW, color, 90, false);
            case SHARP_RIGHT: return new Icon(Kind.TURN_ARROW, color, 135, false);
            case U_TURN: return new Icon(Kind.TURN_ARROW, color, 0, true);
            default: return new Icon(Kind.TURN_ARROW, color, 0, false);
        }
    }

    /** A round button that shows an icon. */
    static View roundButton(Context c, Kind kind, int iconColor, int backgroundColor, String description, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        int size = Ui.dp(c, 44);
        f.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        f.setBackground(Ui.rounded(c, backgroundColor, 22));
        f.setElevation(Ui.dp(c, 3));
        View icon = new View(c);
        icon.setBackground(of(kind, iconColor));
        int pad = Ui.dp(c, 22);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pad, pad, Gravity.CENTER);
        f.addView(icon, lp);
        f.setContentDescription(description);
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    /** An icon without a background, sized in dp. */
    static View view(Context c, Drawable d, float dpSize) {
        View v = new View(c);
        v.setBackground(d);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(Ui.dp(c, dpSize), Ui.dp(c, dpSize)));
        return v;
    }
}
