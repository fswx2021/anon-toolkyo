/// Destination of an ANON animation action. The Android host supplies values
/// separately, so titles and import URLs are never interpreted as route paths.
class AnimeFeatureRequest {
  const AnimeFeatureRequest(
    this.feature, [
    this.value = '',
    this.appearance,
    this.subjectPreview,
  ]);

  factory AnimeFeatureRequest.fromMessage(Object? message) {
    if (message is! Map || message['feature'] is! String) {
      throw const FormatException('无法识别动画入口');
    }
    return AnimeFeatureRequest(
      message['feature'] as String,
      message['value']?.toString() ?? '',
      int.tryParse(message['appearance']?.toString() ?? ''),
      message['subjectPreview'] is String
          ? message['subjectPreview'] as String
          : null,
    );
  }

  final String feature;
  final String value;
  final int? appearance;
  final String? subjectPreview;
  Object? get arguments => switch (feature) {
    'subject' => subjectPreview,
    'import' => value,
    _ => null,
  };

  String get route => switch (feature) {
    'popular' => '/popular/',
    'timeline' => '/timeline/',
    'collect' => '/collect/',
    'history' || 'resume' => '/settings/history/',
    'rules' || 'import' => '/settings/plugin/',
    'catalog' => '/settings/plugin/shop',
    'downloads' => '/settings/download/',
    'settings' => '/settings/',
    'licenses' => '/licenses',
    'sync' => '/settings/sync',
    'imageSearch' => '/search/image',
    'search' =>
      value.trim().isEmpty
          ? '/search/'
          : Uri(
              path: '/search/',
              queryParameters: {'query': value.trim()},
            ).toString(),
    'subject' when (int.tryParse(value) ?? 0) > 0 =>
      '/subject/${int.parse(value)}',
    _ => throw const FormatException('无法识别动画入口'),
  };
}
