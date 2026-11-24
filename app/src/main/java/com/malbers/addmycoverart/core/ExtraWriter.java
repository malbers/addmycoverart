package com.malbers.addmycoverart.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * P6: saves extra pictures into the album's Scan/ folder (handoff "Writing extras"). Scan/ is created only here,
 * on Save. Never overwrites: a name that exists gets -2, -3… Music files are never opened.
 */
public final class ExtraWriter {
    public static final String SCAN = "Scan";

    private ExtraWriter() {}

    /** The album's existing scan folder (Scan/ or Scans/, any case), else the Scan/ it would create. */
    public static File scanDir(File album) {
        File[] kids = album.listFiles(File::isDirectory);
        if (kids != null) for (File k : kids) {
            if (k.getName().equalsIgnoreCase("scan") || k.getName().equalsIgnoreCase("scans")) return k;
        }
        return new File(album, SCAN);
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
