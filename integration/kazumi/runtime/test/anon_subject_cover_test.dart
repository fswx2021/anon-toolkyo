import 'package:flutter_test/flutter_test.dart';
import 'package:kazumi/anon/subject_cover.dart';

void main() {
  const id = 513878;
  const poster = 'assets/anon/posters/513878.webp';
  const large = 'https://lain.bgm.tv/pic/cover/l/db/94/513878_5f77g.jpg';
  const common =
      'https://lain.bgm.tv/r/400/pic/cover/l/db/94/513878_5f77g.jpg';

  test('offline discovery still attempts the official current cover', () {
    final urls = subjectCoverUrls(
      id,
      withBundledSubjectCover(id, const {}),
      preferOriginal: false,
    );
    expect(urls.first, 'https://api.bgm.tv/v0/subjects/513878/image?type=common');
    expect(Uri.parse(urls[1]).host, 'wsrv.nl');
    expect(urls.last, poster);
  });

  test('lists load smaller images and retain original and offline fallbacks', () {
    final images = withBundledSubjectCover(id, const {
      'large': large,
      'common': common,
      'small': common,
    });
    final list = subjectCoverUrls(id, images, preferOriginal: false);
    expect(list.take(2), [common, large]);
    expect(Uri.parse(list[3]).queryParameters['url'], common.substring(8));
    expect(list.last, poster);
    final detail = subjectCoverUrls(id, images, preferOriginal: true);
    expect(detail.take(2), [large, common]);
    expect(detail[2], endsWith('type=large'));
  });

  test('unrelated hosts and generic subject icons do not replace a real cover', () {
    final list = subjectCoverUrls(
      id,
      const {
        'large': 'https://example.org/cover.jpg',
        'small': 'https://lain.bgm.tv/img/no_icon_subject.png',
        'common': '//lain.bgm.tv/r/400/pic/cover/l/db/94/513878_5f77g.jpg',
      },
      preferOriginal: true,
    );
    expect(list.first, common);
    expect(list, hasLength(3));
    expect(list.last, startsWith('https://wsrv.nl/'));
  });
}
