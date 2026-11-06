package com.malbers.addmycoverart.core;

/** Either an image (with the URL it came from) or a reason it could not be found. */
public final class LookupResult {
    public static final String NO_TAGS = "no tags";
    /** MusicBrainz returned releases, but none passed the checks. */
    public static final String NO_MATCH = "no confident match";
    /** MusicBrainz returned nothing at all for the album's searches. */
    public static final String NOT_ON_MB = "not on MusicBrainz";
    public static final String NO_ART = "no art on file";
    public static final String NETWORK = "network error";
    /** Source prefix when the image was copied out of the album's own first track. */
    public static final String FROM_TRACKS = "embedded:";

    public final byte[] image;
    public final String source;
    public final String reason;
    /** Artist and album as searched, for display. Either may be null. */
    public final String artist;
    public final String album;
    /** Found by the track-list fallback rather than by album title or MusicBrainz ID. */
    public final boolean byTrackList;

    private LookupResult(byte[] image, String source, String reason, String artist, String album, boolean byTrackList) {
        this.image = image;
        this.source = source;
        this.reason = reason;
        this.artist = artist;
        this.album = album;
        this.byTrackList = byTrackList;
    }

    public boolean fromTracks() {
        return source != null && source.startsWith(FROM_TRACKS);
    }

    static LookupResult found(byte[] image, String source, String artist, String album) {
        return new LookupResult(image, source, null, artist, album, false);
    }

    /** The same image, marked as found by the track-list fallback. */
    static LookupResult viaTrackList(LookupResult r) {
        return new LookupResult(r.image, r.source, r.reason, r.artist, r.album, true);
    }

    static LookupResult notFound(String reason, String artist, String album) {
        return new LookupResult(null, null, reason, artist, album, false);
    }
}
