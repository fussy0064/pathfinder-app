// Runs inside a background Isolate. It owns the native engine pointer and every route handle.
// Messages are plain lists / typed data so they can cross the Isolate boundary safely.
import 'dart:ffi';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

import 'bindings.dart';
import 'models.dart';

void workerMain(List<Object?> init) {
  final reply = init[0] as SendPort;
  final graphPath = init[1] as String;
  final libPath = init[2] as String?;

  PathfinderBindings bindings;
  try {
    bindings = PathfinderBindings(openPathfinderLibrary(libPath));
  } catch (_) {
    reply.send(<Object?>['open_error', kStatusLibrary]);
    return;
  }

  Pointer<Void> engine = nullptr;
  final pathPtr = graphPath.toNativeUtf8();
  final statusPtr = calloc<Int32>();
  try {
    engine = bindings.engineOpen(pathPtr, statusPtr);
    if (engine == nullptr) {
      reply.send(<Object?>['open_error', statusPtr.value]);
      return;
    }
  } finally {
    calloc.free(pathPtr);
    calloc.free(statusPtr);
  }

  final handleOut = calloc<Pointer<Void>>();
  final out4 = calloc<Double>(4);
  final nextStepOut = calloc<Int32>();
  final routes = <int, Pointer<Void>>{};
  var nextRouteId = 1;
  final commands = ReceivePort();

  void releaseAll() {
    for (final h in routes.values) {
      bindings.handleFree(h);
    }
    routes.clear();
  }

  commands.listen((dynamic message) {
    final msg = message as List<Object?>;
    switch (msg[0] as String) {
      case 'route':
        final id = msg[1] as int;
        handleOut.value = nullptr;
        final status = bindings.routeCompute(
            engine, msg[2] as double, msg[3] as double, msg[4] as double, msg[5] as double, handleOut);
        if (status != kStatusOk) {
          reply.send(<Object?>['result', id, status, 0, 0.0, 0.0, null, null, null, null, null]);
          return;
        }
        final h = handleOut.value;
        final routeId = nextRouteId++;
        routes[routeId] = h; // kept alive for progress() until releaseRoute
        final n = bindings.pointCount(h);
        final s = bindings.stepCount(h);
        // Copy out of native memory (the handle stays alive, but Dart objects must not point into it).
        reply.send(<Object?>[
          'result', id, kStatusOk, routeId, bindings.distanceM(h), bindings.timeS(h),
          Float64List.fromList(bindings.lats(h).asTypedList(n)),
          Float64List.fromList(bindings.lons(h).asTypedList(n)),
          Int32List.fromList(bindings.stepTypes(h).asTypedList(s)),
          Uint32List.fromList(bindings.stepPoints(h).asTypedList(s)),
          Float64List.fromList(bindings.stepDists(h).asTypedList(s)),
        ]);
      case 'progress':
        final id = msg[1] as int;
        final h = routes[msg[2] as int];
        if (h == null) {
          reply.send(<Object?>['progress', id, kStatusBadArgs, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0]);
          return;
        }
        final status = bindings.progress(h, msg[3] as double, msg[4] as double, out4, nextStepOut);
        final announce = status == kStatusOk ? bindings.takeAnnounce(h) : 0;
        reply.send(<Object?>[
          'progress', id, status, out4[0], out4[1], out4[2], out4[3], nextStepOut.value,
          announce, bindings.bearingDeg(h),
        ]);
      case 'release':
        final h = routes.remove(msg[1] as int);
        if (h != null) bindings.handleFree(h);
      case 'close':
        releaseAll();
        bindings.engineClose(engine);
        calloc.free(handleOut);
        calloc.free(out4);
        calloc.free(nextStepOut);
        reply.send(<Object?>['closed']);
        commands.close();
    }
  });

  reply.send(<Object?>['ready', commands.sendPort, bindings.nodeCount(engine), bindings.usesCh(engine)]);
}
