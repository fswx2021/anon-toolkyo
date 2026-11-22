import 'package:flutter/material.dart';

/// Navigation and typography shared by animation features inside ANON.
class AnonFeatureFrame extends StatelessWidget {
  const AnonFeatureFrame({
    super.key,
    required this.title,
    required this.body,
    this.actions = const [],
  });

  final String title;
  final Widget body;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        leading: const BackButton(),
        titleSpacing: 0,
        centerTitle: false,
        title: Text(
          title,
          style: theme.textTheme.titleLarge?.copyWith(
            fontWeight: FontWeight.w700,
            letterSpacing: 0.1,
          ),
        ),
        actions: [...actions, const SizedBox(width: 8)],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Divider(height: 1, color: theme.colorScheme.outlineVariant),
        ),
      ),
      body: SafeArea(top: false, bottom: false, child: body),
    );
  }
}
