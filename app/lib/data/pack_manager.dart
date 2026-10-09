// Downloads map packs from a catalog.json built by tools/make_pack.py.
// The catalog rules below are the same as validate_catalog() in that script.
//
// Security rules (all enforced here):
//  - https only (http is allowed only in tests), no redirects
//  - file names come from a fixed list; paths cannot leave the base URL
//  - every file must match the declared size and SHA-256 before it is installed
//  - files are downloaded to a temp folder, then moved into place (no half-installed data)
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;

class PackException implements Exception {
  PackException(this.message);
  final String message;

  @override
  String toString() => message;
}

const Set<String> kAllowedFiles = {'map_graph.bin', 'map_graph.bin.ch', 'places.db', 'tiles.mbtiles'};
const int kMaxFileBytes = 4 * 1024 * 1024 * 1024;
const int kMaxCatalogBytes = 1024 * 1024;

final RegExp _idRe = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp _pathRe = RegExp(r'^[A-Za-z0-9._/-]{1,200}$');
final RegExp _shaRe = RegExp(r'^[0-9a-f]{64}$');

class PackFile {
  PackFile(this.name, this.path, this.size, this.sha256);
  final String name;
  final String path;
  final int size;
  final String sha256;
}

class PackInfo {
  PackInfo(this.id, this.name, this.version, this.files);
  final String id;
  final String name;
  final String version;
  final List<PackFile> files;

  int get totalBytes => files.fold(0, (sum, f) => sum + f.size);
}

class DownloadProgress {
  DownloadProgress(this.doneBytes, this.totalBytes, this.currentFile);
  final int doneBytes;
  final int totalBytes;
  final String currentFile;

  double get fraction => totalBytes <= 0 ? 0 : (doneBytes / totalBytes).clamp(0.0, 1.0).toDouble();
}

class CancelToken {
  bool _cancelled = false;
  bool get cancelled => _cancelled;
  void cancel() => _cancelled = true;
}

/// Parses and validates a catalog. Throws PackException for anything unexpected.
List<PackInfo> parseCatalog(String text) {
  Object? doc;
  try {
    doc = jsonDecode(text);
  } on FormatException {
    throw PackException('Catalog is not valid JSON');
  }
  if (doc is! Map || doc['schema'] != 1) throw PackException('Unsupported catalog version');
  final packs = doc['packs'];
  if (packs is! List || packs.length > 100) throw PackException('Catalog has no valid pack list');
  final seen = <String>{};
  final result = <PackInfo>[];
  for (final raw in packs) {
    if (raw is! Map) throw PackException('Bad pack entry');
    final id = raw['id'];
    if (id is! String || !_idRe.hasMatch(id) || !seen.add(id)) throw PackException('Bad or duplicate pack id');
    final name = raw['name'];
    final version = raw['version'];
    if (name is! String || name.isEmpty || name.length > 80) throw PackException('Bad pack name');
    if (version is! String || version.isEmpty || version.length > 32) throw PackException('Bad pack version');
    final rawFiles = raw['files'];
    if (rawFiles is! List || rawFiles.isEmpty || rawFiles.length > kAllowedFiles.length) {
      throw PackException('Bad file list in pack $id');
    }
    final names = <String>{};
    final files = <PackFile>[];
    for (final f in rawFiles) {
      if (f is! Map) throw PackException('Bad file entry in pack $id');
      final fname = f['name'];
      final path = f['path'];
      final size = f['size'];
      final sha = f['sha256'];
      if (fname is! String || !kAllowedFiles.contains(fname) || !names.add(fname)) {
        throw PackException('File not allowed in pack $id');
      }
      if (path is! String || !_pathRe.hasMatch(path) || path.startsWith('/') || path.contains('//') || path.split('/').contains('..')) {
        throw PackException('Bad file path in pack $id');
      }
      if (size is! int || size < 1 || size > kMaxFileBytes) throw PackException('Bad file size in pack $id');
      if (sha is! String || !_shaRe.hasMatch(sha)) throw PackException('Bad checksum in pack $id');
      files.add(PackFile(fname, path, size, sha));
    }
    if (!names.contains('map_graph.bin')) throw PackException('Pack $id has no road graph');
    result.add(PackInfo(id, name, version, List.unmodifiable(files)));
  }
  return result;
}

class PackManager {
  /// [baseUrl] must be https (set [allowInsecureForTests] only in unit tests).
  PackManager({required Uri baseUrl, required this.dataDir, this.allowInsecureForTests = false})
      : _base = baseUrl.path.endsWith('/') ? baseUrl : baseUrl.replace(path: '${baseUrl.path}/') {
    final okScheme = _base.scheme == 'https' || (allowInsecureForTests && _base.scheme == 'http');
    if (!okScheme || _base.host.isEmpty) throw PackException('Pack server must be an https address');
  }

  final Uri _base;
  final String dataDir;
  final bool allowInsecureForTests;

  static const Duration _connectTimeout = Duration(seconds: 20);
  static const Duration _idleTimeout = Duration(seconds: 30);

  HttpClient _client() => HttpClient()..connectionTimeout = _connectTimeout;

  Uri _uriFor(String path) {
    final u = _base.resolve(path);
    if (u.scheme != _base.scheme || u.host != _base.host || u.port != _base.port || !u.path.startsWith(_base.path)) {
      throw PackException('Pack file is outside the pack server');
    }
    return u;
  }

  Never _mapError(Object e) {
    if (e is PackException) throw e;
    if (e is TimeoutException) throw PackException('The server did not answer in time');
    if (e is HandshakeException || e is TlsException) throw PackException('Secure connection failed');
    if (e is SocketException) throw PackException('No connection to the pack server');
    if (e is FileSystemException) throw PackException('Could not write to storage (is it full?)');
    throw PackException('Download failed');
  }

  Future<List<PackInfo>> fetchCatalog() async {
    final client = _client();
    try {
      final req = await client.getUrl(_uriFor('catalog.json'));
      req.followRedirects = false;
      final resp = await req.close().timeout(_idleTimeout);
      if (resp.statusCode != 200) {
        await resp.drain<void>();
        throw PackException('Server returned ${resp.statusCode}');
      }
      final bytes = <int>[];
      await for (final chunk in resp.timeout(_idleTimeout)) {
        bytes.addAll(chunk);
        if (bytes.length > kMaxCatalogBytes) throw PackException('Catalog is too large');
      }
      return parseCatalog(utf8.decode(bytes));
    } catch (e) {
      _mapError(e);
    } finally {
      client.close(force: true);
    }
  }

  /// Downloads and installs a pack. Throws PackException on any problem; nothing is installed then.
  Stream<DownloadProgress> install(PackInfo pack, {CancelToken? cancel}) async* {
    final tmp = Directory(p.join(dataDir, '.download'));
    try {
      if (await tmp.exists()) await tmp.delete(recursive: true);
      await tmp.create(recursive: true);
      final total = pack.totalBytes;
      var done = 0;
      final staged = <String, File>{};
      for (final f in pack.files) {
        final target = File(p.join(tmp.path, f.name)); // f.name is from the fixed list
        await for (final n in _download(f, target, cancel)) {
          done += n;
          yield DownloadProgress(done, total, f.name);
        }
        staged[f.name] = target;
      }
      // Everything verified. Move into place; the graph goes last.
      const order = ['map_graph.bin.ch', 'places.db', 'tiles.mbtiles', 'map_graph.bin'];
      for (final name in order) {
        final file = staged[name];
        if (file != null) await file.rename(p.join(dataDir, name));
      }
      if (!staged.containsKey('map_graph.bin.ch')) {
        final stale = File(p.join(dataDir, 'map_graph.bin.ch'));
        if (await stale.exists()) await stale.delete(); // an old index would not match the new graph
      }
    } catch (e) {
      _mapError(e);
    } finally {
      try {
        if (await tmp.exists()) await tmp.delete(recursive: true);
      } catch (_) {}
    }
  }

  Stream<int> _download(PackFile f, File target, CancelToken? cancel) async* {
    final client = _client();
    try {
      final req = await client.getUrl(_uriFor(f.path));
      req.followRedirects = false;
      final resp = await req.close().timeout(_idleTimeout);
      if (resp.statusCode != 200) {
        await resp.drain<void>();
        throw PackException('Server returned ${resp.statusCode} for ${f.name}');
      }
      if (resp.contentLength >= 0 && resp.contentLength != f.size) {
        await resp.drain<void>();
        throw PackException('Unexpected size for ${f.name}');
      }
      final sink = target.openWrite();
      var got = 0;
      try {
        await for (final chunk in resp.timeout(_idleTimeout)) {
          if (cancel?.cancelled ?? false) throw PackException('Cancelled');
          got += chunk.length;
          if (got > f.size) throw PackException('Server sent more data than declared');
          sink.add(chunk);
          yield chunk.length;
        }
      } finally {
        await sink.close();
      }
      if (got != f.size) throw PackException('Download of ${f.name} is incomplete');
      final digest = await sha256.bind(target.openRead()).first;
      if (digest.toString() != f.sha256) throw PackException('Checksum mismatch for ${f.name}');
    } finally {
      client.close(force: true);
    }
  }

  /// Deletes all installed map data (graph, index, search, tiles).
  Future<void> removeAll() async {
    for (final name in kAllowedFiles) {
      final f = File(p.join(dataDir, name));
      if (await f.exists()) await f.delete();
    }
  }
}
