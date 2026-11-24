import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:kazumi/anon/anime_startup_rules.dart';
import 'package:kazumi/plugins/plugins.dart';
import 'package:kazumi/plugins/plugins_controller.dart';

void main() {
  test(
    'adds later arrivals without replacing edits or restoring removals',
    () async {
      final directory = await Directory.systemTemp.createTemp('anime-startup-');
      addTearDown(() => directory.delete(recursive: true));
      final source = File('${directory.path}/anime-sources.json');
      final plugins = PluginsController(pluginJsonWriter: (_) async {});
      final installed = Plugin.fromJson({'name': 'Existing', 'version': '9'});
      await plugins.updatePlugins([installed]);

      Map<String, Object> rule(String name, {bool startup = true}) => {
        'rule': {'name': name, 'version': '1'},
        'enabled': true,
        if (startup) 'startupEnabled': true,
      };
      final first = [
        rule('Existing'),
        rule('First'),
        rule('Legacy', startup: false),
      ];
      await source.writeAsString(jsonEncode(first));
      await AnimeStartupRules.apply(plugins, directory);
      expect(plugins.pluginList.map((rule) => rule.name), [
        'Existing',
        'First',
      ]);
      expect(plugins.pluginList.first.version, '9');

      await plugins.removePlugin(plugins.pluginList.last);
      await source.writeAsString(jsonEncode([...first, rule('Second')]));
      await AnimeStartupRules.apply(plugins, directory);
      expect(plugins.pluginList.map((rule) => rule.name), [
        'Existing',
        'Second',
      ]);
    },
  );
}
