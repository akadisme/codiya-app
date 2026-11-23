import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'config.dart';

/// 안드로이드 쪽 콜백문자 기능과 연결
class CallbackBridge {
  static const _ch = MethodChannel('codiya/callback');

  static Future<void> setDevice(String token) async {
    if (!Platform.isAndroid) return;
    try {
      await _ch.invokeMethod('setDevice', {'token': token, 'api': apiBase});
    } catch (_) {}
  }

  static Future<Map<String, dynamic>> status() async {
    if (!Platform.isAndroid) return {};
    try {
      final r = await _ch.invokeMethod('status');
      return Map<String, dynamic>.from(r as Map);
    } catch (_) {
      return {};
    }
  }

  static Future<void> call(String m, [Map<String, dynamic>? args]) async {
    try {
      await _ch.invokeMethod(m, args);
    } catch (_) {}
  }
}

/// 콜백문자 켜기: 권한 확인 + 테스트 + 템플릿 관리로 이동
class CallbackScreen extends StatefulWidget {
  const CallbackScreen({super.key, required this.onOpenLink});
  final void Function(String url) onOpenLink;

  @override
  State<CallbackScreen> createState() => _CallbackScreenState();
}

class _CallbackScreenState extends State<CallbackScreen> with WidgetsBindingObserver {
  Map<String, dynamic> _s = {};
  bool _loading = true;

  static const gold = Color(0xFF0AA394);
  static const mint = Color(0xFF21A67A);
  static const sky = Color(0xFF3A86D8);
  static const violet = Color(0xFF7D5BD0);
  static const coral = Color(0xFFE5604D);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh(); // 권한 화면에서 돌아오면 다시 확인
  }

  Future<void> _refresh() async {
    final s = await CallbackBridge.status();
    if (!mounted) return;
    setState(() {
      _s = s;
      _loading = false;
    });
  }

  bool get _role => _s['role'] == true;
  bool get _phone => _s['phone'] == true;
  bool get _notify => _s['notify'] == true;
  bool get _overlay => _s['overlay'] == true;
  bool get _allOn => _role && _phone && _notify;
  // ✅ 한 번에 켜기: 핵심이 다 켜졌는지 (번호 확인 · 전화 · 알림 · 통화기록 · 연락처 · 다른 앱 위에 표시)
  bool get _ready => _allOn && _overlay && _s['calllog'] == true && _s['contacts'] == true && _s['summary'] == true;

  Widget _feat(String icon, String title, String desc, bool on) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(icon, style: const TextStyle(fontSize: 20)),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14.5)),
            const SizedBox(height: 2),
            Text(desc, style: const TextStyle(color: Color(0xFF5B6870), fontSize: 12.5, height: 1.45)),
          ]),
        ),
        if (on) const Padding(padding: EdgeInsets.only(left: 6, top: 2), child: Icon(Icons.check_circle, color: mint, size: 20)),
      ]),
    );
  }

  /// 맨 위: 무엇이 연결되는지 한 페이지로 + [동의하고 한 번에 켜기] 버튼 하나
  Widget _setupCard() {
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _ready ? mint.withValues(alpha: .5) : gold.withValues(alpha: .45), width: 1.5),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(_ready ? '✅ 다 켜졌어요' : '✅ 버튼 한 번으로 다 켜기',
            style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
        const SizedBox(height: 4),
        Text(_ready ? '전화가 오면 전화팝업이 떠요. 아래에서 하나씩 다시 확인할 수 있어요.'
                    : '아래 기능이 한 번에 연결돼요. 버튼을 누르고 뜨는 창마다 [허용]만 눌러 주세요.',
            style: const TextStyle(color: Color(0xFF5B6870), fontSize: 13.5, height: 1.5)),
        const SizedBox(height: 14),
        _feat('📞', '전화팝업', '전화가 울리면 누구인지 · 지난 메모를 먼저 보여줘요. 휴대폰에 저장된 이름이 먼저 떠요.',
            _role && _phone && _s['calllog'] == true && _s['contacts'] == true),
        _feat('📨', '콜백문자', '통화가 끝나면 골라 보내는 문자 창이 떠요. (내 문자앱으로 나가요)', _overlay),
        _feat('🤖', '통화요약 자동 저장', '에이닷 · 익시오 · 갤럭시 AI 통화요약이 오면 방금 통화한 고객 메모에 자동으로 저장해요. (요약 알림만 읽어요)', _s['summary'] == true),
        _feat('📤', 'PC 문자 발송', 'PC 코디야에서 보낸 문자를 이 휴대폰 문자로 보내요. (문자비 0원)', _s['psms'] == true),
        _feat('🔔', '알림', '통화요약 도착 · 고객센터 답변 알림을 받아요.', _notify),
        if (!_ready) ...[
          const SizedBox(height: 6),
          const Text(
              '버튼을 누르면 ① 걸려온 번호 확인 → ② 권한 묶음(전화 · 통화 기록 · 연락처 · 문자 · 알림) → ③ 다른 앱 위에 표시 → ④ 알림 읽기(통화요약 자동 저장) → ⑤ 배터리 "제한 없음" 순서로 창이 차례로 떠요. 창마다 [허용]만 누르고 ◀ 뒤로 가기를 누르면 다음 창이 떠요.\n'
              '걸려온 번호의 저장 이름과 통화요약 알림은 전화팝업 · 고객 메모에만 쓰고, 다른 곳에 보내지 않아요.',
              style: TextStyle(color: Color(0xFF7A8A92), fontSize: 12, height: 1.55)),
          const SizedBox(height: 12),
          _btn('동의하고 한 번에 켜기', gold, () => CallbackBridge.call('setupAll'), icon: Icons.check_circle_outline),
        ],
      ]),
    );
  }

  void _manage() {
    Navigator.of(context).popUntil((r) => r.isFirst);
    widget.onOpenLink('$siteUrl/appapi/callback.php');
  }

  Widget _btn(String label, Color color, VoidCallback? onTap, {IconData? icon, bool soft = false}) {
    return SizedBox(
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          gradient: soft || onTap == null
              ? null
              : LinearGradient(colors: [Color.lerp(color, Colors.white, .22)!, color]),
          color: onTap == null ? const Color(0xFFDDE3E6) : (soft ? color.withValues(alpha: .12) : null),
          boxShadow: soft || onTap == null
              ? null
              : [BoxShadow(color: color.withValues(alpha: .3), blurRadius: 12, offset: const Offset(0, 4))],
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(14),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 16),
              child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                if (icon != null) ...[
                  Icon(icon, size: 20, color: soft ? color : Colors.white),
                  const SizedBox(width: 8),
                ],
                Text(label,
                    style: TextStyle(
                        fontWeight: FontWeight.w800,
                        fontSize: 15.5,
                        color: onTap == null ? const Color(0xFF9A968E) : (soft ? color : Colors.white))),
              ]),
            ),
          ),
        ),
      ),
    );
  }

  /// 아직 안 된 것만 — 무엇을 허용하면 어떤 기능을 쓸 수 있는지
  List<Widget> _missingList() {
    final items = <Widget>[];
    var n = 0;
    void add(String title, String why, String btn, Color color, VoidCallback onTap) {
      n++;
      items.add(_step(n, title, why, false, btn, color, onTap));
    }

    if (!_role) {
      add('🔍 걸려온 번호 확인', '이걸 허용해야 전화가 올 때 누구인지(고객 이름 · 저장 안 된 번호) 전화팝업에 보여요.\n"기본 발신번호 표시 및 스팸 앱" 창에서 [기본으로 설정]을 눌러주세요. 전화 걸기·받기에는 영향이 없어요.',
          '🔍 허용하기', gold, () => CallbackBridge.call('requestRole'));
    }
    if (!(_phone && _notify)) {
      add('📞 전화 · 알림 권한', '이걸 허용해야 통화가 끝난 걸 알고 콜백문자를 보낼 수 있어요.', '📞 허용하기', sky, () async {
        await CallbackBridge.call('requestPhone');
        await Future.delayed(const Duration(milliseconds: 600));
        final s = await CallbackBridge.status();
        if (s['phone'] != true && mounted) await CallbackBridge.call('openAppSettings');
      });
    }
    if (!_overlay) {
      add('🪟 다른 앱 위에 표시', '이걸 허용해야 통화 중 · 통화 후에 전화팝업과 "콜백문자 보낼까요?" 창이 화면에 떠요.',
          '🪟 허용하기', coral, () => CallbackBridge.call('requestOverlay'));
    }
    if (_s['calllog'] != true || _s['contacts'] != true) {
      add('📒 통화 기록 · 연락처', '이걸 허용해야 전화팝업에 고객 메모와 저장된 이름이 보여요.', '📒 허용하기', gold, () async {
        await CallbackBridge.call('requestMemo');
        await Future.delayed(const Duration(milliseconds: 600));
        final s = await CallbackBridge.status();
        if (s['calllog'] != true && mounted) await CallbackBridge.call('openAppSettings');
      });
    }
    if (_s['summary'] != true) {
      add('🤖 알림 읽기 (통화요약)', '이걸 허용해야 에이닷 · 익시오 · 갤럭시 AI 통화요약이 그 고객 메모에 자동으로 저장돼요. (요약 알림만 읽어요)',
          '🤖 허용하기', const Color(0xFF3A86D8), () => CallbackBridge.call('requestSummary'));
    }
    if (!(_s['battery'] == true && _s['saver'] != true)) {
      add('🔋 배터리 "제한 없음"', '이걸 켜야 휴대폰이 쉬고 있을 때도 전화가 오면 전화팝업이 바로 떠요. 앱 정보에서 [배터리] → "제한 없음"을 골라주세요.',
          '🔋 앱 정보 열기', mint, () => CallbackBridge.call('openBattery'));
    }
    if ((_s['templates'] ?? 0) == 0 && _s['linked'] == true) {
      add('✏️ 콜백문자 템플릿', '템플릿을 1개 만들어야 통화가 끝났을 때 보낼 현장 안내 문자가 나와요.', '✏️ 템플릿 만들기', violet, _manage);
    }
    if (n == 0) {
      return [
        Container(
          padding: const EdgeInsets.all(14),
          margin: const EdgeInsets.only(bottom: 14),
          decoration: BoxDecoration(color: mint.withValues(alpha: .1), borderRadius: BorderRadius.circular(14)),
          child: const Text('✅ 모두 켜졌어요! 전화가 오면 전화팝업이 뜨고, 통화가 끝나면 콜백문자를 보낼 수 있어요.',
              style: TextStyle(fontWeight: FontWeight.w700, color: mint)),
        ),
      ];
    }
    return [
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
        child: Text('아직 안 된 것 $n가지', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14.5, color: Color(0xFF3B4850))),
      ),
      ...items,
    ];
  }

  Widget _step(int n, String title, String desc, bool done, String btn, Color color, VoidCallback onTap) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: done ? mint.withValues(alpha: .4) : const Color(0xFFDDE3E6)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          CircleAvatar(
            radius: 14,
            backgroundColor: done ? mint : color.withValues(alpha: .15),
            child: done
                ? const Icon(Icons.check, size: 17, color: Colors.white)
                : Text('$n', style: TextStyle(color: color, fontWeight: FontWeight.w800)),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15.5))),
          if (done)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
              decoration: BoxDecoration(color: mint.withValues(alpha: .12), borderRadius: BorderRadius.circular(99)),
              child: const Text('완료', style: TextStyle(color: mint, fontWeight: FontWeight.w800, fontSize: 12.5)),
            ),
        ]),
        const SizedBox(height: 6),
        Text(desc, style: const TextStyle(color: Color(0xFF5B6870), fontSize: 13.5, height: 1.5)),
        if (!done) ...[const SizedBox(height: 12), _btn(btn, color, onTap)],
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F7F8),
      appBar: AppBar(title: const Text('📨 콜백문자'), backgroundColor: const Color(0xFFF5F7F8)),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : !Platform.isAndroid || _s['supported'] != true
              ? const Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('통화 감지는 안드로이드 10 이상에서 쓸 수 있어요.\n홈페이지의 "직접 보내기"는 계속 쓸 수 있어요.'),
                )
              : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
                  _setupCard(),
                  // v1.2.7: '하나씩 직접 켜기'는 없애고, 아직 안 된 것만 "이걸 허용해야 ○○를 쓸 수 있어요"로
                  ..._missingList(),
                  const SizedBox(height: 6),
                  _btn(_overlay ? '🔔 테스트 창 띄워보기' : '🔔 테스트 알림 보기', mint, _allOn ? () async {
                    await CallbackBridge.call('test');
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                        content: Text('잠시 후 확인 창(또는 알림)이 떠요 · 테스트 번호 010-0000-0000')));
                  } : null),
                  const SizedBox(height: 10),
                  _btn('템플릿·설정·발송내역·이용권', sky, _manage, soft: true),
                  const SizedBox(height: 10),
                  _btn(_s['psms'] == true ? '📤 PC 문자 발송 (켜져 있어요 · 설정 보기)' : '📤 PC 문자 발송 켜기 (선택 · 내 휴대폰으로 무료)', const Color(0xFF0AA394),
                      () async { await CallbackBridge.call('psmsOpen'); }, soft: true),
                  if ((_s['diag'] ?? '').toString().isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16),
                          border: Border.all(color: const Color(0xFFDDE3E6))),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          const Expanded(child: Text('🔎 최근 통화 감지 기록', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 14.5))),
                          TextButton(
                            onPressed: () {
                              Clipboard.setData(ClipboardData(text: '${_s['phone_state'] ?? ''}\n\n${_s['diag']}'));
                              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('📋 복사했어요 · PC 홈페이지 [발송내역] 탭에서도 볼 수 있어요')));
                            },
                            child: const Text('📋 복사'),
                          ),
                        ]),
                        const SizedBox(height: 6),
                        SelectableText('${_s['phone_state'] ?? ''}\n\n${_s['diag']}', style: const TextStyle(fontSize: 13, height: 1.6, color: Color(0xFF3B4850))),
                      ]),
                    ),
                  ],
                  const SizedBox(height: 18),
                  const Text(
                    '· 문자는 내 휴대폰 문자앱으로 나가요 (요금제의 문자 사용)\n'
                    '· 알림 버튼을 누르면 내용이 채워진 문자앱이 열려요. 보내기만 누르세요\n'
                    '· 저장된 연락처와의 통화는 콜백문자 창이 뜨지 않아요 (전화팝업은 떠요)\n'
                    '· 에이닷·갤럭시 AI 통화 요약에서 [공유] → "통화 메모에 저장"을 누르면 고객 메모에 날짜별로 쌓여요',
                    style: TextStyle(color: Color(0xFF5B6870), fontSize: 13, height: 1.7),
                  ),
                  if (_s['linked'] == false)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text('⚠️ 아직 홈페이지 로그인이 연결되지 않았어요. 앱에서 홈페이지에 로그인해주세요.',
                          style: TextStyle(color: coral, fontWeight: FontWeight.w700)),
                    ),
                ]),
    );
  }
}
