import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';
import 'package:kazumi/anon/anon_settings_page.dart';
import 'package:kazumi/anon/anon_subject_page.dart';
import 'package:kazumi/anon/anon_watch_stats.dart';
import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/modules/collect/collect_layout.dart';
import 'package:kazumi/modules/collect/collect_module.dart';
import 'package:kazumi/modules/history/history_module.dart';
import 'package:kazumi/modules/my/watch_stats.dart';
import 'package:kazumi/pages/collect/collect_library_view.dart';
import 'package:kazumi/pages/history/history_list_view.dart';
import 'package:kazumi/pages/popular/popular_controller.dart';
import 'package:kazumi/pages/popular/popular_page.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

class _TestPaths extends PathProviderPlatform {
  _TestPaths(this.path);
  final String path;
  @override
  Future<String?> getApplicationSupportPath() async => path;
}

BangumiItem subject(int id, String name) => BangumiItem(
  id: id,
  type: 2,
  name: '作品 $id',
  nameCn: name,
  summary: '',
  airDate: '2026-11-21',
  airWeekday: 6,
  rank: 15,
  images: {},
  tags: [],
  alias: [],
  ratingScore: 8.2,
  votes: 10,
  votesCount: List.filled(10, 1),
  info: '',
);

void main() {
  late Directory directory;
  late PathProviderPlatform originalPaths;
  setUpAll(() async {
    directory = await Directory.systemTemp.createTemp('anon_feature_ui_');
    originalPaths = PathProviderPlatform.instance;
    PathProviderPlatform.instance = _TestPaths(directory.path);
    Hive.init(directory.path);
    await GStorage.init();
    await GStorage.putSetting(SettingsKeys.showRating, true);
  });
  tearDownAll(() async {
    await Hive.close();
    PathProviderPlatform.instance = originalPaths;
    expect(directory.parent.absolute.path, Directory.systemTemp.absolute.path);
    expect(
      directory.uri.pathSegments.where((s) => s.isNotEmpty).last,
      startsWith('anon_feature_ui_'),
    );
    await directory.delete(recursive: true);
  });

  testWidgets('窄屏热门筛选不会遮挡返回与搜索', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 550);
    addTearDown(tester.view.reset);
    final navigator = GlobalKey<NavigatorState>();
    final controller = PopularController()..trendList.add(subject(0, '番剧'));
    await tester.pumpWidget(
      MaterialApp(navigatorKey: navigator, home: const Scaffold()),
    );
    navigator.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => PopularPage(controller: controller),
      ),
    );
    await tester.pumpAndSettle();
    final back = tester.getRect(find.byType(BackButton));
    final menu = tester.getRect(find.text('热门番组'));
    final search = tester.getRect(find.byTooltip('搜索'));
    expect(menu.left, greaterThanOrEqualTo(back.right));
    expect(menu.right, lessThanOrEqualTo(search.left));
    await tester.tap(find.text('热门番组'));
    await tester.pumpAndSettle();
    expect(find.text('热门番组'), findsNWidgets(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('追番展示写入动画馆共用的设置', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: AnonLibraryDisplaySettings()),
    );
    await tester.runAsync(() async {
      await tester.tap(find.byType(Switch));
      await Hive.box<dynamic>('setting').flush();
    });
    await tester.pumpAndSettle();
    expect(GStorage.getSetting(SettingsKeys.showRating), false);
  });
  testWidgets('作品资料在窄屏保留选源、追番和滑动标签', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 540);
    addTearDown(tester.view.reset);
    const tabs = ['概览', '吐槽', '角色', '关联', '制作人员'];
    var opened = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultTabController(
          length: tabs.length,
          child: Builder(
            builder: (context) {
              final controller = DefaultTabController.of(context);
              return AnonSubjectPage(
                item: subject(0, 'BanG Dream! It’s MyGO!!!!!'),
                showRating: true,
                tabController: controller,
                tabs: tabs,
                collectAction: const Icon(Icons.favorite_border),
                menu: const SizedBox.shrink(),
                onWatch: () => opened++,
                body: TabBarView(
                  controller: controller,
                  children: [
                    for (final label in tabs)
                      Builder(
                        builder: (context) => CustomScrollView(
                          slivers: [
                            SliverOverlapInjector(
                              handle:
                                  NestedScrollView.sliverOverlapAbsorberHandleFor(
                                    context,
                                  ),
                            ),
                            SliverToBoxAdapter(child: Text('$label正文')),
                          ],
                        ),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('吐槽'));
    await tester.pumpAndSettle();
    expect(find.text('吐槽正文').hitTestable(), findsOneWidget);
    await tester.tap(find.text('选源播放'));
    expect(opened, 1);
    await tester.tap(find.text('评分分布'));
    await tester.pumpAndSettle();
    expect(find.text('1 人'), findsWidgets);
    expect(find.text('选源播放').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('追番按收藏状态显示数目并打开选中作品', (tester) async {
    final entries = [
      CollectedBangumi(subject(1, '第一部'), DateTime(2026), 1),
      CollectedBangumi(subject(2, '第二部'), DateTime(2025), 2),
    ];
    int? opened;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: CollectLibraryView(
            entries: entries,
            showRating: false,
            layout: CollectLayout.list,
            onOpen: (item) => opened = item.id,
            onChangeType: (_, _) {},
            canEdit: (_) => true,
            onLayoutChanged: (_) {},
          ),
        ),
      ),
    );
    expect(find.text('全部 2'), findsOneWidget);
    await tester.tap(find.text('想看 1'));
    await tester.pumpAndSettle();
    await tester.tapAt(tester.getCenter(find.text('第二部')));
    expect(opened, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('观看记录按在线和缓存筛选，统计保留未筛选总数', (tester) async {
    final online = History(subject(1, '在线作品'), 1, 'A', DateTime.now(), '', '');
    final cached = History(
      subject(2, '缓存作品'),
      2,
      'B',
      DateTime.now(),
      '',
      '',
      entryKind: HistoryEntryKind.offline,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: HistoryListView(
            entries: [online, cached],
            header: const AnonWatchStatsRow(
              stats: WatchStats(
                watchedBangumiCount: 7,
                watchedEpisodeCount: 31,
                downloadTaskCount: 5,
              ),
            ),
            itemBuilder: (history, _) => Text(history.bangumiItem.nameCn),
          ),
        ),
      ),
    );
    await tester.tap(find.widgetWithText(ChoiceChip, '缓存'));
    await tester.pumpAndSettle();
    expect(find.text('缓存作品'), findsOneWidget);
    expect(find.text('在线作品'), findsNothing);
    expect(find.text('31'), findsOneWidget);
  });

  testWidgets('动画设置只有具体的动画功能入口', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: AnonAnimationSettings()));
    expect(find.text('动画设置'), findsOneWidget);
    for (final label in ['播放与手势', '快捷键', '弹幕', '追番展示', '离线下载']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('关于 Kazumi'), findsNothing);
  });
}
