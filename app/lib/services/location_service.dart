import 'package:geolocator/geolocator.dart';

enum LocationState { unknown, ready, denied, deniedForever, serviceOff }

class LocationService {
  Future<LocationState> ensurePermission() async {
    if (!await Geolocator.isLocationServiceEnabled()) return LocationState.serviceOff;
    var perm = await Geolocator.checkPermission();
    if (perm == LocationPermission.denied) perm = await Geolocator.requestPermission();
    if (perm == LocationPermission.deniedForever) return LocationState.deniedForever;
    if (perm == LocationPermission.denied) return LocationState.denied;
    return LocationState.ready;
  }

  Stream<Position> positions() => Geolocator.getPositionStream(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.best, distanceFilter: 3),
      );
}
