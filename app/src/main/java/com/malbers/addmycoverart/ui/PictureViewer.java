package com.malbers.addmycoverart.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;

/**
 * p3b (Michael: the album page's picture row is "too small", "full screen like EverSignal"): one picture at a time,
 * whole, on black. Name and "3 of 17" at the bottom. Left / Right (D-pad or swipe) steps; tap or Back closes.
 * Read-only.
 */
public final class PictureViewer {
    public interface OnClose {
        /** The index of the picture showing when the viewer closed. */
        void closed(int index);
    }

    /**
     * p6b MUST-FIX (Michael: "I almost didn't pick one since it looked a bit blurry"): pictures that are only a small
     * preview so far. The viewer says so and asks for the full-size one first, then swaps it in.
     */
    public interface Upgrader {
        boolean isPreview(int i);

        /** Fetch picture i at full size (off the main thread); call back on the main thread, null on failure. */
        void fetch(int i, java.util.function.Consumer<File> done);
    }

    private PictureViewer() {}

    /** "1200 × 1200 · 425 KB  ·  " (pixel size from the header only, no decode), or "" when unreadable. */
    static String sizeOf(File f) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        String bytes = bytes(f.length());
        if (o.outWidth <= 0) return bytes + "  ·  ";
        return o.outWidth + " × " + o.outHeight + " · " + bytes + "  ·  ";
    }

    static String bytes(long n) {
        if (n >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024));
        return Math.max(1, Math.round(n / 1024.0)) + " KB";
    }

    public static void show(Context c, List<File> pics, List<String> labels, int start, OnClose onClose) {
        show(c, pics, labels, start, onClose, null);
    }

    public static void show(Context c, List<File> pics0, List<String> labels, int start, OnClose onClose, Upgrader up) {
        if (pics0.isEmpty()) return;
        List<File> pics = new java.util.ArrayList<>(pics0);
        boolean[] failed = new boolean[pics.size()];
        Dialog d = new Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView label = Theme.oneLine(Theme.monoText(c, "", 15, 400, Theme.TEXT_2));
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(Theme.d(6), 0, 0, Color.BLACK);
        // p3c (Michael: "maybe carousel indicator would be good too"): dots under the label, the current one bright.
        // Up to 20; a bigger booklet keeps just "15 of 35" (Michael: the counter is fine "for a full booklet").
        android.widget.LinearLayout dots = new android.widget.LinearLayout(c);
        dots.setGravity(Gravity.CENTER);
        boolean useDots = pics.size() > 1 && pics.size() <= 20;
        android.view.View[] dot = new android.view.View[useDots ? pics.size() : 0];
        for (int i = 0; i < dot.length; i++) {
            dot[i] = new android.view.View(c);
            android.widget.LinearLayout.LayoutParams dp = new android.widget.LinearLayout.LayoutParams(Theme.d(8), Theme.d(8));
            if (i > 0) dp.leftMargin = Theme.d(8);
            dots.addView(dot[i], dp);
        }
        // P4 (Michael, on a bright booklet page the label and dots vanished): both sit on an always-on dark pill.
        // No fade: here "it's not about enjoying the art, but more about ensuring it's correct".
        android.widget.LinearLayout pill = new android.widget.LinearLayout(c);
        pill.setOrientation(android.widget.LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER_HORIZONTAL);
        pill.setBackground(Theme.round(0xB8121110, 22)); // ground at ~72%
        pill.setPadding(Theme.d(20), Theme.d(10), Theme.d(20), Theme.d(useDots ? 12 : 10));
        // P6 (Michael: the size was cut to "1869…" with lots of room): the name and the size/counter are two
        // pieces sized to their text; only the name shortens (in the middle), the size and counter never do.
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(Math.max(Theme.d(160), c.getResources().getDisplayMetrics().widthPixels / 2));
        TextView info = Theme.monoText(c, "", 15, 400, Theme.TEXT_2);
        info.setSingleLine(true);
        android.widget.LinearLayout line = new android.widget.LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(label, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(info, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pill.addView(line, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (useDots) {
            android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Theme.d(8);
            pill.addView(dots, dlp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Theme.d(16);
        root.addView(pill, lp);

        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int px = Math.min(m.widthPixels, m.heightPixels);
        int[] at = {start};
        Future<?>[] pending = {null};
        Runnable[] showAt = new Runnable[1];
        showAt[0] = () -> {
            int i = at[0];
            label.setText(labels.get(i));
            boolean preview = up != null && up.isPreview(i) && !failed[i];
            // 1.1.0 wording (Michael: "thumbnail preview - loading full resolution"), dots animate while it loads
            String tail = (i + 1) + " of " + pics.size();
            info.setText("  ·  " + (preview ? "Thumbnail preview · loading full resolution  ·  "
                    : up != null && failed[i] ? "Thumbnail preview only (full resolution didn't load)  ·  " : sizeOf(pics.get(i)))
                    + tail);
            if (preview) {
                int[] tick = {0};
                Runnable[] loading = new Runnable[1];
                loading[0] = () -> {
                    if (at[0] != i || !d.isShowing() || !up.isPreview(i) || failed[i]) return;
                    tick[0] = (tick[0] + 1) % 4;
                    String dd = "...".substring(0, tick[0]) + "   ".substring(tick[0]);
                    info.setText("  ·  Thumbnail preview · loading full resolution" + dd + "  ·  " + tail);
                    info.postDelayed(loading[0], 450);
                };
                info.postDelayed(loading[0], 450);
            }
            if (preview) {
                up.fetch(i, f -> {
                    if (f != null) pics.set(i, f);
                    else failed[i] = true;
                    if (at[0] == i && d.isShowing()) showAt[0].run();
                });
            }
            for (int k = 0; k < dot.length; k++) {
                android.graphics.drawable.GradientDrawable o = new android.graphics.drawable.GradientDrawable();
                o.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                o.setColor(k == i ? Theme.TEXT : 0x55F2EDE4);
                o.setStroke(Math.max(1, Theme.d(1)), 0x99000000); // stays visible on a white booklet page
                dot[k].setBackground(o);
            }
            if (pending[0] != null) pending[0].cancel(true);
            img.animate().cancel();
            img.setAlpha(0.35f);
            pending[0] = CoverLoader.get().load(pics.get(i), px, b -> {
                if (at[0] != i || !d.isShowing()) return;
                img.setImageBitmap(b);
                img.animate().alpha(1f).setDuration(200).start();
            });
        };
        Runnable next = () -> { if (at[0] < pics.size() - 1) { at[0]++; showAt[0].run(); } };
        Runnable prev = () -> { if (at[0] > 0) { at[0]--; showAt[0].run(); } };

        GestureDetector g = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { d.dismiss(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) < Math.abs(vy)) return false;
                if (vx < 0) next.run(); else prev.run();
                return true;
            }
        });
        root.setOnTouchListener((v, e) -> g.onTouchEvent(e));
        // mouse wheel steps too (PC control / a mouse on the streamer)
        root.setOnGenericMotionListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_SCROLL) return false;
            float s = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (s < 0) next.run(); else if (s > 0) prev.run();
            return true;
        });
        d.setOnKeyListener((dlg, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                    next.run(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_PAGE_UP:
                    prev.run(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    d.dismiss(); return true;
                default: return false;
            }
        });
        d.setOnDismissListener(x -> {
            if (pending[0] != null) pending[0].cancel(true);
            if (onClose != null) onClose.closed(at[0]);
        });
        d.setContentView(root);
        d.show();
        showAt[0].run();
    }
}
