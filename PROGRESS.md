# PROGRESS

**Current stage: Phase 2 - DONE (waiting for your OK to start Phase 3)**

## Roadmap
| Phase | Name | Status |
|---|---|---|
| 1 | Data pipeline + graph pre-processing | DONE |
| 2 | C++ core pathfinding engine | DONE |
| 3 | Mobile bridge (FFI/JNI) | NOT STARTED |
| 4 | Flutter UI + map rendering | NOT STARTED |
| 5 | Testing, optimization, packaging | NOT STARTED |

## Phase 1 (done)
Graph format "PFGR" v1, extractor (.osm/.pbf -> map_graph.bin), validator, graph_info.

## Phase 2 checklist
- [x] mmap loader that validates the whole file before use (`MappedGraph`)
- [x] Spatial index: GPS -> nearest node (uniform grid, exact result)
- [x] A* query (time as cost) + Dijkstra for checking
- [x] `extern "C"` API (`core/include/pathfinder.h`) -> `libpathfinder.so`
- [x] `route_cli` tool
- [x] Tests: 5952 checks, 0 failed (also clean with ASan + UBSan)

## Test results
- A* and Dijkstra gave the same cost on 500 random routes
  (A* looked at 307,506 nodes, Dijkstra 412,024)
- Nearest node matches brute force on 2000 random points
- Fuzz: 1500 corrupted graph files -> 700 rejected, 800 loaded and queried, 0 crashes
- Sample map demo:
  - node1 -> node9: OK, 442 m, 35.8 s, 5 points
  - node9 -> node1: "no route found" (one-way street)
  - far away point: "no road near the target point"

## C API (short)
```
pf_engine_open(path, &status)   pf_engine_close(e)
pf_route_find(e, slat, slon, tlat, tlon, &route)   pf_route_free(&route)
```
Status codes: 0 OK, 1 bad args, 2 no start road, 3 no target road, 4 no route, 5 file, 6 invalid graph, 7 internal.

## Security done in Phase 2
- File is validated before any use; all indexes checked
- GPS input checked (NaN, out of range, null pointers)
- Snap limit 5 km (point must be near a road)
- Overflow guards on costs; path rebuild loop is bounded
- Calls are serialized with a mutex; no exceptions cross the C boundary
- Memory freed with `pf_route_free` (safe to call twice)

## Known limits (honest list)
- Contraction Hierarchies and bidirectional A* are NOT done yet. Plain A* is used.
  Planned for Phase 5 (after we measure speed on a real city map).
- Speed not measured on a real map yet (no .pbf available here). Needs your test file.
- GPS snaps to the nearest node, not the nearest road segment.
- Query memory is about 16 bytes per node (fine for city/region maps).
- If the .bin file is changed while the app runs, it can crash (normal for mmap).
- Linux/Android/iOS (POSIX) only. No Windows.

## NEXT: Phase 3 (needs your permission)
1. Cross-compile `libpathfinder.so` for Android (arm64-v8a, armeabi-v7a)
2. Dart FFI bindings (Flutter plugin)
3. Run routing in a Dart Isolate (UI never freezes)
4. Optional JNI wrapper for pure Java/Kotlin
