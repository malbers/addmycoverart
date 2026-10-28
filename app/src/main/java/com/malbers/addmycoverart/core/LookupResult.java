package com.malbers.addmycoverart.core;

/** Either an image (with the URL it came from) or a reason it could not be found. */
public final class LookupResult {
    public static final String NO_TAGS = "no tags";
    public static final String NO_MATCH = "no confident match";
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

    private LookupResult(byte[] image, String source, String reason, String artist, String album) {
        this.image = image;
        this.source = source;
        this.reason = reason;
        this.artist = artist;
        this.album = album;
    }

    public boolean fromTracks() {
        return source != null && source.startsWith(FROM_TRACKS);
    }

    static LookupResult found(byte[] image, String source, String artist, String album) {
        return new LookupResult(image, source, null, artist, album);
    }

    static LookupResult notFound(String reason, String artist, String album) {
        return new LookupResult(null, null, reason, artist, album);
    }
}
