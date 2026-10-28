package com.malbers.addmycoverart.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One list style for every screen: optional thumbnail, title, subtitle, and a coloured status on the right. */
public final class ItemAdapter extends BaseAdapter {
    public static final class Item {
        public final String title;
        public final String sub;
        public final String status;
        public final int statusColor;
        public final Bitmap thumb;
        public final boolean showThumb;
        public final Object tag;
        /** Small leading icon (folder, drive), or 0 for none. */
        public int icon;

        public Item withIcon(int res) {
            icon = res;
            return this;
        }

        public Item(String title, String sub, String status, int statusColor, Bitmap thumb, boolean showThumb, Object tag) {
            this.title = title;
            this.sub = sub;
            this.status = status;
            this.statusColor = statusColor;
            this.thumb = thumb;
            this.showThumb = showThumb;
            this.tag = tag;
        }
    }

    private final Context c;
    private final List<Item> items = new ArrayList<>();

    public ItemAdapter(Context c) {
        this.c = c;
    }

    public void add(Item i) {
        items.add(i);
        notifyDataSetChanged();
    }

    public void addAll(List<Item> list) {
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Item getItem(int p) { return items.get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        Holder h = convert == null ? new Holder(c) : (Holder) convert.getTag();
        Item it = items.get(position);
        h.thumb.setVisibility(it.showThumb ? View.VISIBLE : View.GONE);
        h.icon.setVisibility(it.icon != 0 ? View.VISIBLE : View.GONE);
        if (it.icon != 0) h.icon.setImageResource(it.icon);
        if (it.thumb != null) h.thumb.setImageBitmap(it.thumb);
        else h.thumb.setImageResource(com.malbers.addmycoverart.R.drawable.ic_disc_placeholder);
        h.title.setText(it.title);
        h.sub.setText(it.sub == null ? "" : it.sub);
        h.sub.setVisibility(it.sub == null ? View.GONE : View.VISIBLE);
        h.status.setText(it.status == null ? "" : it.status);
        h.status.setTextColor(it.statusColor);
        return h.root;
    }

    private static final class Holder {
        final LinearLayout root;
        final ImageView thumb, icon;
        final TextView title, sub, status;

        Holder(Context c) {
            root = Ui.row(c);
            int pad = Ui.dp(c, 10);
            root.setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 8));
            thumb = Ui.coverView(c, Ui.dp(c, 44));
            root.addView(thumb);
            icon = new ImageView(c);
            int iz = Ui.dp(c, 24);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iz, iz);
            ip.leftMargin = Ui.dp(c, 4);
            root.addView(icon, ip);
            LinearLayout texts = Ui.column(c);
            texts.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
            title = Ui.oneLine(c, "", 16, Ui.TEXT, true);
            sub = Ui.oneLine(c, "", 13, Ui.SUB, false);
            texts.addView(title);
            texts.addView(sub);
            root.addView(texts, Ui.weight(1));
            status = Ui.oneLine(c, "", 14, Ui.SUB, true);
            status.setGravity(Gravity.END);
            root.addView(status);
            root.setTag(this);
        }
    }
}
