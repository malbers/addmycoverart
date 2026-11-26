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
 * Reads album tags and "has embedded picture" from FLAC, MP3 (ID3v2.2-2.4, ID3v1), DSF, MP4/M4A and APEv2
 * (Monkey's Audio, WavPack) files.
 * Reads metadata only, never audio, and never throws: anything unreadable comes back as {@link Tags#EMPTY}.
 */
public final class TagReader {
    private static final int MAX_METADATA = 4 * 1024 * 1024;

    private TagReader() {}

    public static Tags read(File f) {
        Builder b = parse(f);
        return b == null ? Tags.EMPTY : b.build();
    }

    /** Track and disc numbers, FLAC playing time and the release ID, for the rip check. Never throws. */
    public static TrackInfo readTrack(File f) {
        Builder b = parse(f);
        return b == null ? TrackInfo.EMPTY : b.track();
    }

    // 1.1.1 (Václav H.'s DSF/APE library, Michael's old MP3s): tags are also read from DSF (ID3v2 at the end),
    // MP4/M4A (iTunes atoms), APEv2 footers (Monkey's Audio, WavPack, some MP3s), ID3v2.2 and ID3v1.
    private static final int KIND_NONE = 0, KIND_FLAC = 1, KIND_ID3 = 2, KIND_DSF = 3, KIND_MP4 = 4;

    private static int kind(File f) {
        byte[] h = new byte[8];
        int n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            while (n < 8) {
                int r = in.read(h, n, 8 - n);
                if (r < 0) break;
                n += r;
            }
        } catch (IOException | RuntimeException e) {
            return KIND_NONE;
        }
        if (n >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return KIND_FLAC;
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return KIND_ID3;
        if (n >= 4 && h[0] == 'D' && h[1] == 'S' && h[2] == 'D' && h[3] == ' ') return KIND_DSF;
        if (n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return KIND_MP4;
        return KIND_NONE;
    }

    private static Builder parse(File f) {
        Builder b = null;
        try {
            switch (kind(f)) {
                case KIND_FLAC: b = flacAt(f); break;
                case KIND_ID3: b = id3At(f, 0); break;
                case KIND_DSF: { long p = dsfMetadata(f); if (p > 0) b = id3At(f, p); break; }
                case KIND_MP4: b = Mp4.read(f, false, null); break;
                default: if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso")) b = sacdText(f); break;
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through: unreadable or truncated
        }
        // Tags at the end of the file fill whatever is still missing (Builder.put keeps the first value).
        try {
            b = fillFromApe(f, b);
        } catch (IOException | RuntimeException ignored) {
            // no APE tag
        }
        try {
            b = fillFromId3v1(f, b);
        } catch (IOException | RuntimeException ignored) {
            // no ID3v1 tag
        }
        return b;
    }

    /**
     * 1.1.2: an SACD ISO's own album title and artist, from the Master TOC text (sector 511, "SACDText", big-endian
     * offsets to Album_Title and Album_Artist). Checked on 17 of Michael's ISOs 2026-11-25: 15 clean, one with no text
     * (Boston), one with title and artist swapped on the disc (search falls back to the file name). Null when absent.
     */
    static Builder sacdText(File f) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            if (raf.length() < 512L * 2048) return null;
            byte[] id = new byte[8];
            raf.seek(510L * 2048); raf.readFully(id);
            if (!"SACDMTOC".equals(new String(id, StandardCharsets.ISO_8859_1))) return null;
            byte[] t = new byte[2048];
            raf.seek(511L * 2048); raf.readFully(t);
            return sacdText(t);
        }
    }

    /** The Master_Text sector itself (2048 bytes); package-visible for tests. */
    static Builder sacdText(byte[] t) {
        if (t.length < 32 || !"SACDText".equals(new String(t, 0, 8, StandardCharsets.ISO_8859_1))) return null;
        String title = sacdString(t, ((t[16] & 0xFF) << 8) | (t[17] & 0xFF));
        String artist = sacdString(t, ((t[18] & 0xFF) << 8) | (t[19] & 0xFF));
        if (title == null && artist == null) return null;
        Builder b = new Builder();
        if (title != null) b.put("ALBUM", title);
        if (artist != null) b.put("ARTIST", artist);
        return b;
    }

    private static String sacdString(byte[] t, int pos) {
        if (pos <= 0 || pos >= t.length) return null;
        int end = pos;
        while (end < t.length && t[end] != 0) end++;
        String s = new String(t, pos, end - pos, StandardCharsets.ISO_8859_1).trim();
        return s.isEmpty() ? null : s;
    }

    private static Builder flacAt(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            skip(in, 4);
            return flac(in);
        }
    }

    /** An ID3v2 tag starting at byte {@code offset} (0 for MP3, the metadata pointer for DSF). */
    private static Builder id3At(File f, long offset) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            skip(in, offset);
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'I' || magic[1] != 'D' || magic[2] != '3') return null;
            return id3(in, magic[3] & 0xff);
        }
    }

    /** DSF: the 8-byte little-endian pointer to the ID3v2 metadata chunk, at byte 20 of the DSD header. 0 when none. */
    private static long dsfMetadata(File f) throws IOException {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 28) return 0;
            r.seek(20);
            byte[] d = new byte[8];
            r.readFully(d);
            long p = 0;
            for (int i = 7; i >= 0; i--) p = p << 8 | (d[i] & 0xff);
            return p > 0 && p < r.length() - 10 ? p : 0;
        }
    }

    /** The embedded front cover (else the first picture) as image bytes, or null. Never throws. */
    public static byte[] readPicture(File f) {
        byte[] pic = null;
        try {
            switch (kind(f)) {
                case KIND_FLAC:
                    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                        skip(in, 4);
                        pic = flacPicture(in);
                    }
                    break;
                case KIND_ID3: pic = id3PictureAt(f, 0); break;
                case KIND_DSF: { long p = dsfMetadata(f); if (p > 0) pic = id3PictureAt(f, p); break; }
                case KIND_MP4: {
                    byte[][] out = new byte[1][];
                    Mp4.read(f, true, out);
                    pic = out[0];
                    break;
                }
                default: break;
            }
            if (pic == null) pic = apePicture(f);
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return pic;
    }

    private static byte[] id3PictureAt(File f, long offset) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            skip(in, offset);
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'I' || magic[1] != 'D' || magic[2] != '3') return null;
            return id3Picture(in, magic[3] & 0xff);
        }
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
        if (major < 2 || major > 4) return null;
        in.readUnsignedByte();
        int flags = in.readUnsignedByte();
        int size = syncsafe(in.readInt());
        if (size > MAX_PICTURE_METADATA) return null;
        byte[] tag = new byte[size];
        in.readFully(tag);
        int p = 0;
        if (major > 2 && (flags & 0x40) != 0) p += major == 4 ? syncsafe(be32(tag, 0)) : be32(tag, 0) + 4;
        byte[] first = null;
        int hs = major == 2 ? 6 : 10;
        while (p + hs <= tag.length && tag[p] != 0) {
            String id = new String(tag, p, major == 2 ? 3 : 4, StandardCharsets.US_ASCII);
            int len = frameSize(tag, p, major);
            p += hs;
            if (len < 0 || p + len > tag.length) break;
            if (id.equals("APIC") || id.equals("PIC")) {
                int q = id.equals("PIC") ? picDataStart(tag, p, len) : apicDataStart(tag, p, len);
                if (q >= 0) {
                    byte[] pic = Arrays.copyOfRange(tag, q, p + len);
                    int type = id.equals("PIC") ? (len > 4 ? tag[p + 4] & 0xff : -1) : apicType(tag, p, len);
                    if (type == FRONT_COVER) return pic;
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

    /** ID3v2.2 PIC: encoding, 3-char image format, picture type, description, data. -1 when no image. */
    private static int picDataStart(byte[] tag, int p, int len) {
        int end = p + len;
        int enc = tag[p];
        int q = p + 5;
        if (enc == 1 || enc == 2) {
            while (q + 1 < end && !(tag[q] == 0 && tag[q + 1] == 0)) q += 2;
            q += 2;
        } else {
            while (q < end && tag[q] != 0) q++;
            q++;
        }
        return q < end && ArtLookup.isImage(Arrays.copyOfRange(tag, q, Math.min(end, q + 4))) ? q : -1;
    }

    /** Frame body size: 3 bytes in ID3v2.2, syncsafe in 2.4, plain in 2.3. */
    private static int frameSize(byte[] tag, int p, int major) {
        if (major == 2) return (tag[p + 3] & 0xff) << 16 | (tag[p + 4] & 0xff) << 8 | (tag[p + 5] & 0xff);
        return major == 4 ? syncsafe(be32(tag, p + 4)) : be32(tag, p + 4);
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

    // ---- APEv2 (footer at the end, before an ID3v1 tag if there is one) ----

    /** {key, flags, value} for each item, or null when the file has no APEv2 tag. */
    private static java.util.List<Object[]> apeItems(File f) throws IOException {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long len = r.length();
            long end = len;
            if (len >= 128) {
                r.seek(len - 128);
                byte[] t = new byte[3];
                r.readFully(t);
                if (t[0] == 'T' && t[1] == 'A' && t[2] == 'G') end = len - 128;
            }
            if (end < 32) return null;
            r.seek(end - 32);
            byte[] ft = new byte[32];
            r.readFully(ft);
            if (!new String(ft, 0, 8, StandardCharsets.US_ASCII).equals("APETAGEX")) return null;
            int size = le32(ft, 12);
            int count = le32(ft, 16);
            if (size < 32 || size > MAX_PICTURE_METADATA || size > end) return null;
            byte[] d = new byte[size - 32];
            r.seek(end - size);
            r.readFully(d);
            java.util.List<Object[]> out = new java.util.ArrayList<>();
            int p = 0;
            for (int i = 0; i < count && p + 8 <= d.length; i++) {
                int vlen = le32(d, p);
                int flags = le32(d, p + 4);
                p += 8;
                int k = p;
                while (k < d.length && d[k] != 0) k++;
                String key = new String(d, p, k - p, StandardCharsets.US_ASCII);
                p = k + 1;
                if (vlen < 0 || p + vlen > d.length) break;
                out.add(new Object[]{key, flags, Arrays.copyOfRange(d, p, p + vlen)});
                p += vlen;
            }
            return out;
        }
    }

    private static Builder fillFromApe(File f, Builder b) throws IOException {
        java.util.List<Object[]> items = apeItems(f);
        if (items == null) return b;
        if (b == null) b = new Builder();
        for (Object[] it : items) {
            String key = (String) it[0];
            int flags = (Integer) it[1];
            byte[] v = (byte[]) it[2];
            if (((flags >> 1) & 3) == 0) {
                String s = new String(v, StandardCharsets.UTF_8);
                int nul = s.indexOf('\u0000'); // several values: the first one
                b.put(key, nul >= 0 ? s.substring(0, nul) : s);
            } else if (key.equalsIgnoreCase("Cover Art (Front)") && apeImageStart(v) >= 0) {
                b.picture = true;
            }
        }
        return b;
    }

    /** APE binary cover: "file name\0" then the image bytes. */
    private static int apeImageStart(byte[] v) {
        int z = 0;
        while (z < v.length && v[z] != 0) z++;
        z++;
        return z < v.length && ArtLookup.isImage(Arrays.copyOfRange(v, z, Math.min(v.length, z + 4))) ? z : -1;
    }

    private static byte[] apePicture(File f) throws IOException {
        java.util.List<Object[]> items = apeItems(f);
        if (items == null) return null;
        for (Object[] it : items) {
            if (!((String) it[0]).equalsIgnoreCase("Cover Art (Front)")) continue;
            byte[] v = (byte[]) it[2];
            int z = apeImageStart(v);
            if (z >= 0) return Arrays.copyOfRange(v, z, v.length);
        }
        return null;
    }

    // ---- ID3v1 (last 128 bytes of old MP3s) ----

    private static Builder fillFromId3v1(File f, Builder b) throws IOException {
        if (b != null && b.album != null && (b.artist != null || b.albumArtist != null)) return b;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 128) return b;
            r.seek(r.length() - 128);
            byte[] d = new byte[128];
            r.readFully(d);
            if (d[0] != 'T' || d[1] != 'A' || d[2] != 'G') return b;
            if (b == null) b = new Builder();
            b.put("ARTIST", v1(d, 33, 30));
            b.put("ALBUM", v1(d, 63, 30));
            if (d[125] == 0 && d[126] != 0) b.put("TRACKNUMBER", String.valueOf(d[126] & 0xff)); // ID3v1.1
            return b;
        }
    }

    private static String v1(byte[] d, int p, int len) {
        int end = p;
        while (end < p + len && d[end] != 0) end++;
        return new String(d, p, end - p, StandardCharsets.ISO_8859_1).trim();
    }

    // ---- MP4 / M4A (moov › udta › meta › ilst) ----

    private static final class Mp4 {
        private Mp4() {}

        /** Tags (and, when {@code wantPicture}, the cover into {@code pic[0]}). Null when there is no ilst. */
        static Builder read(File f, boolean wantPicture, byte[][] pic) throws IOException {
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                long[] moov = child(r, 0, r.length(), "moov");
                long[] udta = moov == null ? null : child(r, moov[0], moov[1], "udta");
                long[] meta = udta == null ? null : child(r, udta[0], udta[1], "meta");
                long[] ilst = meta == null ? null : child(r, meta[0] + 4, meta[1], "ilst"); // meta has 4 bytes of version/flags
                if (ilst == null) return null;
                Builder b = new Builder();
                long p = ilst[0];
                while (p + 8 <= ilst[1]) {
                    long[] h = header(r, p, ilst[1]);
                    if (h == null) break;
                    String name = new String(type(r, p), StandardCharsets.ISO_8859_1);
                    item(r, name, p + h[0], p + h[1], b, wantPicture, pic);
                    p += h[1];
                }
                return b;
            }
        }

        private static void item(java.io.RandomAccessFile r, String name, long start, long end, Builder b,
                                 boolean wantPicture, byte[][] pic) throws IOException {
            String key = null;
            switch (name) {
                case "©alb": key = "ALBUM"; break;
                case "©ART": key = "ARTIST"; break;
                case "aART": key = "ALBUMARTIST"; break;
                case "trkn": case "disk": case "covr": break;
                case "----": {
                    long[] nm = child(r, start, end, "name");
                    if (nm == null || nm[1] - nm[0] < 4 || nm[1] - nm[0] > 256) return;
                    String n = new String(bytes(r, nm[0] + 4, (int) (nm[1] - nm[0] - 4)), StandardCharsets.UTF_8);
                    if (!n.equalsIgnoreCase("MusicBrainz Album Id")) return;
                    key = "MUSICBRAINZ_ALBUMID";
                    break;
                }
                default: return;
            }
            long[] data = child(r, start, end, "data");
            if (data == null || data[1] - data[0] < 8) return;
            long payload = data[0] + 8; // type (4) + locale (4)
            int len = (int) Math.min(data[1] - payload, MAX_PICTURE_METADATA);
            if (name.equals("covr")) {
                byte[] head = bytes(r, payload, Math.min(4, len));
                if (!ArtLookup.isImage(head)) return;
                b.picture = true;
                if (wantPicture && pic != null && pic[0] == null) pic[0] = bytes(r, payload, len);
                return;
            }
            if (name.equals("trkn") || name.equals("disk")) {
                if (len < 6) return;
                byte[] d = bytes(r, payload, 6);
                int n = (d[2] & 0xff) << 8 | (d[3] & 0xff);
                int total = (d[4] & 0xff) << 8 | (d[5] & 0xff);
                b.put(name.equals("trkn") ? "TRACKNUMBER" : "DISCNUMBER", total > 0 ? n + "/" + total : String.valueOf(n));
                return;
            }
            if (len > 4096) return;
            b.put(key, new String(bytes(r, payload, len), StandardCharsets.UTF_8));
        }

        /** {content start, content end} of the first child atom of {@code type} in [start, end), or null. */
        private static long[] child(java.io.RandomAccessFile r, long start, long end, String type) throws IOException {
            long p = start;
            while (p + 8 <= end) {
                long[] h = header(r, p, end);
                if (h == null) return null;
                if (new String(type(r, p), StandardCharsets.ISO_8859_1).equals(type)) return new long[]{p + h[0], p + h[1]};
                p += h[1];
            }
            return null;
        }

        /** {header length, atom length} of the atom at {@code p}, or null when it is malformed. */
        private static long[] header(java.io.RandomAccessFile r, long p, long end) throws IOException {
            r.seek(p);
            long size = r.readInt() & 0xffffffffL;
            int hl = 8;
            if (size == 1) {
                r.seek(p + 8);
                size = r.readLong();
                hl = 16;
            } else if (size == 0) {
                size = end - p;
            }
            if (size < hl || p + size > end) return null;
            return new long[]{hl, size};
        }

        private static byte[] type(java.io.RandomAccessFile r, long p) throws IOException {
            return bytes(r, p + 4, 4);
        }

        private static byte[] bytes(java.io.RandomAccessFile r, long p, int n) throws IOException {
            byte[] d = new byte[Math.max(0, n)];
            r.seek(p);
            r.readFully(d);
            return d;
        }
    }

    // ---- FLAC ----

    private static Builder flac(DataInputStream in) throws IOException {
        Builder b = new Builder();
        int total = 0;
        while (true) {
            int header = in.readUnsignedByte();
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7f;
            int len = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            total += len;
            if (total > MAX_METADATA) break;
            if (type == 0 && len >= 18) {
                byte[] d = new byte[len];
                in.readFully(d);
                b.durationMs = streamInfoMs(d);
            } else if (type == 4) {
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
        return b;
    }

    /** STREAMINFO: 20-bit sample rate at byte 10, 36-bit total sample count ending at byte 17. Null when unknown. */
    static Long streamInfoMs(byte[] d) {
        int rate = (d[10] & 0xff) << 12 | (d[11] & 0xff) << 4 | (d[12] & 0xff) >> 4;
        long samples = (long) (d[13] & 0x0f) << 32 | (long) (d[14] & 0xff) << 24 | (d[15] & 0xff) << 16
                | (d[16] & 0xff) << 8 | (d[17] & 0xff);
        if (rate == 0 || samples == 0) return null;
        return samples * 1000 / rate;
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

    private static Builder id3(DataInputStream in, int major) throws IOException {
        if (major < 2 || major > 4) return null;
        in.readUnsignedByte(); // revision
        int flags = in.readUnsignedByte();
        int size = syncsafe(in.readInt());
        if (size > MAX_METADATA) return null;
        byte[] tag = new byte[size];
        in.readFully(tag);
        int p = 0;
        if (major > 2 && (flags & 0x40) != 0) { // extended header
            int ext = major == 4 ? syncsafe(be32(tag, 0)) : be32(tag, 0) + 4;
            p += ext;
        }
        Builder b = new Builder();
        int hs = major == 2 ? 6 : 10;
        while (p + hs <= tag.length) {
            if (tag[p] == 0) break; // padding
            String id = new String(tag, p, major == 2 ? 3 : 4, StandardCharsets.US_ASCII);
            int len = frameSize(tag, p, major);
            p += hs;
            if (len < 0 || p + len > tag.length) break;
            switch (id) {
                case "TALB": case "TAL": b.put("ALBUM", text(tag, p, len)); break;
                case "TPE1": case "TP1": b.put("ARTIST", text(tag, p, len)); break;
                case "TPE2": case "TP2": b.put("ALBUMARTIST", text(tag, p, len)); break;
                case "TRCK": case "TRK": b.put("TRACKNUMBER", text(tag, p, len)); break;
                case "TPOS": case "TPA": b.put("DISCNUMBER", text(tag, p, len)); break;
                case "APIC": if (apicDataStart(tag, p, len) >= 0) b.picture = true; break;
                case "PIC": if (picDataStart(tag, p, len) >= 0) b.picture = true; break;
                case "TXXX": case "TXX": {
                    String[] kv = text(tag, p, len).split("\u0000", 2);
                    if (kv.length == 2 && kv[0].equalsIgnoreCase("MusicBrainz Album Id")) b.put("MUSICBRAINZ_ALBUMID", kv[1]);
                    break;
                }
                default: break;
            }
            p += len;
        }
        return b;
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
        Integer track, trackTotal, disc;
        Long durationMs;

        void put(String key, String value) {
            if (value == null || value.trim().isEmpty()) return;
            String v = value.trim();
            switch (key.toUpperCase(Locale.ROOT)) {
                case "ALBUM": if (album == null) album = v; break;
                case "ARTIST": if (artist == null) artist = v; break;
                case "ALBUMARTIST": case "ALBUM ARTIST": case "ALBUM_ARTIST": if (albumArtist == null) albumArtist = v; break;
                case "MUSICBRAINZ_ALBUMID": if (mbid == null) mbid = v; break;
                // "3" or "3/14"
                // Track 0 is kept: the Eversolo CD Ripper numbers tracks from 0, and the rip check reports it.
                case "TRACKNUMBER": case "TRACK": // TRACK / DISC: APEv2 names
                    if (track == null) track = number(v, 0, true);
                    if (trackTotal == null) trackTotal = number(v, 1, false);
                    break;
                case "TRACKTOTAL": case "TOTALTRACKS": if (trackTotal == null) trackTotal = number(v, 0, false); break;
                case "DISCNUMBER": case "DISC": if (disc == null) disc = number(v, 0, false); break;
                default: break;
            }
        }

        /** Part {@code i} of "n" or "n/m", or null. */
        private static Integer number(String v, int i, boolean zeroOk) {
            String[] parts = v.split("/");
            if (i >= parts.length) return null;
            try {
                int n = Integer.parseInt(parts[i].trim());
                return n > 0 || (zeroOk && n == 0) ? n : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Tags build() {
            return new Tags(album, artist, albumArtist, mbid, picture);
        }

        TrackInfo track() {
            return new TrackInfo(track, trackTotal, disc, durationMs, mbid);
        }
    }
}
