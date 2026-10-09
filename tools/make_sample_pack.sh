#!/usr/bin/env bash
# Builds a tiny data pack (graph + places) for a quick app smoke test.
# Usage: tools/make_sample_pack.sh   (run after building: mkdir build && cd build && cmake .. && make)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/sample_pack"
mkdir -p "$OUT"
"$ROOT/build/extractor" "$ROOT/data/sample/tiny.osm" "$OUT/map_graph.bin"
python3 "$ROOT/tools/places/build_places.py" "$ROOT/data/sample/places.osm" "$OUT/places.db"
"$ROOT/build/graph_info" "$OUT/map_graph.bin" | head -3
echo "Pack ready in $OUT"
echo "Android: adb push $OUT/. /sdcard/Android/data/com.pathfinder.pathfinder_app/files/pathfinder/"
echo "Emulator GPS: set location to lat 10.0, lon 10.0, then search 'City Hospital'."
