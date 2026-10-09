import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

import '../app_controller.dart';
import 'format.dart';

/// Bottom card for every state except navigation.
class StatusPanel extends StatelessWidget {
  const StatusPanel({super.key, required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    switch (c.phase) {
      case AppPhase.loading:
        return const _Card(child: _Busy('Loading offline data...'));
      case AppPhase.calculating:
        return const _Card(child: _Busy('CALCULATING PATH...'));
      case AppPhase.needsData:
        return _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Map data not found', style: theme.textTheme.titleMedium),
              const SizedBox(height: 6),
              const Text('Copy map_graph.bin (and optionally tiles.mbtiles and places.db) into:'),
              const SizedBox(height: 6),
              SelectableText(c.data?.dir ?? '', style: theme.textTheme.bodySmall),
            ],
          ),
        );
      case AppPhase.fatal:
        return _Card(child: Text(c.message ?? 'Error', style: TextStyle(color: theme.colorScheme.error)));
      case AppPhase.routeFailed:
        return _Card(
          child: Row(
            children: [
              Icon(Icons.error_outline, color: theme.colorScheme.error),
              const SizedBox(width: 12),
              Expanded(child: Text(c.message ?? 'No route')),
              TextButton(onPressed: c.clearRoute, child: const Text('Dismiss')),
            ],
          ),
        );
      case AppPhase.routeReady:
        final r = c.route!;
        return _Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('PATH FOUND: ${formatDuration(r.timeS)}', style: theme.textTheme.titleLarge),
              const SizedBox(height: 2),
              Text(
                '${formatDistance(r.distanceM)} to ${c.destination?.name ?? 'destination'}'
                '${c.calcMillis != null ? '  (calculated in ${c.calcMillis} ms)' : ''}',
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: c.startNavigation,
                      icon: const Icon(Icons.navigation),
                      label: const Text('Start'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(onPressed: c.clearRoute, child: const Text('Cancel')),
                ],
              ),
            ],
          ),
        );
      case AppPhase.arrived:
        return _Card(
          child: Row(
            children: [
              const Icon(Icons.flag),
              const SizedBox(width: 12),
              Expanded(child: Text('You have arrived', style: theme.textTheme.titleMedium)),
              FilledButton(onPressed: c.clearRoute, child: const Text('Done')),
            ],
          ),
        );
      case AppPhase.idle:
      case AppPhase.navigating:
        return const SizedBox.shrink();
    }
  }
}

/// Top banner while navigating: next maneuver and distance to it.
class NavBanner extends StatelessWidget {
  const NavBanner({super.key, required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final r = c.route;
    if (r == null || r.steps.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final idx = math.min(r.steps.length - 1, math.max(0, c.progress?.nextStep ?? (r.steps.length > 1 ? 1 : 0)));
    final step = r.steps[idx];
    final dist = c.progress?.toNextM ?? r.steps.first.distanceM;
    return Material(
      color: theme.colorScheme.primary,
      elevation: 3,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Icon(turnIcon(step.type), size: 44, color: theme.colorScheme.onPrimary),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(formatDistance(dist),
                      style: theme.textTheme.headlineSmall?.copyWith(color: theme.colorScheme.onPrimary)),
                  Text(turnText(step.type),
                      style: theme.textTheme.titleMedium?.copyWith(color: theme.colorScheme.onPrimary)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Bottom bar while navigating: progress bar, remaining distance/time, end button.
class NavBottomBar extends StatelessWidget {
  const NavBottomBar({super.key, required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final r = c.route;
    if (r == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final total = r.distanceM <= 0 ? 1.0 : r.distanceM;
    final pr = c.progress;
    final remaining = pr?.remainingM ?? r.distanceM;
    final fraction = pr == null ? 0.0 : (pr.alongM / total).clamp(0.0, 1.0).toDouble();
    final etaSeconds = r.timeS * (remaining / total).clamp(0.0, 1.0).toDouble();
    return _Card(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LinearProgressIndicator(value: fraction, minHeight: 6, borderRadius: BorderRadius.circular(3)),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: c.rerouting
                    ? Row(children: [
                        const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                        const SizedBox(width: 10),
                        Text('Rerouting...', style: theme.textTheme.titleMedium),
                      ])
                    : Text('${formatDuration(etaSeconds)}  |  ${formatDistance(remaining)}',
                        style: theme.textTheme.titleMedium),
              ),
              OutlinedButton(onPressed: c.clearRoute, child: const Text('End')),
            ],
          ),
        ],
      ),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => Material(
        color: Theme.of(context).colorScheme.surface,
        elevation: 4,
        borderRadius: BorderRadius.circular(12),
        child: Padding(padding: const EdgeInsets.all(16), child: child),
      );
}

class _Busy extends StatelessWidget {
  const _Busy(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(text, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 10),
          const LinearProgressIndicator(),
        ],
      );
}
