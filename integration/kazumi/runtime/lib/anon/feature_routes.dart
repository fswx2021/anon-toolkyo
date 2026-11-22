import 'package:flutter/material.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/anon/anon_library_page.dart';
import 'package:kazumi/anon/anon_settings_page.dart';
import 'package:kazumi/anon/feature_host.dart';
import 'package:kazumi/anon/subject_page.dart';
import 'package:kazumi/bean/widget/image_preview.dart';
import 'package:kazumi/core_module.dart';
import 'package:kazumi/pages/download/download_page_module.dart';
import 'package:kazumi/pages/info/info_controller.dart';
import 'package:kazumi/pages/info/info_module.dart';
import 'package:kazumi/pages/logs/logs_page.dart';
import 'package:kazumi/pages/plugin_editor/plugin_module.dart';
import 'package:kazumi/pages/popular/popular_controller.dart';
import 'package:kazumi/pages/popular/popular_module.dart';
import 'package:kazumi/pages/search/search_module.dart';
import 'package:kazumi/pages/settings/danmaku/danmaku_module.dart';
import 'package:kazumi/pages/settings/decoder_settings.dart';
import 'package:kazumi/pages/settings/download_settings.dart';
import 'package:kazumi/pages/settings/keyboard_settings.dart';
import 'package:kazumi/pages/settings/player_settings.dart';
import 'package:kazumi/pages/settings/proxy/proxy_module.dart';
import 'package:kazumi/pages/settings/renderer_settings.dart';
import 'package:kazumi/pages/settings/storage_settings.dart';
import 'package:kazumi/pages/settings/super_resolution_settings.dart';
import 'package:kazumi/pages/settings/sync/bangumi_sync_page.dart';
import 'package:kazumi/pages/settings/sync/sync_settings_page.dart';
import 'package:kazumi/pages/settings/sync/webdav_server_page.dart';
import 'package:kazumi/pages/settings/sync/webdav_sync_page.dart';
import 'package:kazumi/pages/timeline/timeline_controller.dart';
import 'package:kazumi/pages/timeline/timeline_module.dart';
import 'package:kazumi/pages/video/video_module.dart';
import 'package:kazumi/services/sync/danmaku_shield_sync_service.dart';

final _settings = createModule(
  path: '/settings',
  register: (c) {
    c
      ..route('/', child: (_, _) => const AnonAnimationSettings())
      ..route('/sync', child: (_, _) => const SyncSettingsPage())
      ..route('/keyboard', child: (_, _) => const KeyboardSettingsPage())
      ..route(
        '/collection-display',
        child: (_, _) => const AnonLibraryDisplaySettings(),
      )
      ..route(
        '/webdav',
        child: (_, _) => WebDavSyncPage(
          danmakuShieldSync: inject<DanmakuShieldSyncService>(),
        ),
      )
      ..route('/webdav/editor', child: (_, _) => const WebDavServerPage())
      ..route('/bangumi', child: (_, _) => const BangumiSyncPage())
      ..route('/storage', child: (_, _) => const StorageSettingsPage())
      ..route('/storage/logs', child: (_, _) => const LogsPage())
      ..route('/player', child: (_, _) => const PlayerSettingsPage())
      ..route('/player/decoder', child: (_, _) => const DecoderSettings())
      ..route('/player/renderer', child: (_, _) => const RendererSettings())
      ..route('/player/super', child: (_, _) => const SuperResolutionSettings())
      ..route(
        '/download-settings',
        child: (_, _) => const DownloadSettingsPage(),
      )
      ..route('/history', child: (_, _) => const AnonLibraryPage(history: true))
      ..module(downloadModule)
      ..module(danmakuModule)
      ..module(pluginModule)
      ..module(proxyModule);
  },
);

final anonFeatures = createModule(
  register: (c) {
    c
      ..module(coreModule)
      ..addSingleton<PopularController>(PopularController.new)
      ..addSingleton<TimelineController>(TimelineController.new)
      ..route('/', child: (_, _) => const AnimeFeatureHost())
      ..route(
        '/licenses',
        child: (_, _) => const LicensePage(applicationName: 'anon toolkyo'),
      )
      ..route(
        '/collect',
        child: (_, _) => const AnonLibraryPage(history: false),
      )
      ..route(
        '/subject/:id',
        provide: (scope) => scope.add<InfoController>(InfoController.new),
        child: (context, state) => SubjectPage(
          id: int.parse(state['id']!),
          preview: state.arguments is String ? state.arguments as String : null,
        ),
      )
      ..route(
        ImageViewer.routePath,
        child: (context, state) {
          final args = state.arguments;
          if (args is! ImageViewerRouteArgs) {
            return Scaffold(
              appBar: AppBar(),
              body: const Center(child: Text('图片预览参数无效')),
            );
          }
          return ImageViewer(
            imageUrls: args.imageUrls,
            initialIndex: args.initialIndex,
            heroTag: args.heroTag,
          );
        },
      )
      ..module(popularModule)
      ..module(timelineModule)
      ..module(searchModule)
      ..module(infoModule)
      ..module(videoModule)
      ..module(_settings);
  },
);
