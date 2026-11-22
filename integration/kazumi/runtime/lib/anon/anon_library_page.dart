import 'package:flutter/material.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/pages/collect/collect_controller.dart';
import 'package:kazumi/pages/collect/collect_page.dart';
import 'package:kazumi/pages/history/history_controller.dart';
import 'package:kazumi/pages/history/history_page.dart';

class AnonLibraryPage extends StatelessWidget {
  const AnonLibraryPage({super.key, required this.history});
  final bool history;

  @override
  Widget build(BuildContext context) {
    return history
        ? HistoryPage(controller: inject<HistoryController>())
        : CollectPage(controller: inject<CollectController>());
  }
}
