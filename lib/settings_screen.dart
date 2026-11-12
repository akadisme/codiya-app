import 'package:flutter/material.dart';

import 'callback_screen.dart';
import 'config.dart';
import 'push.dart';

/// 알림 설정·회원 유형·기기 정보
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.onOpenLink});
  final void Function(String url)? onOpenLink;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _busy = false;
  final _push = PushService.instance;

  Future<void> _update({bool? push}) async {
    setState(() => _busy = true);
    final ok = await _push.updateSettings(push: push);
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(ok ? '저장했어요' : '저장하지 못했어요. 인터넷 연결을 확인해 주세요.')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: ValueListenableBuilder<bool>(
        valueListenable: _push.ready,
        builder: (context, ready, _) => AbsorbPointer(
          absorbing: _busy || !ready,
          child: ListView(
            children: [
              if (!ready)
                const ListTile(
                  leading: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
                  title: Text('알림 설정을 준비하고 있어요'),
                ),
              SwitchListTile(
                title: const Text('알림 받기'),
                subtitle: const Text('코디야 소식·알림'),
                value: _push.pushOn,
                onChanged: (v) => _update(push: v),
              ),
              const Divider(),
              if (Theme.of(context).platform == TargetPlatform.android && widget.onOpenLink != null)
                ListTile(
                  leading: const Text('📨', style: TextStyle(fontSize: 22)),
                  title: const Text('콜백문자'),
                  subtitle: const Text('통화가 끝나면 현장 안내 문자 보내기'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => CallbackScreen(onOpenLink: widget.onOpenLink!))),
                ),
              if (Theme.of(context).platform == TargetPlatform.android && widget.onOpenLink != null) const Divider(),
              if (Theme.of(context).platform == TargetPlatform.android && widget.onOpenLink != null)
                ListTile(
                  leading: const Text('📇', style: TextStyle(fontSize: 22)),
                  title: const Text('명함 등록'),
                  subtitle: const Text('명함을 찍거나 사진을 고르면 AI가 이름·번호를 읽어 등록 → 그 고객 전화 때 명함이 떠요'),
                  trailing: const Icon(Icons.photo_camera_outlined),
                  onTap: () => CallbackBridge.call('cardRegister'),
                ),
              if (Theme.of(context).platform == TargetPlatform.android && widget.onOpenLink != null)
                ListTile(
                  leading: const Text('📋', style: TextStyle(fontSize: 22)),
                  title: const Text('명함·고객 목록'),
                  subtitle: const Text('등록한 명함 보기 · 잘못 읽은 번호·이름 고치기'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => widget.onOpenLink!('$siteUrl/appapi/callback.php?cards=1#memo'),
                ),
              if (Theme.of(context).platform == TargetPlatform.android && widget.onOpenLink != null) const Divider(),
              ListTile(
                title: const Text('내 회원 유형'),
                subtitle: const Text('홈페이지 회원정보 수정에서 바꿀 수 있어요'),
                trailing: Text(memberTypes[_push.memberType] ?? '로그인 후 표시',
                    style: theme.textTheme.titleSmall),
              ),
              const Divider(),
              ListTile(
                title: const Text('기기 번호'),
                subtitle: const Text('관리자 화면의 "설치된 기기"에 이 번호로 보여요'),
                trailing: Text(_push.deviceId != null ? '#${_push.deviceId}' : '-',
                    style: theme.textTheme.titleMedium),
              ),
              ListTile(
                title: const Text('앱 버전'),
                trailing: Text(_push.appVersion.isEmpty ? '-' : _push.appVersion),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
