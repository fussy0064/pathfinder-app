import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

import 'data/data_store.dart';
import 'data/mbtiles_provider.dart';
import 'data/pack_manager.dart';
import 'data/places_db.dart';
import 'l10n/strings.dart';
import 'services/deepseek_client.dart';
import 'services/guidance_text.dart';
import 'services/heading.dart';
import 'services/location_service.dart';
import 'services/voice_service.dart';
import 'settings/app_settings.dart';

enum AppPhase { loading, needsData, idle, calculating, routeReady, routeFailed, navigating, arrived, fatal }

/// All app state. The map and screens only read from here and call its methods.
class AppController extends ChangeNotifier {
  AppController(this._location, this.settings, {VoiceService? voice}) : voice = voice ?? VoiceService() {
    _voiceLang = settings.language;
    settings.addListener(_onSettings);
  }

  static const double _offRouteM = 50; // farther than this from the route = off route
  static const int _offRouteUpdates = 3; // ... for this many GPS updates in a row = reroute
  static const double _arriveM = 20;
  static const double _maxAccuracyM = 50; // ignore worse GPS fixes while navigating
  // Set at build time: flutter build apk --dart-define=PACK_BASE_URL=https://example.org/packs/
  static const String _packUrl = String.fromEnvironment('PACK_BASE_URL');

  final LocationService _location;
  final AppSettings settings;
  final VoiceService voice;

  AppPhase phase = AppPhase.loading;
  String? messageKey; // localization key of the current message
  Map<String, String> _messageArgs = const {};
  DataStatus? data;
  PlacesDb? places;
  MbtilesTileProvider? tiles;
  String? tilesProblemKey;
  LocationState locationState = LocationState.unknown;
  LatLng? user;
  Place? destination;
  RouteResult? route;
  List<LatLng> routePoints = const [];
  RouteProgress? progress;
  int? calcMillis;
  bool rerouting = false;
  PackManager? packs; // null when no download server is configured
  String? packsProblemKey;
  bool usesCh = false; // true = fast routing index (.ch) is active
  bool voiceAvailable = false; // a text-to-speech voice for the chosen language exists
  double? heading; // smoothed travel direction in degrees (0 = north), used while navigating

  PathfinderEngine? _engine;
  StreamSubscription<Position>? _sub;
  AppLanguage _voiceLang = AppLanguage.en;
  double? _gpsHeading;
  int _offCount = 0;
  bool _progressBusy = false;
  bool _disposed = false;

  Strings get t => Strings(settings.language);
  String? get message => messageKey == null ? null : t(messageKey!, _messageArgs);

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void _setMessage(String? key, [Map<String, String> args = const {}]) {
    messageKey = key;
    _messageArgs = args;
  }

  void _speak(String text) {
    if (settings.voiceOn) voice.speak(text);
  }

  String _keyForStatus(int status) {
    switch (status) {
      case kStatusBadArgs:
        return 'errBadArgs';
      case kStatusNoStart:
        return 'errNoStart';
      case kStatusNoTarget:
        return 'errNoTarget';
      case kStatusNoRoute:
        return 'errNoRoute';
      case kStatusFile:
        return 'errFile';
      case kStatusInvalidGraph:
        return 'errInvalidGraph';
      case kStatusInternal:
        return 'errInternal';
      case kStatusLibrary:
        return 'errLibrary';
      default:
        return 'errUnknown';
    }
  }

  void _onSettings() {
    if (settings.language != _voiceLang) {
      _voiceLang = settings.language;
      _configureVoice();
    }
    _notify();
  }

  Future<void> _configureVoice() async {
    voiceAvailable = await voice.setLocales(t.voiceLocales);
    _notify();
  }

  /// Speaks a short sample (Settings > Test voice).
  void testVoice() => voice.speak(t('testPhrase'));

  Future<void> init() async {
    phase = AppPhase.loading;
    _notify();
    await _configureVoice();
    await _startLocation();
    await _loadData();
  }

  /// Re-reads the data files (call after installing or deleting map data).
  Future<void> reloadData() async {
    if (phase == AppPhase.navigating || phase == AppPhase.calculating || phase == AppPhase.routeReady) return;
    final old = route;
    if (old != null) _engine?.releaseRoute(old.routeId);
    route = null;
    routePoints = const [];
    destination = null;
    await _closeData();
    phase = AppPhase.loading;
    _notify();
    await _loadData();
  }

  /// Deletes all installed map data, then reloads (the app goes back to "needs data").
  Future<void> deleteAllData() async {
    final dir = data?.dir;
    if (dir == null) return;
    await _closeData();
    await DataStore.deleteAll(dir);
    await reloadData();
  }

  Future<void> _closeData() async {
    places?.dispose();
    places = null;
    tiles?.dispose();
    tiles = null;
    tilesProblemKey = null;
    usesCh = false;
    final e = _engine;
    _engine = null;
    if (e != null) await e.close();
  }

  Future<void> _loadData() async {
    try {
      final d = await DataStore.check();
      data = d;
      if (_packUrl.isNotEmpty && packs == null) {
        final uri = Uri.tryParse(_packUrl);
        try {
          if (uri == null) throw PackException('bad url');
          packs = PackManager(baseUrl: uri, dataDir: d.dir);
        } on PackException {
          packsProblemKey = 'errPackUrl';
        }
      }
      if (d.hasTiles) {
        try {
          tiles = MbtilesTileProvider.open(d.tilesPath);
        } catch (e) {
          tilesProblemKey = e is FormatException ? 'errTilesVector' : 'errTilesOpen';
        }
      }
      if (d.hasPlaces) {
        try {
          places = PlacesDb.open(d.placesPath);
        } catch (_) {
          places = null; // search disabled, routing still works
        }
      }
      if (!d.hasGraph) {
        phase = AppPhase.needsData;
        _notify();
        return;
      }
      final engine = await PathfinderEngine.open(d.graphPath);
      _engine = engine;
      usesCh = engine.usesCh;
      phase = AppPhase.idle;
      _setMessage(null);
    } on PathfinderException catch (e) {
      phase = AppPhase.fatal;
      _setMessage(_keyForStatus(e.status));
    } catch (_) {
      phase = AppPhase.fatal;
      _setMessage('errStart');
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
    if (p.speed.isFinite && p.speed > 1.0 && p.heading.isFinite && p.heading >= 0) {
      _gpsHeading = p.heading; // GPS heading is only meaningful while moving
    }
    if (phase == AppPhase.navigating) {
      if (progress == null && _gpsHeading != null) heading = smoothHeading(heading, _gpsHeading!);
      if (p.accuracy <= _maxAccuracyM) _updateProgress(user!); // notifies when done
    }
    _notify();
  }

  // ---- search ----

  /// AI search: DeepSeek turns the typed words into place kinds / names; the offline database finds the places.
  /// Only the typed words leave the phone. Throws AiException (its key is a localization key).
  Future<List<Place>> aiSearch(String text) async {
    if (!settings.aiOn || !settings.hasApiKey) throw AiException('aiNeedsSetup');
    final db = places;
    if (db == null) throw AiException('searchNoData');
    final key = await settings.readApiKey();
    if (key == null || key.isEmpty) throw AiException('aiErrNoKey');
    final intent = await DeepSeekClient(apiKey: key).interpret(text);
    final u = user;
    final out = <Place>[];
    final seen = <int>{};
    void add(List<Place> found) {
      for (final p in found) {
        if (out.length < 15 && seen.add(p.id)) out.add(p);
      }
    }

    if (intent.kinds.isNotEmpty) {
      add(db.searchByKinds(intent.kinds, nearLat: u?.latitude, nearLon: u?.longitude, limit: 10));
    }
    for (final n in intent.names) {
      add(db.search(n, limit: 8));
    }
    if (out.isEmpty) throw AiException('aiNoResults');
    return out;
  }

  /// Settings > Test connection. Sends the single word "pharmacy".
  Future<void> testAiConnection() async {
    final key = await settings.readApiKey();
    if (key == null || key.isEmpty) throw AiException('aiErrNoKey');
    await DeepSeekClient(apiKey: key).interpret('pharmacy');
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
      _fail('errWaitingGpsPosition');
      return;
    }
    phase = AppPhase.calculating;
    _setMessage(null);
    _notify();
    final watch = Stopwatch()..start();
    try {
      final r = await e.route(GeoPoint(u.latitude, u.longitude), GeoPoint(d.lat, d.lon));
      _setRoute(r);
      calcMillis = watch.elapsedMilliseconds;
      phase = AppPhase.routeReady;
    } on PathfinderException catch (ex) {
      _fail(_keyForStatus(ex.status));
      return;
    } on ArgumentError {
      _fail('errInvalidLocation');
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

  void _fail(String key) {
    _setMessage(key);
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
    heading = null;
    _setMessage(null);
    phase = AppPhase.idle;
    voice.stop();
    _notify();
  }

  // ---- navigation ----

  void startNavigation() {
    if (route == null) return;
    _offCount = 0;
    progress = null;
    heading = _gpsHeading;
    phase = AppPhase.navigating;
    _speak(t('vNavStart'));
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
      // heading: the road direction while on the route, otherwise the GPS direction
      final target = pr.offRouteM < 30 ? pr.bearingDeg : _gpsHeading;
      if (target != null) heading = smoothHeading(heading, target);
      if (pr.announce != 0 && r.steps.isNotEmpty) {
        final step = r.steps[math.min(math.max(pr.nextStep, 0), r.steps.length - 1)];
        final phrase = guidancePhrase(t, step.type, pr.announce, pr.toNextM);
        if (phrase != null) _speak(phrase);
      }
      if (pr.remainingM <= _arriveM) {
        phase = AppPhase.arrived;
        _speak(t('vArrived'));
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
    _speak(t('vRerouting'));
    _notify();
    try {
      final r = await e.route(GeoPoint(from.latitude, from.longitude), GeoPoint(d.lat, d.lon));
      if (phase == AppPhase.navigating) _setRoute(r);
    } on PathfinderException catch (ex) {
      _setMessage(_keyForStatus(ex.status)); // keep the old route and try again later
    } finally {
      _offCount = 0;
      rerouting = false;
      _notify();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    settings.removeListener(_onSettings);
    _sub?.cancel();
    voice.stop();
    _engine?.close();
    places?.dispose();
    tiles?.dispose();
    super.dispose();
  }
}
