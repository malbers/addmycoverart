package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** tags4 D1/D2: a picture already in the album folder is found by its bytes, not its name. */
public class ExtraWriterSameTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] BACK = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] OTHER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 9, 9};

    private static void put(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    @Test
    public void sameBytesInScanIsFound() throws Exception {
        File album = tmp.newFolder("Help");
        put(new File(album, "Scan/back.jpg"), BACK);
        assertEquals("back.jpg", ExtraWriter.sameAs(album, BACK).getName());
        assertNull(ExtraWriter.sameAs(album, OTHER));
    }

    @Test
    public void renamedCopyStillCounts() throws Exception {
        File album = tmp.newFolder("Revolver");
        put(new File(album, "Scans/my back scan.JPG"), BACK);
        assertNotNull(ExtraWriter.sameAs(album, BACK));
    }

    @Test
    public void folderJpgCountsForTheFront() throws Exception {
        File album = tmp.newFolder("Abbey Road");
        put(new File(album, "folder.jpg"), BACK);
        assertNotNull(ExtraWriter.sameAs(album, BACK));
    }

    @Test
    public void sameNameDifferentPictureIsNotADuplicate() throws Exception {
        File album = tmp.newFolder("Rubber Soul");
        put(new File(album, "Scan/back.jpg"), OTHER);
        assertNull(ExtraWriter.sameAs(album, BACK));
    }

    @Test
    public void emptyAlbumHasNothing() throws Exception {
        File album = tmp.newFolder("Empty");
        assertTrue(ExtraWriter.existingPictures(album).isEmpty());
        assertNull(ExtraWriter.sameAs(album, BACK));
    }
}
