package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 1.1.0, Browse (handoff 02a-c): what one folder looks like as a tile, read-only. Either the folder is an album
 * (it holds audio files directly), or it is a folder of albums / folders, shown as a 2x2 mosaic of the first
 * four album covers inside it.
 */
public final class FolderSummary {
    /** One mosaic cell: the album's cover file, or null for an album with no cover (a mini blank cell). */
    public static final class Cell {
        public final File art;

        Cell(File art) { this.art = art; }
    }

    public final File dir;
    /** Audio files directly in this folder; > 0 means the folder is an album. */
    public final int tracks;
    public final File firstAudio;
    /** The cover file to show, or null. */
    public final File art;
    /** Visible subfolders directly inside. */
    public final int subfolders;
    /** Album subfolders directly inside, and how many of them have no cover file. */
    public final int albums;
    public final int missing;
    /** Up to four albums found inside (breadth first), for the mosaic. */
    public final List<Cell> mosaic;
    /** 1.1.0 badges: pictures in this album's Scan/ folder; album subfolders directly inside that have any. */
    public final int extras;
    public final int albumsWithExtras;

    private FolderSummary(File dir, int tracks, File firstAudio, File art, int subfolders, int albums, int missing, List<Cell> mosaic,
                          int extras, int albumsWithExtras) {
        this.dir = dir;
        this.tracks = tracks;
        this.firstAudio = firstAudio;
        this.art = art;
        this.subfolders = subfolders;
        this.albums = albums;
        this.missing = missing;
        this.mosaic = mosaic;
        this.extras = extras;
        this.albumsWithExtras = albumsWithExtras;
    }

    /**
     * An album = a folder holding audio files directly, with no music in its subfolders. A folder with loose tracks
     * AND album subfolders (Michael's TestsSongs: two .m4a files beside eight test albums) is a folder, so it can be
     * opened; its own tracks are still found by a scan. A Scan/ subfolder of pictures leaves an album an album.
     */
    public boolean isAlbum() { return tracks > 0 && !hasMusicInside(); }

    /** Album subfolders directly inside, or albums deeper down (the mosaic found some). */
    public boolean hasMusicInside() { return albums > 0 || !mosaic.isEmpty(); }

    public boolean isEmpty() { return tracks == 0 && subfolders == 0; }

    /** The folder as an Album (only meaningful when {@link #isAlbum()}). */
    /** Pictures in an album folder's Scan/ (or Scans/) folder; 0 when there is none. */
    public static int extrasOf(File albumDir) {
        File[] kids = albumDir.listFiles(File::isDirectory);
        return kids == null ? 0 : Listing.countExtras(java.util.Arrays.asList(kids));
    }

    public Album toAlbum() {
        return new Album(dir, tracks, firstAudio, art == null ? null : art.getName(), false);
    }

    /** How deep the mosaic looks for albums, and how many folders it may open doing so. */
    private static final int MOSAIC_DEPTH = 3, MOSAIC_VISITS = 40, MOSAIC_PER_FOLDER = 6;

    public static FolderSummary of(File dir) {
        Listing self = Listing.of(dir);
        int albums = 0, missing = 0, withExtras = 0;
        List<Cell> mosaic = new ArrayList<>();
        List<File> deeper = new ArrayList<>();
        for (File sub : self.dirs) {
            Listing l = Listing.of(sub);
            if (l.tracks > 0) {
                albums++;
                if (l.art == null) missing++;
                if (l.extras > 0) withExtras++;
                if (mosaic.size() < 4) mosaic.add(new Cell(l.art));
            } else if (!l.dirs.isEmpty()) {
                deeper.add(sub);
            }
        }
        if (mosaic.size() < 4 && !deeper.isEmpty()) fillMosaic(deeper, mosaic);
        return new FolderSummary(dir, self.tracks, self.firstAudio, self.art, self.dirs.size(), albums, missing, mosaic,
                self.extras, withExtras);
    }

    /** Breadth first through folders-of-folders (Music/Artist/Album), stopping at four albums or the visit cap. */
    private static void fillMosaic(List<File> start, List<Cell> mosaic) {
        Deque<File> queue = new ArrayDeque<>(start);
        Deque<Integer> depths = new ArrayDeque<>();
        for (int i = 0; i < start.size(); i++) depths.add(1);
        int visits = 0;
        while (!queue.isEmpty() && mosaic.size() < 4) {
            File cur = queue.poll();
            int depth = depths.poll();
            List<File> subs = Listing.of(cur).dirs;
            // only the first few of each folder, so a long artist list doesn't use up the budget before any album
            for (File sub : subs.subList(0, Math.min(subs.size(), MOSAIC_PER_FOLDER))) {
                if (++visits > MOSAIC_VISITS || mosaic.size() >= 4) return;
                Listing l = Listing.of(sub);
                if (l.tracks > 0) mosaic.add(new Cell(l.art));
                else if (!l.dirs.isEmpty() && depth < MOSAIC_DEPTH) {
                    queue.add(sub);
                    depths.add(depth + 1);
                }
            }
        }
    }

    /** One read of a folder: its audio count, first audio file, best cover file and visible subfolders. */
    static final class Listing {
        int tracks;
        File firstAudio;
        File art;
        /** Pictures in a Scan/ or Scans/ subfolder (counted only for a folder that is an album). */
        int extras;
        final List<File> dirs = new ArrayList<>();

        static Listing of(File dir) {
            Listing out = new Listing();
            File[] kids = dir.listFiles();
            if (kids == null) return out;
            Arrays.sort(kids, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            List<String> files = new ArrayList<>();
            for (File k : kids) {
                String n = k.getName();
                if (k.isDirectory()) {
                    if (!n.startsWith(".")) out.dirs.add(k);
                } else if (AlbumScanner.isAudioName(n)) {
                    out.tracks++;
                    if (out.firstAudio == null) out.firstAudio = k;
                } else {
                    files.add(n);
                }
            }
            String art = AlbumScanner.bestArtFile(dir, files.toArray(new String[0]));
            if (art != null) out.art = new File(dir, art);
            if (out.tracks > 0) out.extras = countExtras(out.dirs);
            return out;
        }

        static int countExtras(List<File> dirs) {
            int n = 0;
            for (File d : dirs) {
                String name = d.getName();
                if (!name.equalsIgnoreCase("scan") && !name.equalsIgnoreCase("scans")) continue;
                String[] pics = d.list();
                if (pics != null) for (String p : pics) {
                    String l = p.toLowerCase(Locale.ROOT);
                    if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")) n++;
                }
            }
            return n;
        }
    }
}
