import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:kazumi/anon/anon_feature_frame.dart';
import 'package:kazumi/bean/card/network_img_layer.dart';
import 'package:kazumi/modules/bangumi/bangumi_item.dart';

class AnonSubjectPage extends StatelessWidget {
  const AnonSubjectPage({
    super.key,
    required this.item,
    required this.showRating,
    required this.tabController,
    required this.tabs,
    required this.body,
    required this.collectAction,
    required this.menu,
    required this.onWatch,
  });

  final BangumiItem item;
  final bool showRating;
  final TabController tabController;
  final List<String> tabs;
  final Widget body;
  final Widget collectAction;
  final Widget menu;
  final VoidCallback onWatch;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnonFeatureFrame(
      title: '番剧详情',
      actions: [menu],
      body: Column(
        children: [
          Expanded(
            child: NestedScrollView(
              headerSliverBuilder: (context, innerScrolled) => [
                SliverToBoxAdapter(
                  child: _SubjectMasthead(item: item, showRating: showRating),
                ),
                SliverOverlapAbsorber(
                  handle: NestedScrollView.sliverOverlapAbsorberHandleFor(
                    context,
                  ),
                  sliver: SliverAppBar(
                    pinned: true,
                    primary: false,
                    automaticallyImplyLeading: false,
                    toolbarHeight: 0,
                    forceElevated: innerScrolled,
                    scrolledUnderElevation: 0,
                    backgroundColor: colors.surface,
                    bottom: TabBar(
                      controller: tabController,
                      isScrollable: true,
                      tabAlignment: TabAlignment.start,
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      dividerColor: colors.outlineVariant,
                      tabs: [for (final name in tabs) Tab(text: name)],
                    ),
                  ),
                ),
              ],
              body: body,
            ),
          ),
          Material(
            color: colors.surface,
            child: SafeArea(
              top: false,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Divider(height: 1, color: colors.outlineVariant),
                  Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 720),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 7,
                        ),
                        child: Row(
                          children: [
                            collectAction,
                            const SizedBox(width: 12),
                            Expanded(
                              child: FilledButton.icon(
                                style: FilledButton.styleFrom(
                                  minimumSize: const Size(0, 46),
                                ),
                                onPressed: onWatch,
                                icon: const Icon(Icons.play_arrow_rounded),
                                label: const Text('选源播放'),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SubjectMasthead extends StatelessWidget {
  const _SubjectMasthead({required this.item, required this.showRating});
  final BangumiItem item;
  final bool showRating;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final title = item.nameCn.trim().isEmpty ? item.name : item.nameCn;
    final metadata = [
      if (item.airDate.trim().isNotEmpty) item.airDate,
      ...item.metaTags.where((tag) => tag.trim().isNotEmpty).take(2),
    ].join(' · ');
    final cover = ['large', 'common', 'medium', 'small', 'grid']
        .map((size) => item.images[size]?.trim() ?? '')
        .firstWhere((url) => url.isNotEmpty, orElse: () => '');
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 840),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(18, 18, 18, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  NetworkImgLayer(
                    src: cover,
                    width: 90,
                    height: 128,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          style: theme.textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        if (item.name.trim().isNotEmpty && item.name != title)
                          Padding(
                            padding: const EdgeInsets.only(top: 3),
                            child: Text(
                              item.name,
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: colors.onSurfaceVariant,
                              ),
                            ),
                          ),
                        if (metadata.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 12),
                            child: Text(
                              metadata,
                              style: theme.textTheme.bodyMedium,
                            ),
                          ),
                        if (showRating && item.ratingScore > 0)
                          Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Text(
                              [
                                '${item.ratingScore.toStringAsFixed(1)} 分',
                                if (item.votes > 0) '${item.votes} 人评分',
                                if (item.rank > 0) '排名 ${item.rank}',
                              ].join(' · '),
                              style: theme.textTheme.labelLarge?.copyWith(
                                color: colors.primary,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
              if (showRating && item.votes > 0 && item.votesCount.length == 10)
                ExpansionTile(
                  key: PageStorageKey('score-${item.id}'),
                  tilePadding: EdgeInsets.zero,
                  childrenPadding: const EdgeInsets.only(bottom: 8),
                  visualDensity: VisualDensity.compact,
                  title: Text('评分分布', style: theme.textTheme.labelLarge),
                  children: [
                    for (var score = 10; score >= 1; score--)
                      Row(
                        children: [
                          SizedBox(width: 28, child: Text('$score')),
                          Expanded(
                            child: LinearProgressIndicator(
                              value:
                                  item.votesCount[score - 1] /
                                  math.max(
                                    item.votes,
                                    item.votesCount[score - 1],
                                  ),
                              minHeight: 3,
                            ),
                          ),
                          const SizedBox(width: 8),
                          SizedBox(
                            width: 66,
                            child: Text(
                              '${item.votesCount[score - 1]} 人',
                              textAlign: TextAlign.end,
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}
