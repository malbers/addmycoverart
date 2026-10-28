package com.malbers.addmycoverart.core;

import java.io.File;

/** A folder that directly contains audio files. */
public final class Album {
    public final File dir;
    public final int trackCount;
    public final File firstAudio;
    /** Name of the art file already in the folder, or null. */
    public final String existingArt;
    /** The first track carries a picture; it gets copied out to folder.jpg rather than fetched. */
    public final boolean embeddedArt;

    public Album(File dir, int trackCount, File firstAudio, String existingArt, boolean embeddedArt) {
        this.dir = dir;
        this.trackCount = trackCount;
        this.firstAudio = firstAudio;
        this.existingArt = existingArt;
        this.embeddedArt = embeddedArt;
    }

    /**
     * Only an image file counts. Art inside the tracks alone still gets a folder.jpg, copied out of the
     * tracks, so the cover never depends on the player reading tags.
     */
    public boolean hasArt() {
        return existingArt != null;
    }
}
