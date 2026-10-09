# PROGRESS

**Current stage: Phase 4 - DONE (code written; Flutter parts NOT run yet). Waiting for your OK to start Phase 5.**

## Roadmap
| Phase | Name | Status |
|---|---|---|
| 1 | Data pipeline + graph pre-processing | DONE |
| 2 | C++ core pathfinding engine | DONE |
| 3 | Mobile bridge (FFI/JNI) | DONE (needs your device test) |
| 4 | Flutter UI + map rendering | DONE (needs your device test) |
| 5 | Testing, optimization, packaging | NOT STARTED |

## Phase 4 checklist
- [x] Navigation logic in C++ (tested): turn steps, progress along route, off-route distance
- [x] C API v0.4.0: step accessors + `pf_route_handle_progress`
- [x] Dart plugin: `RouteStep`, `TurnType`, `RouteProgress`, `progress()`, `releaseRoute()`; `LatLng` renamed `GeoPoint`
- [x] Search data builder `tools/places/build_places.py` (+ tested SQL)
- [x] Flutter app `app/` (5 screens/states from your diagram):
  1. Map view with GPS dot, offline chip
  2. Destination input: offline autocomplete + long-press pin
  3. "CALCULATING PATH..." then "PATH FOUND: 25 min"
  4. Navigation: turn banner, progress bar, remaining time/distance, auto reroute
  5. Offline mode: chip + data status card (downloader is Phase 5)
- [x] Offline raster tiles from `.mbtiles` (custom flutter_map TileProvider)
- [x] `tools/make_sample_pack.sh` for a quick smoke test
- [x] Docs: `app/README.md`

## What I verified here
| Check | Result |
|---|---|
| C++ tests (engine + navigation), normal build | 5973 + 30 checks, 0 failed |
| Same with ASan + UBSan | 0 failed |
| Navigation tests as 32-bit program | 30 checks, 0 failed |
| Turn types: left, right, slight, sharp, u-turn, gentle curve ignored, tight arc = ONE turn, S-bend = 2 turns | pass |
| Progress: on route, 20 m off, past corner, before start, past end, out-and-back road never goes backwards | pass |
| Search SQL + builder (Python): case-insensitive, prefix first, `%` `_` literal, SQL injection text harmless | pass |
| CLI demo (sample map): depart 222 m, right, arrive; 442 m, 35.8 s | pass |
| All Dart files parse (syntax only) | 0 errors |

## NOT verified (I could not do these here)
- No Dart/Flutter code was compiled or run. Type errors are possible. Send me the error text and I will fix it.
- Dart package versions (flutter_map 7, geolocator 13, sqlite3 2.x) were chosen from memory; pub.dev is not reachable from here.
- No phone/emulator run, no real GPS, no screenshots of the UI.
- ARM cross-build was not repeated after adding `nav.cpp` (it is plain C++; 32-bit x86 passed).
- Real-map speed and a real `.pbf` were never tested.

## Security done in Phase 4
- No INTERNET permission, no network code (offline by design); backups off, cleartext off
- Search text: parameterized SQL, `%`/`_`/`\` escaped, length limited to 80, result limit
- Databases opened read-only; versions checked
- GPS values validated in Dart and C++; bad fixes (accuracy > 50 m) ignored while navigating
- Native route memory is released on reroute / end / close
- Only raster tiles accepted; vector tiles give a clear message

## Known limits
- Turn text comes from road shape only (no street names, no roundabout exits)
- No voice, no keep-awake, no heading-up map rotation
- Contraction Hierarchies and bidirectional A* not done (Phase 5, after real-map timing)
- GPS snaps to nearest node, not nearest road segment
- Data files are copied by hand (adb) until the Phase 5 downloader

## NEXT: Phase 5 (needs your permission)
1. In-app downloader / importer for regional packs (graph + places + tiles)
2. Benchmark on a real city `.pbf`, then add Contraction Hierarchies if needed
3. Snap to nearest road segment
4. Release build, signing notes, final security review, CI
