package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FolderSummaryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File dir(String path, String... files) throws Exception {
        File d = new File(tmp.getRoot(), path);
        assertTrue(d.isDirectory() || d.mkdirs());
        for (String f : files) {
            File x = new File(d, f);
            // picture names get real JPEG bytes: an empty art file no longer counts as a cover (1.1.0)
            if (AlbumScanner.isArtName(f)) java.nio.file.Files.write(x.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
            else assertTrue(x.createNewFile());
        }
        return d;
    }

    @Test
    public void artistFolderCountsAlbumsAndMissing() throws Exception {
        dir("Agnes Obel/Aventine", "01.flac", "Folder.JPG");
        dir("Agnes Obel/Citizen of Glass", "01.flac", "02.flac");
        dir("Agnes Obel/Philharmonics", "01.flac", "cover.jpg");
        dir("Agnes Obel/Scans");
        FolderSummary s = FolderSummary.of(new File(tmp.getRoot(), "Agnes Obel"));
        assertFalse(s.isAlbum());
        assertEquals(4, s.subfolders);
        assertEquals(3, s.albums);
        assertEquals(1, s.missing);
        assertEquals(3, s.mosaic.size());
        assertEquals("Folder.JPG", s.mosaic.get(0).art.getName());
        assertNull(s.mosaic.get(1).art); // Citizen of Glass: blank cell
        assertEquals("cover.jpg", s.mosaic.get(2).art.getName());
    }

    @Test
    public void albumFolderIsAnAlbum() throws Exception {
        File d = dir("Help", "02.flac", "01.flac", "front.jpg", "cover.jpg", "notes.txt");
        FolderSummary s = FolderSummary.of(d);
        assertTrue(s.isAlbum());
        assertEquals(2, s.tracks);
        assertEquals("01.flac", s.firstAudio.getName());
        assertEquals("cover.jpg", s.art.getName()); // cover before front
        Album a = s.toAlbum();
        assertTrue(a.hasArt());
        assertEquals(2, a.trackCount);
    }

    @Test
    public void emptyFolder() throws Exception {
        File d = dir("New Rips", "readme.txt");
        FolderSummary s = FolderSummary.of(d);
        assertTrue(s.isEmpty());
        assertEquals(0, s.mosaic.size());
    }

    @Test
    public void rootMosaicLooksInsideArtistFolders() throws Exception {
        dir("Music/Agnes Obel/Aventine", "01.flac", "folder.jpg");
        dir("Music/Agnes Obel/Citizen", "01.flac");
        dir("Music/Beatles/Help", "01.flac", "folder.jpg");
        dir("Music/Beatles/Abbey Road", "01.flac", "folder.jpg");
        dir("Music/Beatles/Revolver", "01.flac", "folder.jpg");
        FolderSummary s = FolderSummary.of(new File(tmp.getRoot(), "Music"));
        assertEquals(2, s.subfolders);
        assertEquals(0, s.albums); // none directly inside
        assertEquals(4, s.mosaic.size());
    }

    @Test
    public void bestArtOrder() {
        assertEquals("folder.jpg", AlbumScanner.bestArtFile(new String[]{"front.jpg", "cover.jpg", "folder.jpg"}));
        assertEquals("Cover.jpg", AlbumScanner.bestArtFile(new String[]{"Cover.png", "Cover.jpg"}));
        assertEquals("album.jpg", AlbumScanner.bestArtFile(new String[]{"01.flac", "album.jpg"}));
        assertNull(AlbumScanner.bestArtFile(new String[]{"back.jpg", "01.flac"}));
    }

    /** Found on Michael's A6: 0-byte album.jpg (Miss Perfumado, Decade) and PDF booklets named album.jpg (Diana Krall). */
    @Test
    public void emptyOrPdfArtIsNoCover() throws Exception {
        File empty = dir("Cesaria Evora/Miss Perfumado", "01.flac");
        assertTrue(new File(empty, "album.jpg").createNewFile());
        File pdf = dir("Diana Krall/The Girl in the Other Room", "01.flac");
        java.nio.file.Files.write(new File(pdf, "album.jpg").toPath(), "%PDF-1.3\n".getBytes("US-ASCII"));

        assertEquals("is empty", AlbumScanner.artProblem(new File(empty, "album.jpg")));
        assertEquals("isn't a picture (PDF)", AlbumScanner.artProblem(new File(pdf, "album.jpg")));
        assertNull(AlbumScanner.findArtFile(empty));

        ScanResult r = AlbumScanner.scan(tmp.getRoot());
        assertEquals(2, r.missing.size());
        assertEquals(0, r.withArt.size());
        assertNull(FolderSummary.of(empty).art);
        assertEquals(1, FolderSummary.of(new File(tmp.getRoot(), "Cesaria Evora")).missing);
        assertEquals("album.jpg", AlbumScanner.badArtFiles(pdf).get(0)[0]);
    }

    @Test
    public void realPngAndJpgCount() throws Exception {
        File d = dir("X/Y", "01.flac");
        java.nio.file.Files.write(new File(d, "cover.png").toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10});
        assertNull(AlbumScanner.artProblem(new File(d, "cover.png")));
        assertEquals("cover.png", AlbumScanner.findArtFile(d));
        java.nio.file.Files.write(new File(d, "folder.jpg").toPath(), new byte[]{'G', 'I', 'F', '8'});
        assertEquals("isn't a picture", AlbumScanner.artProblem(new File(d, "folder.jpg")));
        assertEquals("cover.png", AlbumScanner.bestArtFile(d, d.list()));
    }

    /** TestsSongs on Michael's A6: loose tracks beside album folders = a folder; an album with Scan/ stays an album. */
    @Test
    public void looseTracksBesideAlbumsIsAFolder() throws Exception {
        File t = dir("TestsSongs", "01 ALAC 24-96.m4a", "02 AAC 256k.m4a");
        dir("TestsSongs/EverSignal EQ test", "01.flac");
        dir("TestsSongs/BINI", "01.m4a", "folder.jpg");
        FolderSummary s = FolderSummary.of(t);
        assertFalse(s.isAlbum());
        assertEquals(2, s.albums);
        assertEquals(2, s.tracks);
        File v = dir("Vaclav/Four Seasons", "01.flac", "folder.jpg");
        dir("Vaclav/Four Seasons/Scan", "booklet-01.jpg");
        assertTrue(FolderSummary.of(v).isAlbum());
    }

    /** 1.1.0 badges: pictures in an album's Scan folder, and how many albums in a folder have any. */
    @Test
    public void extraArtCounts() throws Exception {
        dir("Agnes Obel/Aventine", "01.flac", "folder.jpg");
        dir("Agnes Obel/Aventine/Scan", "back.jpg", "booklet-01.jpg", "notes.txt");
        dir("Agnes Obel/Citizen of Glass", "01.flac", "folder.jpg");
        dir("Agnes Obel/Philharmonics", "01.flac");
        dir("Agnes Obel/Philharmonics/SCANS", "tray.png");
        FolderSummary album = FolderSummary.of(new File(tmp.getRoot(), "Agnes Obel/Aventine"));
        assertTrue(album.isAlbum());
        assertEquals(2, album.extras);
        assertEquals(2, FolderSummary.extrasOf(new File(tmp.getRoot(), "Agnes Obel/Aventine")));
        FolderSummary artist = FolderSummary.of(new File(tmp.getRoot(), "Agnes Obel"));
        assertEquals(3, artist.albums);
        assertEquals(2, artist.albumsWithExtras);
        assertEquals(0, FolderSummary.of(new File(tmp.getRoot(), "Agnes Obel/Citizen of Glass")).extras);
    }

    @Test
    public void liveScanReportsAndStops() throws Exception {
        dir("A/One", "01.flac");
        dir("A/Two", "01.flac", "folder.jpg");
        dir("A/Three", "01.flac");
        List<String> seen = new ArrayList<>();
        AlbumScanner.scan(new File(tmp.getRoot(), "A"), a -> {
            seen.add(a.dir.getName());
            return seen.size() == 2; // stop after the second
        });
        assertEquals(2, seen.size());
    }
}
