#!/usr/bin/env python3
"""Applies the Android settings from app/README.md after `flutter create`.
Usage: python3 tools/setup_android.py app
Adds location permissions, turns off backups and cleartext traffic, sets minSdk 24. Safe to run twice."""
import pathlib
import re
import sys

app = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app")
manifest = app / "android/app/src/main/AndroidManifest.xml"
text = manifest.read_text()
perms = ['<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>',
         '<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>']
for p in perms:
    if p not in text:
        text = text.replace("<application", p + "\n    <application", 1)
if "android:allowBackup" not in text:
    text = text.replace("<application", '<application android:allowBackup="false" android:usesCleartextTraffic="false"', 1)
manifest.write_text(text)

for name in ("build.gradle.kts", "build.gradle"):
    f = app / "android/app" / name
    if f.exists():
        g = f.read_text()
        g = re.sub(r"minSdk\s*=\s*flutter\.minSdkVersion", "minSdk = 24", g)
        g = re.sub(r"minSdkVersion\s+flutter\.minSdkVersion", "minSdkVersion 24", g)
        f.write_text(g)
print("android settings applied")
