# Making and hosting map packs

A pack = road graph + optional fast index + optional search + optional map tiles.
You build it on a PC, upload it to any HTTPS static host (GitHub Pages, S3, your own server).

```
# 1. roads
build/extractor region.osm.pbf map_graph.bin
# 2. fast index (optional but recommended; one time, desktop only)
build/ch_builder map_graph.bin                 # writes map_graph.bin.ch
# 3. search (optional)
pip install osmium
python3 tools/places/build_places.py region.osm.pbf places.db
# 4. tiles (optional): raster png/jpg tiles.mbtiles from QGIS / MOBAC
# 5. pack + catalog (put the 4 files in one folder first)
python3 tools/make_pack.py build ./files ./site --id mycity --name "My City" --version 2026-10 --merge
python3 tools/make_pack.py check ./site/catalog.json
# 6. upload ./site/* to https://your-server/packs/   (catalog.json must be at that folder's root)
```
Build the app with: `flutter build apk --release --dart-define=PACK_BASE_URL=https://your-server/packs/`
(see RELEASE.md for the INTERNET permission).

Rules the app enforces (same as `make_pack.py check`): https only, no redirects, allowed file names only,
exact size and SHA-256. Re-run `make_pack.py build` whenever a file changes.

## Measured sizes (real map: Monaco, 15,704 nodes)
| File | Size |
|---|---|
| map_graph.bin | 0.6 MB |
| map_graph.bin.ch | 1.0 MB |
| places.db (1,253 places) | 0.14 MB |
| Download total | 1.7 MB |

The `.ch` file can be larger than the graph (grid-like city maps need many shortcuts).
On a synthetic 490,000-node grid it was 58 MB for a 32 MB graph. Skip the `.ch` file if download size matters more than speed:
the app then uses A*.
