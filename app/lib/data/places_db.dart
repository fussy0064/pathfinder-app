import 'dart:math' as math;

import 'package:sqlite3/sqlite3.dart';

class Place {
  Place({required this.id, required this.name, required this.kind, required this.lat, required this.lon});

  factory Place.dropped(double lat, double lon, [String name = 'Dropped pin']) =>
      Place(id: -1, name: name, kind: 'pin', lat: lat, lon: lon);

  final int id;
  final String name;
  final String kind;
  final double lat;
  final double lon;

  /// 'amenity:fast_food' -> 'Fast food'
  String get kindLabel {
    var k = kind.contains(':') ? kind.split(':').last : kind;
    k = k.replaceAll('_', ' ');
    return k.isEmpty ? '' : k[0].toUpperCase() + k.substring(1);
  }
}

/// Offline place search over places.db (built by tools/places/build_places.py).
class PlacesDb {
  PlacesDb._(this._db);
  final Database _db;
  bool _closed = false;

  // Must match tests/test_places.py (the SQL is tested there).
  static const String _sql =
      "SELECT id, name, kind, lat, lon FROM places WHERE name_norm LIKE ? ESCAPE '\\' "
      "ORDER BY (name_norm LIKE ? ESCAPE '\\') DESC, length(name_norm), name_norm LIMIT ?";

  static PlacesDb open(String path) {
    final db = sqlite3.open(path, mode: OpenMode.readOnly);
    try {
      final v = db.select('PRAGMA user_version').first.columnAt(0) as int;
      if (v != 1) throw FormatException('Unsupported places.db version $v');
      return PlacesDb._(db);
    } catch (_) {
      db.dispose();
      rethrow;
    }
  }

  /// Makes % _ and \ match themselves in a LIKE pattern.
  static String escapeLike(String q) =>
      q.replaceAll(r'\', r'\\').replaceAll('%', r'\%').replaceAll('_', r'\_');

  List<Place> search(String query, {int limit = 20}) {
    if (_closed) return const [];
    var q = query.trim().toLowerCase();
    if (q.length < 2) return const [];
    if (q.length > 80) q = q.substring(0, 80);
    final e = escapeLike(q);
    final rows = _db.select(_sql, ['%$e%', '$e%', math.min(50, math.max(1, limit))]);
    return [
      for (final r in rows)
        Place(
          id: r['id'] as int,
          name: r['name'] as String,
          kind: r['kind'] as String,
          lat: (r['lat'] as num).toDouble(),
          lon: (r['lon'] as num).toDouble(),
        ),
    ];
  }

  /// Same SQL is tested in tests/test_places.py. [n] = number of kinds, [near] = order by distance.
  static String kindsSql(int n, bool near) =>
      'SELECT id, name, kind, lat, lon FROM places WHERE kind IN (${List.filled(n, '?').join(',')}) '
      'ORDER BY ${near ? '((lat - ?) * (lat - ?) + ((lon - ?) * ?) * ((lon - ?) * ?))' : 'name_norm'} LIMIT ?';

  /// Places of the given kinds (e.g. 'amenity:pharmacy'), nearest first when a position is given.
  List<Place> searchByKinds(List<String> kinds, {double? nearLat, double? nearLon, int limit = 10}) {
    if (_closed || kinds.isEmpty) return const [];
    final ks = kinds.length > 5 ? kinds.sublist(0, 5) : kinds;
    final near = nearLat != null && nearLon != null && nearLat.isFinite && nearLon.isFinite;
    final params = <Object?>[...ks];
    if (near) {
      final c = math.cos(nearLat * math.pi / 180);
      params.addAll([nearLat, nearLat, nearLon, c, nearLon, c]);
    }
    params.add(math.min(50, math.max(1, limit)));
    final rows = _db.select(kindsSql(ks.length, near), params);
    return [
      for (final r in rows)
        Place(
          id: r['id'] as int,
          name: r['name'] as String,
          kind: r['kind'] as String,
          lat: (r['lat'] as num).toDouble(),
          lon: (r['lon'] as num).toDouble(),
        ),
    ];
  }

  void dispose() {
    if (_closed) return;
    _closed = true;
    _db.dispose();
  }
}
