package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArtWriterTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 9, 9};
    private static final byte[] JPEG3 = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};

    @Test
    public void writesFolderJpgAndLogs() throws Exception {
        File dir = tmp.newFolder("Help");
        File log = new File(tmp.getRoot(), "run.log");
        File out = ArtWriter.save(dir, JPEG, "https://caa/x", log);
        assertEquals(new File(dir, "folder.jpg"), out);
        assertArrayEquals(JPEG, Files.readAllBytes(out.toPath()));
        assertFalse(new File(dir, "folder.jpg.tmp").exists());
        String line = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue(line.contains(out.getPath()));
        assertTrue(line.contains("https://caa/x"));
    }

    @Test
    public void skipsWhenAnyArtAppeared() throws Exception {
        File dir = tmp.newFolder("Rubber Soul");
        Files.write(new File(dir, "album.jpg").toPath(), JPEG3);
        assertNull(ArtWriter.save(dir, JPEG, "u", new File(tmp.getRoot(), "run.log")));
        assertFalse(new File(dir, "folder.jpg").exists());
    }

    @Test
    public void neverOverwritesFolderJpg() throws Exception {
        File dir = tmp.newFolder("Revolver");
        File existing = new File(dir, "folder.jpg");
        Files.write(existing.toPath(), new byte[]{1, 2, 3});
        assertNull(ArtWriter.save(dir, JPEG, "u", new File(tmp.getRoot(), "run.log")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing.toPath()));
    }

    /** Miss Perfumado on Michael's A6: a 0-byte album.jpg. It must not block the fetch, and stays as it was. */
    @Test
    public void emptyAlbumJpgDoesNotBlock() throws Exception {
        File dir = tmp.newFolder("Miss Perfumado");
        File empty = new File(dir, "album.jpg");
        assertTrue(empty.createNewFile());
        File out = ArtWriter.save(dir, JPEG, "u", new File(tmp.getRoot(), "run.log"));
        assertEquals(new File(dir, "folder.jpg"), out);
        assertTrue(empty.exists());
        assertEquals(0, empty.length());
    }

    /** Diana Krall / Mellon Collie: a PDF booklet named album.jpg. */
    @Test
    public void pdfNamedJpgDoesNotBlock() throws Exception {
        File dir = tmp.newFolder("The Girl in the Other Room");
        Files.write(new File(dir, "album.jpg").toPath(), "%PDF-1.3 booklet".getBytes(StandardCharsets.US_ASCII));
        assertNotNull(ArtWriter.save(dir, JPEG, "u", new File(tmp.getRoot(), "run.log")));
    }
}
