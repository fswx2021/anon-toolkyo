import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hive_ce/hive.dart';
import 'package:kazumi/anon/history_viewing_stats.dart';
import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/modules/bangumi/bangumi_tag.dart';
import 'package:kazumi/modules/collect/collect_type.dart';
import 'package:kazumi/modules/history/history_module.dart';
import 'package:kazumi/modules/history/history_sync.dart';
import 'package:kazumi/pages/history/history_record_tile.dart';
import 'package:kazumi/repositories/history_repository.dart';

void main() {
  test(
    'counts the first saved playback per player visit and persists it',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'anon_watch_history_',
      );
      addTearDown(() async {
        await Hive.close();
        await directory.delete(recursive: true);
      });
      Hive.init(directory.path);
      Hive.registerAdapter(HistoryAdapter());
      Hive.registerAdapter(ProgressAdapter());
      Hive.registerAdapter(BangumiItemAdapter());
      Hive.registerAdapter(BangumiTagAdapter());
      final box = await Hive.openBox<History>('histories');

      var private = true;
      final repository = HistoryRepository(
        historiesBox: box,
        privateModeReader: () => private,
        watchCountDeviceReader: () async => 'device-a',
        progressSyncAppender: ({
          required history,
          required episode,
          required road,
          required progressMs,
          required updatedAt,
        }) async {},
      );
      final old = History(
        _subject(),
        1,
        'online',
        DateTime(2024, 1, 1),
        '',
        '',
      );
      await box.put(old.key, old);
      expect(old.watchCount, 0);
      final visit = PlaybackHistorySession();
      await repository.updateHistory(
        identity: _identity('online', 1),
        progress: const Duration(seconds: 10),
        session: visit,
      );
      expect(visit.recorded, isFalse);
      private = false;
      for (final selection in [('online', 1), ('online', 2), ('backup', 2)]) {
        await repository.updateHistory(
          identity: _identity(selection.$1, selection.$2),
          progress: const Duration(seconds: 10),
          session: visit,
        );
      }
      expect(box.get(old.key)!.watchCount, 1);
      expect(box.get(old.key)!.progresses[2]!.progress.inSeconds, 10);
      expect(box.get(History.getKey('backup', _subject()))!.watchCount, 0);

      await repository.updateHistory(
        identity: _identity('backup', 2),
        progress: const Duration(seconds: 20),
        session: PlaybackHistorySession(),
      );
      await box.close();
      final restored = await Hive.openBox<History>('histories');
      final stats = HistoryViewingStats.forWorks(restored.values).values.single;
      expect(stats.count, 2);
      expect(
        stats.lastWatchTime.isAfter(old.lastWatchTime) ||
            stats.lastWatchTime.isAtSameMomentAs(old.lastWatchTime),
        isTrue,
      );
      expect(restored.get(old.key)!.watchCounts, {'device-a': 1});
    },
  );

  test('sync combines per-device counters without adding duplicates', () {
    final subject = _subject();
    HistorySyncEvent event(String device, int sequence, int count) {
      return HistorySyncEvent.upsertWatchState(
        deviceId: device,
        seq: sequence,
        history: History(
          subject,
          1,
          'online',
          DateTime(2024),
          '',
          '',
          watchCounts: {device: count},
        ),
        episode: 1,
        updatedAt: sequence + (device == 'a' ? 10 : 20),
      );
    }

    final events = [event('b', 1, 3), event('a', 2, 2), event('a', 1, 1)];
    final stream = HistorySyncStreamMerger(HistorySyncSnapshot.empty())
      ..addAll(events)
      ..addAll(events);
    final encoded = jsonEncode(stream.snapshot().toJson());
    final restored = HistorySyncSnapshot.fromJson(
      jsonDecode(encoded) as Map<String, dynamic>,
    );
    expect(restored.histories.single.watchCounts, {'a': 2, 'b': 3});
    expect(restored.histories.single.watchCount, 5);
    expect(restored.histories.single.lastWatchTime.millisecondsSinceEpoch, 21);
  });

  testWidgets('history tile shows full last-watch time and known count', (
    tester,
  ) async {
    final watchTime = DateTime(2026, 11, 23, 9, 5);
    final history = History(_subject(), 2, 'online', watchTime, '', '');
    Widget app(int count) => MaterialApp(
      home: Scaffold(
        body: HistoryRecordTile(
          history: history,
          stats: HistoryViewingStats(count, watchTime),
          onPlay: () {},
          onDetails: () {},
          onDelete: () async {},
          collectType: CollectType.none,
          onChangeCollect: null,
          borderRadius: BorderRadius.circular(8),
        ),
      ),
    );
    await tester.pumpWidget(app(0));
    expect(find.text('观看次数未记录'), findsOneWidget);
    expect(find.text('上次观看 2026-11-23 09:05'), findsOneWidget);
    await tester.pumpWidget(app(2));
    expect(find.text('已记录 2 次观看'), findsOneWidget);
  });
}

PlaybackHistoryIdentity _identity(String source, int episode) =>
    PlaybackHistoryIdentity.online(
      bangumiItem: _subject(),
      pluginName: source,
      episodeNumber: episode,
      episodeTitle: '第 $episode 集',
      road: 0,
      onlineBangumiSrc: 'https://example.test/source',
      episodePageUrl: '/$episode',
    );

BangumiItem _subject() => BangumiItem(
  id: 1,
  type: 2,
  name: 'Test series',
  nameCn: '测试番剧',
  summary: '',
  airDate: '',
  airWeekday: 0,
  rank: 0,
  images: const {},
  tags: const [],
  alias: const [],
  ratingScore: 0,
  votes: 0,
  votesCount: const [],
  info: '',
);
