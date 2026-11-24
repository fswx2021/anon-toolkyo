import 'dart:convert';
import 'dart:io';

import 'package:kazumi/plugins/plugins.dart';
import 'package:kazumi/plugins/plugins_controller.dart';
import 'package:kazumi/services/logging/logger.dart';
import 'package:kazumi/services/storage/anon_legacy_import.dart';
import 'package:path_provider/path_provider.dart';

class AnimeStartupRules {
  Future<void>? _inFlight;

  Future<void> sync(PluginsController plugins) {
    if (!Platform.isAndroid) return Future.value();
    return _inFlight ??= _sync(plugins).whenComplete(() => _inFlight = null);
  }

  Future<void> _sync(PluginsController plugins) async {
    try {
      await apply(plugins, await getApplicationSupportDirectory());
    } catch (error, stack) {
      KazumiLogger().w(
        'ANON: startup anime rules will be retried',
        error: error,
        stackTrace: stack,
      );
    }
  }

  static Future<void> apply(
    PluginsController plugins,
    Directory directory,
  ) async {
    final source = File('${directory.path}/anime-sources.json');
    if (!await source.exists()) return;
    final rows = jsonDecode(await source.readAsString()) as List;
    final staged = AnonLegacyImport.pendingRules([
      for (final row in rows)
        if (row is Map && row['startupEnabled'] == true) row,
    ], {});
    if (staged.isEmpty) return;

    // Keep a receipt for each provisioned rule so later retries add only new
    // arrivals. Rules removed in Flutter must stay removed.
    final receipt = File('${directory.path}/anon-initial-anime-rules.json');
    final consumed = await receipt.exists()
        ? (jsonDecode(await receipt.readAsString()) as List)
              .cast<String>()
              .toSet()
        : <String>{};
    final pending = staged
        .where((rule) => !consumed.contains(pluginNameKey(rule.name)))
        .toList();
    if (pending.isEmpty) return;

    final existing = plugins.pluginList
        .map((p) => pluginNameKey(p.name))
        .toSet();
    final missing = pending
        .where((rule) => !existing.contains(pluginNameKey(rule.name)))
        .toList();
    if (missing.isNotEmpty) await plugins.updatePlugins(missing);

    consumed.addAll(pending.map((rule) => pluginNameKey(rule.name)));
    final temporary = File('${receipt.path}.tmp');
    await temporary.writeAsString(jsonEncode(consumed.toList()), flush: true);
    await temporary.rename(receipt.path);
  }
}
