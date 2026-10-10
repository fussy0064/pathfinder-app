import 'package:flutter/material.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

import '../l10n/strings.dart';

String formatDistance(double m) {
  if (m.isNaN || m < 0) return '-';
  if (m < 1000) {
    final r = m < 100 ? (m / 5).round() * 5 : (m / 10).round() * 10;
    return '$r m';
  }
  return '${(m / 1000).toStringAsFixed(1)} km';
}

String formatDuration(Strings t, double seconds) {
  if (seconds.isNaN || seconds < 0) return '-';
  final min = (seconds / 60).round();
  if (min < 1) return t('lessThanMin');
  if (min < 60) return t('minutes', {'n': '$min'});
  return t('hoursMinutes', {'h': '${min ~/ 60}', 'm': (min % 60).toString().padLeft(2, '0')});
}

IconData turnIcon(TurnType type) {
  switch (type) {
    case TurnType.depart:
      return Icons.navigation;
    case TurnType.straight:
      return Icons.straight;
    case TurnType.slightLeft:
      return Icons.turn_slight_left;
    case TurnType.left:
      return Icons.turn_left;
    case TurnType.sharpLeft:
      return Icons.turn_sharp_left;
    case TurnType.slightRight:
      return Icons.turn_slight_right;
    case TurnType.right:
      return Icons.turn_right;
    case TurnType.sharpRight:
      return Icons.turn_sharp_right;
    case TurnType.uTurn:
      return Icons.u_turn_left;
    case TurnType.arrive:
      return Icons.flag;
  }
}
