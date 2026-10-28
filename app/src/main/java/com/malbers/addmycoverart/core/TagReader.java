package com.malbers.addmycoverart.core;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads album tags and "has embedded picture" from the header of a FLAC or MP3 (ID3v2.3/2.4) file.
 * Reads metadata only, never audio, and never throws: anything unreadable comes back as {@link Tags#EMPTY}.
 */
public final class TagReader {
    private static final int MAX_METADATA = 4 * 1024 * 1024;

    private TagReader() {}

    public static Tags read(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') return flac(in);
            if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') return id3(in, magic[3] & 0xff);
        } catch (IOException | RuntimeException ignored) {
            // fall through: unreadable or truncated
        }
        return Tags.EMPTY;
    }

    /** The embedded front cover (else the first picture) as image bytes, or null. Never throws. */
    public static byte[] readPicture(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') return flacPicture(in);
            if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') return id3Picture(in, magic[3] & 0xff);
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return null;
    }

    private static final int FRONT_COVER = 3;
    private static final int MAX_PICTURE_METADATA = 16 * 1024 * 1024;

    private static byte[] flacPicture(DataInputStream in) throws IOException {
        byte[] first = null;
        int total = 0;
        while (true) {
            int header = in.readUnsignedByte();
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7f;
            int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            total += len;
            if (total > MAX_PICTURE_METADATA) break;
            if (type == 6) {
                byte[] d = new byte[len];
                in.readFully(d);
                // PICTURE: type, mime, description, width/height/depth/colours (16 bytes), data.
                int p = 4;
                p += 4 + be32(d, p);
                p += 4 + be32(d, p);
                p += 16;
                int dataLen = be32(d, p);
                p += 4;
                if (dataLen > 0 && p + dataLen <= d.length) {
                    byte[] pic = Arrays.copyOfRange(d, p, p + dataLen);
                    if (be32(d, 0) == FRONT_COVER) return pic;
                    if (first == null) first = pic;
                }
            } else {
                skip(in, len);
            }
            if (last) break;
        }
        return first;
    }

    private static byte[] id3Picture(DataInputStream in, int major) throws IOException {
        if (major != 3 && major != 4) return null;
        in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int size = syncsafe(in.readInt());
        if (size > MAX_PICTURE_METADATA) return null;
        byte[] tag = new byte[size];
        in.readFully(tag);
        int p = 0;
        if ((flags & 0x40) != 0) p += major == 4 ? syncsafe(be32(tag, 0)) : be32(tag, 0) + 4;
        byte[] first = null;
        while (p + 10 <= tag.length && tag[p] != 0) {
            String id = new String(tag, p, 4, StandardCharsets.US_ASCII);
            int len = major == 4 ? syncsafe(be32(tag, p + 4)) : be32(tag, p + 4);
            p += 10;
            if (len < 0 || p + len > tag.length) break;
            if (id.equals("APIC")) {
                int q = apicDataStart(tag, p, len);
                if (q >= 0) {
                    byte[] pic = Arrays.copyOfRange(tag, q, p + len);
                    if (apicType(tag, p, len) == FRONT_COVER) return pic;
                    if (first == null) first = pic;
                }
            }
            p += len;
        }
        return first;
    }

    /** Offset of the image bytes in an APIC frame body at {@code p}, or -1 when the frame holds no image (seen: 17-byte placeholders). */
    private static int apicDataStart(byte[] tag, int p, int len) {
        // APIC: encoding, mime\0, picture type, description (terminator depends on encoding), data.
        int end = p + len;
        int enc = tag[p];
        int q = p + 1;
        while (q < end && tag[q] != 0) q++;
        q += 2; // mime terminator + picture type
        if (enc == 1 || enc == 2) {
            while (q + 1 < end && !(tag[q] == 0 && tag[q + 1] == 0)) q += 2;
            q += 2;
        } else {
            while (q < end && tag[q] != 0) q++;
            q++;
        }
        return q < end && ArtLookup.isImage(Arrays.copyOfRange(tag, q, Math.min(end, q + 4))) ? q : -1;
    }

    private static int apicType(byte[] tag, int p, int len) {
        int q = p + 1;
        while (q < p + len && tag[q] != 0) q++;
        return q + 1 < p + len ? tag[q + 1] & 0xff : -1;
    }

    /** Reads a FLAC PICTURE block's header from the stream and skips the rest; true when it holds image bytes. */
    private static boolean flacPictureHasData(DataInputStream in, int len) throws IOException {
        int used = 4;
        in.readInt(); // picture type
        int mime = in.readInt();
        skip(in, mime);
        int desc = in.readInt();
        skip(in, desc);
        skip(in, 16);
        int dataLen = in.readInt();
        used += 4 + mime + 4 + desc + 16 + 4;
        byte[] head = new byte[Math.min(4, Math.max(0, dataLen))];
        in.readFully(head);
        used += head.length;
        skip(in, len - used);
        return dataLen > 0 && ArtLookup.isImage(head);
    }

    // ---- FLAC ----

    private static Tags flac(DataInputStream in) throws IOException {
        Builder b = new Builder();
        int total = 0;
        while (true) {
            int header = in.readUnsignedByte();
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7f;
            int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            total += len;
            if (total > MAX_METADATA) break;
            if (type == 4) {
                byte[] data = new byte[len];
                in.readFully(data);
                vorbis(data, b);
            } else if (type == 6) {
                if (flacPictureHasData(in, len)) b.picture = true;
            } else {
                skip(in, len);
            }
            if (last) break;
        }
        return b.build();
    }

    private static void vorbis(byte[] d, Builder b) {
        int p = 0;
        int vendor = le32(d, p);
        p += 4 + vendor;
        int count = le32(d, p);
        p += 4;
        for (int i = 0; i < count && p + 4 <= d.length; i++) {
            int len = le32(d, p);
            p += 4;
            if (len < 0 || p + len > d.length) return;
            String c = new String(d, p, len, StandardCharsets.UTF_8);
            p += len;
            int eq = c.indexOf('=');
            if (eq > 0) b.put(c.substring(0, eq), c.substring(eq + 1));
        }
    }

    private static int le32(byte[] d, int p) {
        if (p + 4 > d.length) throw new IndexOutOfBoundsException();
        return (d[p] & 0xff) | (d[p + 1] & 0xff) << 8 | (d[p + 2] & 0xff) << 16 | (d[p + 3] & 0xff) << 24;
    }

    // ---- ID3v2 ----

    private static Tags id3(DataInputStream in, int major) throws IOException {
        if (major != 3 && major != 4) return Tags.EMPTY;
        in.readUnsignedByte(); // revision
        int flags = in.readUnsignedByte();
        int size = syncsafe(in.readInt());
        if (size > MAX_METADATA) return Tags.EMPTY;
        byte[] tag = new byte[size];
        in.readFully(tag);
        int p = 0;
        if ((flags & 0x40) != 0) { // extended header
            int ext = major == 4 ? syncsafe(be32(tag, 0)) : be32(tag, 0) + 4;
            p += ext;
        }
        Builder b = new Builder();
        while (p + 10 <= tag.length) {
            if (tag[p] == 0) break; // padding
            String id = new String(tag, p, 4, StandardCharsets.US_ASCII);
            int len = major == 4 ? syncsafe(be32(tag, p + 4)) : be32(tag, p + 4);
            p += 10;
            if (len < 0 || p + len > tag.length) break;
            switch (id) {
                case "TALB": b.put("ALBUM", text(tag, p, len)); break;
                case "TPE1": b.put("ARTIST", text(tag, p, len)); break;
                case "TPE2": b.put("ALBUMARTIST", text(tag, p, len)); break;
                case "APIC": if (apicDataStart(tag, p, len) >= 0) b.picture = true; break;
                case "TXXX": {
                    String[] kv = text(tag, p, len).split("\u0000", 2);
                    if (kv.length == 2 && kv[0].equalsIgnoreCase("MusicBrainz Album Id")) b.put("MUSICBRAINZ_ALBUMID", kv[1]);
                    break;
                }
                default: break;
            }
            p += len;
        }
        return b.build();
    }

    /** Decodes an ID3 text frame body; the result keeps NULs that separate multiple strings. */
    private static String text(byte[] d, int p, int len) {
        if (len < 1) return "";
        int enc = d[p];
        Charset cs;
        switch (enc) {
            case 1: cs = StandardCharsets.UTF_16; break; // BOM decides the byte order
            case 2: cs = StandardCharsets.UTF_16BE; break;
            case 3: cs = StandardCharsets.UTF_8; break;
            default: cs = StandardCharsets.ISO_8859_1;
        }
        String s = new String(d, p + 1, len - 1, cs);
        if (enc == 1 || enc == 2) {
            // UTF-16 string terminators decode to U+0000; a BOM after a separator decodes to U+FEFF.
            s = s.replace("﻿", "");
        }
        return trimNuls(s);
    }

    private static String trimNuls(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end);
    }

    private static int be32(byte[] d, int p) {
        return (d[p] & 0xff) << 24 | (d[p + 1] & 0xff) << 16 | (d[p + 2] & 0xff) << 8 | (d[p + 3] & 0xff);
    }

    private static int syncsafe(int v) {
        return (v & 0x7f) | ((v >> 8) & 0x7f) << 7 | ((v >> 16) & 0x7f) << 14 | ((v >> 24) & 0x7f) << 21;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("truncated");
                s = 1;
            }
            n -= s;
        }
    }

    private static final class Builder {
        String album, artist, albumArtist, mbid;
        boolean picture;

        void put(String key, String value) {
            if (value == null || value.trim().isEmpty()) return;
            String v = value.trim();
            switch (key.toUpperCase(Locale.ROOT)) {
                case "ALBUM": if (album == null) album = v; break;
                case "ARTIST": if (artist == null) artist = v; break;
                case "ALBUMARTIST": case "ALBUM ARTIST": case "ALBUM_ARTIST": if (albumArtist == null) albumArtist = v; break;
                case "MUSICBRAINZ_ALBUMID": if (mbid == null) mbid = v; break;
                default: break;
            }
        }

        Tags build() {
            return new Tags(album, artist, albumArtist, mbid, picture);
        }
    }
}
