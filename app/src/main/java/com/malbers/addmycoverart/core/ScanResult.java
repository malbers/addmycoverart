package com.malbers.addmycoverart.core;

import java.util.List;

public final class ScanResult {
    public final List<Album> missing;
    public final List<Album> withArt;

    public ScanResult(List<Album> missing, List<Album> withArt) {
        this.missing = missing;
        this.withArt = withArt;
    }
}
