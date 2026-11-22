import 'package:flutter/material.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/anon/anon_feature_frame.dart';
import 'package:kazumi/modules/collect/collect_layout.dart';
import 'package:kazumi/services/storage/storage.dart';

class AnonAnimationSettings extends StatelessWidget {
  const AnonAnimationSettings({super.key});

  static const _sections = [
    (
      title: '观看',
      items: [
        (Icons.play_circle_outline_rounded, '播放与手势', '/settings/player/'),
        (Icons.keyboard_outlined, '快捷键', '/settings/keyboard/'),
        (Icons.subtitles_outlined, '弹幕', '/settings/danmaku/'),
        (Icons.view_agenda_outlined, '追番展示', '/settings/collection-display/'),
        (
          Icons.download_for_offline_outlined,
          '离线下载',
          '/settings/download-settings/',
        ),
      ],
    ),
    (
      title: '账户与同步',
      items: [
        (Icons.account_circle_outlined, 'Bangumi 账户', '/settings/bangumi/'),
        (Icons.cloud_sync_outlined, 'WebDAV 同步', '/settings/webdav/'),
      ],
    ),
    (
      title: '数据',
      items: [
        (Icons.wifi_rounded, '网络服务', '/settings/proxy/'),
        (Icons.folder_outlined, '缓存与存储', '/settings/storage/'),
      ],
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return AnonFeatureFrame(
      title: '动画设置',
      body: ListView(
        padding: EdgeInsets.fromLTRB(
          20,
          4,
          20,
          24 + MediaQuery.paddingOf(context).bottom,
        ),
        children: [
          for (final section in _sections)
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 720),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(2, 22, 0, 6),
                      child: Semantics(
                        header: true,
                        child: Text(
                          section.title,
                          style: theme.textTheme.labelLarge?.copyWith(
                            color: colors.primary,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ),
                    for (final item in section.items)
                      Column(
                        children: [
                          ListTile(
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 4,
                            ),
                            leading: Icon(
                              item.$1,
                              color: colors.onSurfaceVariant,
                              size: 22,
                            ),
                            title: Text(item.$2),
                            trailing: Icon(
                              Icons.chevron_right_rounded,
                              color: colors.outline,
                            ),
                            onTap: () => context.pushNamed(item.$3),
                          ),
                          Divider(
                            height: 1,
                            indent: 46,
                            color: colors.outlineVariant,
                          ),
                        ],
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

class AnonLibraryDisplaySettings extends StatefulWidget {
  const AnonLibraryDisplaySettings({super.key});

  @override
  State<AnonLibraryDisplaySettings> createState() =>
      _AnonLibraryDisplaySettingsState();
}

class _AnonLibraryDisplaySettingsState
    extends State<AnonLibraryDisplaySettings> {
  bool _saving = false;

  Future<void> _save(Future<void> Function() update) async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      await update();
    } catch (_) {
      if (mounted)
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('设置保存失败，请重试')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final showRating = GStorage.getSetting(SettingsKeys.showRating);
    final layout = CollectLayout.fromValue(
      GStorage.getSetting(SettingsKeys.defaultCollectLayout),
    );
    return AnonFeatureFrame(
      title: '追番展示',
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('显示番剧评分'),
            value: showRating,
            onChanged: _saving
                ? null
                : (value) => _save(
                    () => GStorage.putSetting(SettingsKeys.showRating, value),
                  ),
          ),
          ListTile(
            title: const Text('追番默认布局'),
            trailing: DropdownButton<CollectLayout>(
              value: layout,
              items: [
                for (final option in CollectLayout.values)
                  DropdownMenuItem(value: option, child: Text(option.label)),
              ],
              onChanged: _saving
                  ? null
                  : (value) {
                      if (value != null)
                        _save(
                          () => GStorage.putSetting(
                            SettingsKeys.defaultCollectLayout,
                            value.name,
                          ),
                        );
                    },
            ),
          ),
        ],
      ),
    );
  }
}
