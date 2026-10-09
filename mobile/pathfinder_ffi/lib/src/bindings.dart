import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';

DynamicLibrary openPathfinderLibrary([String? overridePath]) {
  if (overridePath != null) return DynamicLibrary.open(overridePath);
  if (Platform.isAndroid || Platform.isLinux) return DynamicLibrary.open('libpathfinder.so');
  if (Platform.isIOS || Platform.isMacOS) return DynamicLibrary.process();
  throw UnsupportedError('Unsupported platform: ${Platform.operatingSystem}');
}

// Opaque C types are used as Pointer<Void>, so there is no struct layout to get wrong.
class PathfinderBindings {
  PathfinderBindings(DynamicLibrary lib)
      : engineOpen = lib.lookupFunction<Pointer<Void> Function(Pointer<Utf8>, Pointer<Int32>),
            Pointer<Void> Function(Pointer<Utf8>, Pointer<Int32>)>('pf_engine_open'),
        engineClose = lib.lookupFunction<Void Function(Pointer<Void>), void Function(Pointer<Void>)>(
            'pf_engine_close'),
        nodeCount = lib.lookupFunction<Uint32 Function(Pointer<Void>), int Function(Pointer<Void>)>(
            'pf_engine_node_count'),
        routeCompute = lib.lookupFunction<
            Int32 Function(Pointer<Void>, Double, Double, Double, Double, Pointer<Pointer<Void>>),
            int Function(Pointer<Void>, double, double, double, double,
                Pointer<Pointer<Void>>)>('pf_route_compute'),
        distanceM = lib.lookupFunction<Double Function(Pointer<Void>), double Function(Pointer<Void>)>(
            'pf_route_handle_distance_m'),
        timeS = lib.lookupFunction<Double Function(Pointer<Void>), double Function(Pointer<Void>)>(
            'pf_route_handle_time_s'),
        pointCount = lib.lookupFunction<Uint32 Function(Pointer<Void>), int Function(Pointer<Void>)>(
            'pf_route_handle_point_count'),
        lats = lib.lookupFunction<Pointer<Double> Function(Pointer<Void>),
            Pointer<Double> Function(Pointer<Void>)>('pf_route_handle_lats'),
        lons = lib.lookupFunction<Pointer<Double> Function(Pointer<Void>),
            Pointer<Double> Function(Pointer<Void>)>('pf_route_handle_lons'),
        handleFree = lib.lookupFunction<Void Function(Pointer<Void>), void Function(Pointer<Void>)>(
            'pf_route_handle_free'),
        usesCh = lib.lookupFunction<Int32 Function(Pointer<Void>), int Function(Pointer<Void>)>(
            'pf_engine_uses_ch'),
        stepCount = lib.lookupFunction<Uint32 Function(Pointer<Void>), int Function(Pointer<Void>)>(
            'pf_route_handle_step_count'),
        stepTypes = lib.lookupFunction<Pointer<Int32> Function(Pointer<Void>),
            Pointer<Int32> Function(Pointer<Void>)>('pf_route_handle_step_types'),
        stepPoints = lib.lookupFunction<Pointer<Uint32> Function(Pointer<Void>),
            Pointer<Uint32> Function(Pointer<Void>)>('pf_route_handle_step_points'),
        stepDists = lib.lookupFunction<Pointer<Double> Function(Pointer<Void>),
            Pointer<Double> Function(Pointer<Void>)>('pf_route_handle_step_dists'),
        progress = lib.lookupFunction<
            Int32 Function(Pointer<Void>, Double, Double, Pointer<Double>, Pointer<Int32>),
            int Function(Pointer<Void>, double, double, Pointer<Double>,
                Pointer<Int32>)>('pf_route_handle_progress');

  final Pointer<Void> Function(Pointer<Utf8>, Pointer<Int32>) engineOpen;
  final void Function(Pointer<Void>) engineClose;
  final int Function(Pointer<Void>) nodeCount;
  final int Function(Pointer<Void>, double, double, double, double, Pointer<Pointer<Void>>)
      routeCompute;
  final double Function(Pointer<Void>) distanceM;
  final double Function(Pointer<Void>) timeS;
  final int Function(Pointer<Void>) pointCount;
  final Pointer<Double> Function(Pointer<Void>) lats;
  final Pointer<Double> Function(Pointer<Void>) lons;
  final void Function(Pointer<Void>) handleFree;
  final int Function(Pointer<Void>) usesCh;
  final int Function(Pointer<Void>) stepCount;
  final Pointer<Int32> Function(Pointer<Void>) stepTypes;
  final Pointer<Uint32> Function(Pointer<Void>) stepPoints;
  final Pointer<Double> Function(Pointer<Void>) stepDists;
  final int Function(Pointer<Void>, double, double, Pointer<Double>, Pointer<Int32>) progress;
}
