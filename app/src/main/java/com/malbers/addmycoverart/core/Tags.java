package com.malbers.addmycoverart.core;

/** The handful of tags the lookup needs, read from one audio file. Any field may be null. */
public final class Tags {
    public static final Tags EMPTY = new Tags(null, null, null, null, false);

    public final String album;
    public final String artist;
    public final String albumArtist;
    public final String mbAlbumId;
    public final boolean hasPicture;

    public Tags(String album, String artist, String albumArtist, String mbAlbumId, boolean hasPicture) {
        this.album = album;
        this.artist = artist;
        this.albumArtist = albumArtist;
        this.mbAlbumId = mbAlbumId;
        this.hasPicture = hasPicture;
    }

    /** Album artist when present, else track artist. */
    public String bestArtist() {
        return albumArtist != null ? albumArtist : artist;
    }
}
