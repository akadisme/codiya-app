import 'dart:async';

import 'package:flutter/material.dart';

import 'push.dart';
import 'settings_screen.dart';

/// 받은 알림 목록
class InboxScreen extends StatefulWidget {
  const InboxScreen({super.key, required this.onOpenLink});
  final void Function(String link) onOpenLink;

  @override
  State<InboxScreen> createState() => InboxScreenState();
}

class InboxScreenState extends State<InboxScreen> {
  List<Map<String, dynamic>> _items = [];
  bool _loading = true;
  String? _error;
  StreamSubscription<void>? _sub;

  @override
  void initState() {
    super.initState();
    _sub = PushService.instance.changes.stream.listen((_) => load());
    load();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    if (PushService.instance.token == null) {
      setState(() {
        _loading = false;
        _error = '알림 설정을 준비하고 있어요';
      });
      return;
    }
    try {
      final r = await PushService.instance.inbox();
      if (!mounted) return;
      final items = (r['items'] as List? ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
      setState(() {
        _items = items;
        _loading = false;
        _error = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '알림을 불러오지 못했어요. 아래로 당겨 다시 시도해 주세요.';
      });
    }
  }

  Future<void> _open(Map<String, dynamic> n) async {
    final nid = (n['id'] as num?)?.toInt() ?? 0;
    PushService.instance.markOpened(nid);
    final link = '${n['link'] ?? ''}';
    if (link.startsWith('http')) {
      widget.onOpenLink(link);
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${n['title'] ?? ''}'),
        content: SingleChildScrollView(child: Text('${n['body'] ?? ''}')),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('닫기'))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('알림'),
        actions: [
          IconButton(
            tooltip: '설정',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => SettingsScreen(onOpenLink: widget.onOpenLink)),
            ),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: load,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView.separated(
                physics: const AlwaysScrollableScrollPhysics(),
                itemCount: _items.isEmpty ? 1 : _items.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  if (_items.isEmpty) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 120, horizontal: 24),
                      child: Text(
                        _error ?? '아직 받은 알림이 없어요',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodyMedium?.copyWith(color: theme.hintColor),
                      ),
                    );
                  }
                  final n = _items[i];
                  final read = n['is_read'] == true;
                  final sent = '${n['sent_at'] ?? ''}';
                  return ListTile(
                    contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 6),
                    leading: CircleAvatar(
                      radius: 5,
                      backgroundColor: read ? Colors.transparent : theme.colorScheme.primary,
                    ),
                    minLeadingWidth: 10,
                    title: Text(
                      '${n['title'] ?? ''}',
                      style: TextStyle(fontWeight: read ? FontWeight.w400 : FontWeight.w700),
                    ),
                    subtitle: Text('${n['body'] ?? ''}', maxLines: 2, overflow: TextOverflow.ellipsis),
                    trailing: Text(
                      sent.length >= 16 ? sent.substring(5, 16) : sent,
                      style: theme.textTheme.bodySmall?.copyWith(color: theme.hintColor),
                    ),
                    onTap: () => _open(n),
                  );
                },
              ),
      ),
    );
  }
}
