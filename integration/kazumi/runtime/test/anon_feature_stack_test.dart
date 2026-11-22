import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';
import 'package:kazumi/anon/feature_request.dart';
import 'package:kazumi/anon/feature_routes.dart';
import 'package:kazumi/anon/feature_stack.dart';
import 'package:kazumi/anon/subject_page.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

class _TestPaths extends PathProviderPlatform {
  _TestPaths(this.directory);
  final Directory directory;
  @override
  Future<String> getApplicationSupportPath() async => directory.path;
}

void main() {
  test('ANON registers every animation action and its nested pages without the standalone shell', () async {
    final temp = await Directory.systemTemp.createTemp('anon-feature-routes-');
    final previous = PathProviderPlatform.instance;
    PathProviderPlatform.instance = _TestPaths(temp);
    addTearDown(() async {
      PathProviderPlatform.instance = previous;
      await Hive.close();
      await temp.delete(recursive: true);
    });
    Hive.init(temp.path);
    await GStorage.init();
    final routes = bootstrapModule(anonFeatures).routes;
    final actions = [
      'search',
      'imageSearch',
      'subject',
      'import',
      'collect',
      'history',
      'downloads',
      'rules',
      'catalog',
      'popular',
      'timeline',
      'settings',
      'licenses',
      'sync',
      'resume',
    ];
    final paths = [
      for (final action in actions) AnimeFeatureRequest(action, '42').route,
      '/info/',
      '/video/',
      '/image-preview',
      '/settings/plugin/editor',
      '/settings/plugin/test',
      '/settings/proxy/editor',
      '/settings/webdav/editor',
      '/settings/bangumi/',
      '/settings/player/decoder',
      '/settings/player/renderer',
      '/settings/player/super',
      '/settings/danmaku/shield',
      '/settings/storage/logs',
      '/settings/collection-display/',
      '/settings/download-settings/',
      '/settings/keyboard/',
    ];
    for (final path in paths) {
      expect(routes.match(Uri.parse(path)), isNotNull, reason: path);
    }
    for (final path in [
      '/onboarding',
      '/tab/popular/',
      '/tab/my',
      '/settings/update',
    ]) {
      expect(routes.match(Uri.parse(path)), isNull, reason: path);
    }
  });

  test(
    'a cached subject must match the selected ID and contain a usable title',
    () {
      final item = parseSubjectPreview(
        7,
        '{"id":7,"name":"Work","name_cn":"作品","date":"2024-04-01","rating":{"score":8.2},"images":{"large":"https://example.org/a.webp"}}',
      );
      expect(item?.nameCn, '作品');
      expect(item?.ratingScore, 8.2);
      expect(item?.images['large'], 'https://example.org/a.webp');
      expect(parseSubjectPreview(8, '{"id":7,"name":"Wrong work"}'), isNull);
      expect(parseSubjectPreview(7, '{"id":7}'), isNull);
      expect(parseSubjectPreview(7, 'bad JSON'), isNull);
    },
  );

  testWidgets(
    'a notification keeps the current episode; a new explicit search replaces the whole feature',
    (tester) async {
      late BuildContext host;
      final module = createModule(
        register: (c) {
          c
            ..route(
              '/',
              child: (context, _) {
                host = context;
                return const Scaffold(body: Text('native anchor'));
              },
            )
            ..route(
              '/subject/:id',
              child: (context, _) => Scaffold(
                appBar: AppBar(),
                body: TextButton(
                  onPressed: () => context.pushNamed('/video/'),
                  child: const Text('open episode'),
                ),
              ),
            )
            ..route(
              '/video',
              child: (context, _) => Scaffold(
                appBar: AppBar(),
                body: TextButton(
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) =>
                        const AlertDialog(title: Text('episode controls')),
                  ),
                  child: const Text('playing'),
                ),
              ),
            )
            ..route(
              '/search',
              child: (_, state) => Scaffold(
                appBar: AppBar(),
                body: Text(state.uri.queryParameters['query']!),
              ),
            );
        },
      );
      await tester.pumpWidget(
        ModularApp(
          module: module,
          child: Builder(
            builder: (context) => MaterialApp.router(
              theme: ThemeData(splashFactory: NoSplash.splashFactory),
              routerConfig: ModularApp.routerConfigOf(context),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final stack = AnimeFeatureStack();
      final calls = <String>[];
      Future<void> open(AnimeFeatureRequest request) => stack.open(
        host,
        request,
        publish: () async {
          calls.add('summary');
        },
        close: () async {
          calls.add('return to ANON');
        },
      );
      unawaited(open(const AnimeFeatureRequest('subject', '7')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('open episode'));
      await tester.pumpAndSettle();
      await open(const AnimeFeatureRequest('resume'));
      await tester.pumpAndSettle();
      expect(find.text('playing'), findsOneWidget);
      await tester.tap(find.text('playing'));
      await tester.pumpAndSettle();
      expect(find.text('episode controls'), findsOneWidget);
      unawaited(open(const AnimeFeatureRequest('search', 'MyGO!!!!!')));
      await tester.pumpAndSettle();
      expect(find.text('MyGO!!!!!'), findsOneWidget);
      expect(find.text('playing', skipOffstage: false), findsNothing);
      expect(find.text('episode controls', skipOffstage: false), findsNothing);
      expect(calls, isEmpty);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(calls, ['summary', 'return to ANON']);
    },
  );
}
