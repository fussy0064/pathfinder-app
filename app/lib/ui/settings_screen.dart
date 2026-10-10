import 'package:flutter/material.dart';

import '../app_controller.dart';
import '../l10n/strings.dart';
import '../services/deepseek_client.dart';

/// Language, voice guidance, map rotation and the optional DeepSeek AI assistant.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final TextEditingController _key = TextEditingController();
  bool _busy = false;

  AppController get c => widget.controller;

  @override
  void dispose() {
    _key.clear(); // do not leave the key in memory longer than needed
    _key.dispose();
    super.dispose();
  }

  void _snack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _saveKey() async {
    await c.settings.saveApiKey(_key.text);
    _key.clear();
    _snack(c.t('saved'));
  }

  Future<void> _test() async {
    setState(() => _busy = true);
    try {
      await c.testAiConnection();
      _snack(c.t('connectionOk'));
    } on AiException catch (e) {
      _snack(c.t(e.key));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final s = c.settings;
        final t = c.t;
        final theme = Theme.of(context);
        return Scaffold(
          appBar: AppBar(title: Text(t('settings'))),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(t('language'), style: theme.textTheme.titleMedium),
              const SizedBox(height: 8),
              SegmentedButton<AppLanguage>(
                segments: const [
                  ButtonSegment(value: AppLanguage.en, label: Text('English')),
                  ButtonSegment(value: AppLanguage.sw, label: Text('Kiswahili')),
                ],
                selected: {s.language},
                onSelectionChanged: (set) => s.setLanguage(set.first),
              ),
              const Divider(height: 32),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(t('voiceGuidance')),
                value: s.voiceOn,
                onChanged: s.setVoiceOn,
              ),
              if (s.voiceOn && !c.voiceAvailable)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(t('voiceMissing', {'lang': t.languageName}), style: TextStyle(color: theme.colorScheme.error)),
                ),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton.icon(
                  onPressed: s.voiceOn && c.voiceAvailable ? c.testVoice : null,
                  icon: const Icon(Icons.record_voice_over),
                  label: Text(t('testVoice')),
                ),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(t('headingUp')),
                value: s.headingUp,
                onChanged: s.setHeadingUp,
              ),
              const Divider(height: 32),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(t('aiAssistant')),
                value: s.aiOn,
                onChanged: s.setAiOn,
              ),
              Text(t('aiPrivacy'), style: theme.textTheme.bodySmall),
              if (s.aiOn) ...[
                const SizedBox(height: 12),
                TextField(
                  controller: _key,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  maxLength: 200,
                  decoration: InputDecoration(
                    labelText: t('apiKey'),
                    helperText: s.hasApiKey ? '${t('saved')} *****' : null,
                    border: const OutlineInputBorder(),
                  ),
                ),
                Wrap(spacing: 8, children: [
                  FilledButton(onPressed: _busy ? null : _saveKey, child: Text(t('save'))),
                  OutlinedButton(onPressed: !s.hasApiKey || _busy ? null : _test, child: Text(t('testConnection'))),
                  TextButton(onPressed: s.hasApiKey ? s.removeApiKey : null, child: Text(t('removeKey'))),
                ]),
                if (_busy) const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator()),
              ],
            ],
          ),
        );
      },
    );
  }
}
