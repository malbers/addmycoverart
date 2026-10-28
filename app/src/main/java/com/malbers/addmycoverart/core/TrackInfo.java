package com.malbers.addmycoverart.core;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-track facts from one file's header, for the rip check. Any field may be null. */
public final class TrackInfo {
    public static final TrackInfo EMPTY = new TrackInfo(null, null, null, null, null);

    /** "19.Too Hot…" → 19; "1-03 Title" (disc-track) → 3. Four-digit starts ("1967-1970") are years, not tracks. */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(?:\\d{1,2}[-_](?=\\d{1,3}\\D))?(\\d{1,3})\\D");

    public final Integer track;
    public final Integer trackTotal;
    public final Integer disc;
    /** Playing time from the FLAC STREAMINFO block; null for other formats, or when the file states none. */
    public final Long durationMs;
    public final String mbAlbumId;
    /** File name without extension, for messages; null when not tied to a file. */
    public final String name;
    /** File size in bytes, or -1. */
    public final long bytes;
    /** The number the file name starts with ("19.Too Hot…" → 19), or null. */
    public final Integer fileNumber;

    public TrackInfo(Integer track, Integer trackTotal, Integer disc, Long durationMs, String mbAlbumId) {
        this(track, trackTotal, disc, durationMs, mbAlbumId, null, -1, null);
    }

    private TrackInfo(Integer track, Integer trackTotal, Integer disc, Long durationMs, String mbAlbumId,
                      String name, long bytes, Integer fileNumber) {
        this.track = track;
        this.trackTotal = trackTotal;
        this.disc = disc;
        this.durationMs = durationMs;
        this.mbAlbumId = mbAlbumId;
        this.name = name;
        this.bytes = bytes;
        this.fileNumber = fileNumber;
    }

    /** The same facts, tied to the file they came from. */
    public TrackInfo withFile(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        Matcher m = LEADING_NUMBER.matcher(n);
        Integer num = m.find() ? Integer.valueOf(m.group(1)) : null;
        return new TrackInfo(track, trackTotal, disc, durationMs, mbAlbumId, base, f.length(), num);
    }

    /** The same track with a different tag number (used to undo tags that count from 0). */
    public TrackInfo withTrack(Integer t) {
        return new TrackInfo(t, trackTotal, disc, durationMs, mbAlbumId, name, bytes, fileNumber);
    }

    /** How messages refer to this track: its file name when known, else its number. */
    public String label() {
        if (name != null) return name.length() > 44 ? name.substring(0, 43) + "…" : name;
        return "track " + track;
    }
}
