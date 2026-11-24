package com.malbers.addmycoverart.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * 1.1.0 redesign (handoff 2026-11-23): tokens, fonts and the three button types.
 *
 * The handoff draws everything on a 1334 x 720 canvas and calls those units dp. They are not: the A6 Gen 1 is
 * 2160 x 1080 px at density 320 (app area 1000 x 540 dp), the Play 1334 x 720 px at 213 dpi (also ~1000 x 540 dp),
 * measured 2026-11-23. So every handoff number goes through {@link #d} / {@link #sp}: one factor that maps the
 * canvas onto the real app area, the way EverSignal draws with its own scale. Proportions stay exact.
 */
public final class Theme {
    private Theme() {}

    // ---- colour tokens (handoff "Design tokens")
    public static final int GROUND = Color.parseColor("#121110");
    public static final int SURFACE = Color.parseColor("#1B1A17");
    public static final int SURFACE_RAISED = Color.parseColor("#24221E");
    public static final int BLANK = Color.parseColor("#1C1A17");
    public static final int LINE = 0x14F2EDE4;          // rgba(242,237,228,.08)
    public static final int LINE_STRONG = 0x29F2EDE4;   // .16
    public static final int TEXT = Color.parseColor("#F2EDE4");
    public static final int TEXT_2 = Color.parseColor("#C9C2B6");
    public static final int TEXT_3 = Color.parseColor("#A39C90");
    public static final int TEXT_4 = Color.parseColor("#8A847A");
    public static final int ACCENT = Color.parseColor("#F0A63C");
    public static final int ACCENT_PRESSED = Color.parseColor("#F5B65A");
    public static final int ON_ACCENT = Color.parseColor("#1A1206");
    public static final int SUCCESS = Color.parseColor("#3CC46E");
    public static final int SUCCESS_TINT = 0x1A3CC46E;  // .10
    public static final int WASH = 0x0FF2EDE4;          // .06, ghost fill / quiet hover
    public static final int ACCENT_DISABLED = 0x59F0A63C; // .35

    // ---- scale: px per canvas unit
    private static float k = 1.5f;

    public static void init(Activity a) {
        DisplayMetrics m = a.getResources().getDisplayMetrics();
        k = Math.min(m.widthPixels / 1334f, m.heightPixels / 720f);
        density = m.density;
    }

    /** A handoff size (canvas units) in pixels. */
    public static int d(float v) { return Math.round(v * k); }

    /**
     * A handoff text size in pixels (use with COMPLEX_UNIT_PX). p1b (Michael on the A6: "font shrunk by a lot"):
     * text does NOT take the layout factor. The designer sized text for a screen they took to be 1334 dp wide,
     * so text is set at its handoff size in dp (x TEXT_SCALE), while spacing and boxes keep {@link #d}.
     */
    public static float sp(float v) { return v * TEXT_SCALE * density; }
    /** The one knob for text size on the device: 1.0 = the handoff number as dp. */
    public static final float TEXT_SCALE = 1.0f;
    private static float density = 2f;

    // ---- fonts (bundled, OFL: assets/fonts)
    private static Typeface[] hanken = new Typeface[10];
    private static Typeface mono400, mono500;

    public static Typeface hanken(Context c, int weight) {
        int i = weight / 100;
        if (hanken[i] == null) {
            try {
                hanken[i] = new Typeface.Builder(c.getAssets(), "fonts/HankenGrotesk.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception e) {
                hanken[i] = Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        return hanken[i];
    }

    public static Typeface mono(Context c, int weight) {
        if (weight >= 500) {
            if (mono500 == null) mono500 = asset(c, "fonts/IBMPlexMono-Medium.ttf");
            return mono500;
        }
        if (mono400 == null) mono400 = asset(c, "fonts/IBMPlexMono-Regular.ttf");
        return mono400;
    }

    private static Typeface asset(Context c, String path) {
        try { return Typeface.createFromAsset(c.getAssets(), path); } catch (Exception e) { return Typeface.MONOSPACE; }
    }

    // ---- text
    public static TextView text(Context c, String s, float size, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size));
        t.setTypeface(hanken(c, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView monoText(Context c, String s, float size, int weight, int color) {
        TextView t = text(c, s, size, 400, color);
        t.setTypeface(mono(c, weight));
        return t;
    }

    public static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /**
     * One line that, when it doesn't fit, scrolls gently to its end and back (Android's marquee: it waits at the
     * start, glides, repeats), with soft edges. Text that fits stays still.
     */
    public static TextView scrolling(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontalFadingEdgeEnabled(true);
        t.setFadingEdgeLength(d(24));
        t.setSelected(true); // a marquee only runs on a selected (or focused) view
        return t;
    }

    /** Line height as a multiple of the text size (handoff "/ 1.45"). */
    public static TextView lineHeight(TextView t, float mult) {
        t.setLineSpacing(0, mult);
        return t;
    }

    public static GradientDrawable round(int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(d(radius));
        return g;
    }

    public static GradientDrawable round(int fill, float radius, int stroke, float strokeW) {
        GradientDrawable g = round(fill, radius);
        g.setStroke(Math.max(1, d(strokeW)), stroke);
        return g;
    }

    // ---- buttons (handoff "Buttons"): 60 tall, radius 30. The focus ring sits 3 outside the body (3 wide), so
    // every button carries a 6 margin inside its bounds; the visible body stays exactly 60.
    public static final int PRIMARY = 0, GHOST = 1, QUIET = 2;
    private static final float RING_GAP = 3, RING_W = 3, RING_OUT = RING_GAP + RING_W;

    public static TextView button(Context c, String label, int kind, View.OnClickListener l) {
        // P6 (Michael 2026-11-23: "not clear that Rip Error Check is a button till you click it"): no text-only
        // buttons; every secondary button is the ghost pill.
        if (kind == QUIET) kind = GHOST;
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setFocusable(true);
        b.setClickable(true);
        float padH = kind == PRIMARY ? 34 : kind == GHOST ? 29 : 20;
        b.setMinHeight(d(60 + 2 * RING_OUT));
        b.setHeight(d(60 + 2 * RING_OUT));
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(kind == PRIMARY ? 22 : kind == GHOST ? 21 : 20));
        b.setTypeface(hanken(c, kind == PRIMARY ? 600 : 500));
        if (kind == PRIMARY) b.setTextColor(ON_ACCENT);
        else if (kind == GHOST) b.setTextColor(TEXT);
        else b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {android.R.attr.state_hovered}, {android.R.attr.state_focused}, {}},
                new int[]{TEXT, TEXT, TEXT, TEXT_2}));
        b.setBackground(buttonBg(kind));
        // p1b: AFTER setBackground, which replaces the padding with the drawable's own (the inset), which is
        // why the first build's buttons were cramped and "Scan this folder" was cut off
        b.setPadding(d(padH + RING_OUT), 0, d(padH + RING_OUT), 0);
        b.setOnClickListener(l);
        if (kind == QUIET) b.setTag(QUIET_TAG);
        return b;
    }

    /** Marks quiet buttons (and a group led by one): the action bar then uses 28 on the left, not 40. */
    public static final String QUIET_TAG = "quiet";

    /** "Done" gets a wider primary (0 40). */
    public static TextView wide(TextView b) {
        b.setPadding(d(40 + RING_OUT), 0, d(40 + RING_OUT), 0);
        return b;
    }

    public static void setEnabledPrimary(TextView b, boolean on) {
        int l = b.getPaddingLeft(), r = b.getPaddingRight();
        b.setEnabled(on);
        b.setFocusable(on);
        b.setBackground(on ? buttonBg(PRIMARY) : body(ACCENT_DISABLED, 0, 0));
        b.setPadding(l, 0, r, 0);
    }

    private static Drawable buttonBg(int kind) {
        int fill = kind == PRIMARY ? ACCENT : kind == GHOST ? WASH : Color.TRANSPARENT;
        int pressed = kind == PRIMARY ? ACCENT_PRESSED : kind == GHOST ? 0x1AF2EDE4 : WASH;
        int stroke = kind == GHOST ? LINE_STRONG : 0;
        int ring = kind == PRIMARY ? TEXT : ACCENT;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, withRing(body(pressed, stroke, 1), ring));
        s.addState(new int[]{android.R.attr.state_pressed}, body(pressed, stroke, 1));
        s.addState(new int[]{android.R.attr.state_hovered}, body(pressed, stroke, 1));
        s.addState(new int[]{}, body(fill, stroke, 1));
        return s;
    }

    private static Drawable body(int fill, int stroke, float strokeW) {
        GradientDrawable g = stroke != 0 ? round(fill, 30, stroke, strokeW) : round(fill, 30);
        int i = d(RING_OUT);
        return new InsetDrawable(g, i, i, i, i);
    }

    private static Drawable withRing(Drawable body, int ring) {
        GradientDrawable r = new GradientDrawable();
        r.setColor(Color.TRANSPARENT);
        r.setCornerRadius(d(30 + RING_OUT));
        r.setStroke(d(RING_W), ring);
        return new LayerDrawable(new Drawable[]{r, body});
    }
}
