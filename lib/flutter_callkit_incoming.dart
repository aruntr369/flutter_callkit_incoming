import 'dart:async';
import 'package:flutter/services.dart';

import 'entities/entities.dart';

/// Instance to use library functions.
/// * showCallkitIncoming
/// * showCallkitIncomingDirect (Android only)
/// * startCall
/// * endCall
/// * endAllCalls
/// * callConnected
class FlutterCallkitIncoming {
  static const MethodChannel _channel =
      MethodChannel('flutter_callkit_incoming');
  static const EventChannel _eventChannel =
      EventChannel('flutter_callkit_incoming_events');

  /// Listen to Callkit events.
  ///
  /// FlutterCallkitIncoming.onEvent.listen((event) {
  ///   Event.ACTION_CALL_INCOMING
  ///   Event.ACTION_CALL_START
  ///   Event.ACTION_CALL_ACCEPT
  ///   Event.ACTION_CALL_DECLINE
  ///   Event.ACTION_CALL_ENDED
  ///   Event.ACTION_CALL_TIMEOUT
  ///   Event.ACTION_CALL_CALLBACK (Android only)
  /// }
  static Stream<CallEvent?> get onEvent =>
      _eventChannel.receiveBroadcastStream().map(_receiveCallEvent);

  /* -------------------------------------------------- */
  /* Incoming                                           */
  /* -------------------------------------------------- */

  /// Show Callkit Incoming.
  /// iOS: CallKit
  /// Android: Notification + custom UI
  static Future<void> showCallkitIncoming(CallKitParams params) async {
    await _channel.invokeMethod(
      "showCallkitIncoming",
      params.toJson(),
    );
  }

  /// Show Callkit Incoming directly via UI (Android only).
  /// This triggers the `fromUi` flow on Android.
  static Future<void> showCallkitIncomingDirect(
    CallKitParams params,
  ) async {
    await _channel.invokeMethod(
      "showCallkitIncomingDirect",
      params.toJson(),
    );
  }

  /// Show Missed Call Notification (Android only)
  static Future<void> showMissCallNotification(
    CallKitParams params,
  ) async {
    await _channel.invokeMethod(
      "showMissCallNotification",
      params.toJson(),
    );
  }

  /// Hide incoming call notification (Android only)
  static Future<void> hideCallkitIncoming(
    CallKitParams params,
  ) async {
    await _channel.invokeMethod(
      "hideCallkitIncoming",
      params.toJson(),
    );
  }

  /* -------------------------------------------------- */
  /* Call control                                       */
  /* -------------------------------------------------- */

  /// Start an outgoing call.
  static Future<void> startCall(CallKitParams params) async {
    await _channel.invokeMethod(
      "startCall",
      params.toJson(),
    );
  }

  /// End a call by ID.
  static Future<void> endCall(String id) async {
    await _channel.invokeMethod(
      "endCall",
      {'id': id},
    );
  }

  /// End all active calls.
  static Future<void> endAllCalls() async {
    await _channel.invokeMethod("endAllCalls");
  }

  /// Notify native side that call is connected.
  static Future<void> setCallConnected(String id) async {
    await _channel.invokeMethod(
      "callConnected",
      {'id': id},
    );
  }

  /* -------------------------------------------------- */
  /* Audio / state (mostly iOS)                         */
  /* -------------------------------------------------- */

  static Future<void> muteCall(
    String id, {
    bool isMuted = true,
  }) async {
    await _channel.invokeMethod(
      "muteCall",
      {'id': id, 'isMuted': isMuted},
    );
  }

  static Future<bool> isMuted(String id) async {
    return (await _channel.invokeMethod(
          "isMuted",
          {'id': id},
        )) as bool? ??
        false;
  }

  static Future<void> holdCall(
    String id, {
    bool isOnHold = true,
  }) async {
    await _channel.invokeMethod(
      "holdCall",
      {'id': id, 'isOnHold': isOnHold},
    );
  }

  /* -------------------------------------------------- */
  /* Info                                               */
  /* -------------------------------------------------- */

  /// Get active calls.
  static Future<dynamic> activeCalls() async {
    return await _channel.invokeMethod("activeCalls");
  }

  /// iOS: VoIP push token
  /// Android: empty string
  static Future<dynamic> getDevicePushTokenVoIP() async {
    return await _channel.invokeMethod("getDevicePushTokenVoIP");
  }

  /* -------------------------------------------------- */
  /* Permissions / config                               */
  /* -------------------------------------------------- */

  /// Silence Callkit events (Android + iOS)
  static Future<void> silenceEvents() async {
    await _channel.invokeMethod("silenceEvents", true);
  }

  /// Unsilence Callkit events
  static Future<void> unsilenceEvents() async {
    await _channel.invokeMethod("silenceEvents", false);
  }

  /// Android 13+: POST_NOTIFICATIONS permission
  static Future<void> requestNotificationPermission(dynamic data) async {
    await _channel.invokeMethod(
      "requestNotificationPermission",
      data,
    );
  }

  /// Android 14+: Fullscreen intent permission
  static Future<void> requestFullIntentPermission() async {
    await _channel.invokeMethod("requestFullIntentPermission");
  }

  /// Android 14+: Check fullscreen intent permission
  static Future<bool> canUseFullScreenIntent() async {
    return (await _channel.invokeMethod(
          "canUseFullScreenIntent",
        )) as bool? ??
        true;
  }

  /* -------------------------------------------------- */
  /* Event mapper                                       */
  /* -------------------------------------------------- */

  static CallEvent? _receiveCallEvent(dynamic data) {
    if (data is Map) {
      final event =
          Event.values.firstWhere((e) => e.name == data['event']);
      final body = Map<String, dynamic>.from(data['body']);
      return CallEvent(body, event);
    }
    return null;
  }
}
