import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../app_controller.dart';
import '../data/places_db.dart';
import '../services/location_service.dart';
import 'data_screen.dart';
import 'panels.dart';
import 'search_screen.dart';
import 'settings_screen.dart';

class MapScreen extends StatefulWidget {
  const MapScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  final MapController _map = MapController();
  bool _centeredOnUser = false;
  bool _centeredOnData = false;
  bool _wasNavigating = false;
  int? _fittedRouteId;

  AppController get c => widget.controller;

  @override
  void initState() {
    super.initState();
    c.addListener(_onChange);
  }

  @override
  void dispose() {
    c.removeListener(_onChange);
    _map.dispose();
    super.dispose();
  }

  bool get _headingUpActive => c.settings.headingUp && c.heading != null;

  // Moves the camera. The map may not be ready on the first calls, so failures are retried later.
  void _onChange() {
    try {
      final user = c.user;
      if (c.phase == AppPhase.navigating && user != null) {
        // Heading-up: rotate the map by -heading so the way you travel points to the top of the screen.
        final rotation = _headingUpActive ? -c.heading! : 0.0;
        _map.moveAndRotate(user, 17.5, rotation);
        _wasNavigating = true;
        return;
      }
      if (_wasNavigating) {
        _map.rotate(0); // back to north-up when navigation ends
        _wasNavigating = false;
      }
      if (!_centeredOnUser && user != null) {
        _map.move(user, 15);
        _centeredOnUser = true;
      } else if (!_centeredOnUser && !_centeredOnData && c.tiles?.info.center != null) {
        _map.move(c.tiles!.info.center!, 12);
        _centeredOnData = true;
      }
      final r = c.route;
      if (r != null && c.phase == AppPhase.routeReady && _fittedRouteId != r.routeId && c.routePoints.length > 1) {
        _map.fitCamera(CameraFit.bounds(
          bounds: LatLngBounds.fromPoints(c.routePoints),
          padding: const EdgeInsets.fromLTRB(40, 110, 40, 230),
        ));
        _fittedRouteId = r.routeId;
      }
    } catch (_) {
      // map not attached yet
    }
  }

  Future<void> _openSearch() async {
    final place = await Navigator.of(context).push<Place>(
      MaterialPageRoute(builder: (_) => SearchScreen(controller: c)),
    );
    if (place != null) await c.chooseDestination(place);
  }

  bool get _canManageData =>
      c.phase == AppPhase.idle || c.phase == AppPhase.needsData || c.phase == AppPhase.routeFailed || c.phase == AppPhase.fatal;

  void _openData() {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => DataScreen(controller: c)));
  }

  void _openSettings() {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => SettingsScreen(controller: c)));
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final theme = Theme.of(context);
        final t = c.t;
        final navigating = c.phase == AppPhase.navigating;
        final tiles = c.tiles;
        final canPlan = c.phase == AppPhase.idle || c.phase == AppPhase.routeFailed;
        final headingUp = navigating && _headingUpActive;
        return Scaffold(
          body: Stack(
            children: [
              FlutterMap(
                mapController: _map,
                options: MapOptions(
                  initialCenter: const LatLng(0, 0),
                  initialZoom: 2,
                  minZoom: 1,
                  maxZoom: 19,
                  interactionOptions: InteractionOptions(
                    flags: navigating ? InteractiveFlag.none : (InteractiveFlag.all & ~InteractiveFlag.rotate),
                  ),
                  onLongPress: (_, point) {
                    if (canPlan || c.phase == AppPhase.routeReady) {
                      c.chooseDestination(Place.dropped(point.latitude, point.longitude, t('droppedPin')));
                    }
                  },
                ),
                children: [
                  if (tiles != null)
                    TileLayer(
                      tileProvider: tiles,
                      minZoom: tiles.info.minZoom.toDouble(),
                      maxNativeZoom: tiles.info.maxZoom,
                      maxZoom: 19,
                    ),
                  if (c.routePoints.length > 1)
                    PolylineLayer(polylines: [
                      Polyline(
                        points: c.routePoints,
                        strokeWidth: 6,
                        color: theme.colorScheme.primary,
                        borderStrokeWidth: 2,
                        borderColor: Colors.white,
                      ),
                    ]),
                  MarkerLayer(markers: [
                    if (c.destination != null)
                      Marker(
                        point: LatLng(c.destination!.lat, c.destination!.lon),
                        width: 40,
                        height: 40,
                        alignment: Alignment.topCenter,
                        rotate: true, // stay upright when the map rotates
                        child: Icon(Icons.location_on, size: 40, color: Colors.red.shade700),
                      ),
                    if (c.user != null)
                      Marker(
                        point: c.user!,
                        width: 36,
                        height: 36,
                        rotate: headingUp, // heading-up: arrow stays pointing to the top of the screen
                        child: navigating
                            ? _Arrow(angleDeg: headingUp ? 0 : (c.heading ?? 0), color: theme.colorScheme.primary)
                            : _Dot(color: theme.colorScheme.primary),
                      ),
                  ]),
                ],
              ),
              SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    children: [
                      if (navigating)
                        NavBanner(c: c)
                      else
                        Row(children: [
                          Expanded(child: _SearchBar(label: t('whereTo'), onTap: canPlan || c.phase == AppPhase.routeReady ? _openSearch : null)),
                          const SizedBox(width: 8),
                          Material(
                            color: theme.colorScheme.surface,
                            elevation: 3,
                            shape: const CircleBorder(),
                            child: IconButton(
                              tooltip: t('settings'),
                              icon: const Icon(Icons.settings),
                              onPressed: _openSettings,
                            ),
                          ),
                        ]),
                      const SizedBox(height: 8),
                      Align(alignment: Alignment.centerLeft, child: _StatusChips(c: c, onData: _canManageData ? _openData : null)),
                      const Spacer(),
                      if (navigating) NavBottomBar(c: c) else StatusPanel(c: c, onManageData: _canManageData ? _openData : null),
                    ],
                  ),
                ),
              ),
            ],
          ),
          floatingActionButton: _buildFabs(navigating),
        );
      },
    );
  }

  Widget? _buildFabs(bool navigating) {
    final s = c.settings;
    final tr = c.t;
    if (navigating) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 120),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          FloatingActionButton.small(
            heroTag: 'voice',
            tooltip: s.voiceOn ? tr('voiceOnTip') : tr('voiceOffTip'),
            onPressed: () => s.setVoiceOn(!s.voiceOn),
            child: Icon(s.voiceOn ? Icons.volume_up : Icons.volume_off),
          ),
          const SizedBox(height: 8),
          FloatingActionButton.small(
            heroTag: 'rotate',
            tooltip: s.headingUp ? tr('headingUpTip') : tr('northUpTip'),
            onPressed: () => s.setHeadingUp(!s.headingUp),
            child: Icon(s.headingUp ? Icons.navigation : Icons.explore),
          ),
        ]),
      );
    }
    if (c.user == null) return null;
    return Padding(
      padding: EdgeInsets.only(bottom: c.phase == AppPhase.idle ? 0 : 150),
      child: FloatingActionButton.small(
        heroTag: 'locate',
        tooltip: tr('myLocation'),
        onPressed: () => _map.move(c.user!, 16),
        child: const Icon(Icons.my_location),
      ),
    );
  }
}

class _Dot extends StatelessWidget {
  const _Dot({required this.color});
  final Color color;

  @override
  Widget build(BuildContext context) => Center(
        child: Container(
          width: 24,
          height: 24,
          decoration: BoxDecoration(
            color: color,
            shape: BoxShape.circle,
            border: Border.all(color: Colors.white, width: 3),
          ),
        ),
      );
}

class _Arrow extends StatelessWidget {
  const _Arrow({required this.angleDeg, required this.color});
  final double angleDeg;
  final Color color;

  @override
  Widget build(BuildContext context) => Transform.rotate(
        angle: angleDeg * math.pi / 180,
        child: Icon(Icons.navigation, size: 34, color: color, shadows: const [Shadow(color: Colors.white, blurRadius: 6)]),
      );
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({required this.label, required this.onTap});
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.surface,
      elevation: 3,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          child: Row(
            children: [
              Icon(Icons.search, color: theme.colorScheme.outline),
              const SizedBox(width: 10),
              Text(label, style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.outline)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Small status chips: offline mode, GPS problems, map-file problems.
class _StatusChips extends StatelessWidget {
  const _StatusChips({required this.c, this.onData});
  final AppController c;
  final VoidCallback? onData;

  @override
  Widget build(BuildContext context) {
    final t = c.t;
    final chips = <Widget>[
      _Chip(Icons.cloud_off, c.usesCh ? '${t('offlineMode')} | ${t('fastRouting')}' : t('offlineMode'), onTap: onData),
    ];
    switch (c.locationState) {
      case LocationState.denied:
      case LocationState.deniedForever:
        chips.add(_Chip(Icons.location_disabled, t('locationNeeded')));
      case LocationState.serviceOff:
        chips.add(_Chip(Icons.location_off, t('gpsOff')));
      case LocationState.ready:
        if (c.user == null) chips.add(_Chip(Icons.gps_not_fixed, t('waitingGps')));
      case LocationState.unknown:
        break;
    }
    if (c.tilesProblemKey != null) chips.add(_Chip(Icons.map_outlined, t(c.tilesProblemKey!)));
    if (c.data != null && !c.data!.hasTiles) chips.add(_Chip(Icons.map_outlined, t('noTiles')));
    if (c.settings.voiceOn && !c.voiceAvailable) chips.add(_Chip(Icons.volume_off, t('voiceMissingShort', {'lang': t.languageName})));
    return Wrap(spacing: 6, runSpacing: 4, children: chips);
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.icon, this.text, {this.onTap});
  final IconData icon;
  final String text;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.surface,
      elevation: 1,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 14),
            const SizedBox(width: 5),
            Text(text, style: theme.textTheme.labelMedium),
            if (onTap != null) ...[const SizedBox(width: 4), const Icon(Icons.chevron_right, size: 14)],
          ]),
        ),
      ),
    );
  }
}
