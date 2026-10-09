import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Where the three offline data files are and which of them exist.
class DataStatus {
  DataStatus(this.dir, this.hasGraph, this.hasTiles, this.hasPlaces, {this.hasCh = false});
  final String dir;
  final bool hasGraph;
  final bool hasTiles;
  final bool hasPlaces;
  final bool hasCh;

  String get graphPath => p.join(dir, 'map_graph.bin');
  String get tilesPath => p.join(dir, 'tiles.mbtiles');
  String get placesPath => p.join(dir, 'places.db');
  String get chPath => p.join(dir, 'map_graph.bin.ch');
}

class DataStore {
  static const List<String> fileNames = ['map_graph.bin', 'map_graph.bin.ch', 'places.db', 'tiles.mbtiles'];

  /// Deletes all installed map data files (nothing else).
  static Future<void> deleteAll(String dir) async {
    for (final name in fileNames) {
      final f = File(p.join(dir, name));
      if (await f.exists()) await f.delete();
    }
  }

  /// Android: app-specific external folder (you can `adb push` files there).
  /// Other platforms: application support folder. Phase 5 adds an in-app downloader.
  static Future<DataStatus> check() async {
    Directory? base;
    if (Platform.isAndroid) base = await getExternalStorageDirectory();
    base ??= await getApplicationSupportDirectory();
    final dir = Directory(p.join(base.path, 'pathfinder'));
    if (!await dir.exists()) await dir.create(recursive: true);
    bool has(String name) => File(p.join(dir.path, name)).existsSync();
    return DataStatus(dir.path, has('map_graph.bin'), has('tiles.mbtiles'), has('places.db'),
        hasCh: has('map_graph.bin.ch'));
  }
}
