import 'package:flutter/material.dart';
import 'package:flutter_mobx/flutter_mobx.dart';
import 'package:kazumi/modules/my/watch_stats.dart';
import 'package:kazumi/pages/my/my_controller.dart';

class AnonWatchStats extends StatefulWidget {
  const AnonWatchStats({super.key, required this.controller});
  final MyController controller;

  @override
  State<AnonWatchStats> createState() => _AnonWatchStatsState();
}

class _AnonWatchStatsState extends State<AnonWatchStats> {
  @override
  void initState() {
    super.initState();
    widget.controller.attach();
  }

  @override
  void didUpdateWidget(AnonWatchStats oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.detach();
      widget.controller.attach();
    }
  }

  @override
  void dispose() {
    widget.controller.detach();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Observer(
    builder: (_) => AnonWatchStatsRow(stats: widget.controller.watchStats),
  );
}

class AnonWatchStatsRow extends StatelessWidget {
  const AnonWatchStatsRow({super.key, required this.stats});
  final WatchStats stats;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      container: true,
      label: '观看统计',
      child: Row(
        children: [
          for (final item in [
            (stats.watchedBangumiCount, '看过番剧'),
            (stats.watchedEpisodeCount, '观看集数'),
            (stats.downloadTaskCount, '下载任务'),
          ])
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      '${item.$1}',
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: theme.colorScheme.primary,
                      ),
                    ),
                  ),
                  Text(item.$2, style: theme.textTheme.labelMedium),
                ],
              ),
            ),
        ],
      ),
    );
  }
}
