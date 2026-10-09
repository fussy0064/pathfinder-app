# PROGRESS

**Current stage: Phase 1 - DONE (waiting for your OK to start Phase 2)**

## Roadmap
| Phase | Name | Status |
|---|---|---|
| 1 | Data pipeline + graph pre-processing | DONE |
| 2 | C++ core pathfinding engine | NOT STARTED |
| 3 | Mobile bridge (FFI/JNI) | NOT STARTED |
| 4 | Flutter UI + map rendering | NOT STARTED |
| 5 | Testing, optimization, packaging | NOT STARTED |

## Phase 1 checklist
- [x] Repo structure + CMake + .gitignore
- [x] Binary graph format "PFGR" v1 (`core/include/graph_format.hpp`)
- [x] Extractor tool using libosmium (`tools/extractor`)
  - keeps only `highway` roads (drops footway, buildings, private/no access)
  - handles oneway, roundabout, maxspeed (km/h or mph)
  - weight = distance (m) and time (deciseconds)
- [x] Forward-star layout (nodes array + edges array)
- [x] Validator + `graph_info` tool (checks magic, version, size, indexes)
- [x] Tested on `data/sample/tiny.osm`

## Test output (sample map)
```
Ways used : 4
Nodes     : 9
Edges     : 14
VALID graph
```
Corrupted files are rejected (`size mismatch`, `bad magic`).

## Security done in Phase 1
- Input file is never trusted: every size and index is checked
- Safe write: temp file, then rename
- Compiler hardening flags (stack protector, FORTIFY)
- `.gitignore` blocks secrets, keys, big map files

## Not done yet (left for later phases)
- Contraction Hierarchies preprocessing (Phase 2)
- Real `.pbf` test (needs a Geofabrik download on your PC)
- Edge simplification (merge degree-2 nodes)

## NEXT: Phase 2 (needs your permission)
1. mmap loader using `validate()`
2. Spatial index (GPS -> nearest node)
3. A* / bidirectional query
4. `extern "C"` API
5. Unit tests
