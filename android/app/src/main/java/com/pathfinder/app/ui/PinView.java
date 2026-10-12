package com.pathfinder.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A map pin whose tip is the bottom centre of the view. Drawn in the middle of the screen while editing a pin. */
final class PinView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    PinView(Context ctx) {
        super(ctx);
        setColor(0xFFC62828);
        setClickable(false);
    }

    void setColor(int color) {
        fill.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = w * 0.36f, cx = w / 2f, cy = r + 2f;
        path.rewind();
        path.moveTo(cx, h);
        path.lineTo(cx - r * 0.85f, cy + r * 0.5f);
        path.lineTo(cx + r * 0.85f, cy + r * 0.5f);
        path.close();
        fill.setColor(fill.getColor());
        canvas.drawPath(path, fill);
        canvas.drawCircle(cx, cy, r, fill);
        int keep = fill.getColor();
        fill.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r * 0.4f, fill);
        fill.setColor(keep);
    }
}
