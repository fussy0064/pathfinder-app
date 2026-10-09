#!/usr/bin/env python3
"""Build places.db (offline search) from an .osm / .osm.pbf file.

Usage: build_places.py <input.osm.pbf> <output.db>
Needs: pip install osmium
Schema (version 1): places(id, name, name_norm, kind, lat, lon). name_norm = lower-case name.
"""
import os
import sqlite3
import sys

import osmium

POI_KEYS = ("amenity", "shop", "tourism", "leisure", "office", "historic", "place", "railway", "aeroway")
MAX_NAME = 120


def norm(s: str) -> str:
    return s.strip().casefold()


class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rows = []
        self.seen = set()

    def _add(self, tags, lat, lon):
        name = tags.get("name")
        if not name or len(name) > MAX_NAME or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            return
        kind = None
        for k in POI_KEYS:
            if k in tags:
                kind = f"{k}:{tags.get(k)}"
                break
        if kind is None and "highway" in tags:
            kind = "street"
        if kind is None:
            return
        key = (norm(name), round(lat, 2), round(lon, 2))  # one entry per name per ~1 km
        if key in self.seen:
            return
        self.seen.add(key)
        self.rows.append((name.strip(), norm(name), kind, lat, lon))

    def node(self, n):
        if n.location.valid():
            self._add(n.tags, n.location.lat, n.location.lon)

    def way(self, w):
        pts = [nd for nd in w.nodes if nd.location.valid()]
        if pts:
            mid = pts[len(pts) // 2]
            self._add(w.tags, mid.lat, mid.lon)


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 1
    src, dst = argv[1], argv[2]
    c = Collector()
    c.apply_file(src, locations=True)
    tmp = dst + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        CREATE TABLE places(
          id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_norm TEXT NOT NULL,
          kind TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL);
        CREATE INDEX idx_places_norm ON places(name_norm);
        PRAGMA user_version = 1;
        """
    )
    db.executemany("INSERT INTO places(name,name_norm,kind,lat,lon) VALUES (?,?,?,?,?)", c.rows)
    db.commit()
    db.close()
    os.replace(tmp, dst)
    print(f"places: {len(c.rows)} written to {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
