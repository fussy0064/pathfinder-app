import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_app/l10n/strings.dart';
import 'package:pathfinder_app/services/guidance_text.dart';
import 'package:pathfinder_app/services/heading.dart';
import 'package:pathfinder_ffi/pathfinder_ffi.dart';

void main() {
  const en = Strings(AppLanguage.en);
  const sw = Strings(AppLanguage.sw);

  group('Strings', () {
    test('both languages answer, unknown keys fall back to the key', () {
      expect(en('start'), 'Start');
      expect(sw('start'), 'Anza');
      expect(en('doesNotExist'), 'doesNotExist');
      expect(sw('pathFound', {'time': 'dk 5'}), 'NJIA IMEPATIKANA: dk 5');
      expect(en.voiceLocales.first, 'en-US');
      expect(sw.voiceLocales.first, 'sw-TZ');
      expect(sw.languageName, 'Kiswahili');
    });
  });

  group('spoken distance', () {
    test('English', () {
      expect(spokenDistance(en, 4), '10 meters');
      expect(spokenDistance(en, 47), '50 meters');
      expect(spokenDistance(en, 180), '200 meters');
      expect(spokenDistance(en, 1200), '1.2 kilometers');
      expect(spokenDistance(en, 12000), '12 kilometers');
      expect(spokenDistance(en, double.nan), '10 meters');
    });

    test('Swahili', () {
      expect(spokenDistance(sw, 180), 'mita 200');
      expect(spokenDistance(sw, 1200), 'kilomita 1.2');
    });
  });

  group('voice phrases', () {
    test('English', () {
      expect(guidancePhrase(en, TurnType.left, 2, 180), 'In 200 meters, turn left.');
      expect(guidancePhrase(en, TurnType.slightRight, 1, 480), 'In 500 meters, bear right.');
      expect(guidancePhrase(en, TurnType.right, 3, 30), 'Turn right.');
      expect(guidancePhrase(en, TurnType.uTurn, 2, 150), 'In 150 meters, make a U-turn.');
      expect(guidancePhrase(en, TurnType.arrive, 2, 190), 'In 200 meters, you will arrive at your destination.');
      expect(guidancePhrase(en, TurnType.left, 0, 100), isNull);
    });

    test('Swahili', () {
      expect(guidancePhrase(sw, TurnType.left, 2, 180), 'Baada ya mita 200, geuka kushoto.');
      expect(guidancePhrase(sw, TurnType.right, 3, 30), 'Geuka kulia.');
      expect(guidancePhrase(sw, TurnType.slightLeft, 1, 480), 'Baada ya mita 500, elekea kushoto kidogo.');
      expect(guidancePhrase(sw, TurnType.arrive, 2, 190), 'Baada ya mita 200, utafika unakoenda.');
    });

    test('every maneuver has text in both languages', () {
      for (final type in TurnType.values) {
        expect(turnText(en, type), isNotEmpty, reason: '$type English');
        expect(turnText(sw, type), isNotEmpty, reason: '$type Swahili');
        expect(turnText(sw, type), isNot(turnText(en, type)), reason: '$type differs');
      }
    });
  });

  group('heading', () {
    test('first value is normalized', () {
      expect(smoothHeading(null, 370), closeTo(10, 1e-9));
      expect(smoothHeading(null, -10), closeTo(350, 1e-9));
    });

    test('wraps around north the short way', () {
      expect(smoothHeading(350, 10, alpha: 0.5), closeTo(0, 1e-9));
      expect(smoothHeading(10, 350, alpha: 0.5), closeTo(0, 1e-9));
      expect(smoothHeading(90, 100, alpha: 0.5), closeTo(95, 1e-9));
    });

    test('alpha limits', () {
      expect(smoothHeading(100, 200, alpha: 1), closeTo(200, 1e-9));
      expect(smoothHeading(100, 200, alpha: 0), closeTo(100, 1e-9));
      expect(smoothHeading(100, 200, alpha: 5), closeTo(200, 1e-9)); // clamped
    });
  });
}
