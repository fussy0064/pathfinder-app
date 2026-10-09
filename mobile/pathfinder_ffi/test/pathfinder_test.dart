// Needs a host-built library and graph (see mobile/README.md):
//   PATHFINDER_LIB=/path/libpathfinder.so PATHFINDER_GRAPH=/path/tiny.bin flutter test
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

void main() {
  final lib = Platform.environment['PATHFINDER_LIB'];
  final graph = Platform.environment['PATHFINDER_GRAPH'];
  final skip = (lib == null || graph == null) ? 'set PATHFINDER_LIB and PATHFINDER_GRAPH' : false;

  test('LatLng rejects bad values', () {
    expect(() => LatLng(91, 0), throwsArgumentError);
    expect(() => LatLng(0, 181), throwsArgumentError);
    expect(() => LatLng(double.nan, 0), throwsArgumentError);
    expect(() => LatLng(10, 10), returnsNormally);
  });

  test('open fails for a missing file', () async {
    expect(
      PathfinderEngine.open('/nonexistent/map.bin', libraryPath: lib),
      throwsA(isA<PathfinderException>()),
    );
  }, skip: lib == null ? 'set PATHFINDER_LIB' : false);

  test('route on the sample map', () async {
    final e = await PathfinderEngine.open(graph!, libraryPath: lib);
    expect(e.nodeCount, 9);
    final r = await e.route(LatLng(10.0, 10.0), LatLng(10.002, 10.002));
    expect(r.pointCount, 5);
    expect(r.distanceM, closeTo(442, 3));
    expect(r.timeS, closeTo(35.8, 0.5));
    await expectLater(
      e.route(LatLng(10.002, 10.002), LatLng(10.0, 10.0)),
      throwsA(isA<PathfinderException>().having((x) => x.status, 'status', kStatusNoRoute)),
    );
    await e.close();
    await e.close(); // second close is safe
    await expectLater(e.route(LatLng(10, 10), LatLng(10.001, 10)), throwsStateError);
  }, skip: skip);
}
