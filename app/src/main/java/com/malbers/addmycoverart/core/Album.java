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
    /**
     * 1.1.2: a SACD ISO that shares its folder with other albums (Michael's ISO/<Artist>/ layout, 2026-11-25: the
     * Eversolo shows every ISO as its own album, folder or not). Its cover is "<ISO name>.jpg" next to it and its
     * extras go to Scan/<ISO name>/, so it never takes the folder.jpg or Scan/ of its neighbours.
     */
    public final boolean sharedIso;

    public Album(File dir, int trackCount, File firstAudio, String existingArt, boolean embeddedArt) {
        this(dir, trackCount, firstAudio, existingArt, embeddedArt, false);
    }

    public Album(File dir, int trackCount, File firstAudio, String existingArt, boolean embeddedArt, boolean sharedIso) {
        this.dir = dir;
        this.trackCount = trackCount;
        this.firstAudio = firstAudio;
        this.existingArt = existingArt;
        this.embeddedArt = embeddedArt;
        this.sharedIso = sharedIso;
    }

    /** 1.1.2: the ISO's name without ".iso" (null for an ordinary album). */
    public String isoBase() {
        if (!sharedIso || firstAudio == null) return null;
        String n = firstAudio.getName();
        return n.substring(0, n.length() - 4);
    }

    /**
     * 1.1.2: what to call this album on screen: the folder's name, or for an ISO sharing its folder the ISO's name
     * without its bracketed notes (Michael's scan showed "Billy Joel", the artist folder, for The Nylon Curtain).
     */
    public String name() {
        if (!sharedIso) return dir.getName();
        String n = isoBase().replaceAll("\\[[^\\]]*\\]", " ").replaceAll("(?i)\\([^()]*\\biso\\b[^()]*\\)", " ")
                .replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? isoBase() : n;
    }

    /** 1.1.2: the cover file this album gets: folder.jpg, or "<ISO name>.jpg" for an ISO sharing its folder. */
    public String coverName() {
        return sharedIso ? isoBase() + ".jpg" : ArtWriter.NAME;
    }

    /** 1.1.2: the folder extras go to: the album's Scan/, or Scan/<ISO name>/ for an ISO sharing its folder. */
    public File scanDir() {
        File scan = ExtraWriter.scanDir(dir);
        return sharedIso ? new File(scan, isoBase()) : scan;
    }

    /**
     * Only an image file counts. Art inside the tracks alone still gets a folder.jpg, copied out of the
     * tracks, so the cover never depends on the player reading tags.
     */
    public boolean hasArt() {
        return existingArt != null;
    }
}
