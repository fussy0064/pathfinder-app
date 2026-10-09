import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pathfinder_app/data/pack_manager.dart';
import 'package:path/path.dart' as p;

String sha(List<int> b) => sha256.convert(b).toString();

Map<String, Object?> goodPack({String path = 'a/map_graph.bin', int size = 5, String? hash}) => {
      'schema': 1,
      'packs': [
        {
          'id': 'a',
          'name': 'A',
          'version': '1',
          'files': [
            {'name': 'map_graph.bin', 'path': path, 'size': size, 'sha256': hash ?? sha(utf8.encode('hello'))},
          ],
        }
      ],
    };

void main() {
  group('parseCatalog', () {
    test('accepts a good catalog', () {
      final packs = parseCatalog(jsonEncode(goodPack()));
      expect(packs.single.id, 'a');
      expect(packs.single.totalBytes, 5);
    });

    test('rejects hostile or broken catalogs', () {
      void bad(String name, Object? doc) {
        expect(() => parseCatalog(doc is String ? doc : jsonEncode(doc)), throwsA(isA<PackException>()), reason: name);
      }

      bad('not json', '{{{');
      bad('wrong schema', {...goodPack(), 'schema': 2});
      bad('not a map', []);
      bad('path traversal', goodPack(path: '../../etc/passwd'));
      bad('absolute path', goodPack(path: '/etc/passwd'));
      bad('url as path', goodPack(path: 'http://evil.example/x'));
      bad('double slash', goodPack(path: 'a//b'));
      bad('size zero', goodPack(size: 0));
      bad('size huge', goodPack(size: 1 << 50));
      bad('bad sha', goodPack(hash: 'ABC'));
      bad('upper-case sha', goodPack(hash: 'A' * 64));
      final noGraph = goodPack();
      ((noGraph['packs'] as List).first as Map)['files'] = [
        {'name': 'places.db', 'path': 'a/p', 'size': 1, 'sha256': '0' * 64}
      ];
      bad('no graph', noGraph);
      final badName = goodPack();
      (((badName['packs'] as List).first as Map)['files'] as List).first['name'] = 'run.sh';
      bad('file name not allowed', badName);
      final dup = goodPack();
      final files = ((dup['packs'] as List).first as Map)['files'] as List;
      files.add(Map<String, Object?>.from(files.first as Map));
      bad('duplicate file', dup);
    });
  });

  group('PackManager', () {
    late HttpServer server;
    late Directory dir;
    late Uri base;
    final routes = <String, List<int>>{};
    int? forceStatus;

    setUp(() async {
      routes.clear();
      forceStatus = null;
      dir = await Directory.systemTemp.createTemp('pf_pack_test');
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      base = Uri.parse('http://127.0.0.1:${server.port}/packs/');
      server.listen((req) async {
        final data = routes[req.uri.path];
        if (forceStatus != null) {
          req.response.statusCode = forceStatus!;
          if (forceStatus == 302) req.response.headers.set('location', 'http://127.0.0.1:1/evil');
        } else if (data == null) {
          req.response.statusCode = 404;
        } else {
          req.response.add(data);
        }
        await req.response.close();
      });
    });

    tearDown(() async {
      await server.close(force: true);
      await dir.delete(recursive: true);
    });

    PackManager manager() => PackManager(baseUrl: base, dataDir: dir.path, allowInsecureForTests: true);

    PackInfo packOf(Map<String, List<int>> files, {Map<String, String>? hashes, Map<String, int>? sizes}) {
      return PackInfo('a', 'A', '1', [
        for (final e in files.entries)
          PackFile(e.key, 'a/${e.key}', sizes?[e.key] ?? e.value.length, hashes?[e.key] ?? sha(e.value)),
      ]);
    }

    test('refuses http without the test flag', () {
      expect(() => PackManager(baseUrl: Uri.parse('http://example.com/'), dataDir: dir.path), throwsA(isA<PackException>()));
      expect(() => PackManager(baseUrl: Uri.parse('ftp://example.com/'), dataDir: dir.path, allowInsecureForTests: true),
          throwsA(isA<PackException>()));
    });

    test('fetches a catalog', () async {
      routes['/packs/catalog.json'] = utf8.encode(jsonEncode(goodPack()));
      final packs = await manager().fetchCatalog();
      expect(packs.single.name, 'A');
    });

    test('installs verified files and cleans up', () async {
      final graph = utf8.encode('graph-bytes');
      final places = utf8.encode('places');
      routes['/packs/a/map_graph.bin'] = graph;
      routes['/packs/a/places.db'] = places;
      final progress = await manager().install(packOf({'map_graph.bin': graph, 'places.db': places})).toList();
      expect(progress.last.fraction, 1.0);
      expect(await File(p.join(dir.path, 'map_graph.bin')).readAsBytes(), graph);
      expect(await File(p.join(dir.path, 'places.db')).readAsBytes(), places);
      expect(Directory(p.join(dir.path, '.download')).existsSync(), isFalse);
    });

    test('rejects a wrong checksum and installs nothing', () async {
      final graph = utf8.encode('graph-bytes');
      routes['/packs/a/map_graph.bin'] = utf8.encode('GRAPH-BYTES'); // same length, different content
      await expectLater(manager().install(packOf({'map_graph.bin': graph})).drain<void>(), throwsA(isA<PackException>()));
      expect(File(p.join(dir.path, 'map_graph.bin')).existsSync(), isFalse);
      expect(Directory(p.join(dir.path, '.download')).existsSync(), isFalse);
    });

    test('rejects a server that sends more than declared', () async {
      final graph = utf8.encode('graph-bytes');
      routes['/packs/a/map_graph.bin'] = graph;
      final pack = packOf({'map_graph.bin': graph}, sizes: {'map_graph.bin': 4});
      await expectLater(manager().install(pack).drain<void>(), throwsA(isA<PackException>()));
      expect(File(p.join(dir.path, 'map_graph.bin')).existsSync(), isFalse);
    });

    test('does not follow redirects', () async {
      forceStatus = 302;
      final graph = utf8.encode('graph-bytes');
      await expectLater(manager().install(packOf({'map_graph.bin': graph})).drain<void>(), throwsA(isA<PackException>()));
    });

    test('reports a missing file (404)', () async {
      final graph = utf8.encode('graph-bytes');
      await expectLater(manager().install(packOf({'map_graph.bin': graph})).drain<void>(), throwsA(isA<PackException>()));
    });

    test('can be cancelled', () async {
      final graph = utf8.encode('graph-bytes');
      routes['/packs/a/map_graph.bin'] = graph;
      final token = CancelToken()..cancel();
      await expectLater(
          manager().install(packOf({'map_graph.bin': graph}), cancel: token).drain<void>(), throwsA(isA<PackException>()));
      expect(File(p.join(dir.path, 'map_graph.bin')).existsSync(), isFalse);
    });

    test('removes a stale .ch index when the new pack has none', () async {
      final stale = File(p.join(dir.path, 'map_graph.bin.ch'))..writeAsBytesSync([1, 2, 3]);
      final graph = utf8.encode('graph-bytes');
      routes['/packs/a/map_graph.bin'] = graph;
      await manager().install(packOf({'map_graph.bin': graph})).drain<void>();
      expect(stale.existsSync(), isFalse);
    });

    test('removeAll deletes the data files', () async {
      for (final n in kAllowedFiles) {
        File(p.join(dir.path, n)).writeAsBytesSync([0]);
      }
      await manager().removeAll();
      for (final n in kAllowedFiles) {
        expect(File(p.join(dir.path, n)).existsSync(), isFalse);
      }
    });
  });
}
