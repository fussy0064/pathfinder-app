// Needs a host-built library and graph (see mobile/README.md):
//   PATHFINDER_LIB=/path/libpathfinder.so PATHFINDER_GRAPH=/path/tiny.bin flutter test
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

void main() {
  final lib = Platform.environment['PATHFINDER_LIB'];
  final graph = Platform.environment['PATHFINDER_GRAPH'];
  final skip = (lib == null || graph == null) ? 'set PATHFINDER_LIB and PATHFINDER_GRAPH' : false;

  test('GeoPoint rejects bad values', () {
    expect(() => GeoPoint(91, 0), throwsArgumentError);
    expect(() => GeoPoint(0, 181), throwsArgumentError);
    expect(() => GeoPoint(double.nan, 0), throwsArgumentError);
    expect(() => GeoPoint(10, 10), returnsNormally);
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
    expect(e.usesCh, isFalse); // the sample has no .ch file
    final r = await e.route(GeoPoint(10.0, 10.0), GeoPoint(10.002, 10.002));
    expect(r.pointCount, 5);
    expect(r.distanceM, closeTo(442, 3));
    expect(r.timeS, closeTo(35.8, 0.5));
    expect(r.steps.map((s) => s.type).toList(), [TurnType.depart, TurnType.right, TurnType.arrive]);
    expect(r.steps[0].distanceM, closeTo(222, 3));
    final p = await e.progress(r.routeId, GeoPoint(10.001, 10.0));
    expect(p.alongM, closeTo(111, 3));
    expect(p.offRouteM, lessThan(1));
    expect(p.nextStep, 1);
    expect(p.bearingDeg, closeTo(0, 1)); // first leg goes north
    expect(p.announce, inInclusiveRange(0, 3));
    e.releaseRoute(r.routeId);
    await expectLater(e.progress(r.routeId, GeoPoint(10, 10)), throwsA(isA<PathfinderException>()));
    await expectLater(
      e.route(GeoPoint(10.002, 10.002), GeoPoint(10.0, 10.0)),
      throwsA(isA<PathfinderException>().having((x) => x.status, 'status', kStatusNoRoute)),
    );
    await e.close();
    await e.close(); // second close is safe
    await expectLater(e.route(GeoPoint(10, 10), GeoPoint(10.001, 10)), throwsStateError);
  }, skip: skip);
}
