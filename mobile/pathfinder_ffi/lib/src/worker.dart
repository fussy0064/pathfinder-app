// Runs inside a background Isolate. It owns the native engine pointer.
// Messages are plain lists so they can cross the Isolate boundary safely.
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
  final commands = ReceivePort();

  commands.listen((dynamic message) {
    final msg = message as List<Object?>;
    switch (msg[0] as String) {
      case 'route':
        final id = msg[1] as int;
        handleOut.value = nullptr;
        final status = bindings.routeCompute(
            engine, msg[2] as double, msg[3] as double, msg[4] as double, msg[5] as double, handleOut);
        if (status != kStatusOk) {
          reply.send(<Object?>['result', id, status, 0.0, 0.0, null, null]);
          return;
        }
        final h = handleOut.value;
        try {
          final n = bindings.pointCount(h);
          // Copy out of native memory before the handle is freed.
          final lats = Float64List.fromList(bindings.lats(h).asTypedList(n));
          final lons = Float64List.fromList(bindings.lons(h).asTypedList(n));
          reply.send(<Object?>['result', id, kStatusOk, bindings.distanceM(h), bindings.timeS(h), lats, lons]);
        } finally {
          bindings.handleFree(h);
        }
      case 'close':
        bindings.engineClose(engine);
        calloc.free(handleOut);
        reply.send(<Object?>['closed']);
        commands.close();
    }
  });

  reply.send(<Object?>['ready', commands.sendPort, bindings.nodeCount(engine)]);
}
