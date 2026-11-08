import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';

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
    const gold = Color(0xFF0AA394);
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
      Future.delayed(const Duration(seconds: 6), _setupNag);
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
      Future.delayed(const Duration(seconds: 2), _setupNag);
    }
  }

  /// 로그인은 했는데 전화 메모창·콜백문자 준비가 안 된 분께 안내 (6시간에 한 번, '그만 보기' 가능)
  bool _nagOpen = false;
  Future<void> _setupNag() async {
    if (_nagOpen || !mounted) return;
    final s = await CallbackBridge.status();
    if (s.isEmpty || s['supported'] != true || s['linked'] != true) return;
    final missing = <String>[
      if (s['role'] != true) '번호 확인',
      if (s['phone'] != true || s['notify'] != true) '전화·알림 권한',
      if (s['overlay'] != true) '다른 앱 위에 표시',
      if (s['calllog'] != true) '통화 기록 (메모창)',
      if ((s['templates'] ?? 0) == 0) '콜백문자 템플릿',
    ];
    if (missing.isEmpty) return;
    final p = await SharedPreferences.getInstance();
    if (p.getBool('setup_nag_off') == true) return;
    final last = p.getInt('setup_nag_at') ?? 0;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - last < 6 * 3600 * 1000) return;
    await p.setInt('setup_nag_at', now);
    final c = navKey.currentState?.overlay?.context;
    if (c == null || !mounted) return;
    _nagOpen = true;
    final go = await showDialog<String>(
      context: c,
      builder: (ctx) => AlertDialog(
        title: const Text('📒 전화 메모창 · 📨 콜백문자 켜기'),
        content: Text('전화가 오면 고객 메모를 띄우고, 끊으면 콜백문자를 보내려면 휴대폰 설정이 필요해요.\n\n'
            '아직 안 된 것: ${missing.join(', ')}\n\n[지금 설정하기]를 누르고 차례로 켜 주세요. 1분이면 끝나요.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, 'off'), child: const Text('그만 보기')),
          TextButton(onPressed: () => Navigator.pop(ctx, 'later'), child: const Text('나중에')),
          FilledButton(onPressed: () => Navigator.pop(ctx, 'go'), child: const Text('지금 설정하기')),
        ],
      ),
    );
    _nagOpen = false;
    if (go == 'off') await p.setBool('setup_nag_off', true);
    if (go == 'go') _openCallback();
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
