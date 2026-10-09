import 'dart:async';

import 'package:flutter/material.dart';

import '../data/places_db.dart';

/// Destination input with offline autocomplete. Pops with the chosen Place.
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key, required this.db});
  final PlacesDb? db;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final TextEditingController _text = TextEditingController();
  Timer? _debounce;
  List<Place> _results = const [];
  String _query = '';

  @override
  void dispose() {
    _debounce?.cancel();
    _text.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 200), () {
      final db = widget.db;
      if (db == null || !mounted) return;
      setState(() {
        _query = value.trim();
        _results = db.search(value);
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final db = widget.db;
    final theme = Theme.of(context);
    Widget body;
    if (db == null) {
      body = const _Hint('Search data (places.db) is not installed.\nYou can still long-press the map to drop a pin.');
    } else if (_query.length < 2) {
      body = const _Hint('Type at least 2 letters');
    } else if (_results.isEmpty) {
      body = _Hint('No places match "$_query"');
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
          maxLength: 80,
          buildCounter: (_, {required currentLength, required isFocused, maxLength}) => null,
          decoration: InputDecoration(
            hintText: 'Where to?',
            border: InputBorder.none,
            hintStyle: TextStyle(color: theme.colorScheme.outline),
          ),
          onChanged: _onChanged,
        ),
        actions: [
          if (_text.text.isNotEmpty)
            IconButton(
              tooltip: 'Clear',
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
  const _Hint(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(text, textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodyMedium),
        ),
      );
}
