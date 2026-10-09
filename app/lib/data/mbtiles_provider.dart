import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/painting.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:sqlite3/sqlite3.dart';

// 1x1 transparent PNG, shown where the offline file has no tile.
final Uint8List kTransparentTile = Uint8List.fromList(const [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 11, 73, 68, 65, 84, 120, 156, 99, 96, 0, 2, 0, 0, 5, 0, 1, 122, 94, 171, 63, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130]);

class MbtilesInfo {
  MbtilesInfo(this.minZoom, this.maxZoom, this.center);
  final int minZoom;
  final int maxZoom;
  final LatLng? center;
}

/// Reads raster (png/jpg) tiles from an .mbtiles file. The file is opened read-only.
class MbtilesTileProvider extends TileProvider {
  MbtilesTileProvider._(this._db, this.info);

  final Database _db;
  final MbtilesInfo info;
  bool _closed = false;

  /// Throws FormatException for vector (pbf) tiles, SqliteException for a bad file.
  static MbtilesTileProvider open(String path) {
    final db = sqlite3.open(path, mode: OpenMode.readOnly);
    try {
      final meta = <String, String>{};
      try {
        for (final r in db.select('SELECT name, value FROM metadata')) {
          meta['${r['name']}'] = '${r['value']}';
        }
      } on SqliteException {
        // no metadata table: use defaults
      }
      if ((meta['format'] ?? 'png').toLowerCase() == 'pbf') {
        throw const FormatException('Vector (pbf) tiles are not supported. Use raster png/jpg tiles.');
      }
      final minZ = math.min(22, math.max(0, int.tryParse(meta['minzoom'] ?? '') ?? 0));
      final maxZ = math.min(22, math.max(0, int.tryParse(meta['maxzoom'] ?? '') ?? 16));
      LatLng? center;
      final parts = (meta['center'] ?? '').split(',');
      if (parts.length >= 2) {
        final lon = double.tryParse(parts[0]);
        final lat = double.tryParse(parts[1]);
        if (lat != null && lon != null && lat.abs() <= 90 && lon.abs() <= 180) center = LatLng(lat, lon);
      }
      return MbtilesTileProvider._(db, MbtilesInfo(minZ, maxZ, center));
    } catch (_) {
      db.dispose();
      rethrow;
    }
  }

  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) {
    final z = coordinates.z;
    if (_closed || z < 0 || z > 30) return MemoryImage(kTransparentTile);
    final y = (1 << z) - 1 - coordinates.y; // mbtiles rows are TMS (flipped)
    final rows = _db.select(
      'SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?',
      [z, coordinates.x, y],
    );
    if (rows.isEmpty) return MemoryImage(kTransparentTile);
    final data = rows.first['tile_data'];
    return MemoryImage(data is Uint8List ? data : kTransparentTile);
  }

  @override
  void dispose() {
    if (_closed) return; // flutter_map and the app may both call dispose
    _closed = true;
    _db.dispose();
    super.dispose();
  }
}
