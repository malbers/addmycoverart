package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** P6: the Cover Art Archive listing, the order and names it is offered in, and the Scan/ writer. */
public class CaaExtrasTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String img(String types, boolean front, boolean back, int id) {
        return "{\"types\":[" + types + "],\"front\":" + front + ",\"back\":" + back
                + ",\"image\":\"http://coverartarchive.org/release/r/" + id + ".jpg\","
                + "\"thumbnails\":{\"250\":\"http://coverartarchive.org/release/r/" + id + "-250.jpg\","
                + "\"1200\":\"http://coverartarchive.org/release/r/" + id + "-1200.jpg\"}}";
    }

    private static String listing(String... images) {
        return "{\"images\":[" + String.join(",", images) + "],\"release\":\"https://musicbrainz.org/release/r\"}";
    }

    private static final String FULL = listing(
            img("\"Booklet\"", false, false, 3),
            img("\"Back\"", false, true, 2),
            img("\"Front\"", true, false, 1),
            img("\"Booklet\"", false, false, 4),
            img("\"Medium\"", false, false, 5),
            img("\"Tray\"", false, false, 6),
            img("\"Obi\"", false, false, 7));

    @Test
    public void orderLabelsAndNamesForAnAlbumWithACover() throws Exception {
        List<CaaExtras.Extra> x = CaaExtras.parse(FULL, true);
        assertEquals(6, x.size()); // obi left out
        String[] labels = {"Front (online)", "Back", "Booklet 1", "Booklet 2", "Disc", "Tray"};
        String[] names = {"front.jpg", "back.jpg", "booklet-01.jpg", "booklet-02.jpg", "disc.jpg", "tray.jpg"};
        for (int i = 0; i < labels.length; i++) {
            assertEquals(labels[i], x.get(i).label);
            assertEquals(names[i], x.get(i).fileName);
        }
        // booklets keep the archive's order (3 before 4), links become https, preview 250, save 1200
        assertTrue(x.get(2).saveUrl.endsWith("/3-1200.jpg"));
        assertEquals("https://coverartarchive.org/release/r/1-250.jpg", x.get(0).thumbUrl);
        assertTrue(x.get(0).saveUrl.startsWith("https://"));
    }

    @Test
    public void blankAlbumSavesTheFrontAsFolderJpg() throws Exception {
        List<CaaExtras.Extra> x = CaaExtras.parse(FULL, false);
        assertEquals("Front", x.get(0).label);
        assertEquals("folder.jpg", x.get(0).fileName);
    }

    @Test
    public void frontOnlyAndNone() throws Exception {
        assertTrue(CaaExtras.frontOnly(CaaExtras.parse(listing(img("\"Front\"", true, false, 1)), true)));
        assertFalse(CaaExtras.frontOnly(CaaExtras.parse(FULL, true)));
        List<CaaExtras.Extra> none = CaaExtras.parse(listing(), true);
        assertTrue(none.isEmpty());
        assertFalse(CaaExtras.frontOnly(none));
    }

    @Test
    public void twoDiscsAreNumbered() throws Exception {
        List<CaaExtras.Extra> x = CaaExtras.parse(listing(img("\"Medium\"", false, false, 1), img("\"Medium\"", false, false, 2)), true);
        assertEquals("Disc 1", x.get(0).label);
        assertEquals("disc-02.jpg", x.get(1).fileName);
    }

    @Test
    public void noThumbnailFallsBackToTheFullImage() throws Exception {
        String j = listing("{\"types\":[\"Back\"],\"image\":\"https://x/back.jpg\"}");
        CaaExtras.Extra e = CaaExtras.parse(j, true).get(0);
        assertEquals("https://x/back.jpg", e.thumbUrl);
        assertEquals("https://x/back.jpg", e.saveUrl);
    }

    @Test
    public void writerCreatesScanOnlyOnSaveAndNeverOverwrites() throws Exception {
        File album = tmp.newFolder("Aventine");
        assertFalse(new File(album, "Scan").exists());
        byte[] a = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        byte[] b = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 2};
        File one = ExtraWriter.save(album, "back.jpg", a);
        File two = ExtraWriter.save(album, "back.jpg", b);
        assertEquals("Scan", one.getParentFile().getName());
        assertEquals("back.jpg", one.getName());
        assertEquals("back-2.jpg", two.getName());
        assertArrayEquals(a, Files.readAllBytes(one.toPath()));
        assertArrayEquals(b, Files.readAllBytes(two.toPath()));
    }

    @Test
    public void writerUsesAnExistingScansFolder() throws Exception {
        File album = tmp.newFolder("Four Seasons");
        File scans = new File(album, "Scans");
        assertTrue(scans.mkdir());
        File f = ExtraWriter.save(album, "tray.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        assertEquals(scans, f.getParentFile());
        assertFalse(new File(album, "Scan").exists());
    }
}
