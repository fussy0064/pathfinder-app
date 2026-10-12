package com.pathfinder.app.core;

import java.util.List;

public final class PackInfo {
    public final String id;
    public final String name;
    public final String version;
    public final List<PackFile> files;

    public PackInfo(String id, String name, String version, List<PackFile> files) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.files = files;
    }

    public long totalBytes() {
        long sum = 0;
        for (PackFile f : files) sum += f.size;
        return sum;
    }
}
