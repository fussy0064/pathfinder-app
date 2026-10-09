import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../app_controller.dart';
import '../data/places_db.dart';
import '../services/location_service.dart';
import 'panels.dart';
import 'search_screen.dart';

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

  // Moves the camera. The map may not be ready on the first calls, so failures are retried later.
  void _onChange() {
    try {
      final user = c.user;
      if (c.phase == AppPhase.navigating && user != null) {
        _map.move(user, 17.5); // follow the user
        return;
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
      MaterialPageRoute(builder: (_) => SearchScreen(db: c.places)),
    );
    if (place != null) await c.chooseDestination(place);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final theme = Theme.of(context);
        final navigating = c.phase == AppPhase.navigating;
        final tiles = c.tiles;
        final canPlan = c.phase == AppPhase.idle || c.phase == AppPhase.routeFailed;
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
                      c.chooseDestination(Place.dropped(point.latitude, point.longitude));
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
                        child: Icon(Icons.location_on, size: 40, color: Colors.red.shade700),
                      ),
                    if (c.user != null)
                      Marker(
                        point: c.user!,
                        width: 24,
                        height: 24,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: theme.colorScheme.primary,
                            shape: BoxShape.circle,
                            border: Border.all(color: Colors.white, width: 3),
                          ),
                        ),
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
                        _SearchBar(onTap: canPlan || c.phase == AppPhase.routeReady ? _openSearch : null),
                      const SizedBox(height: 8),
                      Align(alignment: Alignment.centerLeft, child: _StatusChips(c: c)),
                      const Spacer(),
                      if (navigating) NavBottomBar(c: c) else StatusPanel(c: c),
                    ],
                  ),
                ),
              ),
            ],
          ),
          floatingActionButton: navigating || c.user == null
              ? null
              : Padding(
                  padding: EdgeInsets.only(bottom: c.phase == AppPhase.idle ? 0 : 150),
                  child: FloatingActionButton.small(
                    tooltip: 'My location',
                    onPressed: () => _map.move(c.user!, 16),
                    child: const Icon(Icons.my_location),
                  ),
                ),
        );
      },
    );
  }
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({required this.onTap});
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
              Text('Where to?', style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.outline)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Small status chips: offline mode, GPS problems, map-file problems.
class _StatusChips extends StatelessWidget {
  const _StatusChips({required this.c});
  final AppController c;

  @override
  Widget build(BuildContext context) {
    final chips = <Widget>[
      const _Chip(Icons.cloud_off, 'Offline mode'),
    ];
    switch (c.locationState) {
      case LocationState.denied:
      case LocationState.deniedForever:
        chips.add(const _Chip(Icons.location_disabled, 'Location permission needed'));
      case LocationState.serviceOff:
        chips.add(const _Chip(Icons.location_off, 'GPS is off'));
      case LocationState.ready:
        if (c.user == null) chips.add(const _Chip(Icons.gps_not_fixed, 'Waiting for GPS'));
      case LocationState.unknown:
        break;
    }
    if (c.tilesProblem != null) chips.add(_Chip(Icons.map_outlined, c.tilesProblem!));
    if (c.data != null && !c.data!.hasTiles) chips.add(const _Chip(Icons.map_outlined, 'No map tiles'));
    return Wrap(spacing: 6, runSpacing: 4, children: chips);
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.icon, this.text);
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: theme.colorScheme.surface,
      elevation: 1,
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 14),
          const SizedBox(width: 5),
          Text(text, style: theme.textTheme.labelMedium),
        ]),
      ),
    );
  }
}
