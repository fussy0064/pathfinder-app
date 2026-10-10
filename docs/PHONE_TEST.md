# Build the app and test it on your phone

Nothing here was compiled by me (no Flutter/Android SDK in my environment). If a step fails, copy the error text and send it to me.

## 0. Once
- Install Flutter (https://docs.flutter.dev/get-started/install) and Android Studio. In Android Studio > SDK Manager install: **Android SDK**, **NDK (Side by side)**, **CMake**.
- Phone: Settings > About phone > tap "Build number" 7 times > Developer options > turn on **USB debugging**. Connect by USB, accept the prompt.
- Check: `flutter doctor` (fix anything marked with an X) and `adb devices` (your phone must be listed).

## 1. Get the code and build
```
git clone https://github.com/fussy0064/pathfinder-app.git
cd pathfinder-app/app
flutter create --org com.pathfinder --project-name pathfinder_app .
git checkout lib test pubspec.yaml analysis_options.yaml
rm -f test/widget_test.dart
python3 ../tools/setup_android.py .            # add --internet only if you want AI search
flutter pub get
flutter run --release                          # builds, installs and starts it on the phone
```
Only want the APK file? `flutter build apk --release` then
`adb install -r build/app/outputs/flutter-apk/app-release.apk`

## 2. Map data (the app needs at least map_graph.bin)
Linux / macOS / Windows WSL, from the repo root:
```
sudo apt install cmake g++ libosmium2-dev libprotozero-dev zlib1g-dev libexpat1-dev libbz2-dev   # macOS: brew install cmake libosmium
mkdir build && cd build && cmake .. && make && cd ..
# put a city/region extract here as region.osm.pbf (download.geofabrik.de or extract.bbbike.org). Start with a CITY, not a country.
build/extractor region.osm.pbf map_graph.bin
build/ch_builder map_graph.bin                                  # optional: fast routing
pip install osmium && python3 tools/places/build_places.py region.osm.pbf places.db   # optional: search
```
Start the app once (step 1). It shows "Map data not found" and the folder. Then:
```
adb push map_graph.bin map_graph.bin.ch places.db /sdcard/Android/data/com.pathfinder.pathfinder_app/files/pathfinder/
```
(leave out files you did not make). Close and reopen the app.
Optional `tiles.mbtiles` = RASTER png/jpg tiles (QGIS "Generate XYZ tiles (MBTiles)" or MOBAC). Without it the map background is blank, but search, route line, voice and turns still work.

## 3. What to test
1. Allow location. Blue dot appears. Chip says "Offline mode | fast routing" if the .ch file is present.
2. Search a place (or long-press the map to drop a pin) > "PATH FOUND" > Start.
3. Voice: Settings (top right) > Test voice. Language > Kiswahili, test again. No voice? Phone Settings > Text-to-speech > install English / Swahili voice data.
4. Walk or drive: turn banner, "In 200 meters, turn left", map rotates with your direction (compass button = north-up).
5. Leave the route on purpose: "Rerouting" then a new line.
6. Airplane mode: everything above must still work.
7. AI search (only if built with `--internet`): Settings > AI assistant > paste DeepSeek key > Test connection > search "nearest pharmacy".

Report: crashes, wrong turn directions, map rotating the wrong way, bad Swahili wording, slow routing.
