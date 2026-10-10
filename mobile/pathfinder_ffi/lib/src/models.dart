import 'dart:typed_data';

/// A GPS point. The constructor rejects NaN, infinity and out-of-range values.
class GeoPoint {
  GeoPoint(this.lat, this.lon) {
    if (!lat.isFinite || !lon.isFinite || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
      throw ArgumentError('Invalid coordinates: $lat, $lon');
    }
  }
  final double lat;
  final double lon;

  @override
  String toString() => 'GeoPoint($lat, $lon)';
}

/// Maneuver kinds. Order matches PF_TURN_* in core/include/pathfinder.h.
enum TurnType {
  depart, straight, slightLeft, left, sharpLeft, slightRight, right, sharpRight, uTurn, arrive;

  static TurnType fromCode(int code) =>
      (code >= 0 && code < TurnType.values.length) ? TurnType.values[code] : TurnType.straight;
}

/// One maneuver. [distanceM] is the distance from this step to the next one.
class RouteStep {
  RouteStep(this.type, this.pointIndex, this.distanceM);
  final TurnType type;
  final int pointIndex;
  final double distanceM;
}

/// Result of a successful route request. Call PathfinderEngine.releaseRoute(routeId)
/// when the route is no longer needed (frees native memory).
class RouteResult {
  RouteResult(this.routeId, this.distanceM, this.timeS, this.lats, this.lons, this.steps);
  final int routeId;
  final double distanceM;
  final double timeS;
  final Float64List lats;
  final Float64List lons;
  final List<RouteStep> steps;

  int get pointCount => lats.length;
  List<GeoPoint> get points =>
      List<GeoPoint>.generate(lats.length, (i) => GeoPoint(lats[i], lons[i]), growable: false);
}

/// Where the user is on a route (from PathfinderEngine.progress).
class RouteProgress {
  RouteProgress(this.alongM, this.offRouteM, this.remainingM, this.toNextM, this.nextStep,
      {this.announce = 0, this.bearingDeg = 0});
  final double alongM;
  final double offRouteM;
  final double remainingM;
  final double toNextM;
  final int nextStep;

  /// Voice cue for the next maneuver (delivered once): 0 none, 1 about 500 m, 2 about 200 m, 3 now.
  final int announce;

  /// Direction of the road you are on (0 = north, 90 = east).
  final double bearingDeg;
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
