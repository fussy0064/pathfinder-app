# Pathfinder app (Phase 4)

Flutter app: offline map, GPS, destination search, route line, turn-by-turn.
It uses `../mobile/pathfinder_ffi` (C++ engine). The app has no internet code.

## 1. One-time setup (on your PC)
```
cd app
flutter create --org com.pathfinder --project-name pathfinder_app .
git checkout lib test pubspec.yaml analysis_options.yaml   # keep our files if flutter create replaced any
flutter pub get
```
`flutter create` only adds the `android/` and `ios/` folders.
Package versions in `pubspec.yaml` could not be checked from my environment. If `pub get` complains, relax the version and tell me.

## 2. Android settings
`android/app/src/main/AndroidManifest.xml`, inside `<manifest>`:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
```
On the `<application ...>` tag add: `android:allowBackup="false" android:usesCleartextTraffic="false"`.
Do NOT add the INTERNET permission (the app is offline by design).
In `android/app/build.gradle(.kts)` set `minSdk = 24`.
iOS: add `NSLocationWhenInUseUsageDescription` to `ios/Runner/Info.plist`.

## 3. Data files (3 files, same folder)
| File | What | How to make it |
|---|---|---|
| `map_graph.bin` | road graph (required) | `build/extractor region.osm.pbf map_graph.bin` |
| `places.db` | search (optional) | `python3 tools/places/build_places.py region.osm.pbf places.db` (needs `pip install osmium`) |
| `tiles.mbtiles` | map picture (optional) | RASTER png/jpg mbtiles (QGIS "Generate XYZ tiles (MBTiles)", MOBAC, ...). Vector (pbf) tiles are not supported yet. |

Folder on Android: `/sdcard/Android/data/com.pathfinder.pathfinder_app/files/pathfinder/`
```
adb push map_graph.bin places.db tiles.mbtiles /sdcard/Android/data/com.pathfinder.pathfinder_app/files/pathfinder/
```
(The in-app downloader comes in Phase 5.)

## 4. Quick smoke test (no real map needed)
```
mkdir build && cd build && cmake .. && make && cd ..
tools/make_sample_pack.sh           # prints the adb command
```
Run the app on an emulator, set its GPS to lat 10.0, lon 10.0, search "City Hospital".
Expected: "PATH FOUND: 1 min", 440 m, then Start shows "Turn right" after about 220 m.

## 5. Run
```
flutter run                  # debug
flutter test                 # format/search helper tests
flutter build apk --release
```

## How it works
- Search: SQLite `LIKE` on `places.db` (typed text is escaped, so `%` and `_` are plain characters).
- Long-press the map to drop a destination pin (works without places.db).
- Route: engine finds the fastest path; turn steps come from the route shape.
- Navigation: every GPS update (accuracy 50 m or better) is matched to the route in C++.
  More than 50 m off the route for 3 updates in a row triggers a new route from your position.
- Arrival: within 20 m of the end.

## Limits (honest list)
- Turn instructions come from road shape only: no street names, no roundabout exits, no lane info.
  A straight junction gives no instruction.
- No voice guidance, no screen-keep-awake yet.
- Map rotation / heading-up mode not done (north-up only).
- GPS snaps to road nodes, not road segments (can add a few meters of error).
- The screen reads tiles and search results on the UI thread (fine for city-size files).
