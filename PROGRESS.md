# PROGRESS

**Current stage: Phase 3 - DONE, but see "NOT verified" below (waiting for your OK to start Phase 4)**

## Roadmap
| Phase | Name | Status |
|---|---|---|
| 1 | Data pipeline + graph pre-processing | DONE |
| 2 | C++ core pathfinding engine | DONE |
| 3 | Mobile bridge (FFI/JNI) | DONE (needs your device test) |
| 4 | Flutter UI + map rendering | NOT STARTED |
| 5 | Testing, optimization, packaging | NOT STARTED |

## Phase 3 checklist
- [x] C API v0.3.0: added handle API (`pf_route_compute` + accessors). Dart never needs to match a C struct layout
- [x] Flutter FFI plugin `mobile/pathfinder_ffi` (Dart API, Isolate worker, bindings)
- [x] Android build files (Gradle + CMake, 3 ABIs, 16 KB page size, hardened flags)
- [x] iOS podspec + forwarder files
- [x] Dart tests written (`test/pathfinder_test.dart`)
- [x] Docs: `mobile/README.md`
- [ ] JNI wrapper for pure Java/Kotlin (skipped: the Flutter route does not need it. Tell me if you want it)

## What I verified here
| Check | Result |
|---|---|
| C++ tests, normal build | 5965 checks, 0 failed |
| Same tests with ASan + UBSan | 0 failed |
| Same tests with ThreadSanitizer (8 threads, 1 engine) | 0 failed |
| Same tests as 32-bit program (like armeabi-v7a) | 0 failed |
| Engine cross-compiles for ARM 64-bit and ARM 32-bit | OK |
| Shared library exports only the 14 `pf_*` functions | OK |
| Dart files parse with a Dart parser (syntax only) | OK, 0 errors |

## NOT verified (I could not do these here)
- Dart code was never run or type-checked: no Dart/Flutter SDK is reachable from my environment.
- Android `.so` was never built with the real NDK, and nothing ran on a phone or emulator.
- iOS was never built (no Mac).
- The ARM builds above use musl, not Android's bionic library.
Please run the steps in `mobile/README.md` on your PC. If anything fails, send me the error text and I will fix it.

## Security done in Phase 3
- Coordinates are checked in Dart (`LatLng`) and again in C++
- Graph path checked (empty / null byte)
- Native memory is copied out and freed inside the worker (handle freed in `finally`)
- Engine pointer lives only in the worker Isolate
- Library exports only the `pf_*` API (hidden visibility)
- Stack protector, RELRO / now-binding, 16 KB alignment on Android

## Known limits
- Contraction Hierarchies and bidirectional A* not done yet (Phase 5)
- Speed not measured on a real map yet (needs your `.pbf`)
- GPS snaps to nearest node, not nearest road segment
- Graph file must be a real file (copy from assets first)
- Android build.gradle versions (AGP 8.7.3, compileSdk 35) may need a small change for your Flutter version

## NEXT: Phase 4 (needs your permission)
1. Flutter app shell (`app/`) using `pathfinder_ffi`
2. Offline map tiles (MapLibre or flutter_map + .mbtiles)
3. GPS location (`geolocator`)
4. Search screen (SpatiaLite / SQLite)
5. Route line on map + turn-by-turn screen
