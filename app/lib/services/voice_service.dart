import 'package:flutter_tts/flutter_tts.dart';

/// Speaks with the phone's own text-to-speech engine (works offline).
/// A Swahili voice must be installed on the phone; [available] tells if one was found.
class VoiceService {
  final FlutterTts _tts = FlutterTts();
  bool available = false;
  String? _lastText;
  DateTime _lastAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// Picks the first locale that the phone can speak. Returns true when one was found.
  Future<bool> setLocales(List<String> locales) async {
    available = false;
    try {
      for (final loc in locales) {
        final ok = await _tts.isLanguageAvailable(loc);
        if (ok == true || ok == 1) {
          await _tts.setLanguage(loc);
          await _tts.setSpeechRate(0.5);
          await _tts.setVolume(1.0);
          available = true;
          break;
        }
      }
    } catch (_) {
      available = false;
    }
    return available;
  }

  /// Interrupts anything being said. Repeats of the same sentence within 3 seconds are ignored.
  Future<void> speak(String text) async {
    if (!available || text.isEmpty) return;
    final now = DateTime.now();
    if (text == _lastText && now.difference(_lastAt).inSeconds < 3) return;
    _lastText = text;
    _lastAt = now;
    try {
      await _tts.stop();
      await _tts.speak(text);
    } catch (_) {
      // voice problems must never break navigation
    }
  }

  Future<void> stop() async {
    try {
      await _tts.stop();
    } catch (_) {}
  }
}
