package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** 1.1.2: Václav H.'s three reports (SACD ISO, "CD I", long Back button) and Michael's ISO library, 2026-11-25. */
public class IsoAndDiscsTest {
    @Test public void isoCountsAsMusic() {
        assertTrue(AlbumScanner.isAudioName("Dire Straits - Communique.iso"));
        assertTrue(AlbumScanner.isAudioName("X.ISO"));
    }

    @Test public void sacdIsoFolderNameDropsTheFormat() {
        assertArrayEquals(new String[]{"Dire Straits", "Communique (2012 Jap Ed)"},
                FolderNameParser.guess(new File("/s/DD/Dire Straits - 1979 - Communique (2012 Jap Ed) - SACD ISO")));
    }

    @Test public void romanDiscFoldersUseTheParent() {
        assertArrayEquals(new String[]{"David Gilmour", "Rattle That Lock"},
                FolderNameParser.guess(new File("/s/D/David Gilmour - 2015 - Rattle That Lock - Wav/CD I")));
        assertArrayEquals(new String[]{"David Gilmour", "Rattle That Lock"},
                FolderNameParser.guess(new File("/s/D/David Gilmour - 2015 - Rattle That Lock - Wav/CD II")));
        assertEquals(Integer.valueOf(1), RipCheck.discFromName("CD I"));
        assertEquals(Integer.valueOf(2), RipCheck.discFromName("Disc II"));
        assertEquals(Integer.valueOf(4), RipCheck.roman("iv"));
        assertEquals(Integer.valueOf(3), RipCheck.discFromName("CD 3"));
        assertNull(RipCheck.roman("abc"));
    }

    @Test public void isoFileNameIsTheFallbackGuess() {
        File dir = new File("/s/ISO/Boston");
        Album a = new Album(dir, 1, new File(dir, "Boston - Boston (1976) [SACD] (2000 Remaster ISO).iso"), null, false);
        assertArrayEquals(new String[]{"Boston", "Boston"}, FolderNameParser.guess(a));
        Album flac = new Album(new File("/s/Beatles/Help"), 14, new File("/s/Beatles/Help/01.flac"), null, false);
        assertArrayEquals(new String[]{"Beatles", "Help"}, FolderNameParser.guess(flac));
    }

    @Test public void sacdDiscTextGivesTitleAndArtist() throws Exception {
        File f = Files.createTempFile("sacd", ".iso").toFile();
        f.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(513L * 2048);
            raf.seek(510L * 2048);
            raf.write("SACDMTOC".getBytes(StandardCharsets.ISO_8859_1));
            byte[] t = new byte[2048];
            System.arraycopy("SACDText".getBytes(StandardCharsets.ISO_8859_1), 0, t, 0, 8);
            t[16] = 0; t[17] = 64;   // Album_Title at 64
            t[18] = 0; t[19] = (byte) 128;   // Album_Artist at 128
            byte[] title = "Speak & Spell".getBytes(StandardCharsets.ISO_8859_1);
            byte[] artist = "Depeche Mode".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(title, 0, t, 64, title.length);
            System.arraycopy(artist, 0, t, 128, artist.length);
            raf.seek(511L * 2048);
            raf.write(t);
        }
        Tags tags = TagReader.read(f);
        assertEquals("Speak & Spell", tags.album);
        assertEquals("Depeche Mode", tags.bestArtist());
    }

    @Test public void anIsoWithoutDiscTextReadsAsNoTags() throws Exception {
        File f = Files.createTempFile("plain", ".iso").toFile();
        f.deleteOnExit();
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(4096); }
        assertNull(TagReader.read(f).album);
    }

    private static File dir(File parent, String name) { File d = new File(parent, name); d.mkdirs(); return d; }

    private static File touch(File d, String name, int bytes) throws Exception {
        File f = new File(d, name);
        Files.write(f.toPath(), new byte[bytes]);
        return f;
    }

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @Test public void everyIsoIsItsOwnAlbumWhereverItSits() throws Exception {
        File root = Files.createTempDirectory("iso").toFile();
        File bj = dir(root, "Billy Joel");
        touch(bj, "Billy Joel - The Nylon Curtain.iso", 10);       // loose, next to another album
        File pm = dir(bj, "Piano Man");
        touch(pm, "Billy Joel - Piano Man.iso", 10);               // alone in its own folder
        File dm = dir(root, "Depeche Mode");
        touch(dm, "Mute - Speak & Spell.iso", 10);
        touch(dm, "Mute Records - Ultra.iso", 10);
        Files.write(new File(dm, "Mute Records - Ultra.jpg").toPath(), JPEG);   // Ultra already has its own cover
        Files.write(new File(dm, "folder.jpg").toPath(), JPEG);               // a folder.jpg must not count for them

        ScanResult r = AlbumScanner.scan(root);
        java.util.List<Album> all = new java.util.ArrayList<>(r.missing);
        all.addAll(r.withArt);
        Album speak = null, ultra = null, piano = null;
        for (Album a : all) {
            String n = a.firstAudio.getName();
            if (n.contains("Speak")) speak = a;
            if (n.contains("Ultra")) ultra = a;
            if (n.contains("Piano")) piano = a;
        }
        assertNotNull(speak);
        assertTrue(speak.sharedIso);
        assertFalse(speak.hasArt());                                  // the folder.jpg is not Speak & Spell's cover
        assertEquals("Mute - Speak & Spell.jpg", speak.coverName());
        assertEquals(new File(new File(dm, "Scans"), "Mute - Speak & Spell"), speak.scanDir());
        assertTrue(ultra.sharedIso);
        assertTrue(ultra.hasArt());
        assertFalse(piano.sharedIso);                                 // alone in its folder: an ordinary album
        assertEquals("folder.jpg", piano.coverName());
        // the loose Nylon Curtain shares its folder with the "Piano Man" album FOLDER
        Album nylon = null;
        for (Album a : all) if (a.firstAudio.getName().contains("Nylon")) nylon = a;
        assertNotNull(nylon);
        assertTrue(nylon.sharedIso);                                  // never the artist folder's folder.jpg
        assertEquals("Billy Joel - The Nylon Curtain.jpg", nylon.coverName());
    }

    @Test public void anIsoWithOnlyAScanFolderIsStillAlone() throws Exception {
        File d = Files.createTempDirectory("vh").toFile();
        File iso = touch(d, "Dire Straits - 1979 - Communique (2012 Jap Ed) - SACD ISO.iso", 10);
        dir(d, "Scan");
        ScanResult r = AlbumScanner.scan(d);
        assertEquals(1, r.missing.size());
        assertFalse(r.missing.get(0).sharedIso);                      // Václav's layout: folder.jpg + Scan/ as before
        assertEquals(iso, r.missing.get(0).firstAudio);
    }

    @Test public void aSharedIsoCoverIsSavedUnderItsOwnName() throws Exception {
        File d = Files.createTempDirectory("dm").toFile();
        File iso = touch(d, "Mute - Speak & Spell.iso", 10);
        touch(d, "Mute Records - Ultra.iso", 10);
        Files.write(new File(d, "folder.jpg").toPath(), JPEG);
        Album a = new Album(d, 1, iso, null, false, true);
        File log = new File(d, "run.log");
        File saved = ArtWriter.save(a, JPEG, "test", log);
        assertNotNull(saved);                                         // a neighbour's folder.jpg does not block it
        assertEquals("Mute - Speak & Spell.jpg", saved.getName());
        assertNull(ArtWriter.save(a, JPEG, "test", log));             // never overwritten
        File extra = ExtraWriter.save(a, "back.jpg", JPEG);
        assertEquals(new File(new File(new File(d, "Scans"), "Mute - Speak & Spell"), "back.jpg"), extra);
        assertEquals(2, ExtraWriter.existingPictures(a).size());     // its own cover + its own back, not folder.jpg
    }

    @Test public void aFolderOfIsosNeverOpensAsOneAlbum() throws Exception {
        File d = Files.createTempDirectory("dm2").toFile();
        touch(d, "Mute - Speak & Spell.iso", 10);
        touch(d, "Mute Records - Ultra.iso", 10);
        FolderSummary s = FolderSummary.of(d);
        assertTrue(s.sharedIsos());
        assertFalse(s.isAlbum());                                     // scan it: each ISO its own album
        File v = Files.createTempDirectory("vh2").toFile();
        touch(v, "Communique.iso", 10);
        assertTrue(FolderSummary.of(v).isAlbum());                    // one ISO alone: an album as before
    }

    @Test public void violatorIsFoundByItsFileNameAndFolder() {
        // device 2026-11-25: 'No match for "Depeche Mode" by Violator, or "Violator" by 1990'
        File dm = new File("/s/ISO/Depeche Mode");
        Album a = new Album(dm, 1, new File(dm, "1990 - Violator (DMCD7 2006 SACD Remaster).iso"), null, false, true);
        assertArrayEquals(new String[]{"Depeche Mode", "Violator"}, FolderNameParser.guess(a));
        Tags swapped = new Tags("Depeche Mode", "Violator", null, null, false);   // the disc text, title/artist swapped
        java.util.List<String[]> terms = ArtLookup.searchTerms(a, swapped);
        boolean right = false, flipped = false;
        for (String[] x : terms) {
            if (x[0].equals("Depeche Mode") && x[1].equals("Violator")) right = true;
        }
        assertTrue(right);                                            // "Violator" by Depeche Mode is tried
    }

    @Test public void eaglesYearArtistAlbum() {
        File d = new File("/s/ISO/Eagles");
        Album a = new Album(d, 1, new File(d, "1976 - Eagles - Hotel California.iso"), null, false, false);
        assertArrayEquals(new String[]{"Eagles", "Hotel California"}, FolderNameParser.guess(a));
    }

    @Test public void aFolderOfIsosShowsItsAlbumsNotEmpty() throws Exception {
        File root = Files.createTempDirectory("pf").toFile();
        File pf = dir(root, "Pink Floyd");
        touch(pf, "Dark Side.iso", 10);
        touch(pf, "Wish You Were Here.iso", 10);
        touch(pf, "Animals.iso", 10);
        Files.write(new File(pf, "Animals.jpg").toPath(), JPEG);
        FolderSummary own = FolderSummary.of(pf);
        assertEquals(3, own.albums);                                  // was "Empty"
        assertEquals(2, own.missing);
        FolderSummary parent = FolderSummary.of(root);
        assertEquals(3, parent.albums);
        boolean cover = false;
        for (FolderSummary.Cell c : parent.mosaic) if (c.art != null && c.art.getName().equals("Animals.jpg")) cover = true;
        assertTrue(cover);                                            // the ISO's own cover shows on the folder tile
    }

    @Test public void aLeadingYearFolderTakesTheArtistFromAbove() {
        assertArrayEquals(new String[]{"Abba", "The Album"}, FolderNameParser.guess(new File("/s/Abba/1977 - The Album")));
        assertArrayEquals(new String[]{"Prince", "1999"}, FolderNameParser.guess(new File("/s/Prince - 1999")));
    }

    @Test public void dukeEllingtonGluedYear() {
        File d = new File("/s/ISO/Duke Ellington");
        Album a = new Album(d, 1, new File(d, "Duke Ellington - Supreme Jazz-2006 [SACD-R-ISO].iso"), null, false, false);
        assertArrayEquals(new String[]{"Duke Ellington", "Supreme Jazz"}, FolderNameParser.guess(a));
    }

    @Test public void anIsoRanksAlbumsBeforeSingles() throws Exception {
        File d = new File("/s/ISO/Eagles");
        Album iso = new Album(d, 1, new File(d, "1976 - Eagles - Hotel California.iso"), null, false, false);
        assertEquals(0, ArtLookup.tracksFor(iso));                    // unknown, not "1 track"
        Album flac = new Album(new File("/s/Eagles/Hotel California"), 9, new File("/s/x/01.flac"), null, false);
        assertEquals(9, ArtLookup.tracksFor(flac));
        org.json.JSONObject single = new org.json.JSONObject("{\"release-group\":{\"primary-type\":\"Single\"}}");
        org.json.JSONObject album = new org.json.JSONObject("{\"release-group\":{\"primary-type\":\"Album\"}}");
        assertEquals(1, ArtLookup.albumFirst(single));
        assertEquals(0, ArtLookup.albumFirst(album));
    }

    @Test public void anArtworkFolderDoesNotMakeAnIsoShared() throws Exception {
        // Michael's Pixies/Doolittle: Doolittle.iso + Artwork/ (booklet scans) = an ordinary album, folder.jpg + Scan/
        File d = Files.createTempDirectory("doolittle").toFile();
        touch(d, "Doolittle.iso", 10);
        File art = dir(d, "Artwork");
        Files.write(new File(art, "Booklet 02-03.jpg").toPath(), JPEG);
        ScanResult r = AlbumScanner.scan(d);
        assertEquals(1, r.missing.size() + r.withArt.size());
        Album a = r.missing.isEmpty() ? r.withArt.get(0) : r.missing.get(0);
        assertFalse(a.sharedIso);
        assertEquals("folder.jpg", a.coverName());
        assertTrue(FolderSummary.of(d).isAlbum());
    }

    @Test public void anExistingScanOrArtworkFolderIsReusedNeverDoubled() throws Exception {
        File v = Files.createTempDirectory("vh3").toFile();
        dir(v, "Scan");                                               // Václav's own folders stay Scan/
        assertEquals("Scan", ExtraWriter.scanDir(v).getName());
        File px = Files.createTempDirectory("px").toFile();
        dir(px, "Artwork");                                           // downloaded rips (Michael's Pixies)
        assertEquals("Artwork", ExtraWriter.scanDir(px).getName());
        File fresh = Files.createTempDirectory("new").toFile();
        assertEquals("Scans", ExtraWriter.scanDir(fresh).getName());  // nothing yet: the Scans convention
    }
}
