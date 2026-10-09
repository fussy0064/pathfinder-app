# Mobile bridge (Phase 3)

Flutter FFI plugin `pathfinder_ffi`: Dart -> C API -> C++ engine (`core/`).
Routing runs in a background Isolate, so the UI never freezes.

## Use in a Flutter app
`pubspec.yaml`:
```yaml
dependencies:
  pathfinder_ffi:
    path: ../mobile/pathfinder_ffi
```
Code:
```dart
final engine = await PathfinderEngine.open(graphFilePath); // real file path
try {
  final r = await engine.route(GeoPoint(10.0, 10.0), GeoPoint(10.002, 10.002));
  print('${r.distanceM} m, ${r.timeS} s, ${r.steps.length} steps');
  // while driving: where am I on the route?
  final p = await engine.progress(r.routeId, GeoPoint(10.001, 10.0));
  print('${p.remainingM} m to go, next step ${p.nextStep}');
  engine.releaseRoute(r.routeId); // frees native memory
} on PathfinderException catch (e) {
  print(e.message); // e.g. "No route found"
}
await engine.close();
```
Important: Flutter assets are NOT real files. Copy `map_graph.bin` to the app
documents folder first (the Phase 5 downloader will do this). The engine needs a file path for mmap.

## Prerequisites (on your PC)
- Flutter SDK 3.19+
- Android Studio + SDK: install "NDK (Side by side)" and "CMake" in SDK Manager

## Test on your PC (Linux/macOS)
```
# 1. build the library and a sample graph
mkdir build && cd build && cmake .. && make
./extractor ../data/sample/tiny.osm tiny.bin
# 2. run the Dart tests
cd ../mobile/pathfinder_ffi && flutter pub get
PATHFINDER_LIB=$PWD/../../build/libpathfinder.so PATHFINDER_GRAPH=$PWD/../../build/tiny.bin flutter test
```
Expected: 4 tests pass.

## Build for Android
In your Flutter app: `flutter build apk --release`.
Gradle builds `libpathfinder.so` for arm64-v8a, armeabi-v7a, x86_64 using the NDK.
Built with 16 KB page alignment (needed for Android 15+).

## Layout
```
pathfinder_ffi/lib/            Dart API (engine.dart), Isolate (worker.dart), FFI (bindings.dart)
pathfinder_ffi/android/        build.gradle + CMakeLists.txt (compiles ../../core)
pathfinder_ffi/ios/            podspec + forwarder files
pathfinder_ffi/test/           Dart tests
```
