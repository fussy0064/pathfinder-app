import 'dart:async';

import 'package:flutter/material.dart';

import '../app_controller.dart';
import '../data/places_db.dart';
import '../services/deepseek_client.dart';

/// Destination input: offline autocomplete, plus optional "Ask AI" (DeepSeek). Pops with the chosen Place.
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final TextEditingController _text = TextEditingController();
  Timer? _debounce;
  List<Place> _results = const [];
  String _query = '';
  bool _aiBusy = false;
  String? _aiErrorKey;

  AppController get c => widget.controller;

  @override
  void dispose() {
    _debounce?.cancel();
    _text.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 200), () {
      final db = c.places;
      if (db == null || !mounted) return;
      setState(() {
        _aiErrorKey = null;
        _query = value.trim();
        _results = db.search(value);
      });
    });
  }

  Future<void> _askAi() async {
    final text = _text.text.trim();
    if (text.length < 3 || _aiBusy) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _aiBusy = true;
      _aiErrorKey = null;
    });
    try {
      final found = await c.aiSearch(text);
      if (!mounted) return;
      setState(() {
        _results = found;
        _query = text;
      });
    } on AiException catch (e) {
      if (mounted) {
        setState(() {
          _aiErrorKey = e.key;
          _results = const [];
        });
      }
    } finally {
      if (mounted) setState(() => _aiBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = c.t;
    final theme = Theme.of(context);
    final db = c.places;
    final aiReady = c.settings.aiOn && c.settings.hasApiKey;
    Widget body;
    if (_aiBusy) {
      body = Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const LinearProgressIndicator(),
          const SizedBox(height: 12),
          Text(t('aiWorking')),
        ]),
      );
    } else if (_aiErrorKey != null) {
      body = _Hint(t(_aiErrorKey!), error: true);
    } else if (db == null) {
      body = _Hint(t('searchNoData'));
    } else if (_query.length < 2) {
      body = _Hint(t('typeMore'));
    } else if (_results.isEmpty) {
      body = _Hint(t('noMatch', {'q': _query}));
    } else {
      body = ListView.separated(
        itemCount: _results.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, i) {
          final p = _results[i];
          return ListTile(
            leading: Icon(p.kind == 'street' ? Icons.add_road : Icons.place_outlined),
            title: Text(p.name, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(p.kindLabel),
            onTap: () => Navigator.of(context).pop(p),
          );
        },
      );
    }
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: TextField(
          controller: _text,
          autofocus: true,
          textInputAction: TextInputAction.search,
          maxLength: kMaxAiInput,
          buildCounter: (_, {required currentLength, required isFocused, maxLength}) => null,
          decoration: InputDecoration(
            hintText: t('whereTo'),
            border: InputBorder.none,
            hintStyle: TextStyle(color: theme.colorScheme.outline),
          ),
          onChanged: _onChanged,
          onSubmitted: (_) => aiReady ? _askAi() : null,
        ),
        actions: [
          if (aiReady)
            IconButton(
              tooltip: t('askAi'),
              icon: const Icon(Icons.auto_awesome),
              onPressed: _aiBusy ? null : _askAi,
            ),
          IconButton(
            tooltip: t('clear'),
            icon: const Icon(Icons.close),
            onPressed: () {
              _text.clear();
              _onChanged('');
            },
          ),
        ],
      ),
      body: body,
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint(this.text, {this.error = false});
  final String text;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium?.copyWith(color: error ? theme.colorScheme.error : null),
        ),
      ),
    );
  }
}
