package com.malbers.addmycoverart.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * P6: saves extra pictures into the album's Scan/ folder (handoff "Writing extras"). Scan/ is created only here,
 * on Save. Never overwrites: a name that exists gets -2, -3… Music files are never opened.
 */
public final class ExtraWriter {
    /** 1.1.2 (Michael 2026-11-25): new scans folders are "Scans", the common convention (was "Scan"). */
    public static final String SCAN = "Scans";

    /** 1.1.2: folder names that hold an album's extra pictures, any case. Artwork/ is common in downloaded rips. */
    public static boolean isScansName(String n) {
        return n.equalsIgnoreCase("scan") || n.equalsIgnoreCase("scans") || n.equalsIgnoreCase("artwork");
    }

    private ExtraWriter() {}

    /** The album's existing scans folder (Scan/, Scans/ or Artwork/, any case), else the Scans/ it would create. */
    public static File scanDir(File album) {
        File[] kids = album.listFiles(File::isDirectory);
        if (kids != null) for (File k : kids) {
            if (isScansName(k.getName())) return k;   // never a second scans folder next to an existing one
        }
        return new File(album, SCAN);
    }

    /** 1.1.2: saves into the album's own scan folder (Scan/<ISO name>/ for an ISO sharing its folder). */
    public static File save(Album a, String fileName, byte[] image) throws IOException {
        if (!a.sharedIso) return save(a.dir, fileName, image);
        File dir = a.scanDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir.getName());
        File target = freeName(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(image);
            out.getFD().sync();
        }
        return target;
    }

    /** A name in {@code dir} not taken yet: name.jpg, else name-2.jpg, name-3.jpg… */
    public static File freeName(File dir, String fileName) {
        File f = new File(dir, fileName);
        if (!f.exists()) return f;
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        String ext = dot < 0 ? "" : fileName.substring(dot);
        for (int i = 2; ; i++) {
            f = new File(dir, base + "-" + i + ext);
            if (!f.exists()) return f;
        }
    }

    /**
     * tags4 (D1/D2): a picture already in the album folder or its scan folder with exactly these bytes, else null.
     * Compared by content, so a renamed copy still counts and a different picture with the same name does not.
     */
    public static File sameAs(File album, byte[] image) {
        if (image == null || image.length == 0) return null;
        List<File> pics = existingPictures(album);
        for (File f : pics) {
            if (f.length() != image.length) continue;
            try {
                if (java.util.Arrays.equals(java.nio.file.Files.readAllBytes(f.toPath()), image)) return f;
            } catch (IOException ignored) {
                // unreadable: not a match
            }
        }
        return null;
    }

    /** JPEG/PNG files in the album folder and its scan folder (not deeper). */
    /**
     * 1.1.2: an ISO sharing its folder only owns its "<ISO name>.jpg" and Scan/<ISO name>/, never the neighbours'
     * pictures; any other album: the folder and its scan folder, as before.
     */
    public static List<File> existingPictures(Album a) {
        if (!a.sharedIso) return existingPictures(a.dir);
        List<File> out = new ArrayList<>();
        File own = new File(a.dir, a.coverName());
        if (own.isFile()) out.add(own);
        File scan = a.scanDir();
        if (scan.isDirectory()) addPictures(scan, out);
        return out;
    }

    /** 1.1.2: {@link #sameAs(File, byte[])} within the album's own pictures. */
    public static File sameAs(Album a, byte[] image) {
        if (!a.sharedIso) return sameAs(a.dir, image);
        if (image == null || image.length == 0) return null;
        for (File f : existingPictures(a)) {
            if (f.length() != image.length) continue;
            try {
                if (java.util.Arrays.equals(java.nio.file.Files.readAllBytes(f.toPath()), image)) return f;
            } catch (IOException ignored) {
                // unreadable: not a match
            }
        }
        return null;
    }

    public static List<File> existingPictures(File album) {
        List<File> out = new ArrayList<>();
        addPictures(album, out);
        File scan = scanDir(album);
        if (scan.isDirectory()) addPictures(scan, out);
        return out;
    }

    private static void addPictures(File dir, List<File> out) {
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) out.add(f);
        }
    }

    /** Writes one picture into the album's scan folder and returns the file written. */
    public static File save(File album, String fileName, byte[] image) throws IOException {
        File dir = scanDir(album);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("couldn't create " + dir.getName() + "/");
        File target = freeName(dir, fileName);
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(image);
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (target.exists() || !tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("couldn't save " + target.getName());
        }
        return target;
    }
}
