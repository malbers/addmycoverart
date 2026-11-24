package com.malbers.addmycoverart.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * 1.1.0, the album square (handoff "Album tile" + Cover.dc.html blank geometry, which is final): the real cover,
 * or the blank tile: dark fill, 2 dashed accent stroke, a disc glyph (rings 38% and 9% of the side) and a mono
 * label. Muted = "Not found" (grey stroke and label), busy = opacity pulse .45 to 1 over 1.2 s.
 * Square by width unless given a fixed side.
 */
public final class CoverView extends View {
    public static final int FLAG = 0, MUTED = 1, BUSY = 2;

    private static final int EMPTY = 0, IMAGE = 1, BLANK = 2;

    private int mode = EMPTY;
    private Bitmap bitmap;
    private int tone = FLAG;
    private String label;
    private final int fixed;
    /** Corner radius as a fraction of the side (handoff: 3%). */
    private float radiusFrac = 0.03f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private ValueAnimator pulse;

    public CoverView(Context c) { this(c, 0); }

    /** @param fixedPx side in pixels, or 0 for "as wide as the parent gives, and as tall" */
    public CoverView(Context c, int fixedPx) {
        super(c);
        fixed = fixedPx;
        stroke.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x29F2EDE4); // rgba(242,237,228,.16)
        text.setTypeface(Theme.mono(c, 400));
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.02f);
    }

    public void setRadiusFraction(float f) { radiusFrac = f; invalidate(); }

    /** Plain surface, while a cover loads. */
    public void setEmpty() {
        stopPulse();
        mode = EMPTY;
        bitmap = null;
        invalidate();
    }

    public void setImage(Bitmap b) {
        stopPulse();
        if (b == null) { setEmpty(); return; }
        mode = IMAGE;
        bitmap = b;
        invalidate();
    }

    public void setBlank(int tone, String label) {
        mode = BLANK;
        bitmap = null;
        this.tone = tone;
        this.label = label;
        if (tone == BUSY) startPulse(); else stopPulse();
        invalidate();
    }

    public boolean hasImage() { return mode == IMAGE; }

    private String badge;

    /** 1.1.0: "+8" (pictures in the album's Scan folder) at the bottom right; null for none. */
    public void setBadge(String b) {
        badge = b;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int side = fixed > 0 ? fixed : MeasureSpec.getSize(w);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth();
        if (s <= 0) return;
        float r = s * radiusFrac;
        rect.set(0, 0, s, s);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (mode == IMAGE && bitmap != null) {
            // centre crop
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float scale = Math.max(s / bw, s / bh);
            matrix.setScale(scale, scale);
            matrix.postTranslate((s - bw * scale) / 2f, (s - bh * scale) / 2f);
            c.drawBitmap(bitmap, matrix, bmp);
        } else if (mode == BLANK) {
            fill.setColor(Theme.BLANK);
            c.drawRect(rect, fill);
        } else {
            fill.setColor(Theme.SURFACE);
            c.drawRect(rect, fill);
        }
        c.restore();
        if (mode == BLANK) drawBlank(c, s, r);
        drawBadge(getContext(), c, badge, s, s);
    }

    private static final Paint BADGE_BG = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint badgeText;

    /** A small dark pill with light mono text, 6 in from the bottom-right corner. */
    static void drawBadge(android.content.Context ctx, Canvas c, String text, float w, float h) {
        if (text == null || text.isEmpty()) return;
        if (badgeText == null) {
            badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgeText.setTypeface(Theme.mono(ctx, 500));
            badgeText.setColor(Theme.TEXT);
        }
        badgeText.setTextSize(Theme.sp(13));
        BADGE_BG.setColor(0xD9121110);
        Paint.FontMetrics fm = badgeText.getFontMetrics();
        float th = fm.descent - fm.ascent, tw = badgeText.measureText(text);
        float padX = Theme.d(7), padY = Theme.d(3), m = Theme.d(6);
        float bw = tw + 2 * padX, bh = th + 2 * padY;
        float right = w - m, bottom = h - m;
        RectF r = new RectF(right - bw, bottom - bh, right, bottom);
        c.drawRoundRect(r, bh / 2, bh / 2, BADGE_BG);
        c.drawText(text, r.left + padX, r.top + padY - fm.ascent, badgeText);
    }

    private void drawBlank(Canvas c, float s, float r) {
        float sw = Math.max(1, Theme.d(2));
        stroke.setStrokeWidth(sw);
        stroke.setColor(tone == MUTED ? 0x33F2EDE4 : 0xA6F0A63C); // grey .2 / accent .65
        stroke.setPathEffect(new DashPathEffect(new float[]{sw * 3, sw * 2}, 0));
        rect.set(sw / 2, sw / 2, s - sw / 2, s - sw / 2);
        c.drawRoundRect(rect, r, r, stroke);

        // disc glyph, then the label 7% of the side below it; the pair is centred
        boolean hasLabel = label != null && !label.isEmpty();
        float outer = s * 0.38f, ringW = Math.max(1, s * 0.016f);
        text.setTextSize(s * 0.075f);
        text.setColor(tone == MUTED ? Theme.TEXT_3 : Theme.ACCENT);
        Paint.FontMetrics fm = text.getFontMetrics();
        float labelH = hasLabel ? fm.descent - fm.ascent : 0;
        float gap = hasLabel ? s * 0.07f : 0;
        float top = (s - (outer + gap + labelH)) / 2f;
        float cx = s / 2f, cy = top + outer / 2f;
        ring.setStrokeWidth(ringW);
        c.drawCircle(cx, cy, (outer - ringW) / 2f, ring);
        c.drawCircle(cx, cy, (s * 0.09f - ringW) / 2f, ring);
        if (hasLabel) c.drawText(label, cx, top + outer + gap - fm.ascent, text);
    }

    private void startPulse() {
        if (pulse != null) return;
        pulse = ValueAnimator.ofFloat(1f, 0.45f);
        pulse.setDuration(600);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> setAlpha((float) a.getAnimatedValue()));
        pulse.start();
    }

    private void stopPulse() {
        if (pulse == null) return;
        pulse.cancel();
        pulse = null;
        setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulse != null) pulse.pause();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pulse != null) pulse.resume();
    }
}
