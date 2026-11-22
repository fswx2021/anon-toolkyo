import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_modular/flutter_modular.dart';
import 'package:hive_ce_flutter/hive_flutter.dart';
import 'package:kazumi/anon/feature_routes.dart';
import 'package:kazumi/bean/dialog/dialog_helper.dart';
import 'package:kazumi/bean/settings/theme_provider.dart';
import 'package:kazumi/navigation.dart';
import 'package:kazumi/services/network/ech_http_licenses.dart';
import 'package:kazumi/services/network/metered_network_service.dart';
import 'package:kazumi/services/network/proxy_manager.dart';
import 'package:kazumi/services/platform/webview_feature_service.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:media_kit/media_kit.dart';
import 'package:path_provider/path_provider.dart';

const _locale = Locale.fromSubtags(
  languageCode: 'zh',
  scriptCode: 'Hans',
  countryCode: 'CN',
);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  registerEchHttpLicenses();
  MediaKit.ensureInitialized();
  unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge));
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarDividerColor: Colors.transparent,
      statusBarColor: Colors.transparent,
    ),
  );

  try {
    final support = await getApplicationSupportDirectory();
    await Hive.initFlutter('${support.path}/hive');
    await GStorage.init();
    ProxyManager.applyProxy();
  } catch (error, stack) {
    FlutterError.reportError(
      FlutterErrorDetails(exception: error, stack: stack),
    );
    runApp(
      MaterialApp(
        theme: _theme(Brightness.light),
        darkTheme: _theme(Brightness.dark),
        home: Scaffold(
          appBar: AppBar(
            title: const Text('动画'),
            leading: const BackButton(onPressed: SystemNavigator.pop),
          ),
          body: const Center(child: Text('动画资料暂时无法打开，请重新启动应用。')),
        ),
      ),
    );
    return;
  }
  try {
    await WebViewFeatureService.initialize();
  } catch (error) {
    debugPrint('ANON WebView feature detection: $error');
  }
  unawaited(MeteredNetworkService.refresh());
  runApp(
    ModularApp(
      module: anonFeatures,
      navigatorKey: rootNavigatorKey,
      navigatorObservers: [KazumiDialog.observer, rootRouteObserver],
      provide: (scope) => scope.addChangeNotifier<ThemeProvider>(
        () => ThemeProvider()
          ..setTheme(
            _theme(Brightness.light),
            _theme(Brightness.dark),
            notify: false,
          )
          ..setFontFamily(true, notify: false),
      ),
      child: const _AnimeApp(),
    ),
  );
}

ThemeData _theme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final background = dark ? const Color(0xff191a1c) : Colors.white;
  final ink = dark ? const Color(0xffededf0) : const Color(0xff202124);
  final muted = dark ? const Color(0xffa9aab2) : const Color(0xff63656d);
  final panel = dark ? const Color(0xff232427) : const Color(0xfff5f5f6);
  final line = dark ? const Color(0xff323338) : const Color(0xffececef);
  final accent = dark ? const Color(0xfff299a6) : const Color(0xffbf3d50);
  final scheme = ColorScheme.fromSeed(
    seedColor: const Color(0xffbf3d50),
    brightness: brightness,
  ).copyWith(
    primary: accent,
    onPrimary: dark ? const Color(0xff222124) : Colors.white,
    primaryContainer: dark ? const Color(0xff37272d) : const Color(0xffffeff1),
    onPrimaryContainer: ink,
    surface: background,
    surfaceContainerLow: panel,
    surfaceContainer: panel,
    surfaceContainerHigh: dark ? const Color(0xff2b2c30) : const Color(0xfff0f0f2),
    surfaceContainerHighest: dark ? const Color(0xff333438) : const Color(0xffeaeaed),
    onSurface: ink,
    onSurfaceVariant: muted,
    outlineVariant: line,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: background,
    dividerTheme: DividerThemeData(color: line, thickness: 1, space: 1),
    appBarTheme: AppBarThemeData(
      backgroundColor: background,
      foregroundColor: ink,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: TextStyle(color: ink, fontSize: 20, fontWeight: FontWeight.w700),
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
  );
}

class _AnimeApp extends StatelessWidget {
  const _AnimeApp();

  @override
  Widget build(BuildContext context) {
    final appearance = context.watch<ThemeProvider>();
    return MaterialApp.router(
      title: 'anon toolkyo',
      debugShowCheckedModeBanner: false,
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      supportedLocales: const [_locale],
      locale: _locale,
      theme: appearance.light,
      darkTheme: appearance.dark,
      themeMode: appearance.themeMode,
      scaffoldMessengerKey: rootScaffoldMessengerKey,
      routerConfig: ModularApp.routerConfigOf(context),
    );
  }
}
