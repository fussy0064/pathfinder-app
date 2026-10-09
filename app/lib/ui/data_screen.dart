import 'package:flutter/material.dart';

import '../app_controller.dart';
import '../data/pack_manager.dart';

/// Offline mode screen: what is installed, and downloading / deleting map packs.
class DataScreen extends StatefulWidget {
  const DataScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<DataScreen> createState() => _DataScreenState();
}

class _DataScreenState extends State<DataScreen> {
  Future<List<PackInfo>>? _catalog;
  String? _installingId;
  DownloadProgress? _progress;
  CancelToken? _cancel;
  String? _error;

  AppController get c => widget.controller;

  @override
  void initState() {
    super.initState();
    _catalog = c.packs?.fetchCatalog();
  }

  Future<void> _install(PackInfo pack) async {
    final pm = c.packs;
    if (pm == null) return;
    final token = CancelToken();
    setState(() {
      _installingId = pack.id;
      _cancel = token;
      _progress = null;
      _error = null;
    });
    try {
      await for (final pr in pm.install(pack, cancel: token)) {
        if (!mounted) return;
        setState(() => _progress = pr);
      }
      await c.reloadData();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${pack.name} installed')));
    } on PackException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) {
        setState(() {
          _installingId = null;
          _cancel = null;
          _progress = null;
        });
      }
    }
  }

  Future<void> _confirmDelete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete all map data?'),
        content: const Text('Roads, search and map tiles will be removed from this phone. You can download them again.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok == true) {
      await c.deleteAllData();
      if (mounted) setState(() {});
    }
  }

  String _mb(int bytes) => '${(bytes / 1048576).toStringAsFixed(bytes < 10 * 1048576 ? 1 : 0)} MB';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final d = c.data;
        return Scaffold(
          appBar: AppBar(title: const Text('Map data')),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text('Installed', style: theme.textTheme.titleMedium),
              const SizedBox(height: 8),
              _Row('Road graph (routing)', d?.hasGraph ?? false),
              _Row('Fast routing index', c.usesCh, note: c.usesCh ? 'active' : 'not installed, using A*'),
              _Row('Place search', d?.hasPlaces ?? false),
              _Row('Map tiles', d?.hasTiles ?? false),
              if (d != null) ...[
                const SizedBox(height: 8),
                SelectableText(d.dir, style: theme.textTheme.bodySmall),
              ],
              const Divider(height: 32),
              Text('Download', style: theme.textTheme.titleMedium),
              const SizedBox(height: 8),
              ..._downloadSection(theme),
              const Divider(height: 32),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: _installingId == null ? _confirmDelete : null,
                  icon: Icon(Icons.delete_outline, color: theme.colorScheme.error),
                  label: Text('Delete all map data', style: TextStyle(color: theme.colorScheme.error)),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  List<Widget> _downloadSection(ThemeData theme) {
    if (c.packs == null) {
      return [
        Text(
          c.packsProblem ??
              'No download server is set up in this build.\nCopy the files into the folder above, or build the app with '
                  '--dart-define=PACK_BASE_URL=https://your-server/packs/',
        ),
      ];
    }
    return [
      if (_error != null)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
        ),
      FutureBuilder<List<PackInfo>>(
        future: _catalog,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const LinearProgressIndicator();
          if (snap.hasError) {
            final msg = snap.error is PackException ? (snap.error as PackException).message : 'Could not load the list';
            return Row(children: [
              Expanded(child: Text(msg, style: TextStyle(color: theme.colorScheme.error))),
              TextButton(onPressed: () => setState(() => _catalog = c.packs?.fetchCatalog()), child: const Text('Retry')),
            ]);
          }
          final packs = snap.data ?? const <PackInfo>[];
          if (packs.isEmpty) return const Text('The server has no packs yet.');
          return Column(children: [
            for (final pack in packs) _packTile(pack, theme),
          ]);
        },
      ),
    ];
  }

  Widget _packTile(PackInfo pack, ThemeData theme) {
    final busy = _installingId == pack.id;
    final pr = _progress;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(pack.name, style: theme.textTheme.titleSmall),
                  Text('Version ${pack.version} | ${_mb(pack.totalBytes)}', style: theme.textTheme.bodySmall),
                ]),
              ),
              if (busy)
                OutlinedButton(onPressed: _cancel?.cancel, child: const Text('Cancel'))
              else
                FilledButton(onPressed: _installingId == null ? () => _install(pack) : null, child: const Text('Download')),
            ]),
            if (busy) ...[
              const SizedBox(height: 10),
              LinearProgressIndicator(value: pr?.fraction),
              const SizedBox(height: 4),
              Text(pr == null ? 'Starting...' : '${pr.currentFile}  ${_mb(pr.doneBytes)} of ${_mb(pr.totalBytes)}',
                  style: theme.textTheme.bodySmall),
            ],
          ],
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.ok, {this.note});
  final String label;
  final bool ok;
  final String? note;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(children: [
        Icon(ok ? Icons.check_circle : Icons.radio_button_unchecked,
            size: 18, color: ok ? theme.colorScheme.primary : theme.colorScheme.outline),
        const SizedBox(width: 10),
        Expanded(child: Text(label)),
        if (note != null) Text(note!, style: theme.textTheme.bodySmall),
      ]),
    );
  }
}
