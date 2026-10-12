package com.pathfinder.app.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates catalog.json built by tools/make_pack.py. Same rules as validate_catalog() there. */
public final class Catalog {
    private Catalog() {}

    public static final Set<String> ALLOWED_FILES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("map_graph.bin", "map_graph.bin.ch", "places.db", "tiles.mbtiles")));
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    public static final int MAX_CATALOG_BYTES = 1024 * 1024;

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9._/-]{1,200}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");

    public static List<PackInfo> parse(String text) throws PackException {
        Object doc;
        try {
            doc = MiniJson.parse(text);
        } catch (MiniJson.JsonException e) {
            throw new PackException("Catalog is not valid JSON");
        }
        if (!(doc instanceof Map)) throw new PackException("Unsupported catalog version");
        Map<?, ?> root = (Map<?, ?>) doc;
        if (!(root.get("schema") instanceof Long) || (Long) root.get("schema") != 1L) {
            throw new PackException("Unsupported catalog version");
        }
        if (!(root.get("packs") instanceof List) || ((List<?>) root.get("packs")).size() > 100) {
            throw new PackException("Catalog has no valid pack list");
        }
        Set<String> seen = new HashSet<>();
        List<PackInfo> result = new ArrayList<>();
        for (Object raw : (List<?>) root.get("packs")) {
            if (!(raw instanceof Map)) throw new PackException("Bad pack entry");
            Map<?, ?> p = (Map<?, ?>) raw;
            Object id = p.get("id");
            if (!(id instanceof String) || !ID.matcher((String) id).matches() || !seen.add((String) id)) {
                throw new PackException("Bad or duplicate pack id");
            }
            Object name = p.get("name");
            Object version = p.get("version");
            if (!(name instanceof String) || ((String) name).isEmpty() || ((String) name).length() > 80) throw new PackException("Bad pack name");
            if (!(version instanceof String) || ((String) version).isEmpty() || ((String) version).length() > 32) throw new PackException("Bad pack version");
            Object rawFiles = p.get("files");
            if (!(rawFiles instanceof List) || ((List<?>) rawFiles).isEmpty() || ((List<?>) rawFiles).size() > ALLOWED_FILES.size()) {
                throw new PackException("Bad file list in pack " + id);
            }
            Set<String> names = new HashSet<>();
            List<PackFile> files = new ArrayList<>();
            for (Object rf : (List<?>) rawFiles) {
                if (!(rf instanceof Map)) throw new PackException("Bad file entry in pack " + id);
                Map<?, ?> f = (Map<?, ?>) rf;
                Object fname = f.get("name");
                Object path = f.get("path");
                Object size = f.get("size");
                Object sha = f.get("sha256");
                if (!(fname instanceof String) || !ALLOWED_FILES.contains(fname) || !names.add((String) fname)) {
                    throw new PackException("File not allowed in pack " + id);
                }
                if (!(path instanceof String) || !validPath((String) path)) throw new PackException("Bad file path in pack " + id);
                if (!(size instanceof Long) || (Long) size < 1 || (Long) size > MAX_FILE_BYTES) throw new PackException("Bad file size in pack " + id);
                if (!(sha instanceof String) || !SHA.matcher((String) sha).matches()) throw new PackException("Bad checksum in pack " + id);
                files.add(new PackFile((String) fname, (String) path, (Long) size, (String) sha));
            }
            if (!names.contains("map_graph.bin")) throw new PackException("Pack " + id + " has no road graph");
            result.add(new PackInfo((String) id, (String) name, (String) version, Collections.unmodifiableList(files)));
        }
        return result;
    }

    private static boolean validPath(String path) {
        if (!PATH.matcher(path).matches() || path.startsWith("/") || path.contains("//")) return false;
        for (String part : path.split("/", -1)) {
            if (part.equals("..")) return false;
        }
        return true;
    }
}
