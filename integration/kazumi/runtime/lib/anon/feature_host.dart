import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/anon/anime_startup_rules.dart';
import 'package:kazumi/anon/feature_bridge.dart';
import 'package:kazumi/anon/feature_request.dart';
import 'package:kazumi/anon/feature_services.dart';
import 'package:kazumi/anon/feature_stack.dart';
import 'package:kazumi/bean/settings/theme_provider.dart';
import 'package:kazumi/bean/widget/loading_indicator.dart';
import 'package:kazumi/plugins/plugins_controller.dart';
import 'package:kazumi/repositories/history_repository.dart';
import 'package:kazumi/services/logging/logger.dart';
import 'package:kazumi/services/network/metered_network_service.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:mobx/mobx.dart';

class AnimeFeatureHost extends StatefulWidget {
  const AnimeFeatureHost({super.key});

  @override
  State<AnimeFeatureHost> createState() => _AnimeFeatureHostState();
}

class _AnimeFeatureHostState extends State<AnimeFeatureHost>
    with WidgetsBindingObserver {
  late final _bridge = AnimeFeatureBridge(_receive);
  AnimeFeatureRequest? _pending;
  bool _servicesReady = false;
  final _stack = AnimeFeatureStack();
  final _startupRules = AnimeStartupRules();
  int _openRevision = 0;
  Object? _error;
  Timer? _publishTimer;
  ReactionDisposer? _ruleWatch;
  final _watches = <StreamSubscription>[];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(_bridge.start());
      unawaited(_initialize());
    });
  }

  Future<void> _initialize() async {
    try {
      await initializeAnimeServices(
        openDownloads: () => _receive(const AnimeFeatureRequest('downloads')),
      );
      if (!mounted) return;
      _servicesReady = true;
      _watches.add(
        GStorage.histories.watch().listen((_) => _scheduleDashboard()),
      );
      _watches.add(
        GStorage.collectibles.watch().listen((_) => _scheduleDashboard()),
      );
      _ruleWatch = reaction<int>(
        (_) => inject<PluginsController>().pluginList.length,
        (_) => _scheduleDashboard(),
      );
      _scheduleDashboard();
      final request = _pending;
      _pending = null;
      if (request != null) _receive(request);
    } catch (error, stack) {
      KazumiLogger().e(
        'ANON animation initialization',
        error: error,
        stackTrace: stack,
      );
      if (mounted) setState(() => _error = error);
    }
  }

  void _scheduleDashboard() {
    // Batch changes during bulk rule imports, collection sync and history writes.
    _publishTimer ??= Timer(const Duration(milliseconds: 220), () {
      _publishTimer = null;
      unawaited(_publishDashboard());
    });
  }

  Future<void> _publishDashboard() async {
    if (!_servicesReady) return;
    final histories = inject<IHistoryRepository>().getAllHistories();
    final latest = histories.firstOrNull;
    try {
      await animeChannel.invokeMethod<void>('dashboard', {
        'historyCount': histories.length,
        'favoriteCount': GStorage.collectibles.length,
        'ruleCount': inject<PluginsController>().pluginList.length,
        'latestTitle': latest == null
            ? ''
            : (latest.bangumiItem.nameCn.isEmpty
                  ? latest.bangumiItem.name
                  : latest.bangumiItem.nameCn),
        'latestEpisode': latest == null
            ? ''
            : (latest.lastWatchEpisodeName.isEmpty
                  ? '第 ${latest.lastWatchEpisode} 集'
                  : latest.lastWatchEpisodeName),
      });
    } on MissingPluginException {
      // The native summary is refreshed again on Activity attachment/resume.
    } on PlatformException catch (error) {
      debugPrint('ANON dashboard: $error');
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(MeteredNetworkService.refresh());
      if (_servicesReady) {
        unawaited(_startupRules.sync(inject<PluginsController>()));
      }
      _scheduleDashboard();
    }
  }

  void _receive(AnimeFeatureRequest request) {
    if (!mounted) return;
    if (request.appearance case final appearance?) {
      final theme = context.read<ThemeProvider>();
      final mode = switch (appearance) {
        1 => ThemeMode.light,
        2 => ThemeMode.dark,
        _ => ThemeMode.system,
      };
      if (theme.themeMode != mode) theme.setThemeMode(mode);
    }
    if (!_servicesReady) {
      if (request.feature != 'resume' || _pending == null) _pending = request;
      return;
    }
    unawaited(_open(request));
  }

  Future<void> _open(AnimeFeatureRequest request) async {
    final revision = ++_openRevision;
    try {
      await _startupRules.sync(inject<PluginsController>());
      if (!mounted || revision != _openRevision) return;
      if (_error != null) setState(() => _error = null);
      await _stack.open(
        context,
        request,
        publish: _publishDashboard,
        close: SystemNavigator.pop,
      );
    } on FormatException catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _bridge.dispose();
    _publishTimer?.cancel();
    _ruleWatch?.call();
    for (final subscription in _watches) {
      unawaited(subscription.cancel());
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      leading: const BackButton(onPressed: SystemNavigator.pop),
      title: const Text('动画'),
    ),
    body: Center(
      child: _error == null
          ? const LoadingIndicator(semanticsLabel: '正在打开动画功能')
          : Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(_servicesReady ? '无法打开这个动画入口' : '动画资料载入失败'),
                if (!_servicesReady)
                  TextButton(
                    onPressed: () {
                      setState(() => _error = null);
                      unawaited(_initialize());
                    },
                    child: const Text('重试'),
                  ),
              ],
            ),
    ),
  );
}
