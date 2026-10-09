import 'dart:async';
import 'dart:isolate';
import 'dart:typed_data';

import 'models.dart';
import 'worker.dart';

/// Offline route finder. Create it once with [open], call [route] many times,
/// and call [close] when done. All heavy work runs on a background Isolate.
class PathfinderEngine {
  PathfinderEngine._(this._isolate, this._responses);

  final Isolate _isolate;
  final ReceivePort _responses;
  late final SendPort _commands;
  final Map<int, Completer<RouteResult>> _pending = {};
  final Map<int, Completer<RouteProgress>> _pendingProgress = {};
  Completer<void>? _closeDone;
  int _nextId = 0;
  int _nodeCount = 0;
  bool _closed = false;

  /// Number of road nodes in the loaded graph.
  int get nodeCount => _nodeCount;

  /// [graphPath] must be a real file on disk (not a Flutter asset path).
  /// [libraryPath] is only needed for desktop tests.
  static Future<PathfinderEngine> open(String graphPath, {String? libraryPath}) async {
    if (graphPath.isEmpty || graphPath.contains('\u0000')) {
      throw ArgumentError('Invalid graph path');
    }
    final responses = ReceivePort();
    final isolate = await Isolate.spawn<List<Object?>>(
      workerMain,
      <Object?>[responses.sendPort, graphPath, libraryPath],
    );
    final engine = PathfinderEngine._(isolate, responses);
    final ready = Completer<void>();

    responses.listen((dynamic message) {
      final msg = message as List<Object?>;
      switch (msg[0] as String) {
        case 'ready':
          engine._commands = msg[1] as SendPort;
          engine._nodeCount = msg[2] as int;
          ready.complete();
        case 'open_error':
          ready.completeError(PathfinderException(msg[1] as int));
        case 'result':
          final c = engine._pending.remove(msg[1] as int);
          if (c == null) return;
          final status = msg[2] as int;
          if (status != kStatusOk) {
            c.completeError(PathfinderException(status));
          } else {
            final types = msg[8] as Int32List;
            final points = msg[9] as Uint32List;
            final dists = msg[10] as Float64List;
            final steps = List<RouteStep>.generate(
                types.length, (i) => RouteStep(TurnType.fromCode(types[i]), points[i], dists[i]),
                growable: false);
            c.complete(RouteResult(msg[3] as int, msg[4] as double, msg[5] as double,
                msg[6] as Float64List, msg[7] as Float64List, steps));
          }
        case 'progress':
          final c = engine._pendingProgress.remove(msg[1] as int);
          if (c == null) return;
          final status = msg[2] as int;
          if (status != kStatusOk) {
            c.completeError(PathfinderException(status));
          } else {
            c.complete(RouteProgress(msg[3] as double, msg[4] as double, msg[5] as double,
                msg[6] as double, msg[7] as int));
          }
        case 'closed':
          engine._closeDone?.complete();
      }
    });

    try {
      await ready.future;
    } catch (_) {
      responses.close();
      isolate.kill(priority: Isolate.immediate);
      rethrow;
    }
    return engine;
  }

  /// Finds the fastest route. Throws [PathfinderException] when no route exists
  /// (see its `status`) and [ArgumentError] for invalid coordinates.
  Future<RouteResult> route(GeoPoint start, GeoPoint target) {
    if (_closed) return Future<RouteResult>.error(StateError('Engine is closed'));
    final id = _nextId++;
    final completer = Completer<RouteResult>();
    _pending[id] = completer;
    _commands.send(<Object?>['route', id, start.lat, start.lon, target.lat, target.lon]);
    return completer.future;
  }

  /// Where is [position] on a route from [route]? Cheap; call it on every GPS update.
  Future<RouteProgress> progress(int routeId, GeoPoint position) {
    if (_closed) return Future<RouteProgress>.error(StateError('Engine is closed'));
    final id = _nextId++;
    final completer = Completer<RouteProgress>();
    _pendingProgress[id] = completer;
    _commands.send(<Object?>['progress', id, routeId, position.lat, position.lon]);
    return completer.future;
  }

  /// Frees the native memory of a route. Safe to call twice.
  void releaseRoute(int routeId) {
    if (!_closed) _commands.send(<Object?>['release', routeId]);
  }

  /// Frees the native engine and stops the background Isolate. Safe to call twice.
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    for (final c in _pending.values) {
      c.completeError(StateError('Engine closed'));
    }
    _pending.clear();
    for (final c in _pendingProgress.values) {
      c.completeError(StateError('Engine closed'));
    }
    _pendingProgress.clear();
    final done = Completer<void>();
    _closeDone = done;
    _commands.send(<Object?>['close']);
    await done.future.timeout(const Duration(seconds: 2), onTimeout: () {});
    _responses.close();
    _isolate.kill(priority: Isolate.immediate);
  }
}
