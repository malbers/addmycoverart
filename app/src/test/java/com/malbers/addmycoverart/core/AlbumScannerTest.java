package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AlbumScannerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File dir(String path, String... files) throws Exception {
        File d = new File(tmp.getRoot(), path);
        assertTrue(d.isDirectory() || d.mkdirs());
        for (String f : files) assertTrue(new File(d, f).createNewFile());
        return d;
    }

    @Test
    public void classifiesAlbums() throws Exception {
        dir("Beatles/Help", "01.flac", "02.flac", "folder.jpg");
        dir("Beatles/Rubber Soul", "01.flac", "ALBUM.PNG");
        dir("Beatles/Revolver", "01.flac", "02.flac", "03.flac");
        dir("Notes", "notes.txt");
        ScanResult r = AlbumScanner.scan(tmp.getRoot());
        assertEquals(2, r.withArt.size());
        assertEquals(1, r.missing.size());
        Album rev = r.missing.get(0);
        assertEquals("Revolver", rev.dir.getName());
        assertEquals(3, rev.trackCount);
        assertEquals("01.flac", rev.firstAudio.getName());
        assertFalse(rev.hasArt());
        assertEquals("folder.jpg", r.withArt.get(0).existingArt);
    }

    @Test
    public void artOnlyInsideTracksStillCountsAsMissing() throws Exception {
        File d = dir("Roy Orbison/Best Of");
        java.nio.file.Files.write(new File(d, "01.flac").toPath(),
                TagReaderTest.flacWithPictures(3, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 7}));
        ScanResult r = AlbumScanner.scan(tmp.getRoot());
        assertEquals(1, r.missing.size());
        assertTrue(r.missing.get(0).embeddedArt);
    }

    @Test
    public void discFoldersAreSeparateAlbums() throws Exception {
        dir("The Beatles/1967-1970/Disc 1", "01.mp3");
        dir("The Beatles/1967-1970/Disc 2", "01.mp3");
        assertEquals(2, AlbumScanner.scan(tmp.getRoot()).missing.size());
    }

    @Test
    public void hiddenFoldersSkippedAndMissingRootIsEmpty() throws Exception {
        dir(".thumbnails", "x.flac");
        assertEquals(0, AlbumScanner.scan(tmp.getRoot()).missing.size());
        assertEquals(0, AlbumScanner.scan(new File(tmp.getRoot(), "nope")).missing.size());
    }

    @Test
    public void names() {
        assertTrue(AlbumScanner.isArtName("Folder.JPEG"));
        assertTrue(AlbumScanner.isArtName("front.png"));
        assertFalse(AlbumScanner.isArtName("folder-off.jpg"));
        assertFalse(AlbumScanner.isArtName("back.jpg"));
        assertTrue(AlbumScanner.isAudioName("x.DSF"));
        assertFalse(AlbumScanner.isAudioName("x.cue"));
    }
}
