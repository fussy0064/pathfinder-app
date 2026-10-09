# PROGRESS

**Current stage: Phase 5 - code and docs DONE. Two things need YOU (see "Needs you").**

## Roadmap
| Phase | Name | Status |
|---|---|---|
| 1 | Data pipeline + graph pre-processing | DONE |
| 2 | C++ core pathfinding engine | DONE |
| 3 | Mobile bridge (FFI) | DONE (Dart/Android never compiled by me) |
| 4 | Flutter UI + map rendering | DONE (Dart never compiled by me) |
| 5 | Testing, optimization, packaging | DONE except your device/CI steps |

## Needs you
1. **Compile and run the Flutter side.** Easiest: turn on CI. `tools/ci/ci.yml` builds and tests everything on GitHub's servers
   (C++ tests, Flutter analyze + tests, the plugin against the real C++ library, debug and release APK).
   Your token has only `repo` scope; GitHub refuses workflow files without `workflow` scope.
   Either (a) edit the token and tick `workflow`, then tell me, or (b) on github.com create `.github/workflows/ci.yml` and paste the file.
   With (a) I can read the results and fix errors myself. Otherwise follow `app/README.md` and `docs/RELEASE.md` on your PC.
2. **Revoke the GitHub token** when finished (it was pasted into chat).

## Phase 5 checklist
- [x] Contraction Hierarchies index: `build/ch_builder map_graph.bin` -> `map_graph.bin.ch`; engine uses it automatically, falls back to A*
- [x] Real-map test: Monaco `.osm.pbf` (OSRM test data) through extractor, CH, places, pack, route
- [x] Independent check: Python Dijkstra on the raw real graph agrees with the engine on 300 routes (0 differences)
- [x] Benchmarks (`tools/bench/bench_map.cpp` real map, `tools/bench/bench.cpp` synthetic scale)
- [x] Pack tooling: `tools/make_pack.py` (catalog + SHA-256), tested with hostile catalogs
- [x] In-app downloader (`app/lib/data/pack_manager.dart`) + "Map data" screen: https only, no redirects, size + SHA-256, atomic install, cancel, delete
- [x] Security review: `docs/SECURITY.md`; release guide: `docs/RELEASE.md`; pack hosting: `docs/PACKS.md`
- [x] Repo cleanup: build folders removed from git, `.gitignore` fixed
- [ ] Snap GPS to nearest road SEGMENT (still nearest node)
- [ ] Signed catalog (Ed25519), voice guidance, heading-up map

## Speed results (desktop CPU, single thread)
| Test | A* | CH | Notes |
|---|---|---|---|
| REAL map: Monaco, 15,704 nodes, 1000 random pairs | median 0.71 ms, p95 1.8, max 3.7 | median 0.04 ms, p95 0.07, max 0.46 | CH build 0.1 s, file 1.0 MB, 0 disagreements |
| Synthetic grid, 490,000 nodes, 300 long pairs | median 18 ms, p95 82, max 150 | median 0.37 ms, p95 0.66, max 1.8 | CH build 51 s, file 58 MB, 0 disagreements |
| Same synthetic grid, short trips (up to about 4 km) | median 0.2 ms, p95 0.6, max 4.2 | - | typical city driving |
Load time: graph open + validate 0.1 ms (Monaco) / 3.7 ms (490k); CH open 0.5 ms / 24 ms.
Target "under 10 ms": CH meets it for every query tested. A* meets it for short trips, not for long cross-city trips on very large maps.
Caveats: a phone CPU will be slower (I would guess 3 to 6 times; NOT measured). The 490k graph is synthetic, not a real city.
On the small real map plain Dijkstra was slightly faster than A* (heuristic cost > savings), which is fine.

## What I verified here
| Check | Result |
|---|---|
| C++ tests: engine, navigation, CH, plus Python places + pack tests | 5 suites, all pass |
| Same C++ suites under ASan + UBSan | 0 failures |
| ThreadSanitizer (Phase 3, engine code unchanged since) and 32-bit x86 run (Phase 4) | clean |
| CH fuzz (1500 corrupted files) and graph fuzz (1500) | 0 crashes |
| Real data end to end (Monaco): extractor, CH, 1,253 places, pack, route with 12 steps | works |
| All Dart files parse (syntax only) | 0 errors |

## NOT verified
- **No Dart code has been compiled or run** (also the new downloader and its 14 tests). Type errors are possible.
- No Android build, no phone, no emulator, no screenshots. ARM cross-build not repeated after CH was added.
- Dart package versions come from memory (pub.dev unreachable).
- Phone speed and battery use are unmeasured.
- The in-app downloader has never talked to a real server.

## Note on how this phase went
When I started Phase 5 the sandbox already held uncommitted CH code, pack tools and edits to the engine that I had no record of writing.
I treated it as untrusted: read it (including the query/unpacking code and the catalog rules), ran every test under sanitizers,
fuzzed it, and cross-checked it against an independent Dijkstra on real data before adopting and pushing it.

## Known limits
- Turn text from road shape only (no street names, roundabout exits)
- GPS snaps to the nearest road node, not segment
- Catalog is not signed (see docs/SECURITY.md)
- `.ch` file can be large for grid-like maps; skip it to save download size
