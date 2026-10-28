package com.malbers.addmycoverart.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small view builders. Dark, to sit with the Eversolo UI; orange and green from the icon. */
public final class Ui {
    public static final int BG = Color.parseColor("#14171F");
    public static final int CARD = Color.parseColor("#1F2430");
    public static final int CARD_HI = Color.parseColor("#2A3142");
    public static final int TEXT = Color.parseColor("#ECEFF4");
    public static final int SUB = Color.parseColor("#9AA3B2");
    public static final int ACCENT = Color.parseColor("#F0A63C");
    public static final int GOOD = Color.parseColor("#3CC46E");
    public static final int BAD = Color.parseColor("#E5636B");

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView oneLine(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Filled pill button; a primary one is orange with dark text. */
    public static Button button(Context c, String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? BG : TEXT);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(c, primary ? ACCENT : CARD_HI, 24), null));
        int h = dp(c, 20);
        b.setPadding(h, 0, h, 0);
        b.setMinHeight(dp(c, 48));
        b.setOnClickListener(l);
        return b;
    }

    public static Button button(Context c, String label, View.OnClickListener l) {
        return button(c, label, false, l);
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams wrap(int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.leftMargin = leftMargin;
        return p;
    }

    /** A rounded square placeholder showing a disc glyph, used until a cover arrives. */
    public static ImageView coverView(Context c, int sizePx) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackground(rounded(c, CARD_HI, 10));
        v.setClipToOutline(true);
        v.setImageResource(com.malbers.addmycoverart.R.drawable.ic_disc_placeholder);
        v.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        return v;
    }

    /** Decodes image bytes down to roughly {@code maxPx} on the long side. */
    public static Bitmap decode(byte[] bytes, int maxPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }
}
