package com.malbers.addmycoverart.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The only code that writes into a music folder. It creates folder.jpg and nothing else, never overwriting. */
public final class ArtWriter {
    public static final String NAME = "folder.jpg";

    private ArtWriter() {}

    /** Returns the new folder.jpg, or null (nothing written) if the folder already has art. */
    public static File save(File dir, byte[] image, String source, File runLog) throws IOException {
        // Re-check at write time: art may have been added since the scan.
        if (AlbumScanner.findArtFile(dir) != null) return null;
        File target = new File(dir, NAME);
        if (target.exists()) return null;
        File tmp = new File(dir, NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(image);
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (target.exists() || !tmp.renameTo(target)) {
            tmp.delete();
            if (target.exists()) return null;
            throw new IOException("could not rename " + tmp);
        }
        log(runLog, target, source);
        return target;
    }

    private static void log(File runLog, File written, String source) {
        String time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
        try (Writer w = new OutputStreamWriter(new FileOutputStream(runLog, true), StandardCharsets.UTF_8)) {
            w.write(time + "\t" + written.getPath() + "\t" + source + "\n");
        } catch (IOException ignored) {
            // The cover is saved; a missing log line must not turn that into a failure.
        }
    }
}
