import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'api.dart';
import 'callback_screen.dart';

/// 알림을 눌렀을 때 전달되는 내용
class PushTap {
  const PushTap(this.nid, this.link);
  final int nid;
  final String link;
}

/// 푸시 수신·기기 등록·토픽 구독 담당
class PushService {
  PushService._();
  static final PushService instance = PushService._();

  final _fm = FirebaseMessaging.instance;
  final _local = FlutterLocalNotificationsPlugin();

  /// 알림을 눌렀을 때
  final taps = StreamController<PushTap>.broadcast();

  /// 새 알림 도착·읽음 등 알림함을 새로 그려야 할 때
  final changes = StreamController<void>.broadcast();

  /// 기기 등록 완료 여부 (설정 화면 갱신용)
  final ready = ValueNotifier<bool>(false);

  String? token;
  int? deviceId;
  bool pushOn = true;
  String? memberType;
  String appVersion = '';

  static const _channel = AndroidNotificationChannel(
    'default',
    '알림',
    description: '코디야 알림',
    importance: Importance.high,
  );

  Future<void> init() async {
    await _local.initialize(
      const InitializationSettings(
        android: AndroidInitializationSettings('ic_stat_notify'),
        iOS: DarwinInitializationSettings(),
      ),
      onDidReceiveNotificationResponse: (resp) {
        final p = resp.payload;
        if (p != null && p.isNotEmpty) {
          _emit(Map<String, dynamic>.from(jsonDecode(p) as Map));
        }
      },
    );
    await _local
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(_channel);

    try {
      appVersion = (await PackageInfo.fromPlatform()).version;
    } catch (_) {}

    await _fm.requestPermission();
    await _fm.setForegroundNotificationPresentationOptions(alert: true, badge: true, sound: true);

    FirebaseMessaging.onMessage.listen(_onForeground);
    FirebaseMessaging.onMessageOpenedApp.listen((m) => _emit(m.data));
    _fm.onTokenRefresh.listen((t) {
      token = t;
      _setSiteCookie(t);
      register();
    });

    // 앱이 꺼진 상태에서 알림을 눌러 실행된 경우
    final first = await _fm.getInitialMessage();
    if (first != null) {
      Future.delayed(const Duration(milliseconds: 600), () => _emit(first.data));
    }

    try {
      token = await _fm.getToken();
    } catch (e) {
      debugPrint('토큰 받기 실패: $e');
    }
    final t = token;
    if (t != null) await _setSiteCookie(t);
    await register();
  }

  /// 홈페이지(그누보드)가 로그인한 회원과 이 기기를 연결할 수 있게 쿠키로 전달
  Future<void> _setSiteCookie(String t) async {
    try {
      await WebViewCookieManager().setCookie(
        WebViewCookie(name: 'gr_device', value: t, domain: '.codiya.kr', path: '/'),
      );
    } catch (e) {
      debugPrint('쿠키 설정 실패: $e');
    }
  }

  /// 앱을 보고 있을 때 온 알림도 화면 위에 띄움 (이미지 포함)
  Future<void> _onForeground(RemoteMessage m) async {
    changes.add(null);
    final n = m.notification;
    if (n == null || !Platform.isAndroid) return; // 아이폰은 시스템이 표시
    StyleInformation? style;
    AndroidBitmap<Object>? largeIcon;
    final imageUrl = n.android?.imageUrl;
    if (imageUrl != null && imageUrl.startsWith('https://')) {
      try {
        final r = await http.get(Uri.parse(imageUrl)).timeout(const Duration(seconds: 10));
        if (r.statusCode == 200 && r.bodyBytes.isNotEmpty) {
          final bmp = ByteArrayAndroidBitmap(r.bodyBytes);
          largeIcon = bmp;
          style = BigPictureStyleInformation(
            bmp,
            largeIcon: bmp,
            hideExpandedLargeIcon: true,
            contentTitle: n.title,
            summaryText: n.body,
          );
        }
      } catch (_) {}
    }
    // 이미지가 없으면 긴 글(리포트)이 펼쳐서 다 보이게
    style ??= BigTextStyleInformation(n.body ?? '', contentTitle: n.title);
    await _local.show(
      DateTime.now().millisecondsSinceEpoch ~/ 1000 % 100000000,
      n.title,
      n.body,
      NotificationDetails(
        android: AndroidNotificationDetails(
          _channel.id,
          _channel.name,
          channelDescription: _channel.description,
          importance: Importance.high,
          priority: Priority.high,
          icon: 'ic_stat_notify',
          largeIcon: largeIcon,
          styleInformation: style,
        ),
      ),
      payload: jsonEncode(m.data),
    );
  }

  void _emit(Map<String, dynamic> data) {
    final tap = PushTap(int.tryParse('${data['nid'] ?? ''}') ?? 0, '${data['link'] ?? ''}');
    markOpened(tap.nid);
    taps.add(tap);
  }

  /// 앱이 켜질 때마다 서버에 기기 등록 + 구독할 토픽 맞추기
  Future<void> register() async {
    final t = token;
    if (t == null) return;
    try {
      final r = await Api.post('/v1/device/register', {
        'token': t,
        'platform': Platform.isIOS ? 'ios' : 'android',
        'app_version': appVersion,
      });
      if (r['ok'] == true) {
        deviceId = r['device_id'] as int?;
        pushOn = r['push_on'] == true;
        // 회원 유형은 홈페이지 회원정보(가입 때 선택)에서만 정해짐
        final t = r['member_type'];
        memberType = t is String && t.isNotEmpty ? t : null;
        await _syncTopics(List<String>.from(r['topics'] as List));
        ready.value = true;
        changes.add(null);
        await CallbackBridge.setDevice(token!);
      }
    } catch (e) {
      debugPrint('기기 등록 실패: $e');
    }
  }

  Future<void> _syncTopics(List<String> topics) async {
    final prefs = await SharedPreferences.getInstance();
    final old = prefs.getStringList('topics') ?? const <String>[];
    for (final x in old.where((o) => !topics.contains(o))) {
      await _fm.unsubscribeFromTopic(x);
    }
    for (final x in topics) {
      await _fm.subscribeToTopic(x);
    }
    await prefs.setStringList('topics', topics);
  }

  /// 알림 켜기/끄기, 회원 유형 변경
  Future<bool> updateSettings({bool? push, String? type}) async {
    final t = token;
    if (t == null) return false;
    try {
      final r = await Api.post('/v1/device/settings', {
        'token': t,
        if (push != null) 'push_on': push,
        if (type != null) 'member_type': type,
      });
      if (r['ok'] != true) return false;
      pushOn = r['push_on'] == true;
      if (type != null) {
        memberType = type;
        await (await SharedPreferences.getInstance()).setString('member_type', type);
      }
      await _syncTopics(List<String>.from(r['topics'] as List));
      ready.value = false;
      ready.value = true;
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> markOpened(int nid) async {
    final t = token;
    if (nid <= 0 || t == null) return;
    try {
      await Api.post('/v1/open', {'token': t, 'nid': nid});
    } catch (_) {}
    changes.add(null);
  }

  Future<Map<String, dynamic>> inbox() => Api.get('/v1/inbox', {'token': token ?? ''});
}
