package com.malbers.addmycoverart.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 1.1.0, folder (artist) tile on Browse (handoff "Folder tile"): a 2x2 mosaic of the folder's first four album
 * covers, gap 4, radius 10, on the surface colour. Empty cells stay surface; a blank album is a mini dashed
 * orange cell with no label.
 */
public final class MosaicView extends View {
    public static final int NONE = 0, IMAGE = 1, BLANK = 2;

    private final int[] kind = new int[4];
    private final Bitmap[] bitmaps = new Bitmap[4];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Matrix matrix = new Matrix();

    public MosaicView(Context c) {
        super(c);
        dash.setStyle(Paint.Style.STROKE);
        dash.setColor(0xA6F0A63C);
    }

    private String badge;

    /** 1.1.0: "2/5" (albums inside with extra pictures) at the bottom right; null for none. */
    public void setBadge(String b) {
        badge = b;
        invalidate();
    }

    /** Every cell back to empty. */
    public void clear() {
        for (int i = 0; i < 4; i++) { kind[i] = NONE; bitmaps[i] = null; }
        invalidate();
    }

    public void setCell(int i, int k, Bitmap b) {
        kind[i] = k;
        bitmaps[i] = b;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int side = MeasureSpec.getSize(w);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth();
        if (s <= 0) return;
        float r = Theme.d(10);
        rect.set(0, 0, s, s);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setColor(Theme.SURFACE);
        c.drawRect(rect, fill);
        float gap = Theme.d(4), cell = (s - gap) / 2f;
        float sw = Math.max(1, Theme.d(1.5f));
        dash.setStrokeWidth(sw);
        dash.setPathEffect(new DashPathEffect(new float[]{sw * 3, sw * 2}, 0));
        for (int i = 0; i < 4; i++) {
            float x = (i % 2) * (cell + gap), y = (i / 2) * (cell + gap);
            if (kind[i] == IMAGE && bitmaps[i] != null) {
                Bitmap b = bitmaps[i];
                float scale = Math.max(cell / b.getWidth(), cell / b.getHeight());
                matrix.setScale(scale, scale);
                matrix.postTranslate(x + (cell - b.getWidth() * scale) / 2f, y + (cell - b.getHeight() * scale) / 2f);
                c.save();
                c.clipRect(x, y, x + cell, y + cell);
                c.drawBitmap(b, matrix, bmp);
                c.restore();
            } else if (kind[i] == BLANK) {
                fill.setColor(Theme.BLANK);
                c.drawRect(x, y, x + cell, y + cell, fill);
                float cr = cell * 0.03f;
                c.drawRoundRect(x + sw / 2, y + sw / 2, x + cell - sw / 2, y + cell - sw / 2, cr, cr, dash);
            }
        }
        c.restore();
        CoverView.drawBadge(getContext(), c, badge, s, s);
    }
}
