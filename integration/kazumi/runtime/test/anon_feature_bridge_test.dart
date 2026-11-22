import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kazumi/anon/feature_bridge.dart';
import 'package:kazumi/anon/feature_request.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const codec = StandardMethodCodec();

  Future<void> fromAndroid(String method, [Object? value]) async {
    final answered = Completer<void>();
    await messenger.handlePlatformMessage(
      animeChannel.name,
      codec.encodeMethodCall(MethodCall(method, value)),
      (_) => answered.complete(),
    );
    await answered.future;
  }

  test(
    'cold action and hot replacements retain route, query and ANON appearance',
    () async {
      final requests = <AnimeFeatureRequest>[];
      final bridge = AnimeFeatureBridge(requests.add);
      addTearDown(bridge.dispose);
      addTearDown(() => messenger.setMockMethodCallHandler(animeChannel, null));
      messenger.setMockMethodCallHandler(animeChannel, (method) async {
        expect(method.method, 'ready');
        return {
          'feature': 'search',
          'value': '迷子 & MyGO!!!!! #1/2',
          'appearance': '2',
        };
      });
      await bridge.start();
      expect(requests.length, 1);
      expect(Uri.parse(requests.single.route).queryParameters, {
        'query': '迷子 & MyGO!!!!! #1/2',
      });
      expect(requests.single.appearance, 2);
      await fromAndroid('open', {'feature': 'subject', 'value': '401'});
      await fromAndroid('open', {
        'feature': 'import',
        'value': 'https://example.org/a?q=1&v=3',
      });
      expect(requests[1].route, '/subject/401');
      expect(requests.last.route, '/settings/plugin/');
      expect(requests.last.arguments, 'https://example.org/a?q=1&v=3');
    },
  );

  test('headless media engine receives pending action exactly once when a host attaches', () async {
    final requests = <AnimeFeatureRequest>[];
    final bridge = AnimeFeatureBridge(requests.add);
    addTearDown(bridge.dispose);
    addTearDown(() => messenger.setMockMethodCallHandler(animeChannel, null));
    await bridge.start(); // No Activity or platform handler yet.
    expect(requests, isEmpty);
    Object? pending = {'feature': 'downloads'};
    messenger.setMockMethodCallHandler(animeChannel, (_) async {
      final result = pending;
      pending = null;
      return result;
    });
    await fromAndroid('hostAttached');
    await fromAndroid('hostAttached');
    expect(requests.map((r) => r.route), ['/settings/download/']);
  });

  test('a late ready reply cannot supersede a newer hot action', () async {
    final requests = <AnimeFeatureRequest>[];
    final bridge = AnimeFeatureBridge(requests.add);
    addTearDown(bridge.dispose);
    addTearDown(() => messenger.setMockMethodCallHandler(animeChannel, null));
    final delayed = Completer<Object?>();
    messenger.setMockMethodCallHandler(animeChannel, (_) => delayed.future);
    final ready = bridge.start();
    await fromAndroid('open', {'feature': 'catalog', 'appearance': '1'});
    delayed.complete({'feature': 'popular'});
    await ready;
    expect(requests.single.route, '/settings/plugin/shop');
    expect(requests.single.appearance, 1);
  });

  test(
    'notification, library and discovery entry points are individual pages',
    () {
      const routes = {
        'history': '/settings/history/',
        'resume': '/settings/history/',
        'collect': '/collect/',
        'popular': '/popular/',
        'timeline': '/timeline/',
        'search': '/search/',
        'imageSearch': '/search/image',
        'rules': '/settings/plugin/',
        'sync': '/settings/sync',
        'settings': '/settings/',
        'downloads': '/settings/download/',
        'catalog': '/settings/plugin/shop',
      };
      for (final entry in routes.entries) {
        expect(AnimeFeatureRequest(entry.key).route, entry.value);
      }
      for (final value in ['-1', '0', '../info', 'one']) {
        expect(
          () => AnimeFeatureRequest('subject', value).route,
          throwsFormatException,
        );
      }
      expect(
        () => const AnimeFeatureRequest('onboarding').route,
        throwsFormatException,
      );
    },
  );
}
