import 'dart:math' as math;

/// Smooths a compass heading (degrees) without the 359 -> 1 jump problem.
/// [previous] null = first value. [alpha] 0..1: higher follows new values faster.
double smoothHeading(double? previous, double next, {double alpha = 0.3}) {
  final n = normalizeDegrees(next);
  if (previous == null) return n;
  var diff = n - previous;
  while (diff > 180) {
    diff -= 360;
  }
  while (diff < -180) {
    diff += 360;
  }
  return normalizeDegrees(previous + diff * math.min(1.0, math.max(0.0, alpha)));
}

double normalizeDegrees(double d) {
  final r = d % 360;
  return r < 0 ? r + 360 : r;
}
