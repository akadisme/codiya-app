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
                  Container(
                    padding: const EdgeInsets.all(14),
                    margin: const EdgeInsets.only(bottom: 14),
                    decoration: BoxDecoration(
                      color: _allOn ? mint.withValues(alpha: .1) : gold.withValues(alpha: .12),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Text(
                      _allOn
                          ? '✅ 준비 완료! 저장 안 된 번호와 통화가 끝나면 알림이 떠요.'
                          : '아래 6가지를 켜면, 전화가 올 때 메모창이 뜨고 통화가 끝나면 현장 안내 문자를 바로 보낼 수 있어요.',
                      style: TextStyle(fontWeight: FontWeight.w700, color: _allOn ? mint : const Color(0xFF7A5A1C)),
                    ),
                  ),
                  _step(1, '걸려온 번호 확인', '"기본 발신번호 표시 및 스팸 앱으로 설정하시겠습니까?" 창에서 [기본으로 설정]을 눌러주세요. 전화 걸기·받기·통화에는 영향이 없어요. 저장 안 된 번호인지만 확인해요. (후후·T전화·알린다 같은 발신자 표시 앱의 표시 기능은 꺼질 수 있어요)',
                      _role, '🔍 번호 확인 켜기', gold, () => CallbackBridge.call('requestRole')),
                  _step(2, '통화 끝남 알기', '"전화 걸기 및 관리" 권한을 허용해주세요. 통화가 끝났는지만 확인해요.',
                      _phone && _notify, '📞 권한 허용하기', sky, () async {
                    await CallbackBridge.call('requestPhone');
                    await Future.delayed(const Duration(milliseconds: 600));
                    final s = await CallbackBridge.status();
                    if (s['phone'] != true && mounted) {
                      // 예전에 '거부'를 눌렀으면 설정 화면에서 직접 켜야 함
                      await CallbackBridge.call('openAppSettings');
                    }
                  }),
                  _step(3, '통화 후 확인 창 띄우기', '"다른 앱 위에 표시"에서 코디야를 켜주세요. 통화가 끝나면 화면 가운데 "콜백문자를 보내시겠습니까?" 창이 떠요. (끄면 알림줄로 알려줘요)',
                      _overlay, '🪟 확인 창 켜기', coral, () => CallbackBridge.call('requestOverlay')),
                  _step(4, '📒 전화 메모창', '전화가 울리면 위쪽 알림으로 그 고객의 지난 메모가 보여요. 알림을 누르면 위쪽 반 화면에 메모가 열리고 아래 전화 화면으로 그대로 받을 수 있어요. 통화 중에도 "📒 메모 보기·쓰기" 알림을 누르면 열려요. 끊으면 메모창이 떠요. 🖥 PC 홈페이지에서 [PC 메모창]을 켜두면 PC에도 같이 떠요. 저장된 연락처도 보이게 "통화 기록"(걸려온 번호 확인만)을 허용해주세요. 메모는 키보드의 🎤 마이크로 말해서 적을 수도 있어요.',
                      _s['calllog'] == true, '📒 메모창 켜기', gold, () async {
                    await CallbackBridge.call('requestMemo');
                    await Future.delayed(const Duration(milliseconds: 600));
                    final s = await CallbackBridge.status();
                    if (s['calllog'] != true && mounted) await CallbackBridge.call('openAppSettings');
                  }),
                  _step(5, '🤖 통화요약 자동 저장', '에이닷·익시오·갤럭시 AI 등에서 통화요약 알림이 오면, 방금 통화한 고객 메모에 자동으로 저장돼요. "알림 읽기"에서 코디야를 켜주세요. (요약 알림만 저장하고, 다른 알림은 저장하지 않아요. 요약 기능을 안 쓰면 건너뛰어도 돼요)',
                      _s['summary'] == true, '🤖 자동 저장 켜기', const Color(0xFF3A86D8), () => CallbackBridge.call('requestSummary')),
                  _step(6, '📇 명함 등록 (AI가 읽어요)', '고객 명함을 찍으면 AI가 이름·회사·전화번호를 읽어서 그 번호 고객에 자동으로 붙여요. 그 고객이 전화하면 받기 전에 명함이 보여요. (통화 중 메모 화면의 [📇 명함] 버튼으로도 등록할 수 있어요)',
                      false, '📇 명함 찍어서 등록', const Color(0xFF0AA394), () => CallbackBridge.call('cardRegister')),
                  _step(7, '🎙 통화녹음 AI 요약', '휴대폰 전화 앱의 "통화 자동 녹음"을 켜두면, 통화가 끝난 뒤 녹음을 AI가 받아써서 한 줄 요약과 함께 고객 메모에 저장해요. 녹음 파일은 요약 후 서버에 남기지 않아요. (에이닷·갤럭시 AI 요약을 안 쓰는 분께 추천)',
                      false, _s['rec'] == true ? '🎙 켜져 있어요 (누르면 끄기)' : '🎙 녹음 요약 켜기', const Color(0xFF7A5AF8),
                      () async { await CallbackBridge.call('recOn', {'on': _s['rec'] != true}); await _refresh(); }),
                  _step(8, '🔋 배터리 "제한 없음"', '삼성 배터리 절약이 뒤에서 인터넷을 막으면 전화가 올 때 고객 메모를 못 불러와요. 앱 정보에서 [배터리] → "제한 없음", [모바일 데이터] → "백그라운드 데이터 사용 허용"을 켜주세요.',
                      _s['battery'] == true && _s['saver'] != true, '🔋 앱 정보 열기', mint, () => CallbackBridge.call('openBattery')),
                  _step(9, '📤 PC 문자 발송 (내 휴대폰으로 무료)', 'PC 코디야 [문자·단체·예약]에서 "내 휴대폰으로(무료)"를 골라 보내면, 이 휴대폰이 문자를 한 통씩 자동으로 보내요. 내 휴대폰 요금제의 문자로 나가서 문자 비용이 따로 들지 않아요. 예약 발송도 돼요.',
                      _s['psms'] == true, _s['psms'] == true ? '📤 켜져 있어요 (설정 보기)' : '📤 PC 문자 발송 켜기', const Color(0xFF0AA394),
                      () async { await CallbackBridge.call('psmsOpen'); }),
                  _step(10, '문자 템플릿 만들기', (_s['templates'] ?? 0) > 0
                      ? '템플릿 ${_s['templates']}개가 알림 버튼으로 나와요. 현장이 여러 개면 홈페이지에서 템플릿을 늘릴 수 있어요.'
                      : (_s['linked'] == true
                          ? '홈페이지에서 현장 안내 문자(템플릿)를 1개 만들어주세요. 자동 알림까지 무료예요.'
                          : '홈페이지에 로그인하면 내 템플릿을 불러와요.'),
                      (_s['templates'] ?? 0) > 0, '✏️ 템플릿 만들기', violet, _manage),
                  const SizedBox(height: 6),
                  _btn(_overlay ? '🔔 테스트 창 띄워보기' : '🔔 테스트 알림 보기', mint, _allOn ? () async {
                    await CallbackBridge.call('test');
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                        content: Text('잠시 후 확인 창(또는 알림)이 떠요 · 테스트 번호 010-0000-0000')));
                  } : null),
                  const SizedBox(height: 10),
                  _btn('템플릿·설정·발송내역·이용권', sky, _manage, soft: true),
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
                    '· 저장된 연락처와의 통화는 콜백문자 창이 뜨지 않아요 (메모창은 떠요)\n'
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
