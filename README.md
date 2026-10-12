# Pathfinder - Android offline navigation kit

Open the `android` folder in Android Studio and build. Java only, no third-party libraries.

- `android/`  the Android Studio project (Java app + build files)
- `core/`     the C++ routing engine that the app builds with the NDK (`core/jni` is the Java bridge)

## Build
1. Android Studio > Open > select the `android` folder. Let Gradle sync.
2. If asked, install: Android SDK Platform 35, NDK (Side by side), CMake (SDK Manager > SDK Tools).
3. Build variants: `onlineDebug` (adds INTERNET: AI search + map downloads) or `offlineDebug` (no internet permission).
4. Run on a phone (USB debugging on). Command line: `cd android && ./gradlew installOnlineDebug`

## Map data
The app needs `map_graph.bin` in `/sdcard/Android/data/<app id>/files/pathfinder/`
(app id: `com.pathfinder.app.online` or `com.pathfinder.app`). Optional files there: `map_graph.bin.ch`, `places.db`, `tiles.mbtiles`.
