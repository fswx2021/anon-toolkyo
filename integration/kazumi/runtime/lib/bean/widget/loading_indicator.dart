import 'dart:math' as math;

import 'package:flutter/material.dart';

/// ANON's guitar pick keeps time while the animation module waits.
class LoadingIndicator extends StatefulWidget {
  const LoadingIndicator({
    super.key,
    this.size = 48,
    this.color,
    this.semanticsLabel = '加载中',
  });

  final double size;
  final Color? color;
  final String semanticsLabel;

  @override
  State<LoadingIndicator> createState() => _LoadingIndicatorState();
}

class _LoadingIndicatorState extends State<LoadingIndicator>
    with SingleTickerProviderStateMixin {
  late final _beat = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1320),
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context) || !TickerMode.of(context)) {
      _beat.stop();
    } else if (!_beat.isAnimating) {
      _beat.repeat();
    }
  }

  @override
  void dispose() {
    _beat.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final accent = widget.color ?? colors.primary;
    return Semantics(
      label: widget.semanticsLabel,
      child: RepaintBoundary(
        child: SizedBox.square(
          dimension: widget.size,
          child: CustomPaint(
            painter: _BandPickPainter(
              beat: MediaQuery.disableAnimationsOf(context)
                  ? const AlwaysStoppedAnimation<double>(0)
                  : _beat,
              accent: accent,
              ink: widget.color == null
                  ? colors.onPrimary
                  : accent.computeLuminance() >= .34
                  ? const Color(0xff321922)
                  : Colors.white,
            ),
          ),
        ),
      ),
    );
  }
}

class _BandPickPainter extends CustomPainter {
  _BandPickPainter({
    required this.beat,
    required this.accent,
    required this.ink,
  }) : super(repaint: beat);

  static final _pick = Path()
    ..moveTo(32, 12)
    ..cubicTo(46, 11, 53, 17, 51, 26)
    ..cubicTo(49, 36, 43, 47, 37, 53)
    ..quadraticBezierTo(32, 58, 27, 53)
    ..cubicTo(21, 47, 15, 36, 13, 26)
    ..cubicTo(11, 17, 18, 11, 32, 12)
    ..close();

  static final _shine = Path()
    ..moveTo(20, 23)
    ..quadraticBezierTo(23, 18, 29, 18);

  static final _star = Path()
    ..moveTo(0, -5)
    ..quadraticBezierTo(.6, -.6, 5, 0)
    ..quadraticBezierTo(.6, .6, 0, 5)
    ..quadraticBezierTo(-.6, .6, -5, 0)
    ..quadraticBezierTo(-.6, -.6, 0, -5)
    ..close();

  final Animation<double> beat;
  final Color accent;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    final side = math.min(size.width, size.height);
    if (side <= 0) return;
    final phase = beat.value * 2 * math.pi;
    final beatLift = math.cos(phase);
    final paint = Paint()..color = accent;
    canvas.save();
    canvas.translate((size.width - side) / 2, (size.height - side) / 2);
    canvas.scale(side / 64);

    canvas.save();
    canvas.translate(32, 32 - 1.8 * beatLift);
    canvas.rotate(-.13 + .12 * math.sin(phase));
    canvas.translate(-32, -32);
    canvas.drawPath(_pick, paint);
    paint
      ..color = ink.withValues(alpha: .52)
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 2;
    canvas.drawPath(_shine, paint);
    paint
      ..color = ink
      ..strokeWidth = 3.8;
    for (var index = 0; index < 3; index++) {
      final x = 25.0 + index * 7;
      final pulse = (1 + math.cos(phase * 2 - index * 1.45)) / 2;
      final halfHeight = 3 + (index == 1 ? 4 : 2) * pulse;
      canvas.drawLine(
        Offset(x, 34 - halfHeight),
        Offset(x, 34 + halfHeight),
        paint,
      );
    }
    canvas.restore();

    paint
      ..style = PaintingStyle.fill
      ..color = accent.withValues(alpha: .65 + .25 * beatLift);
    canvas.save();
    canvas.translate(54, 14 - beatLift);
    canvas.scale(.8 + .13 * beatLift);
    canvas.drawPath(_star, paint);
    canvas.restore();
    paint.color = accent.withValues(alpha: .51 - .19 * beatLift);
    canvas.drawCircle(Offset(9, 40 + beatLift), 2.25, paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_BandPickPainter oldDelegate) =>
      oldDelegate.beat != beat ||
      oldDelegate.accent != accent ||
      oldDelegate.ink != ink;
}
