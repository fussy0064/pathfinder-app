import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

import 'data/data_store.dart';
import 'data/mbtiles_provider.dart';
import 'data/places_db.dart';
import 'services/location_service.dart';

enum AppPhase { loading, needsData, idle, calculating, routeReady, routeFailed, navigating, arrived, fatal }

/// All app state. The map and screens only read from here and call its methods.
class AppController extends ChangeNotifier {
  AppController(this._location);

  static const double _offRouteM = 50; // farther than this from the route = off route
  static const int _offRouteUpdates = 3; // ... for this many GPS updates in a row = reroute
  static const double _arriveM = 20;
  static const double _maxAccuracyM = 50; // ignore worse GPS fixes while navigating

  final LocationService _location;

  AppPhase phase = AppPhase.loading;
  String? message;
  DataStatus? data;
  PlacesDb? places;
  MbtilesTileProvider? tiles;
  String? tilesProblem;
  LocationState locationState = LocationState.unknown;
  LatLng? user;
  Place? destination;
  RouteResult? route;
  List<LatLng> routePoints = const [];
  RouteProgress? progress;
  int? calcMillis;
  bool rerouting = false;

  PathfinderEngine? _engine;
  StreamSubscription<Position>? _sub;
  int _offCount = 0;
  bool _progressBusy = false;
  bool _disposed = false;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> init() async {
    phase = AppPhase.loading;
    _notify();
    try {
      final d = await DataStore.check();
      data = d;
      if (d.hasTiles) {
        try {
          tiles = MbtilesTileProvider.open(d.tilesPath);
        } catch (e) {
          tilesProblem = e is FormatException ? e.message : 'Map tiles file could not be opened';
        }
      }
      if (d.hasPlaces) {
        try {
          places = PlacesDb.open(d.placesPath);
        } catch (_) {
          places = null; // search disabled, routing still works
        }
      }
      await _startLocation();
      if (!d.hasGraph) {
        phase = AppPhase.needsData;
        _notify();
        return;
      }
      _engine = await PathfinderEngine.open(d.graphPath);
      phase = AppPhase.idle;
    } on PathfinderException catch (e) {
      phase = AppPhase.fatal;
      message = e.message;
    } catch (_) {
      phase = AppPhase.fatal;
      message = 'Could not start the app';
    }
    _notify();
  }

  Future<void> _startLocation() async {
    locationState = await _location.ensurePermission();
    if (locationState != LocationState.ready) return;
    _sub = _location.positions().listen(_onPosition, onError: (Object _) {
      locationState = LocationState.serviceOff;
      _notify();
    });
  }

  void _onPosition(Position p) {
    user = LatLng(p.latitude, p.longitude);
    if (phase == AppPhase.navigating && p.accuracy <= _maxAccuracyM) {
      _updateProgress(user!); // notifies when done
    }
    _notify();
  }

  // ---- route planning ----

  Future<void> chooseDestination(Place place) async {
    destination = place;
    await _calculate();
  }

  Future<void> _calculate() async {
    final e = _engine, u = user, d = destination;
    if (e == null || d == null) return;
    if (u == null) {
      _fail('Waiting for your GPS position...');
      return;
    }
    phase = AppPhase.calculating;
    message = null;
    _notify();
    final watch = Stopwatch()..start();
    try {
      final r = await e.route(GeoPoint(u.latitude, u.longitude), GeoPoint(d.lat, d.lon));
      _setRoute(r);
      calcMillis = watch.elapsedMilliseconds;
      phase = AppPhase.routeReady;
    } on PathfinderException catch (ex) {
      _fail(ex.message);
      return;
    } on ArgumentError {
      _fail('Invalid location');
      return;
    }
    _notify();
  }

  void _setRoute(RouteResult r) {
    final old = route;
    route = r;
    routePoints = List<LatLng>.generate(r.lats.length, (i) => LatLng(r.lats[i], r.lons[i]), growable: false);
    progress = null;
    if (old != null) _engine?.releaseRoute(old.routeId);
  }

  void _fail(String text) {
    message = text;
    phase = AppPhase.routeFailed;
    _notify();
  }

  void clearRoute() {
    final old = route;
    if (old != null) _engine?.releaseRoute(old.routeId);
    route = null;
    routePoints = const [];
    progress = null;
    destination = null;
    message = null;
    phase = AppPhase.idle;
    _notify();
  }

  // ---- navigation ----

  void startNavigation() {
    if (route == null) return;
    _offCount = 0;
    progress = null;
    phase = AppPhase.navigating;
    _notify();
  }

  Future<void> _updateProgress(LatLng pos) async {
    final r = route, e = _engine;
    if (r == null || e == null || _progressBusy || rerouting) return;
    _progressBusy = true;
    try {
      final pr = await e.progress(r.routeId, GeoPoint(pos.latitude, pos.longitude));
      if (phase != AppPhase.navigating || route != r) return;
      progress = pr;
      if (pr.remainingM <= _arriveM) {
        phase = AppPhase.arrived;
      } else if (pr.offRouteM > _offRouteM) {
        if (++_offCount >= _offRouteUpdates) {
          _progressBusy = false;
          await _reroute(pos);
        }
      } else {
        _offCount = 0;
      }
      _notify();
    } catch (_) {
      // engine closed or route released: ignore this update
    } finally {
      _progressBusy = false;
    }
  }

  Future<void> _reroute(LatLng from) async {
    final e = _engine, d = destination;
    if (e == null || d == null) return;
    rerouting = true;
    _notify();
    try {
      final r = await e.route(GeoPoint(from.latitude, from.longitude), GeoPoint(d.lat, d.lon));
      if (phase == AppPhase.navigating) _setRoute(r);
    } on PathfinderException catch (ex) {
      message = ex.message; // keep the old route and try again later
    } finally {
      _offCount = 0;
      rerouting = false;
      _notify();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _sub?.cancel();
    _engine?.close();
    places?.dispose();
    tiles?.dispose();
    super.dispose();
  }
}
