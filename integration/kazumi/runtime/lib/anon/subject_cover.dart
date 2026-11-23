import 'package:kazumi/services/network/bangumi_image_url_rewriter.dart';

const _bundledCover = 'anon_bundled_cover';
final bundledSubjectCoverPattern =
    RegExp(r'^assets/anon/posters/[0-9]+\.webp$');

Map<String, String> withBundledSubjectCover(int id, Map<String, String> images) {
  final poster = 'assets/anon/posters/$id.webp';
  return {'large': poster, ...images, _bundledCover: poster};
}

List<String> subjectCoverUrls(
  int id,
  Map<String, String> images, {
  required bool preferOriginal,
}) {
  final sizes = preferOriginal
      ? const ['large', 'medium', 'common', 'small', 'grid']
      : const ['common', 'small', 'medium', 'large', 'grid'];
  final remote = <String>{};
  for (final size in sizes) {
    final value = images[size]?.trim() ?? '';
    final uri = Uri.tryParse(value.startsWith('//') ? 'https:$value' : value);
    if (uri == null ||
        uri.userInfo.isNotEmpty ||
        uri.hasPort ||
        !BangumiImageUrlRewriter.isBangumiImage(uri) ||
        uri.path.endsWith('/no_icon_subject.png')) {
      continue;
    }
    remote.add(uri.replace(scheme: 'https').toString());
  }
  final official = id > 0
      ? 'https://api.bgm.tv/v0/subjects/$id/image?type=${preferOriginal ? 'large' : 'common'}'
      : null;
  final mirrorSource = remote.isNotEmpty ? remote.first : official;
  return {
    ...remote.take(2),
    if (official != null) official,
    if (mirrorSource != null)
      BangumiImageUrlRewriter.rewrite(Uri.parse(mirrorSource)).toString(),
    if (id > 0 && images.values.contains('assets/anon/posters/$id.webp'))
      'assets/anon/posters/$id.webp',
  }.toList(growable: false);
}
