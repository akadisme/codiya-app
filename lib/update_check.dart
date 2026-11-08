import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import 'config.dart';

/// 앱 업데이트 확인
///  · 관리자 '강제 업데이트' 켜짐 + 내 버전 < 최소 버전 → 닫을 수 없는 창 (업데이트해야 사용)
///  · 내 버전 < 최신 버전 → "새 버전이 있어요" 안내 (버전마다 한 번)
///  · 업데이트 주소: 플레이스토어에서 받은 앱 → 스토어 / APK로 받은 앱 → 다운로드 페이지
class UpdateCheck {
  static const _ch = MethodChannel('codiya/callback');
  static DateTime? _last;
  static bool _showing = false;

  /// a < b 이면 음수
  static int cmp(String a, String b) {
    final x = a.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    final y = b.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    for (var i = 0; i < 4; i++) {
      final d = (i < x.length ? x[i] : 0) - (i < y.length ? y[i] : 0);
      if (d != 0) return d;
    }
    return 0;
  }

  static Future<void> run(BuildContext context, {bool force = false}) async {
    if (_showing) return;
    final now = DateTime.now();
    if (!force && _last != null && now.difference(_last!) < const Duration(minutes: 30)) return;
    _last = now;
    try {
      final r = await http.get(Uri.parse('$apiBase/v1/app/version')).timeout(const Duration(seconds: 6));
      if (r.statusCode != 200) return;
      final j = jsonDecode(r.body) as Map<String, dynamic>;
      final mine = (await PackageInfo.fromPlatform()).version;
      final latest = (j['latest'] ?? '').toString();
      final min = (j['min'] ?? '').toString();
      final must = j['force'] == true && min.isNotEmpty && cmp(mine, min) < 0;
      final newer = latest.isNotEmpty && cmp(mine, latest) < 0;
      if (!must && !newer) return;

      var fromStore = false;
      if (Platform.isAndroid) {
        try {
          fromStore = (await _ch.invokeMethod('installer')) == 'com.android.vending';
        } catch (_) {}
      }
      final url = (fromStore ? j['store_url'] : j['download_url'])?.toString() ?? '$siteUrl/app/';

      if (!must) {
        final p = await SharedPreferences.getInstance();
        if (p.getString('upd_seen') == latest) return; // 이 버전 안내는 이미 봤음
        await p.setString('upd_seen', latest);
      }
      if (!context.mounted) return;
      _showing = true;
      await showDialog<void>(
        context: context,
        barrierDismissible: !must,
        builder: (ctx) => PopScope(
          canPop: !must,
          child: AlertDialog(
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
            title: Text(must ? '🔄 업데이트가 필요해요' : '✨ 새 버전이 있어요', style: const TextStyle(fontWeight: FontWeight.w800)),
            content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('지금 $mine → 최신 $latest', style: const TextStyle(color: Color(0xFF5B6870))),
              if ((j['notes'] ?? '').toString().isNotEmpty) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(color: const Color(0xFFE3F6EE), borderRadius: BorderRadius.circular(12)),
                  child: Text(j['notes'].toString(), style: const TextStyle(color: Color(0xFF1D7D5C), height: 1.5)),
                ),
              ],
              if (must) ...[
                const SizedBox(height: 10),
                const Text('새 버전으로 업데이트해야 계속 쓸 수 있어요.', style: TextStyle(fontWeight: FontWeight.w700)),
              ],
              if (!fromStore) ...[
                const SizedBox(height: 8),
                const Text('다운로드 페이지에서 받아 설치하면 기존 앱 위에 업데이트돼요.', style: TextStyle(fontSize: 12.5, color: Color(0xFF5B6870))),
              ],
            ]),
            actions: [
              if (!must) TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('나중에')),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: const Color(0xFF0AA394), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
                onPressed: () => launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication),
                child: const Text('업데이트', style: TextStyle(fontWeight: FontWeight.w800)),
              ),
            ],
          ),
        ),
      );
    } catch (_) {
    } finally {
      _showing = false;
    }
  }
}
