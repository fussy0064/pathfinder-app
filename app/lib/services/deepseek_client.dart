// Optional AI search with DeepSeek (https://api.deepseek.com, OpenAI-style chat API).
//
// Privacy and safety rules:
//  - Only the words the user typed are sent. Never location, routes or map data.
//  - The answer is NOT trusted: the model may only pick "kinds" from a fixed list; "names" are
//    stripped to plain letters/digits. The offline database does the real search (parameterized SQL).
//  - https only, host fixed to api.deepseek.com, no redirects, short timeouts, small size limits.
//  - The API key comes from secure storage and is never logged or put in error messages.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// [key] is a localization key (see lib/l10n/strings.dart).
class AiException implements Exception {
  AiException(this.key);
  final String key;

  @override
  String toString() => 'AiException($key)';
}

class AiIntent {
  AiIntent(this.kinds, this.names);
  final List<String> kinds;
  final List<String> names;
  bool get isEmpty => kinds.isEmpty && names.isEmpty;
}

/// Place kinds the model may choose from (same codes as places.db "kind").
const Map<String, String> kAiKinds = {
  'amenity:pharmacy': 'pharmacy, chemist, duka la dawa',
  'amenity:hospital': 'hospital, hospitali',
  'amenity:clinic': 'clinic, zahanati, kituo cha afya',
  'amenity:doctors': 'doctor, daktari',
  'amenity:dentist': 'dentist, daktari wa meno',
  'amenity:fuel': 'fuel station, petrol, kituo cha mafuta',
  'amenity:restaurant': 'restaurant, mgahawa',
  'amenity:cafe': 'cafe, coffee, kahawa',
  'amenity:fast_food': 'fast food, chakula cha haraka',
  'amenity:bank': 'bank, benki',
  'amenity:atm': 'ATM, cash machine',
  'amenity:school': 'school, shule',
  'amenity:university': 'university, chuo kikuu',
  'amenity:police': 'police, polisi',
  'amenity:place_of_worship': 'church, mosque, temple, kanisa, msikiti',
  'amenity:marketplace': 'market, soko',
  'amenity:bus_station': 'bus station, stendi ya basi',
  'amenity:toilets': 'toilet, choo',
  'amenity:post_office': 'post office, posta',
  'shop:supermarket': 'supermarket, duka kubwa',
  'shop:convenience': 'small shop, kiosk, duka',
  'shop:bakery': 'bakery, mkate',
  'tourism:hotel': 'hotel',
  'tourism:guest_house': 'guest house, nyumba ya wageni',
  'tourism:attraction': 'tourist attraction, kivutio',
  'tourism:museum': 'museum, jumba la makumbusho',
  'leisure:park': 'park, bustani',
  'railway:station': 'train station, stesheni ya treni',
  'aeroway:aerodrome': 'airport, uwanja wa ndege',
};

const int kMaxAiInput = 200;

/// Keeps letters, digits, space, - ' . only; lower-case; 2..40 chars. Returns '' if nothing usable.
String sanitizeTerm(String raw) {
  var s = raw.replaceAll(RegExp(r"[^\p{L}\p{N} '.\-]", unicode: true), ' ');
  s = s.replaceAll(RegExp(r'\s+'), ' ').trim().toLowerCase();
  if (s.length < 2) return '';
  return s.length > 40 ? s.substring(0, 40).trim() : s;
}

/// Parses the model answer. Throws AiException('aiErrUnderstand') when it is not usable.
AiIntent parseIntent(String content) {
  if (content.isEmpty || content.length > 4000) throw AiException('aiErrUnderstand');
  final start = content.indexOf('{');
  final end = content.lastIndexOf('}');
  if (start < 0 || end <= start) throw AiException('aiErrUnderstand');
  Object? doc;
  try {
    doc = jsonDecode(content.substring(start, end + 1));
  } on FormatException {
    throw AiException('aiErrUnderstand');
  }
  if (doc is! Map) throw AiException('aiErrUnderstand');
  final kinds = <String>[];
  final rawKinds = doc['kinds'];
  if (rawKinds is List) {
    for (final k in rawKinds) {
      if (k is String && kAiKinds.containsKey(k) && !kinds.contains(k) && kinds.length < 3) kinds.add(k);
    }
  }
  final names = <String>[];
  final rawNames = doc['names'];
  if (rawNames is List) {
    for (final n in rawNames) {
      if (n is! String) continue;
      final clean = sanitizeTerm(n);
      if (clean.isNotEmpty && !names.contains(clean) && names.length < 3) names.add(clean);
    }
  }
  final intent = AiIntent(kinds, names);
  if (intent.isEmpty) throw AiException('aiErrUnderstand');
  return intent;
}

String buildSystemPrompt() {
  final list = kAiKinds.entries.map((e) => '${e.key} (${e.value})').join('\n');
  return 'You are a search helper for an offline map app. The user writes in English or Swahili.\n'
      'Reply with ONLY a JSON object and nothing else: {"kinds": [...], "names": [...]}\n'
      '"kinds": up to 3 codes chosen ONLY from this list, spelled exactly:\n$list\n'
      '"names": up to 3 short place-name keywords the user mentioned (proper names as written on a map), or [].\n'
      'If the request is not about finding a place, reply {"kinds": [], "names": []}.';
}

class DeepSeekClient {
  DeepSeekClient({
    required this.apiKey,
    Uri? endpoint,
    this.model = 'deepseek-chat',
    this.allowInsecureForTests = false,
  }) : endpoint = endpoint ?? Uri.parse('https://api.deepseek.com/chat/completions') {
    final okHost = this.endpoint.host == 'api.deepseek.com' && this.endpoint.scheme == 'https';
    if (!okHost && !allowInsecureForTests) throw ArgumentError('Only https://api.deepseek.com is allowed');
  }

  final String apiKey;
  final Uri endpoint;
  final String model;
  final bool allowInsecureForTests;

  static const Duration _timeout = Duration(seconds: 20);
  static const int _maxResponseBytes = 64 * 1024;

  /// Turns "nearest pharmacy" / "duka la dawa karibu" into search terms.
  Future<AiIntent> interpret(String userText) async {
    final text = userText.trim();
    if (text.isEmpty) throw AiException('aiErrUnderstand');
    final input = text.length > kMaxAiInput ? text.substring(0, kMaxAiInput) : text;
    final body = utf8.encode(jsonEncode({
      'model': model,
      'messages': [
        {'role': 'system', 'content': buildSystemPrompt()},
        {'role': 'user', 'content': input},
      ],
      'temperature': 0,
      'max_tokens': 120,
      'stream': false,
      'response_format': {'type': 'json_object'},
    }));
    final client = HttpClient()..connectionTimeout = _timeout;
    try {
      final req = await client.postUrl(endpoint);
      req.followRedirects = false;
      req.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
      req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $apiKey');
      req.headers.contentLength = body.length;
      req.add(body);
      final resp = await req.close().timeout(_timeout);
      if (resp.statusCode != 200) {
        await resp.drain<void>();
        throw AiException(_statusKey(resp.statusCode));
      }
      final bytes = <int>[];
      await for (final chunk in resp.timeout(_timeout)) {
        bytes.addAll(chunk);
        if (bytes.length > _maxResponseBytes) throw AiException('aiErrServer');
      }
      final doc = jsonDecode(utf8.decode(bytes));
      final content = (doc is Map && doc['choices'] is List && (doc['choices'] as List).isNotEmpty)
          ? ((doc['choices'] as List).first as Map)['message']?['content']
          : null;
      if (content is! String) throw AiException('aiErrUnderstand');
      return parseIntent(content);
    } on AiException {
      rethrow;
    } on TimeoutException {
      throw AiException('aiErrNetwork');
    } on SocketException {
      throw AiException('aiErrNetwork');
    } on HandshakeException {
      throw AiException('aiErrNetwork');
    } on FormatException {
      throw AiException('aiErrUnderstand');
    } on TypeError {
      throw AiException('aiErrUnderstand');
    } finally {
      client.close(force: true);
    }
  }

  static String _statusKey(int code) {
    if (code == 401 || code == 403) return 'aiErrKey';
    if (code == 402) return 'aiErrBalance';
    if (code == 429) return 'aiErrRate';
    return 'aiErrServer';
  }
}
