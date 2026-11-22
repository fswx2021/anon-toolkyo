import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/bean/dialog/dialog_helper.dart';
import 'package:kazumi/pages/collect/collect_controller.dart';
import 'package:kazumi/pages/download/download_controller.dart';
import 'package:kazumi/pages/my/my_controller.dart';
import 'package:kazumi/pages/plugin_editor/plugin_update_actions.dart';
import 'package:kazumi/plugins/plugins_controller.dart';
import 'package:kazumi/services/download/background_download_service.dart';
import 'package:kazumi/services/logging/logger.dart';
import 'package:kazumi/services/shaders/shader_asset_service.dart';
import 'package:kazumi/services/storage/anon_legacy_import.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:kazumi/services/sync/bangumi_sync_service.dart';
import 'package:kazumi/services/sync/danmaku_shield_sync_service.dart';
import 'package:kazumi/services/sync/webdav.dart';

Future<void> _background(String operation, Future<void> Function() work) async {
  try {
    await work();
  } catch (error, stack) {
    KazumiLogger().w('ANON: $operation', error: error, stackTrace: stack);
  }
}

Future<void> initializeAnimeServices({
  required VoidCallback openDownloads,
}) async {
  final plugins = inject<PluginsController>();
  final shield = inject<DanmakuShieldSyncService>();
  await AnonLegacyImport.importDanmakuPreference();
  await AnonLegacyImport.importFavorites();
  await inject<CollectController>().migrateCollect();
  await plugins.init();
  await AnonLegacyImport.importRules(plugins);
  shield.start();
  await _background('downloads', () => inject<DownloadController>().init());
  BackgroundDownloadService()
    ..onNavigateToDownloadRequested = openDownloads
    ..onNotificationPermissionRequired = () async =>
        await KazumiDialog.show<bool>(
          builder: (context) => AlertDialog(
            title: const Text('显示下载进度'),
            content: const Text('允许通知后，可在通知栏查看后台下载进度。'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('稍后'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('允许'),
              ),
            ],
          ),
        ) ??
        false;
  unawaited(
    _background(
      'shaders',
      () => inject<ShaderAssetService>().copyShadersToExternalDirectory(),
    ),
  );
  unawaited(
    _background(
      'danmaku filters',
      () => inject<MyController>().loadShieldList(),
    ),
  );
  unawaited(
    _background('rule updates', () async {
      final count = await plugins.checkPluginUpdatesOnStartup(
        enabled: GStorage.getSetting(SettingsKeys.checkPluginUpdateOnStartup),
      );
      if (count > 0) {
        KazumiDialog.showToast(
          message: '$count 条动画规则可更新',
          showActionButton: true,
          actionLabel: '更新',
          onActionPressed: () =>
              updateAllPluginsWithFeedback(plugins, ensureCatalog: false),
        );
      }
    }),
  );
  if (GStorage.getSetting(SettingsKeys.webDavEnable)) {
    unawaited(
      _background('WebDAV', () async {
        final webDav = inject<WebDav>();
        await webDav.init();
        await shield.syncIfEnabled();
        if (GStorage.getSetting(SettingsKeys.webDavEnableHistory))
          await webDav.syncHistory();
      }),
    );
  }
  if (GStorage.getSetting(SettingsKeys.bangumiSyncEnable)) {
    unawaited(
      _background('Bangumi', () async {
        await BangumiSyncService().ping();
      }),
    );
  }
}
