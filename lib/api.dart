import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';

/// 푸시 센터(api.codiya.kr) 호출
class Api {
  static const _timeout = Duration(seconds: 15);

  static Future<Map<String, dynamic>> post(String path, Map<String, dynamic> body) async {
    final r = await http
        .post(Uri.parse('$apiBase$path'),
            headers: {'Content-Type': 'application/json'}, body: jsonEncode(body))
        .timeout(_timeout);
    return _decode(r);
  }

  static Future<Map<String, dynamic>> get(String path, Map<String, String> query) async {
    final r = await http.get(Uri.parse('$apiBase$path').replace(queryParameters: query)).timeout(_timeout);
    return _decode(r);
  }

  static Map<String, dynamic> _decode(http.Response r) {
    final data = jsonDecode(utf8.decode(r.bodyBytes));
    if (data is Map<String, dynamic>) return data;
    throw const FormatException('응답 형식 오류');
  }
}
