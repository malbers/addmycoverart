package com.malbers.addmycoverart.core;

/** Per-track facts from one file's header, for the rip check. Any field may be null. */
public final class TrackInfo {
    public static final TrackInfo EMPTY = new TrackInfo(null, null, null, null, null);

    public final Integer track;
    public final Integer trackTotal;
    public final Integer disc;
    /** Playing time from the FLAC STREAMINFO block; null for other formats. */
    public final Long durationMs;
    public final String mbAlbumId;

    public TrackInfo(Integer track, Integer trackTotal, Integer disc, Long durationMs, String mbAlbumId) {
        this.track = track;
        this.trackTotal = trackTotal;
        this.disc = disc;
        this.durationMs = durationMs;
        this.mbAlbumId = mbAlbumId;
    }
}
