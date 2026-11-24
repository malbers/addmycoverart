package com.malbers.addmycoverart.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * P6 (handoff "Find more art"): one release's pictures on the Cover Art Archive, as the album page offers them.
 * Order: Front (see below), Back, Booklet pages in the archive's order, Disc (the "Medium" type), Tray. Other types
 * (obi, spine, sticker…) are left out. Previews use the 250 thumbnail; saving takes the 1200 one (else the full image).
 */
public final class CaaExtras {
    public static final String FRONT = "front", BACK = "back", BOOKLET = "booklet", DISC = "disc", TRAY = "tray";

    /** One picture on offer. */
    public static final class Extra {
        public final String kind;
        /** Shown under the thumbnail: "Back", "Booklet 2", "Disc", "Front (online)". */
        public final String label;
        /** File name to save as (in Scan/, except a blank album's front, which becomes folder.jpg). */
        public final String fileName;
        public final String thumbUrl;
        public final String saveUrl;

        Extra(String kind, String label, String fileName, String thumbUrl, String saveUrl) {
            this.kind = kind;
            this.label = label;
            this.fileName = fileName;
            this.thumbUrl = thumbUrl;
            this.saveUrl = saveUrl;
        }
    }

    private CaaExtras() {}

    /**
     * Parses a "release/{mbid}/" listing. The front is offered either way: a blank album saves it as folder.jpg;
     * an album with a cover may save it as Scan/front.jpg (Michael 2026-11-23: the online cover may be the better
     * one), labelled "Front (online)".
     */
    public static List<Extra> parse(String json, boolean albumHasCover) throws JSONException {
        JSONArray images = new JSONObject(json).optJSONArray("images");
        List<JSONObject> fronts = new ArrayList<>(), backs = new ArrayList<>(), booklets = new ArrayList<>(),
                discs = new ArrayList<>(), trays = new ArrayList<>();
        if (images != null) for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.getJSONObject(i);
            String k = kindOf(im);
            if (FRONT.equals(k)) fronts.add(im);
            else if (BACK.equals(k)) backs.add(im);
            else if (BOOKLET.equals(k)) booklets.add(im);
            else if (DISC.equals(k)) discs.add(im);
            else if (TRAY.equals(k)) trays.add(im);
        }
        List<Extra> out = new ArrayList<>();
        if (!fronts.isEmpty()) {
            out.add(extra(fronts.get(0), FRONT, albumHasCover ? "Front (online)" : "Front", albumHasCover ? "front.jpg" : "folder.jpg"));
        }
        add(out, backs, BACK, "Back", "back");
        add(out, booklets, BOOKLET, "Booklet", "booklet");
        add(out, discs, DISC, "Disc", "disc");
        add(out, trays, TRAY, "Tray", "tray");
        return out;
    }

    /** True when nothing but the front is on offer (handoff 07e for an album that has a cover). */
    public static boolean frontOnly(List<Extra> extras) {
        for (Extra e : extras) if (!FRONT.equals(e.kind)) return false;
        return !extras.isEmpty();
    }

    private static void add(List<Extra> out, List<JSONObject> list, String kind, String label, String base) {
        boolean numbered = list.size() > 1 || BOOKLET.equals(kind);
        for (int i = 0; i < list.size(); i++) {
            String n = numbered ? String.format(Locale.US, "%s-%02d", base, i + 1) : base;
            String l = list.size() > 1 || BOOKLET.equals(kind) ? label + " " + (i + 1) : label;
            out.add(extra(list.get(i), kind, l, n + ".jpg"));
        }
    }

    private static Extra extra(JSONObject im, String kind, String label, String fileName) {
        JSONObject th = im.optJSONObject("thumbnails");
        String full = https(im.optString("image", null));
        String t250 = th == null ? null : https(th.optString("250", th.optString("small", null)));
        String t1200 = th == null ? null : https(th.optString("1200", null));
        return new Extra(kind, label, fileName, t250 != null ? t250 : full, t1200 != null ? t1200 : full);
    }

    /** The first type that matters, in order of how the picture is used. */
    static String kindOf(JSONObject im) {
        JSONArray types = im.optJSONArray("types");
        List<String> t = new ArrayList<>();
        if (types != null) for (int i = 0; i < types.length(); i++) t.add(types.optString(i));
        if (im.optBoolean("front") || t.contains("Front")) return FRONT;
        if (im.optBoolean("back") || t.contains("Back")) return BACK;
        if (t.contains("Booklet")) return BOOKLET;
        if (t.contains("Medium")) return DISC;
        if (t.contains("Tray")) return TRAY;
        return null;
    }

    /** The archive still lists some links as http://; this app only uses https. */
    static String https(String u) {
        if (u == null || u.isEmpty()) return null;
        return u.startsWith("http://") ? "https://" + u.substring(7) : u;
    }
}
