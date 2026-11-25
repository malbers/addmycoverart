package com.malbers.addmycoverart.core;

/** Either an image (with the URL it came from) or a reason it could not be found. */
public final class LookupResult {
    public static final String NO_TAGS = "no tags";
    public static final String NO_MATCH = "no confident match";
    /** 1.1.1: no sure match, but a close one Michael can check on the album page (Fetch all never saves it). */
    public static final String CLOSE = "close match to review";
    public static final String NO_ART = "no art on file";
    public static final String NETWORK = "network error";
    /** 1.1.0: the cover database answered "busy" (HTTP 5xx, e.g. MusicBrainz rate limiting): not the streamer's network. */
    public static final String BUSY = "database busy";
    /** Source prefix when the image was copied out of the album's own first track. */
    public static final String FROM_TRACKS = "embedded:";

    public final byte[] image;
    public final String source;
    public final String reason;
    /** Artist and album as searched, for display. Either may be null. */
    public final String artist;
    public final String album;
    /** 1.1.0.1: for {@link #NETWORK}, one plain sentence naming the host and the cause; else null. */
    public final String detail;

    private LookupResult(byte[] image, String source, String reason, String artist, String album, String detail) {
        this.image = image;
        this.source = source;
        this.reason = reason;
        this.artist = artist;
        this.album = album;
        this.detail = detail;
    }

    public boolean fromTracks() {
        return source != null && source.startsWith(FROM_TRACKS);
    }

    static LookupResult found(byte[] image, String source, String artist, String album) {
        return new LookupResult(image, source, null, artist, album, null);
    }

    static LookupResult notFound(String reason, String artist, String album) {
        return new LookupResult(null, null, reason, artist, album, null);
    }

    static LookupResult network(java.io.IOException e, String artist, String album) {
        return new LookupResult(null, null, NETWORK, artist, album, NetFailure.describe(e, new java.util.Date()));
    }
}
