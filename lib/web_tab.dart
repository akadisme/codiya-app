import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'config.dart';

/// 홈페이지(그누보드)를 보여주는 화면
class WebTab extends StatefulWidget {
  const WebTab({super.key, required this.initialUrl, required this.onAppLink});
  final String initialUrl;

  /// codiya://inbox 같은 앱 전용 링크
  final void Function(Uri uri) onAppLink;

  @override
  State<WebTab> createState() => WebTabState();
}

class WebTabState extends State<WebTab> {
  late final WebViewController _c;
  int _progress = 0;

  // 아래로 당겨서 새로고침 (앱에서 손가락과 페이지 스크롤 위치를 직접 확인)
  static const _pullTrigger = 100.0;
  double _scrollY = 0;
  double? _startY;
  int _pointers = 0;
  double _pull = 0;
  bool _refreshing = false;

  void _down(PointerDownEvent e) {
    _pointers++;
    _startY = (_pointers == 1 && _scrollY <= 0 && !_refreshing) ? e.position.dy : null;
  }

  void _move(PointerMoveEvent e) {
    final s = _startY;
    if (s == null) return;
    if (_scrollY > 0 || _pointers > 1) {
      _startY = null;
      if (_pull != 0) setState(() => _pull = 0);
      return;
    }
    final d = (e.position.dy - s).clamp(0.0, 170.0);
    if (d != _pull) setState(() => _pull = d);
  }

  void _up(PointerEvent e) {
    _pointers = (_pointers - 1).clamp(0, 10);
    if (_startY != null && _pull >= _pullTrigger) {
      setState(() => _refreshing = true);
      _c.reload();
      Future.delayed(const Duration(seconds: 8), () {
        if (mounted && _refreshing) setState(() => _refreshing = false);
      });
    }
    _startY = null;
    if (_pull != 0) setState(() => _pull = 0);
  }

  @override
  void initState() {
    super.initState();
    _c = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..setNavigationDelegate(NavigationDelegate(
        onProgress: (p) {
          if (mounted) setState(() => _progress = p);
        },
        onPageStarted: (_) => _scrollY = 0,
        onPageFinished: (_) {
          if (mounted && _refreshing) setState(() => _refreshing = false);
        },
        onNavigationRequest: (req) => _decide(req.url),
      ))
      ..setOnScrollPositionChange((c) => _scrollY = c.y);
    _setup();
  }

  Future<void> _setup() async {
    final ua = await _c.getUserAgent();
    await _c.setUserAgent('${ua ?? ''}$appUserAgentSuffix');
    await _c.loadRequest(Uri.parse(widget.initialUrl));
  }

  Future<void> load(String url) => _c.loadRequest(Uri.parse(url));

  Future<void> reload() => _c.reload();

  /// 뒤로 갈 페이지가 있으면 뒤로 가고 true
  Future<bool> goBack() async {
    if (await _c.canGoBack()) {
      await _c.goBack();
      return true;
    }
    return false;
  }

  static const _inside = {'http', 'https', 'about', 'data', 'blob', 'javascript'};

  NavigationDecision _decide(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null) return NavigationDecision.prevent;
    if (_inside.contains(uri.scheme)) return NavigationDecision.navigate;
    if (uri.scheme == 'codiya') {
      widget.onAppLink(uri);
      return NavigationDecision.prevent;
    }
    // 전화, 문자, 카카오톡, 카드사·결제 앱 등은 바깥 앱으로
    _openExternal(url);
    return NavigationDecision.prevent;
  }

  Future<void> _openExternal(String url) async {
    if (url.startsWith('intent:')) {
      // 예) intent://pay?x=1#Intent;scheme=ispmobile;package=kvp.jjy.MispAndroid320;end
      final hash = url.indexOf('#Intent;');
      final params = <String, String>{};
      if (hash >= 0) {
        for (final part in url.substring(hash + 8).split(';')) {
          final i = part.indexOf('=');
          if (i > 0) params[part.substring(0, i)] = part.substring(i + 1);
        }
      }
      final scheme = params['scheme'];
      if (scheme != null) {
        final rest = url.startsWith('intent://') ? url.substring(9, hash >= 0 ? hash : url.length) : '';
        if (await _launch('$scheme://$rest')) return;
      }
      final fallback = params['S.browser_fallback_url'];
      if (fallback != null && await _launch(Uri.decodeComponent(fallback))) return;
      final pkg = params['package'];
      if (pkg != null) await _launch('market://details?id=$pkg');
      return;
    }
    if (!await _launch(url) && url.startsWith('market:') == false && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('연결할 앱이 설치되어 있지 않아요')),
      );
    }
  }

  Future<bool> _launch(String url) async {
    try {
      return await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final ready = _pull >= _pullTrigger;
    final showing = _pull > 8 || _refreshing;
    final top = _refreshing ? 16.0 : (_pull * 0.6 - 44);
    return Stack(
      children: [
        // Listener는 손가락 동작을 가로채지 않고 보기만 해요 (홈페이지 터치는 그대로)
        Listener(
          onPointerDown: _down,
          onPointerMove: _move,
          onPointerUp: _up,
          onPointerCancel: _up,
          child: WebViewWidget(controller: _c),
        ),
        if (_progress < 100)
          LinearProgressIndicator(
            value: _progress / 100,
            minHeight: 2,
            backgroundColor: Colors.transparent,
          ),
        if (showing)
          Positioned(
            top: top,
            left: 0,
            right: 0,
            child: IgnorePointer(
              child: Center(
                child: Material(
                  elevation: 3,
                  shape: const CircleBorder(),
                  color: Colors.white,
                  child: SizedBox(
                    width: 40,
                    height: 40,
                    child: _refreshing
                        ? const Padding(
                            padding: EdgeInsets.all(10),
                            child: CircularProgressIndicator(strokeWidth: 2.4),
                          )
                        : Transform.rotate(
                            angle: _pull / 30,
                            child: Icon(
                              Icons.refresh,
                              color: ready ? const Color(0xFF1F7A4D) : const Color(0xFF0AA394),
                            ),
                          ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
