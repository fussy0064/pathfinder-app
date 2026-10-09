import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_app/data/places_db.dart';
import 'package:pathfinder_app/ui/format.dart';

void main() {
  test('formatDistance', () {
    expect(formatDistance(42), '40 m');
    expect(formatDistance(437), '440 m');
    expect(formatDistance(999), '1000 m');
    expect(formatDistance(1500), '1.5 km');
    expect(formatDistance(double.nan), '-');
  });

  test('formatDuration', () {
    expect(formatDuration(20), '<1 min');
    expect(formatDuration(25 * 60), '25 min');
    expect(formatDuration(3900), '1 h 05 min');
  });

  test('escapeLike makes wildcards literal', () {
    expect(PlacesDb.escapeLike('100%_x'), r'100\%\_x');
    expect(PlacesDb.escapeLike(r'a\b'), r'a\\b');
  });

  test('Place.kindLabel', () {
    expect(Place(id: 1, name: 'x', kind: 'amenity:fast_food', lat: 0, lon: 0).kindLabel, 'Fast food');
    expect(Place(id: 1, name: 'x', kind: 'street', lat: 0, lon: 0).kindLabel, 'Street');
  });
}
