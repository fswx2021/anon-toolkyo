import 'dart:async';

import 'package:flutter/services.dart';
import 'package:kazumi/anon/feature_request.dart';

const animeChannel = MethodChannel('com.shengjian.player/anime_features');

/// A media service can start the isolate before an Activity has attached.
/// Android retains the request until ready(), and signals a later attachment.
class AnimeFeatureBridge {
  AnimeFeatureBridge(this.open, {this.channel = animeChannel});

  final void Function(AnimeFeatureRequest request) open;
  final MethodChannel channel;
  int _revision = 0;
  bool _disposed = false;

  Future<void> start() async {
    channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'open':
          _revision++;
          open(AnimeFeatureRequest.fromMessage(call.arguments));
        case 'hostAttached':
          await _ready();
        default:
          throw MissingPluginException(call.method);
      }
    });
    await _ready();
  }

  Future<void> _ready() async {
    final before = _revision;
    try {
      final request = await channel.invokeMethod<Object?>('ready');
      if (!_disposed && request != null && before == _revision) {
        _revision++;
        open(AnimeFeatureRequest.fromMessage(request));
      }
    } on MissingPluginException {
      // Wait for the Activity to attach to a background audio engine.
    }
  }

  void dispose() {
    _disposed = true;
    channel.setMethodCallHandler(null);
  }
}
