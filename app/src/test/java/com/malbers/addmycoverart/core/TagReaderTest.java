package com.malbers.addmycoverart.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TagReaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ---- FLAC builders ----

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] vorbis(String... comments) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] vendor = "test".getBytes(StandardCharsets.UTF_8);
        b.write(le32(vendor.length));
        b.write(vendor);
        b.write(le32(comments.length));
        for (String c : comments) {
            byte[] s = c.getBytes(StandardCharsets.UTF_8);
            b.write(le32(s.length));
            b.write(s);
        }
        return b.toByteArray();
    }

    private static void block(ByteArrayOutputStream out, int type, boolean last, byte[] data) {
        out.write((last ? 0x80 : 0) | type);
        out.write(data.length >> 16);
        out.write(data.length >> 8);
        out.write(data.length);
        out.write(data, 0, data.length);
    }

    private File write(String name, byte[] bytes) throws Exception {
        File f = tmp.newFile(name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
        return f;
    }

    @Test
    public void flacVorbisAndPicture() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 0, false, new byte[34]); // STREAMINFO
        block(out, 4, false, vorbis("ALBUM=Help!", "ARTIST=The Beatles", "MUSICBRAINZ_ALBUMID=abc"));
        block(out, 6, true, pictureBlock(3, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1})); // PICTURE
        Tags t = TagReader.read(write("a.flac", out.toByteArray()));
        assertEquals("Help!", t.album);
        assertEquals("The Beatles", t.artist);
        assertEquals("abc", t.mbAlbumId);
        assertTrue(t.hasPicture);
    }

    @Test
    public void flacKeysCaseInsensitive() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 4, true, vorbis("album=x", "albumartist=y"));
        Tags t = TagReader.read(write("b.flac", out.toByteArray()));
        assertEquals("x", t.album);
        assertEquals("y", t.albumArtist);
        assertFalse(t.hasPicture);
    }

    // ---- ID3 builders ----

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] syncsafe(int v) {
        return new byte[]{(byte) ((v >> 21) & 0x7f), (byte) ((v >> 14) & 0x7f), (byte) ((v >> 7) & 0x7f), (byte) (v & 0x7f)};
    }

    private static byte[] frame(int major, String id, byte[] data) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id.getBytes(StandardCharsets.US_ASCII));
        b.write(major == 4 ? syncsafe(data.length) : be32(data.length));
        b.write(new byte[2]);
        b.write(data);
        return b.toByteArray();
    }

    private static byte[] text(int enc, String s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(enc);
        if (enc == 0) b.write(s.getBytes(StandardCharsets.ISO_8859_1));
        else if (enc == 1) { b.write(0xFF); b.write(0xFE); b.write(s.getBytes(StandardCharsets.UTF_16LE)); }
        else if (enc == 2) b.write(s.getBytes(StandardCharsets.UTF_16BE));
        else b.write(s.getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    private static byte[] txxx(int enc, String desc, String value) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(enc);
        b.write(desc.getBytes(StandardCharsets.UTF_8));
        b.write(0);
        b.write(value.getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    private static byte[] id3(int major, byte[]... frames) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : frames) body.write(f);
        body.write(new byte[16]); // padding
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("ID3".getBytes(StandardCharsets.US_ASCII));
        out.write(major);
        out.write(0);
        out.write(0);
        out.write(syncsafe(body.size()));
        out.write(body.toByteArray());
        out.write(new byte[64]); // fake audio
        return out.toByteArray();
    }

    @Test
    public void id3v23Frames() throws Exception {
        byte[] f = id3(3,
                frame(3, "TALB", text(0, "Rubber Soul")),
                frame(3, "TPE1", text(1, "The Beatles")),
                frame(3, "TPE2", text(1, "Beatles")),
                frame(3, "TXXX", txxx(3, "MusicBrainz Album Id", "mb-123")),
                frame(3, "APIC", apic(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1})));
        Tags t = TagReader.read(write("c.mp3", f));
        assertEquals("Rubber Soul", t.album);
        assertEquals("The Beatles", t.artist);
        assertEquals("Beatles", t.albumArtist);
        assertEquals("mb-123", t.mbAlbumId);
        assertTrue(t.hasPicture);
    }

    @Test
    public void id3v24SyncsafeSizes() throws Exception {
        byte[] f = id3(4,
                frame(4, "TALB", text(3, "Björk Album")),
                frame(4, "TPE1", text(2, "Björk")));
        Tags t = TagReader.read(write("d.mp3", f));
        assertEquals("Björk Album", t.album);
        assertEquals("Björk", t.artist);
        assertFalse(t.hasPicture);
    }

    static byte[] pictureBlock(int type, byte[] image) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(be32(type));
        byte[] mime = "image/jpeg".getBytes(StandardCharsets.US_ASCII);
        b.write(be32(mime.length));
        b.write(mime);
        b.write(be32(0)); // description
        b.write(new byte[16]); // width, height, depth, colours
        b.write(be32(image.length));
        b.write(image);
        return b.toByteArray();
    }

    /** A FLAC whose only metadata is the given pictures, as {type, bytes} pairs. */
    static byte[] flacWithPictures(Object... typeAndImage) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 0, false, new byte[34]);
        for (int i = 0; i < typeAndImage.length; i += 2) {
            block(out, 6, i + 2 >= typeAndImage.length, pictureBlock((Integer) typeAndImage[i], (byte[]) typeAndImage[i + 1]));
        }
        return out.toByteArray();
    }

    @Test
    public void flacPicturePrefersFrontCover() throws Exception {
        byte[] back = {(byte) 0xFF, (byte) 0xD8, 'b'};
        byte[] front = {(byte) 0xFF, (byte) 0xD8, 'f'};
        assertArrayEquals(front, TagReader.readPicture(write("p.flac", flacWithPictures(4, back, 3, front))));
        assertArrayEquals(back, TagReader.readPicture(write("q.flac", flacWithPictures(4, back))));
    }

    private static byte[] apic(byte[] img) throws Exception {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.write(0);
        a.write("image/jpeg".getBytes(StandardCharsets.US_ASCII));
        a.write(0);
        a.write(3);
        a.write(0); // empty description
        a.write(img);
        return a.toByteArray();
    }

    @Test
    public void emptyPicturePlaceholdersAreNotPictures() throws Exception {
        // Byte-for-byte a 17-byte APIC placeholder seen in real MP3s: header, no image.
        byte[] placeholder = {0, 'i', 'm', 'a', 'g', 'e', '/', 'j', 'p', 'g', 0, 0, 0, 0, 0, 0, 0};
        File f = write("empty.mp3", id3(3, frame(3, "APIC", placeholder), frame(3, "TALB", text(0, "Talkie Walkie"))));
        assertFalse(TagReader.read(f).hasPicture);
        assertEquals("Talkie Walkie", TagReader.read(f).album);
        assertNull(TagReader.readPicture(f));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        block(out, 6, true, pictureBlock(3, new byte[0]));
        assertFalse(TagReader.read(write("empty.flac", out.toByteArray())).hasPicture);
    }

    @Test
    public void id3PictureBytes() throws Exception {
        ByteArrayOutputStream apic = new ByteArrayOutputStream();
        apic.write(0);
        apic.write("image/jpeg".getBytes(StandardCharsets.US_ASCII));
        apic.write(0);
        apic.write(3);
        apic.write("cover".getBytes(StandardCharsets.US_ASCII));
        apic.write(0);
        byte[] img = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        apic.write(img);
        assertArrayEquals(img, TagReader.readPicture(write("r.mp3", id3(3, frame(3, "APIC", apic.toByteArray())))));
    }

    @Test
    public void noPictureIsNull() throws Exception {
        assertNull(TagReader.readPicture(write("s.mp3", id3(3, frame(3, "TALB", text(0, "x"))))));
        assertNull(TagReader.readPicture(new File(tmp.getRoot(), "none.flac")));
    }

    @Test
    public void garbageAndTruncated() throws Exception {
        byte[] junk = new byte[500];
        new Random(1).nextBytes(junk);
        assertNull(TagReader.read(write("e.flac", junk)).album);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.write(0x04);
        out.write(new byte[]{0, 0x10, 0}); // claims 4 KB, has 3 bytes
        out.write(new byte[]{1, 2, 3});
        assertNull(TagReader.read(write("f.flac", out.toByteArray())).album);

        assertNull(TagReader.read(write("g.mp3", new byte[]{'I', 'D', '3', 2, 0, 0, 0, 0, 0, 10})).album);
        assertNull(TagReader.read(new File(tmp.getRoot(), "missing.flac")).album);
    }

    // ---- 1.1.1: DSF, ID3v2.2, ID3v1, APEv2, MP4 ----

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2};

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    @Test
    public void dsfReadsTheId3TagAtTheEnd() throws Exception {
        byte[] tag = id3(3, frame(3, "TALB", text(0, "Consequence Of Chaos")), frame(3, "TPE1", text(0, "Al Di Meola")),
                frame(3, "APIC", apic(JPEG)));
        byte[] audio = new byte[200];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("DSD ".getBytes(StandardCharsets.US_ASCII));
        out.write(le64(28));
        out.write(le64(28 + audio.length + tag.length));
        out.write(le64(28 + audio.length)); // metadata pointer
        out.write(audio);
        out.write(tag);
        File f = write("t.dsf", out.toByteArray());
        Tags t = TagReader.read(f);
        assertEquals("Consequence Of Chaos", t.album);
        assertEquals("Al Di Meola", t.artist);
        assertTrue(t.hasPicture);
        assertNotNull(TagReader.readPicture(f));
    }

    @Test
    public void dsfWithoutMetadataIsEmpty() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("DSD ".getBytes(StandardCharsets.US_ASCII));
        out.write(le64(28));
        out.write(le64(300));
        out.write(le64(0));
        out.write(new byte[272]);
        assertNull(TagReader.read(write("u.dsf", out.toByteArray())).album);
    }

    private static byte[] frame22(String id, byte[] data) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id.getBytes(StandardCharsets.US_ASCII));
        b.write(data.length >> 16);
        b.write(data.length >> 8);
        b.write(data.length);
        b.write(data);
        return b.toByteArray();
    }

    @Test
    public void id3v22OldItunesFrames() throws Exception {
        ByteArrayOutputStream pic = new ByteArrayOutputStream();
        pic.write(0);
        pic.write("JPG".getBytes(StandardCharsets.US_ASCII));
        pic.write(3); // front cover
        pic.write(0); // empty description
        pic.write(JPEG);
        File f = write("v22.mp3", id3(2, frame22("TAL", text(0, "Midnight Vultures")), frame22("TP1", text(1, "Beck")),
                frame22("PIC", pic.toByteArray())));
        Tags t = TagReader.read(f);
        assertEquals("Midnight Vultures", t.album);
        assertEquals("Beck", t.artist);
        assertTrue(t.hasPicture);
        assertArrayEquals(JPEG, TagReader.readPicture(f));
    }

    private static byte[] id3v1(String artist, String album, int track) {
        byte[] d = new byte[128];
        d[0] = 'T'; d[1] = 'A'; d[2] = 'G';
        byte[] a = artist.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(a, 0, d, 33, a.length);
        byte[] b = album.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(b, 0, d, 63, b.length);
        d[126] = (byte) track;
        return d;
    }

    @Test
    public void id3v1OnlyMp3() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0});
        out.write(new byte[300]);
        out.write(id3v1("Buena Vista Social Club", "Buena Vista Social Club", 4));
        Tags t = TagReader.read(write("v1.mp3", out.toByteArray()));
        assertEquals("Buena Vista Social Club", t.album);
        assertEquals("Buena Vista Social Club", t.artist);
        assertEquals(Integer.valueOf(4), TagReader.readTrack(write("v1b.mp3", out.toByteArray())).track);
    }

    @Test
    public void id3v1FillsWhatId3v2Lacks() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id3(3, frame(3, "TPE1", text(0, "Coldplay"))));
        out.write(id3v1("Cold play", "Parachutes", 1));
        Tags t = TagReader.read(write("mix.mp3", out.toByteArray()));
        assertEquals("Coldplay", t.artist); // ID3v2 wins
        assertEquals("Parachutes", t.album); // ID3v1 fills the gap
    }

    private static byte[] apeItem(String key, int flags, byte[] value) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(le32(value.length));
        b.write(le32(flags));
        b.write(key.getBytes(StandardCharsets.US_ASCII));
        b.write(0);
        b.write(value);
        return b.toByteArray();
    }

    private static byte[] apeTag(byte[]... items) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] i : items) body.write(i);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(body.toByteArray());
        out.write("APETAGEX".getBytes(StandardCharsets.US_ASCII));
        out.write(le32(2000));
        out.write(le32(body.size() + 32));
        out.write(le32(items.length));
        out.write(le32(0));
        out.write(new byte[8]);
        return out.toByteArray();
    }

    @Test
    public void apeTagInMonkeysAudio() throws Exception {
        ByteArrayOutputStream cover = new ByteArrayOutputStream();
        cover.write("cover.jpg".getBytes(StandardCharsets.US_ASCII));
        cover.write(0);
        cover.write(JPEG);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("MAC ".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[200]);
        out.write(apeTag(
                apeItem("Artist", 0, "After Forever".getBytes(StandardCharsets.UTF_8)),
                apeItem("Album", 0, "After Forever".getBytes(StandardCharsets.UTF_8)),
                apeItem("Track", 0, "3/11".getBytes(StandardCharsets.UTF_8)),
                apeItem("Cover Art (Front)", 2, cover.toByteArray())));
        File f = write("a.ape", out.toByteArray());
        Tags t = TagReader.read(f);
        assertEquals("After Forever", t.album);
        assertEquals("After Forever", t.artist);
        assertTrue(t.hasPicture);
        assertArrayEquals(JPEG, TagReader.readPicture(f));
        assertEquals(Integer.valueOf(3), TagReader.readTrack(f).track);
    }

    @Test
    public void apeTagBeforeId3v1InWavPack() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("wvpk".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[100]);
        out.write(apeTag(apeItem("Album Artist", 0, "Alan Parsons Project".getBytes(StandardCharsets.UTF_8)),
                apeItem("Album", 0, "Eye In The Sky".getBytes(StandardCharsets.UTF_8))));
        out.write(id3v1("Other", "Other", 0));
        Tags t = TagReader.read(write("w.wv", out.toByteArray()));
        assertEquals("Eye In The Sky", t.album);
        assertEquals("Alan Parsons Project", t.bestArtist());
    }

    private static byte[] atom(String type, byte[]... children) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : children) body.write(c);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(be32(body.size() + 8));
        b.write(type.getBytes(StandardCharsets.ISO_8859_1));
        b.write(body.toByteArray());
        return b.toByteArray();
    }

    private static byte[] data(int type, byte[] payload) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(be32(type));
        b.write(new byte[4]);
        b.write(payload);
        return atom("data", b.toByteArray());
    }

    @Test
    public void m4aItunesAtoms() throws Exception {
        byte[] ilst = atom("ilst",
                atom("©alb", data(1, "Parachutes".getBytes(StandardCharsets.UTF_8))),
                atom("©ART", data(1, "Coldplay".getBytes(StandardCharsets.UTF_8))),
                atom("trkn", data(0, new byte[]{0, 0, 0, 5, 0, 10, 0, 0})),
                atom("----", atom("mean", new byte[4], "com.apple.iTunes".getBytes(StandardCharsets.UTF_8)),
                        atom("name", new byte[4], "MusicBrainz Album Id".getBytes(StandardCharsets.UTF_8)),
                        data(1, "mb-m4a".getBytes(StandardCharsets.UTF_8))),
                atom("covr", data(13, JPEG)));
        byte[] meta = atom("meta", new byte[4], atom("hdlr", new byte[25]), ilst);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(atom("ftyp", "M4A ".getBytes(StandardCharsets.US_ASCII), new byte[4]));
        out.write(atom("mdat", new byte[300])); // audio before moov, as many encoders write it
        out.write(atom("moov", atom("mvhd", new byte[100]), atom("udta", meta)));
        File f = write("p.m4a", out.toByteArray());
        Tags t = TagReader.read(f);
        assertEquals("Parachutes", t.album);
        assertEquals("Coldplay", t.artist);
        assertEquals("mb-m4a", t.mbAlbumId);
        assertTrue(t.hasPicture);
        assertArrayEquals(JPEG, TagReader.readPicture(f));
        assertEquals(Integer.valueOf(5), TagReader.readTrack(f).track);
        assertEquals(Integer.valueOf(10), TagReader.readTrack(f).trackTotal);
    }
}
