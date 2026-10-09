import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Where the three offline data files are and which of them exist.
class DataStatus {
  DataStatus(this.dir, this.hasGraph, this.hasTiles, this.hasPlaces);
  final String dir;
  final bool hasGraph;
  final bool hasTiles;
  final bool hasPlaces;

  String get graphPath => p.join(dir, 'map_graph.bin');
  String get tilesPath => p.join(dir, 'tiles.mbtiles');
  String get placesPath => p.join(dir, 'places.db');
}

class DataStore {
  /// Android: app-specific external folder (you can `adb push` files there).
  /// Other platforms: application support folder. Phase 5 adds an in-app downloader.
  static Future<DataStatus> check() async {
    Directory? base;
    if (Platform.isAndroid) base = await getExternalStorageDirectory();
    base ??= await getApplicationSupportDirectory();
    final dir = Directory(p.join(base.path, 'pathfinder'));
    if (!await dir.exists()) await dir.create(recursive: true);
    bool has(String name) => File(p.join(dir.path, name)).existsSync();
    return DataStatus(dir.path, has('map_graph.bin'), has('tiles.mbtiles'), has('places.db'));
  }
}
