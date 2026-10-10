import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_app/services/deepseek_client.dart';

void main() {
  group('sanitizeTerm', () {
    test('keeps plain words, drops symbols', () {
      expect(sanitizeTerm('Muhimbili!!'), 'muhimbili');
      expect(sanitizeTerm("O'Brien-Smith"), "o'brien-smith");
      expect(sanitizeTerm('Café  Royal'), 'café royal');
      expect(sanitizeTerm('a'), '');
      expect(sanitizeTerm('   '), '');
      expect(sanitizeTerm('x' * 100).length, 40);
    });

    test('markup and SQL characters are removed', () {
      final s = sanitizeTerm("<script>alert(1)</script>'; DROP TABLE places; --");
      for (final bad in ['<', '>', '(', ')', ';', '%', '_', '\\', '"']) {
        expect(s.contains(bad), isFalse, reason: bad);
      }
    });
  });

  group('parseIntent', () {
    test('accepts a good answer', () {
      final i = parseIntent('{"kinds": ["amenity:pharmacy"], "names": ["Muhimbili"]}');
      expect(i.kinds, ['amenity:pharmacy']);
      expect(i.names, ['muhimbili']);
    });

    test('accepts JSON inside code fences or text', () {
      final i = parseIntent('```json\n{"kinds": ["amenity:fuel"], "names": []}\n```');
      expect(i.kinds, ['amenity:fuel']);
    });

    test('drops kinds that are not in the allowed list', () {
      final i = parseIntent('{"kinds": ["amenity:pharmacy", "amenity:cafe; DROP TABLE places", "shop:weapons", 5], "names": []}');
      expect(i.kinds, ['amenity:pharmacy']);
    });

    test('limits to 3 kinds and 3 names, no duplicates', () {
      final i = parseIntent(
          '{"kinds": ["amenity:bank","amenity:atm","amenity:fuel","amenity:cafe","amenity:bank"], "names": ["aa","bb","cc","dd","aa"]}');
      expect(i.kinds.length, 3);
      expect(i.names, ['aa', 'bb', 'cc']);
    });

    test('rejects unusable answers', () {
      for (final bad in ['', 'hello', '{"kinds": [], "names": []}', '{"kinds": "x"}', '[1,2]', '{broken', '{"kinds": ["nope"], "names": ["a"]}']) {
        expect(() => parseIntent(bad), throwsA(isA<AiException>().having((e) => e.key, 'key', 'aiErrUnderstand')), reason: bad);
      }
      expect(() => parseIntent('{' * 5000), throwsA(isA<AiException>()));
    });
  });

  test('system prompt lists every allowed kind', () {
    final p = buildSystemPrompt();
    for (final k in kAiKinds.keys) {
      expect(p.contains(k), isTrue, reason: k);
    }
    expect(p.contains('ONLY'), isTrue);
  });

  group('DeepSeekClient', () {
    test('only talks to https://api.deepseek.com', () {
      expect(() => DeepSeekClient(apiKey: 'k', endpoint: Uri.parse('https://evil.example/chat')), throwsArgumentError);
      expect(() => DeepSeekClient(apiKey: 'k', endpoint: Uri.parse('http://api.deepseek.com/chat')), throwsArgumentError);
      expect(() => DeepSeekClient(apiKey: 'k'), returnsNormally);
    });

    late HttpServer server;
    late Uri url;
    int status = 200;
    String reply = '';
    Map<String, dynamic>? lastBody;
    String? lastAuth;

    setUp(() async {
      status = 200;
      lastBody = null;
      lastAuth = null;
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      url = Uri.parse('http://127.0.0.1:${server.port}/chat/completions');
      server.listen((req) async {
        lastAuth = req.headers.value('authorization');
        final text = await utf8.decoder.bind(req).join();
        lastBody = jsonDecode(text) as Map<String, dynamic>;
        req.response.statusCode = status;
        if (status == 302) req.response.headers.set('location', 'http://127.0.0.1:1/x');
        req.response.write(reply);
        await req.response.close();
      });
    });

    tearDown(() => server.close(force: true));

    DeepSeekClient client() => DeepSeekClient(apiKey: 'test-key', endpoint: url, allowInsecureForTests: true);

    String chat(String content) => jsonEncode({
          'choices': [
            {'message': {'role': 'assistant', 'content': content}}
          ]
        });

    test('sends only the typed words, with the key in the header', () async {
      reply = chat('{"kinds": ["amenity:pharmacy"], "names": []}');
      final intent = await client().interpret('  nearest pharmacy  ');
      expect(intent.kinds, ['amenity:pharmacy']);
      expect(lastAuth, 'Bearer test-key');
      final body = lastBody!;
      expect(body['model'], 'deepseek-chat');
      final messages = body['messages'] as List;
      expect((messages.last as Map)['content'], 'nearest pharmacy');
      final raw = jsonEncode(body).toLowerCase();
      for (final secret in ['latitude', 'longitude', '"lat"', '"lon"', 'gps', 'test-key']) {
        expect(raw.contains(secret), isFalse, reason: 'request must not contain $secret');
      }
    });

    test('long input is cut', () async {
      reply = chat('{"kinds": ["amenity:bank"], "names": []}');
      await client().interpret('b' * 1000);
      final msg = ((lastBody!['messages'] as List).last as Map)['content'] as String;
      expect(msg.length, kMaxAiInput);
    });

    test('maps HTTP errors to localization keys', () async {
      Future<void> expectKey(int code, String key) async {
        status = code;
        reply = '{}';
        await expectLater(client().interpret('x y'), throwsA(isA<AiException>().having((e) => e.key, 'key', key)), reason: '$code');
      }

      await expectKey(401, 'aiErrKey');
      await expectKey(402, 'aiErrBalance');
      await expectKey(429, 'aiErrRate');
      await expectKey(500, 'aiErrServer');
      await expectKey(302, 'aiErrServer'); // redirects are not followed
    });

    test('unusable answers become aiErrUnderstand', () async {
      for (final body in ['not json at all', '{}', '{"choices": []}', chat('sorry, I cannot help'), chat('{"kinds": ["hack"], "names": []}')]) {
        reply = body;
        await expectLater(client().interpret('x y'), throwsA(isA<AiException>().having((e) => e.key, 'key', 'aiErrUnderstand')), reason: body);
      }
    });

    test('no server = network error, and the key is never in the error text', () async {
      await server.close(force: true);
      try {
        await client().interpret('x y');
        fail('should throw');
      } on AiException catch (e) {
        expect(e.key, 'aiErrNetwork');
        expect(e.toString().contains('test-key'), isFalse);
      }
    });
  });
}
