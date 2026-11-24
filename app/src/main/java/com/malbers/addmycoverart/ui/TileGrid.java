package com.malbers.addmycoverart.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 1.1.0, the cover grid every screen uses (handoff "Album tile": 6 columns, gaps 16 / 18, container padding
 * 14 34 20). No androidx in this app, so it is a ListView of rows of six tiles, with full-width header and
 * label rows between groups. Rows are recycled, covers load as rows come on screen.
 */
public final class TileGrid extends ListView {
    public static final int COLUMNS = 6;

    /** One tile. Album tiles show {@link #art} or the blank square; folder tiles show {@link #cells} as a mosaic. */
    public static final class Tile {
        public boolean folder;
        public String title;
        public CharSequence meta;
        /** Album cover file; null = blank tile. */
        public File art;
        public int tone = CoverView.FLAG;
        public String blankLabel = "No cover";
        /** Folder tiles: one entry per cell, a cover file or null for a blank album; fewer than 4 = empty cells. */
        public List<File> cells;
        public boolean cellsKnown;
        public View.OnClickListener onClick;
        public Object tag;
        /** Fade and scale in once, the first time it is shown (live scanning). */
        public boolean animateIn;
        /** P4: the ring of the focus style without focus (the album being fetched now). */
        public boolean highlight;
        /** P4: 0.55 for "Not tried" tiles after a Stop. */
        public float alpha = 1f;
        /** 1.1.0: corner badge, "+8" on an album (extra pictures), "2/5" on a folder (albums with them). */
        public String badge;
    }

    /** "{a} · {b}" with b in another colour (tile meta: "3 albums · 1 no cover"). */
    public static CharSequence twoTone(String a, int colorA, String b, int colorB) {
        SpannableStringBuilder s = new SpannableStringBuilder(a);
        s.setSpan(new ForegroundColorSpan(colorA), 0, a.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (b != null && !b.isEmpty()) {
            int st = s.length();
            s.append(b);
            s.setSpan(new ForegroundColorSpan(colorB), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    public static CharSequence plain(String a, int color) { return twoTone(a, color, null, 0); }

    // ---- entries
    private static final int T_VIEW = 0, T_ROW = 1;

    private static final class Entry {
        final int type;
        final View view;          // T_VIEW: a header, label or spacer, shown as is
        final List<Tile> tiles;   // T_ROW: up to six

        Entry(View v) { type = T_VIEW; view = v; tiles = null; }
        Entry(List<Tile> t) { type = T_ROW; view = null; tiles = t; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final int coverPx;
    /** P4: the fetch queue uses 5 columns with gaps 18 / 18 and smaller text (title 16, mono status 14). */
    private int columns = COLUMNS;
    private float colGap = 16, rowGap = 18;
    private boolean compact;

    /** Set before adding tiles. */
    public TileGrid columns(int n, float colGap, float rowGap) {
        columns = n;
        this.colGap = colGap;
        this.rowGap = rowGap;
        return this;
    }

    public TileGrid compact() {
        compact = true;
        return this;
    }

    public TileGrid pad(float l, float t, float r, float b) {
        setPadding(Theme.d(l), Theme.d(t), Theme.d(r), Theme.d(b));
        return this;
    }

    /** Scrolls so the tile tagged {@code tag} is in view (the album being fetched). */
    public void scrollToTag(Object tag) {
        int row = rowOf(tag);
        if (row >= 0) smoothScrollToPosition(row);
    }

    public TileGrid(Context c) {
        super(c);
        setDivider(null);
        setSelector(new ColorDrawable(Color.TRANSPARENT));
        setCacheColorHint(0);
        setItemsCanFocus(true);
        setClipToPadding(false);
        setPadding(Theme.d(34), Theme.d(14), Theme.d(34), Theme.d(20));
        setVerticalScrollBarEnabled(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setAdapter(adapter);
        // the size covers are decoded at: one tile's width on this screen (handoff tile ~177 of 1334)
        coverPx = Math.max(128, Theme.d(180));
    }

    /** Container padding (handoff: 14 34 20 for Browse, 22 34 20 for Results). */
    public TileGrid top(float t) {
        setPadding(getPaddingLeft(), Theme.d(t), getPaddingRight(), getPaddingBottom());
        return this;
    }

    /** A full-width row (headline, group label, spacer). */
    public TileGrid addRow(View v) {
        entries.add(new Entry(v));
        adapter.notifyDataSetChanged();
        return this;
    }

    /** Group label: mono 15, caps, 12% tracking (handoff "NO COVER · 7"). */
    public TileGrid addLabel(String s, int color) {
        TextView t = Theme.monoText(getContext(), s.toUpperCase(java.util.Locale.ROOT), 15, 400, color);
        t.setLetterSpacing(0.12f);
        t.setPadding(Theme.d(6), 0, Theme.d(6), Theme.d(10));
        return addRow(t);
    }

    public TileGrid addSpacer(float h) {
        View v = new View(getContext());
        v.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Math.max(1, Theme.d(h))));
        return addRow(v);
    }

    /** Tiles in rows of six, starting a new row. */
    public TileGrid addTiles(List<Tile> tiles) {
        for (int i = 0; i < tiles.size(); i += columns) {
            entries.add(new Entry(new ArrayList<>(tiles.subList(i, Math.min(tiles.size(), i + columns)))));
        }
        adapter.notifyDataSetChanged();
        return this;
    }

    /** Adds one tile to the last row (or a new row once it holds six). */
    public void appendTile(Tile t) {
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (last != null && last.type == T_ROW && last.tiles.size() < columns) last.tiles.add(t);
        else {
            List<Tile> row = new ArrayList<>();
            row.add(t);
            entries.add(new Entry(row));
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * Re-binds the rows on screen (folder summaries arrived). p3c: WITHOUT notifyDataSetChanged, which made the list
     * re-anchor on its current top row every time and so undid a restored scroll position (Michael: Back and Up
     * "neither holds place"). Rows scrolled in later bind from the same tiles anyway.
     */
    public void refresh() {
        int first = getFirstVisiblePosition();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int pos = first + i;
            if (v instanceof RowView && pos < entries.size() && entries.get(pos).type == T_ROW) ((RowView) v).bind(entries.get(pos).tiles);
        }
    }

    /** The row holding the tile tagged {@code tag}, or -1. */
    private int rowOf(Object tag) {
        if (tag == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.type != T_ROW) continue;
            for (Tile t : e.tiles) if (tag.equals(t.tag)) return i;
        }
        return -1;
    }

    /** Where the grid is scrolled to: first visible row and its offset (P3: Back from an album keeps the place). */
    public int[] scrollState() {
        View c = getChildAt(0);
        return new int[]{getFirstVisiblePosition(), c == null ? 0 : c.getTop() - getPaddingTop()};
    }

    /**
     * Scrolls back to a {@link #scrollState()} and focuses the tile whose tag equals {@code focusTag}. With no state
     * (a folder never shown this session, e.g. Up from where the app started), scrolls that tile's row into view.
     */
    public void restore(int[] state, Object focusTag) {
        if (state == null) {
            int row = rowOf(focusTag);
            if (row < 0) { focusFirstTile(); return; }
            state = new int[]{row, 0};
        }
        final int[] st = state;
        // p3b (Michael on the A6: Back "anchors back to top"): a new list's FIRST layout runs with "data changed"
        // and forces the top, overriding a position set before it. So wait for that layout, then set it.
        getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                getViewTreeObserver().removeOnGlobalLayoutListener(this);
                setSelectionFromTop(st[0], st[1]);
                focusAfterRestore(focusTag);
            }
        });
    }

    private void focusAfterRestore(Object focusTag) {
        // twice: the first pass lays the rows out at the restored position, the second finds the tile
        post(() -> post(() -> {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                if (!(row instanceof RowView)) continue;
                for (TileView s : ((RowView) row).slots) {
                    if (s.tile != null && focusTag != null && focusTag.equals(s.tile.tag)) {
                        s.requestFocus();
                        return;
                    }
                }
            }
            focusFirstTile();
        }));
    }

    /** Focus on the first tile (Browse: initial focus is the first tile, handoff). */
    public void focusFirstTile() {
        post(() -> {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                if (row instanceof RowView) {
                    ((RowView) row).slots[0].requestFocus();
                    return;
                }
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) {
            // shown-as-is rows must never be recycled into another position
            return entries.get(p).type == T_ROW ? 1 : IGNORE_ITEM_VIEW_TYPE;
        }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return entries.get(p).type == T_ROW; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            Entry e = entries.get(p);
            if (e.type == T_VIEW) return e.view;
            RowView row = convert instanceof RowView ? (RowView) convert : new RowView(getContext());
            row.bind(e.tiles);
            return row;
        }
    }

    // ---- views

    /** Six slots, each weight 1, gap 16; 18 below the row. */
    private final class RowView extends LinearLayout {
        final TileView[] slots = new TileView[columns];

        RowView(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setPadding(0, 0, 0, Theme.d(rowGap));
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new TileView(c);
                LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
                if (i > 0) lp.leftMargin = Theme.d(colGap);
                addView(slots[i], lp);
            }
        }

        void bind(List<Tile> tiles) {
            for (int i = 0; i < slots.length; i++) {
                if (i < tiles.size()) {
                    slots[i].setVisibility(VISIBLE);
                    slots[i].bind(tiles.get(i));
                } else {
                    slots[i].unbind();
                    slots[i].setVisibility(INVISIBLE);
                }
            }
        }
    }

    /** Padding 6, radius 12; focus = an accent ring 3 wide with a 3 gap to the cover (handoff, frame 02b). */
    private final class TileView extends LinearLayout {
        final CoverView cover;
        final MosaicView mosaic;
        final TextView title, meta;
        Tile tile;
        final Future<?>[] pending = new Future<?>[4];

        TileView(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setFocusable(true);
            setClickable(true);
            setBackground(tileBg());
            int p = Theme.d(6);
            setPadding(p, p, p, p);
            cover = new CoverView(c);
            addView(cover, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            mosaic = new MosaicView(c);
            addView(mosaic, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            title = Theme.oneLine(Theme.text(c, "", compact ? 16 : 19, 600, Theme.TEXT));
            title.setPadding(Theme.d(2), Theme.d(10), Theme.d(2), Theme.d(2));
            addView(title);
            meta = Theme.oneLine(compact ? Theme.monoText(c, "", 14, 400, Theme.TEXT_3) : Theme.text(c, "", 16, 400, Theme.TEXT_3));
            meta.setPadding(Theme.d(2), 0, Theme.d(2), 0);
            addView(meta);
            setOnClickListener(v -> { if (tile != null && tile.onClick != null) tile.onClick.onClick(v); });
        }

        void unbind() {
            for (int i = 0; i < 4; i++) {
                if (pending[i] != null) pending[i].cancel(true);
                pending[i] = null;
            }
            tile = null;
        }

        void bind(Tile t) {
            boolean same = tile == t;
            if (!same) unbind();
            tile = t;
            title.setText(t.title);
            meta.setText(t.meta == null ? "" : t.meta);
            meta.setVisibility(t.meta == null ? GONE : VISIBLE);
            cover.setVisibility(t.folder ? GONE : VISIBLE);
            mosaic.setVisibility(t.folder ? VISIBLE : GONE);
            if (t.folder) bindMosaic(t, same);
            else bindCover(t, same);
            cover.setBadge(t.folder ? null : t.badge);
            mosaic.setBadge(t.folder ? t.badge : null);
            setSelected(t.highlight);
            if (t.animateIn) {
                t.animateIn = false;
                setAlpha(0f);
                setScaleX(0.94f);
                setScaleY(0.94f);
                animate().alpha(t.alpha).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(new DecelerateInterpolator()).start();
            } else {
                setAlpha(t.alpha);
            }
        }

        private void bindCover(Tile t, boolean same) {
            if (t.art == null) {
                cover.setBlank(t.tone, t.blankLabel);
                return;
            }
            android.graphics.Bitmap hit = CoverLoader.get().cached(t.art, coverPx);
            if (hit != null) { cover.setImage(hit); return; }
            if (same && (cover.hasImage() || (pending[0] != null && !pending[0].isDone()))) return; // already shown / on its way
            cover.setEmpty();
            final Tile want = t;
            pending[0] = CoverLoader.get().load(t.art, coverPx, b -> {
                if (tile != want) return; // this view now shows another album
                if (b == null) cover.setBlank(CoverView.MUTED, "");
                else cover.setImage(b);
            });
        }

        private void bindMosaic(Tile t, boolean same) {
            if (!same) mosaic.clear();
            if (t.cells == null) return;
            int cellPx = Math.max(64, coverPx / 2);
            for (int i = 0; i < 4; i++) {
                if (i >= t.cells.size()) { mosaic.setCell(i, MosaicView.NONE, null); continue; }
                File art = t.cells.get(i);
                if (art == null) { mosaic.setCell(i, MosaicView.BLANK, null); continue; }
                final int cell = i;
                final Tile want = t;
                android.graphics.Bitmap hit = CoverLoader.get().cached(art, cellPx);
                if (hit != null) { mosaic.setCell(i, MosaicView.IMAGE, hit); continue; }
                if (same && pending[i] != null && !pending[i].isDone()) continue; // on its way
                pending[i] =CoverLoader.get().load(art, cellPx, b -> {
                    if (tile != want) return;
                    mosaic.setCell(cell, b == null ? MosaicView.NONE : MosaicView.IMAGE, b);
                });
            }
        }
    }

    private static StateListDrawable tileBg() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Theme.round(Color.TRANSPARENT, 12, Theme.ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_selected}, Theme.round(Color.TRANSPARENT, 12, Theme.ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, Theme.round(0x0DF2EDE4, 12)); // .05 wash
        s.addState(new int[]{android.R.attr.state_hovered}, Theme.round(0x0DF2EDE4, 12));
        s.addState(new int[]{}, Theme.round(Color.TRANSPARENT, 12));
        return s;
    }
}
