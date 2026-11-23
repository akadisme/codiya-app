import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'callback_screen.dart';
import 'config.dart';
import 'inbox_screen.dart';
import 'push.dart';
import 'settings_screen.dart';
import 'update_check.dart';
import 'web_tab.dart';

final navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  runApp(const CodiyaApp());
  unawaited(PushService.instance.init());
}

class CodiyaApp extends StatelessWidget {
  const CodiyaApp({super.key});

  @override
  Widget build(BuildContext context) {
    const gold = Color(0xFF088F82);
    return MaterialApp(
      title: '코디야',
      navigatorKey: navKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: gold, primary: gold),
        useMaterial3: true,
        appBarTheme: const AppBarTheme(centerTitle: false),
      ),
      home: const Shell(),
    );
  }
}

/// 홈페이지 전체 화면. 알림함·설정은 알림을 누르거나
/// 홈페이지의 codiya://inbox, codiya://settings 링크로 열림
class Shell extends StatefulWidget {
  const Shell({super.key});

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> with WidgetsBindingObserver {
  final _web = GlobalKey<WebTabState>();
  StreamSubscription<PushTap>? _sub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // 앱 켤 때 업데이트 확인 (강제 업데이트면 업데이트 전까지 사용 불가)
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final c = navKey.currentState?.overlay?.context;
      if (c != null) UpdateCheck.run(c, force: true);
      Future.delayed(const Duration(seconds: 6), _maybeSetup);
    });
    _sub = PushService.instance.taps.stream.listen((t) {
      if (t.link.startsWith('http')) {
        _openLink(t.link);
      } else {
        _openInbox();
      }
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      final c = navKey.currentState?.overlay?.context;
      if (c != null) UpdateCheck.run(c); // 다시 열 때도 (30분에 한 번)
      Future.delayed(const Duration(seconds: 3), _maybeSetup);
    }
  }

  /// 앱에서 처음 로그인하면 "✅ 한 번에 켜기" 화면을 한 번 자동으로 (다 켜져 있거나 이미 한 번 보여줬으면 안 뜸)
  bool _setupBusy = false;
  Future<void> _maybeSetup() async {
    if (_setupBusy || !mounted) return;
    _setupBusy = true;
    try {
      final sp = await SharedPreferences.getInstance();
      // v1.2.4: 빠진 설정이 있으면 하루 한 번 다시 (다 켜지면 안 뜸)
      final today = DateTime.now().toIso8601String().substring(0, 10);
      if (sp.getString('setup_auto_day') == today) return;
      final s = await CallbackBridge.status();
      if (s['supported'] != true || s['linked'] != true) return; // 로그인 전이면 다음에
      final ready = s['role'] == true && s['phone'] == true && s['notify'] == true && s['overlay'] == true && s['calllog'] == true && s['contacts'] == true && s['summary'] == true;
      if (ready) return;
      await sp.setString('setup_auto_day', today);
      _openCallback();
    } finally {
      _setupBusy = false;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  void _openLink(String link) {
    navKey.currentState?.popUntil((r) => r.isFirst);
    _web.currentState?.load(link);
  }

  void _openInbox() {
    navKey.currentState?.popUntil((r) => r.isFirst);
    navKey.currentState?.push(MaterialPageRoute(builder: (_) => InboxScreen(onOpenLink: _openLink)));
  }

  void _openSettings() {
    navKey.currentState?.push(MaterialPageRoute(builder: (_) => SettingsScreen(onOpenLink: _openLink)));
  }

  void _openCallback() {
    navKey.currentState?.popUntil((r) => r.isFirst);
    navKey.currentState?.push(MaterialPageRoute(builder: (_) => CallbackScreen(onOpenLink: _openLink)));
  }

  void _appLink(Uri uri) {
    switch (uri.host) {
      case 'inbox':
        _openInbox();
      case 'settings':
        _openSettings();
      case 'callback':
        _openCallback();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (await _web.currentState?.goBack() ?? false) return;
        await SystemNavigator.pop();
      },
      child: Scaffold(
        body: SafeArea(
          child: WebTab(key: _web, initialUrl: siteUrl, onAppLink: _appLink),
        ),
      ),
    );
  }
}
