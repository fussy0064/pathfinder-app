package com.pathfinder.app.core;

public final class PackFile {
    public final String name;
    public final String path;
    public final long size;
    public final String sha256;

    public PackFile(String name, String path, long size, String sha256) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.sha256 = sha256;
    }
}
