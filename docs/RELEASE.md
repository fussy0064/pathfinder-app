# Release build (Android)

NOT run by me (no Android SDK here). Follow it on your PC, then send me any error text.

## 1. One-time project setup
```
cd app
flutter create --org com.pathfinder --project-name pathfinder_app .
git checkout lib test pubspec.yaml analysis_options.yaml
python3 ../tools/setup_android.py .      # location permissions, backup off, minSdk 24
flutter pub get
```

## 2. Signing key (keep it secret, back it up, never commit it)
```
keytool -genkey -v -keystore ~/pathfinder-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias pathfinder
```
Create `app/android/key.properties` (ignored by git):
```
storePassword=...
keyPassword=...
keyAlias=pathfinder
storeFile=/home/you/pathfinder-release.jks
```
In `app/android/app/build.gradle.kts`, load it and use it for `release` (standard Flutter signing guide:
https://docs.flutter.dev/deployment/android#sign-the-app). If you lose the key you cannot update the app on Google Play.

## 3. Network permission (only if you use the downloader)
The app has NO internet permission by default. To use in-app downloads add, in
`android/app/src/main/AndroidManifest.xml` (inside `<manifest>`):
```xml
<uses-permission android:name="android.permission.INTERNET"/>
```
Skip this and copy data files by hand (or `adb push`) to keep the app fully offline.

## 4. Build
```
flutter build appbundle --release --obfuscate --split-debug-info=build/symbols \
  --dart-define=PACK_BASE_URL=https://your-server/packs/        # last line only if you host packs
flutter build apk --release --split-per-abi                      # for testing on a phone
```
Keep `build/symbols` (needed to read crash reports). Output: `build/app/outputs/bundle/release/app-release.aab`.

## 5. Checks before you publish
- `unzip -l app-release.apk | grep libpathfinder` shows arm64-v8a, armeabi-v7a, x86_64 (apk built without `--split-per-abi`).
- 16 KB pages: `zipalign -c -P 16 -v 4 app-release.apk` (Android SDK build-tools 35+).
- Test on a real phone: GPS fix, search, route, turn banner, reroute (drive or walk off the route), airplane mode.
- Run `ctest` (C++/Python) and `flutter test` in `app/` and `mobile/pathfinder_ffi/`.
- Privacy text for the store: location is used on the device only and is never sent anywhere.
- Review `docs/SECURITY.md` known gaps.

## CI
`tools/ci/ci.yml` builds and tests everything on GitHub's servers (C++ tests, Flutter analyze/test, debug + release APK).
Copy it to `.github/workflows/ci.yml`. A token needs the `workflow` scope to push that file; the GitHub website works too.
