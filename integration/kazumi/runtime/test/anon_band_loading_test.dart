import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kazumi/bean/card/network_img_layer.dart';
import 'package:kazumi/bean/widget/loading_indicator.dart';

void main() {
  testWidgets('the pick follows a beat and respects reduced motion', (
    tester,
  ) async {
    final frame = GlobalKey();

    Future<void> show({
      required bool reduceMotion,
      required bool visible,
    }) async {
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: reduceMotion),
            child: TickerMode(
              enabled: visible,
              child: Center(
                child: RepaintBoundary(
                  key: frame,
                  child: const LoadingIndicator(semanticsLabel: '正在载入播放器'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
    }

    Future<Uint8List> pixels() async {
      final boundary =
          frame.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      return (await tester.runAsync(() async {
        final image = await boundary.toImage();
        try {
          return (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!
              .buffer
              .asUint8List();
        } finally {
          image.dispose();
        }
      }))!;
    }

    final semantics = tester.ensureSemantics();
    try {
      await show(reduceMotion: false, visible: true);
      expect(find.bySemanticsLabel('正在载入播放器'), findsOneWidget);
    } finally {
      semantics.dispose();
    }
    expect(tester.getSize(find.byType(LoadingIndicator)), const Size(48, 48));
    final start = await pixels();
    await tester.pump(const Duration(milliseconds: 280));
    expect(listEquals(start, await pixels()), isFalse);

    await show(reduceMotion: true, visible: true);
    final still = await pixels();
    await tester.pump(const Duration(milliseconds: 280));
    expect(listEquals(still, await pixels()), isTrue);

    await show(reduceMotion: false, visible: false);
    final offstage = await pixels();
    await tester.pump(const Duration(milliseconds: 280));
    expect(listEquals(offstage, await pixels()), isTrue);
  });

  testWidgets('an absent cover displays a static fallback', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Center(child: NetworkImgLayer(width: 100, height: 150)),
      ),
    );
    expect(find.byIcon(Icons.image_outlined), findsOneWidget);
    expect(find.byType(LoadingIndicator), findsNothing);
  });
}
