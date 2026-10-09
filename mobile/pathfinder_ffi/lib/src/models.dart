import 'dart:typed_data';

/// A GPS point. The constructor rejects NaN, infinity and out-of-range values.
class LatLng {
  LatLng(this.lat, this.lon) {
    if (!lat.isFinite || !lon.isFinite || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      throw ArgumentError('Invalid coordinates: $lat, $lon');
    }
  }
  final double lat;
  final double lon;

  @override
  String toString() => 'LatLng($lat, $lon)';
}

/// Result of a successful route request.
class RouteResult {
  RouteResult(this.distanceM, this.timeS, this.lats, this.lons);
  final double distanceM;
  final double timeS;
  final Float64List lats;
  final Float64List lons;

  int get pointCount => lats.length;
  List<LatLng> get points =>
      List<LatLng>.generate(lats.length, (i) => LatLng(lats[i], lons[i]), growable: false);
}

/// Status codes match core/include/pathfinder.h (8 is added on the Dart side).
const int kStatusOk = 0;
const int kStatusBadArgs = 1;
const int kStatusNoStart = 2;
const int kStatusNoTarget = 3;
const int kStatusNoRoute = 4;
const int kStatusFile = 5;
const int kStatusInvalidGraph = 6;
const int kStatusInternal = 7;
const int kStatusLibrary = 8;

const Map<int, String> _messages = {
  kStatusBadArgs: 'Bad arguments',
  kStatusNoStart: 'No road near the start point',
  kStatusNoTarget: 'No road near the target point',
  kStatusNoRoute: 'No route found',
  kStatusFile: 'Cannot open graph file',
  kStatusInvalidGraph: 'Graph file is invalid',
  kStatusInternal: 'Internal error',
  kStatusLibrary: 'Native library could not be loaded',
};

class PathfinderException implements Exception {
  PathfinderException(this.status);
  final int status;
  String get message => _messages[status] ?? 'Unknown error ($status)';

  @override
  String toString() => 'PathfinderException($status): $message';
}
