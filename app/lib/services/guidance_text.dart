import 'dart:math' as math;

import 'package:pathfinder_ffi/pathfinder_ffi.dart';

import '../l10n/strings.dart';

const Map<TurnType, String> _turnKeys = {
  TurnType.depart: 'turnDepart',
  TurnType.straight: 'turnStraight',
  TurnType.slightLeft: 'turnSlightLeft',
  TurnType.left: 'turnLeft',
  TurnType.sharpLeft: 'turnSharpLeft',
  TurnType.slightRight: 'turnSlightRight',
  TurnType.right: 'turnRight',
  TurnType.sharpRight: 'turnSharpRight',
  TurnType.uTurn: 'turnUturn',
  TurnType.arrive: 'turnArrive',
};

String turnText(Strings t, TurnType type) => t(_turnKeys[type] ?? 'turnStraight');

String lowerFirst(String s) => s.isEmpty ? s : s[0].toLowerCase() + s.substring(1);

/// Distance as it is spoken: "50 meters", "300 meters", "1.2 kilometers" / "mita 50" ...
String spokenDistance(Strings t, double meters) {
  final m = (meters.isNaN || meters < 0) ? 0.0 : meters;
  if (m >= 1000) {
    final km = m / 1000;
    return t('vKilometers', {'n': km < 10 ? km.toStringAsFixed(1) : km.round().toString()});
  }
  final rounded = m < 100 ? math.max(10, (m / 10).round() * 10) : (m / 50).round() * 50;
  return t('vMeters', {'n': '$rounded'});
}

/// What to say for a voice cue from the engine. [level]: 1 about 500 m, 2 about 200 m, 3 now.
/// Returns null when there is nothing to say.
String? guidancePhrase(Strings t, TurnType type, int level, double distanceM) {
  if (level <= 0) return null;
  if (type == TurnType.arrive) return t('vArriveIn', {'dist': spokenDistance(t, distanceM)});
  final action = turnText(t, type);
  if (level >= 3) return '$action.';
  return t('vInDist', {'dist': spokenDistance(t, distanceM), 'action': lowerFirst(action)});
}
