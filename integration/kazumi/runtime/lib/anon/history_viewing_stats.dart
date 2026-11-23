import 'package:kazumi/modules/history/history_module.dart';

class HistoryViewingStats {
  const HistoryViewingStats(this.count, this.lastWatchTime);

  final int count;
  final DateTime lastWatchTime;

  String get countLabel => count == 0 ? '观看次数未记录' : '已记录 $count 次观看';

  String get timeLabel {
    if (lastWatchTime.millisecondsSinceEpoch <= 0) return '上次观看时间未记录';
    final local = lastWatchTime.toLocal();
    String twoDigits(int value) => value.toString().padLeft(2, '0');
    final date =
        '${local.year}-${twoDigits(local.month)}-${twoDigits(local.day)}';
    final time = '${twoDigits(local.hour)}:${twoDigits(local.minute)}';
    return '上次观看 $date $time';
  }

  static String workKey(History history) {
    final id = history.bangumiItem.id;
    return id > 0 ? 'bangumi:$id' : 'source:${history.key}';
  }

  static Map<String, HistoryViewingStats> forWorks(Iterable<History> entries) {
    final stats = <String, HistoryViewingStats>{};
    for (final history in entries) {
      final key = workKey(history);
      final previous = stats[key];
      final latest =
          previous != null &&
              previous.lastWatchTime.isAfter(history.lastWatchTime)
          ? previous.lastWatchTime
          : history.lastWatchTime;
      stats[key] = HistoryViewingStats(
        (previous?.count ?? 0) + history.watchCount,
        latest,
      );
    }
    return stats;
  }
}
