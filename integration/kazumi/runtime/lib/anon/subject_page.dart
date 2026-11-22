import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/bean/widget/bangumi_mirror_error_widget.dart';
import 'package:kazumi/bean/widget/loading_indicator.dart';
import 'package:kazumi/modules/bangumi/bangumi_item.dart';
import 'package:kazumi/pages/info/info_controller.dart';
import 'package:kazumi/pages/info/info_page.dart';
import 'package:kazumi/plugins/plugins_controller.dart';
import 'package:kazumi/request/apis/bangumi_api.dart';
import 'package:kazumi/services/storage/storage.dart';

class SubjectPage extends StatefulWidget {
  const SubjectPage({super.key, required this.id, this.preview});
  final int id;
  final String? preview;
  @override
  State<SubjectPage> createState() => _SubjectPageState();
}

class _SubjectPageState extends State<SubjectPage> {
  late Future<BangumiItem?> subject = _load();

  Future<BangumiItem?> _load() async {
    final id = widget.id;
    final preview = parseSubjectPreview(id, widget.preview);
    // Open bundled picks offline. InfoPage independently refreshes the details
    // and lets the user open the source picker even if Bangumi is unavailable.
    try {
      final feed = jsonDecode(
        await rootBundle.loadString(
          'assets/anon/anime-popular-2026-11-21.json',
        ),
      ) as Map;
      for (final row
          in (feed['data'] as List).whereType<Map<String, dynamic>>()) {
        if (row['id'] == id) {
          final item =
              preview ??
              BangumiItem.fromJson({
                ...row,
                'rating': <String, dynamic>{},
                'images': <String, String>{},
              });
          if (!item.images.values.any((url) => url.startsWith('https://lain.bgm.tv/'))) {
            item.images = {
              for (final size in ['large', 'common', 'medium', 'small', 'grid'])
                size: 'assets/anon/posters/$id.webp',
            };
          }
          return item;
        }
      }
    } catch (error) {
      debugPrint('ANON bundled posters: $error');
    }
    if (preview != null) return preview;
    final favorite = GStorage.collectibles.get(id)?.bangumiItem;
    if (favorite != null) return favorite;
    for (final history in GStorage.histories.values) {
      if (history.bangumiItem.id == id) return history.bangumiItem;
    }
    return BangumiApi.getBangumiInfoByID(id);
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<BangumiItem?>(
    future: subject,
    builder: (context, snapshot) {
      final item = snapshot.data;
      if (item != null) {
        return InfoPage(
          inputBangumiItem: item,
          infoController: context.read<InfoController>(),
          pluginsController: inject<PluginsController>(),
        );
      }
      return Scaffold(
        appBar: AppBar(title: const Text('番剧详情')),
        body: Center(
          child: snapshot.connectionState != ConnectionState.done
              ? const LoadingIndicator(semanticsLabel: '正在加载番剧详情')
              : BangumiMirrorErrorWidget(
                  onRetry: () => setState(() => subject = _load()),
                ),
        ),
      );
    },
  );
}

BangumiItem? parseSubjectPreview(int id, String? input) {
  if (input == null || input.isEmpty) return null;
  try {
    final row = jsonDecode(input);
    if (row is! Map<String, dynamic> || row['id'] != id) return null;
    final title = (row['name'] as String? ?? '').trim();
    final translated = (row['name_cn'] as String? ?? '').trim();
    if (title.isEmpty && translated.isEmpty) return null;
    return BangumiItem.fromJson({
      ...row,
      'name': title.isEmpty ? translated : title,
      'rating': row['rating'] ?? <String, dynamic>{},
      'images': row['images'] ?? <String, String>{},
    });
  } catch (_) {
    return null;
  }
}
