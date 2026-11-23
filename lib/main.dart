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
    _sub = PushService.instance.taps.stream.listen(_onTap);
    // 앱이 꺼져 있을 때 · 잠금 화면에서 누른 알림 (화면이 뜬 뒤에 열기)
    WidgetsBinding.instance.addPostFrameCallback((_) => Future.delayed(const Duration(milliseconds: 800), _openPending));
  }

  /// v1.2.5: 알림을 누르면 그 화면으로 — 잠금을 풀고 나서도 열리게 (잠금 중에는 화면이 안 그려져서 놓치던 문제)
  void _onTap(PushTap t) {
    if (WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed || _web.currentState == null) return; // 화면이 뜨면 _openPending 이 열어요
    PushService.instance.pending = null;
    if (t.link.startsWith('http')) {
      _openLink(t.link);
    } else if (t.link.startsWith('codiya://')) {
      _appLink(Uri.parse(t.link));
    } else {
      _openInbox();
    }
  }

  void _openPending() {
    final t = PushService.instance.pending;
    if (t == null || !mounted) return;
    if (_web.currentState == null) { Future.delayed(const Duration(milliseconds: 500), _openPending); return; }
    PushService.instance.pending = null;
    if (t.link.startsWith('http')) {
      _openLink(t.link);
    } else if (t.link.startsWith('codiya://')) {
      _appLink(Uri.parse(t.link));
    } else {
      _openInbox();
    }
  }

  /// v1.2.6: 앱에서 로그인하면(페이지가 바뀔 때) 로그인 연결을 다시 확인하고, 아직 설정 전이면 바로 설정 화면으로
  DateTime _lastLinkCheck = DateTime(2000);
  bool _linkedSeen = false;
  void _onPageDone(String url) {
    if (_linkedSeen || url.contains('/login')) return;
    if (DateTime.now().difference(_lastLinkCheck).inSeconds < 15) return;
    _lastLinkCheck = DateTime.now();
    final t = PushService.instance.token;
    if (t == null) return;
    CallbackBridge.setDevice(t).then((_) => Future.delayed(const Duration(seconds: 3), _maybeSetup));
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      Future.delayed(const Duration(milliseconds: 400), _openPending); // 잠금을 풀고 돌아오면 누른 알림 화면으로
      final c = navKey.currentState?.overlay?.context;
      if (c != null) UpdateCheck.run(c); // 다시 열 때도 (30분에 한 번)
      Future.delayed(const Duration(seconds: 3), _maybeSetup);
    }
  }

  /// v1.2.6 설정 안내
  ///  · 앱에서 처음 로그인하면 → 바로 "✅ 한 번에 켜기" 설정 화면
  ///  · 설정을 다 안 했으면 → 다음 날 오전 10시에 알림 + 그날 처음 앱을 열 때(10시 이후) 안내 팝업 (하루 한 번)
  ///  · 다 켜지면 → 더 이상 안 나와요 (예약 알림도 취소)
  bool _setupBusy = false;
  Future<void> _maybeSetup() async {
    if (_setupBusy || !mounted) return;
    _setupBusy = true;
    try {
      final sp = await SharedPreferences.getInstance();
      final s = await CallbackBridge.status();
      if (s['supported'] != true || s['linked'] != true) return; // 로그인 전이면 다음에
      _linkedSeen = true;
      final ready = s['role'] == true && s['phone'] == true && s['notify'] == true && s['overlay'] == true && s['calllog'] == true && s['contacts'] == true && s['summary'] == true;
      if (ready) {
        await PushService.instance.cancelSetupReminder();
        return;
      }
      final now = DateTime.now();
      final today = now.toIso8601String().substring(0, 10);
      if (sp.getString('setup_first_day') == null) {
        // 처음 로그인 → 바로 설정 화면
        await sp.setString('setup_first_day', today);
        await sp.setString('setup_auto_day', today);
        await PushService.instance.scheduleSetupReminder();
        _openCallback();
        return;
      }
      await PushService.instance.scheduleSetupReminder(); // 다음 오전 10시 알림 (이미 있으면 다시 맞춤)
      if (sp.getString('setup_auto_day') == today || now.hour < 10) return;
      await sp.setString('setup_auto_day', today);
      _showSetupNudge();
    } finally {
      _setupBusy = false;
    }
  }

  void _showSetupNudge() {
    final c = navKey.currentState?.overlay?.context;
    if (c == null) return;
    showDialog<void>(
      context: c,
      builder: (ctx) => AlertDialog(
        title: const Text('📞 전화팝업 · 콜백문자 설정이 아직이에요'),
        content: const Text(
          '📞 전화팝업 — 전화가 오면 고객 이름 · 메모 · 지난 상담이 화면에 바로 떠요.\n\n'
          '💬 콜백문자 — 통화를 끊으면(못 받은 전화도) 정해 둔 문자가 자동으로 가요.\n\n'
          '🤖 통화요약 — 에이닷 · 익시오 요약이 고객 메모에 자동으로 저장돼요.\n\n'
          '고객관리가 훨씬 편해져요. 1분이면 설정돼요.',
          style: TextStyle(height: 1.5),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('다음에')),
          FilledButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              _openCallback();
            },
            child: const Text('설정하러 가기'),
          ),
        ],
      ),
    );
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
    navKey.currentState?.push(MaterialPageRoute(builder: (_) => CallbackScreen(onOpenLink: _openLink))).then((_) async {
      // 설정 화면에서 돌아오면 다 켜졌는지 확인 → 다 켜졌으면 예약 알림 취소
      final s = await CallbackBridge.status();
      final ready = s['role'] == true && s['phone'] == true && s['notify'] == true && s['overlay'] == true && s['calllog'] == true && s['contacts'] == true && s['summary'] == true;
      if (ready) await PushService.instance.cancelSetupReminder();
    });
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
          child: WebTab(key: _web, initialUrl: siteUrl, onAppLink: _appLink, onPageDone: _onPageDone),
        ),
      ),
    );
  }
}
