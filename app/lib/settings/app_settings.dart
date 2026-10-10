import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/strings.dart';

/// User settings. Normal settings live in SharedPreferences; the DeepSeek API key lives in
/// the platform secure storage (Android Keystore / iOS Keychain) and is never logged.
class AppSettings extends ChangeNotifier {
  AppSettings._(this._prefs, this._secure);

  final SharedPreferences _prefs;
  final FlutterSecureStorage _secure;

  AppLanguage language = AppLanguage.en;
  bool voiceOn = true;
  bool headingUp = true;
  bool aiOn = false; // off by default: AI search needs the internet
  bool hasApiKey = false;

  static const _kLang = 'language';
  static const _kVoice = 'voice_on';
  static const _kHeading = 'heading_up';
  static const _kAi = 'ai_on';
  static const _kKey = 'deepseek_api_key';

  static Future<AppSettings> load() async {
    final prefs = await SharedPreferences.getInstance();
    const secure = FlutterSecureStorage();
    final s = AppSettings._(prefs, secure);
    final saved = prefs.getString(_kLang);
    if (saved == 'sw') {
      s.language = AppLanguage.sw;
    } else if (saved == 'en') {
      s.language = AppLanguage.en;
    } else {
      // first start: follow the phone language
      final code = WidgetsBinding.instance.platformDispatcher.locale.languageCode;
      s.language = code == 'sw' ? AppLanguage.sw : AppLanguage.en;
    }
    s.voiceOn = prefs.getBool(_kVoice) ?? true;
    s.headingUp = prefs.getBool(_kHeading) ?? true;
    s.aiOn = prefs.getBool(_kAi) ?? false;
    try {
      final key = await secure.read(key: _kKey);
      s.hasApiKey = key != null && key.isNotEmpty;
    } catch (_) {
      s.hasApiKey = false;
    }
    return s;
  }

  Future<void> setLanguage(AppLanguage v) async {
    language = v;
    await _prefs.setString(_kLang, v.name);
    notifyListeners();
  }

  Future<void> setVoiceOn(bool v) async {
    voiceOn = v;
    await _prefs.setBool(_kVoice, v);
    notifyListeners();
  }

  Future<void> setHeadingUp(bool v) async {
    headingUp = v;
    await _prefs.setBool(_kHeading, v);
    notifyListeners();
  }

  Future<void> setAiOn(bool v) async {
    aiOn = v;
    await _prefs.setBool(_kAi, v);
    notifyListeners();
  }

  Future<String?> readApiKey() async {
    try {
      return await _secure.read(key: _kKey);
    } catch (_) {
      return null;
    }
  }

  Future<void> saveApiKey(String key) async {
    final k = key.trim();
    if (k.isEmpty || k.length > 200 || k.contains(RegExp(r'\s'))) return; // keys never contain spaces
    await _secure.write(key: _kKey, value: k);
    hasApiKey = true;
    notifyListeners();
  }

  Future<void> removeApiKey() async {
    await _secure.delete(key: _kKey);
    hasApiKey = false;
    notifyListeners();
  }
}
