package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.1.2 (Michael 2026-11-25, "for more editions only"): picks that survive switching editions. While browsing
 * More editions, each edition's ticks are parked here by release ID when you move on and come back when you return;
 * one Save then writes the picks from every edition. Plain Java so it can be tested.
 */
public final class EditionBasket {
    /** One edition's pictures, ticks and downloaded full-size files (same order). */
    public static final class Picks {
        public final List<CaaExtras.Extra> extras;
        public final boolean[] selected;
        public final File[] bigs;

        Picks(List<CaaExtras.Extra> extras, boolean[] selected, File[] bigs) {
            this.extras = extras;
            this.selected = selected;
            this.bigs = bigs;
        }
    }

    private final Map<String, Picks> parked = new LinkedHashMap<>();

    /** Park an edition's ticks (replaces what was parked for it). An edition with nothing ticked is forgotten. */
    public void park(String releaseId, List<CaaExtras.Extra> extras, boolean[] selected, File[] bigs) {
        if (releaseId == null) return;
        parked.remove(releaseId);
        if (count(selected) == 0) return;
        parked.put(releaseId, new Picks(extras, selected.clone(), bigs == null ? new File[extras.size()] : bigs.clone()));
    }

    /** The ticks parked for this edition (a copy), or a fresh all-off array of the given size. */
    public boolean[] restore(String releaseId, int size) {
        Picks p = releaseId == null ? null : parked.get(releaseId);
        return p != null && p.selected.length == size ? p.selected.clone() : new boolean[size];
    }

    /** Ticks parked in OTHER editions than the one on screen. */
    public int countOthers(String currentReleaseId) {
        int n = 0;
        for (Map.Entry<String, Picks> e : parked.entrySet()) {
            if (!e.getKey().equals(currentReleaseId)) n += count(e.getValue().selected);
        }
        return n;
    }

    /** Editions (other than the current one) with at least one tick. */
    public int editionsWithPicks(String currentReleaseId) {
        int n = 0;
        for (String id : parked.keySet()) if (!id.equals(currentReleaseId)) n++;
        return n;
    }

    /** Everything to save: the other editions' parked picks, then the current edition's live ticks. */
    public Picks combined(String currentReleaseId, List<CaaExtras.Extra> extras, boolean[] selected, File[] bigs) {
        List<CaaExtras.Extra> x = new ArrayList<>();
        List<Boolean> s = new ArrayList<>();
        List<File> b = new ArrayList<>();
        for (Map.Entry<String, Picks> e : parked.entrySet()) {
            if (e.getKey().equals(currentReleaseId)) continue;
            add(e.getValue().extras, e.getValue().selected, e.getValue().bigs, x, s, b);
        }
        add(extras, selected, bigs, x, s, b);
        boolean[] sel = new boolean[s.size()];
        for (int i = 0; i < sel.length; i++) sel[i] = s.get(i);
        return new Picks(x, sel, b.toArray(new File[0]));
    }

    public void clear() {
        parked.clear();
    }

    private static void add(List<CaaExtras.Extra> extras, boolean[] selected, File[] bigs,
                            List<CaaExtras.Extra> x, List<Boolean> s, List<File> b) {
        for (int i = 0; i < extras.size(); i++) {
            if (i >= selected.length || !selected[i]) continue;
            x.add(extras.get(i));
            s.add(true);
            b.add(bigs != null && i < bigs.length ? bigs[i] : null);
        }
    }

    private static int count(boolean[] s) {
        int n = 0;
        if (s != null) for (boolean v : s) if (v) n++;
        return n;
    }
}
