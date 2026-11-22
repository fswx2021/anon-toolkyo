import 'package:flutter/widgets.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/anon/feature_request.dart';

/// One explicit Android action owns a stack. Internal searches, episodes and
/// dialogs can push freely; only popping that action's root returns to ANON.
class AnimeFeatureStack {
  bool _active = false;
  int _generation = 0;

  Future<void> open(
    BuildContext host,
    AnimeFeatureRequest request, {
    required Future<void> Function() publish,
    required Future<void> Function() close,
  }) async {
    if (request.feature == 'resume' && _active) return;
    final route = request.route;
    final generation = ++_generation;
    if (_active) {
      Navigator.of(host).popUntil((entry) => entry.isFirst);
      host.popUntil((state) => state.uri.path == '/');
    }
    _active = true;
    await host.pushNamed<void>(route, arguments: request.arguments);
    if (!host.mounted || generation != _generation) return;
    _active = false;
    await publish();
    if (host.mounted && generation == _generation) await close();
  }
}
