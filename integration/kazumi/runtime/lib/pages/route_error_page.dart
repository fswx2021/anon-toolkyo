import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/bean/settings/settings_detail_scaffold.dart';
import 'package:kazumi/bean/widget/error_widget.dart';
import 'package:kazumi/bean/widget/state_presentation.dart';

class RouteErrorPage extends StatelessWidget {
  const RouteErrorPage({super.key, required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => SettingsDetailScaffold(
    title: const Text('动画'),
    body: GeneralErrorWidget(
      title: '无法打开页面',
      errMsg: message,
      actions: [
        StateActionButton(
          onPressed: () {
            if (!context.maybePop()) SystemNavigator.pop();
          },
          icon: Icons.arrow_back_rounded,
          text: '返回',
        ),
      ],
    ),
  );
}
