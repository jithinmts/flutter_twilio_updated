import 'dart:async';
import 'dart:developer';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'model/call.dart';
import 'model/contact_data.dart';
import 'model/event.dart';
import 'model/status.dart';

/// Keeps the native side's notion of foreground/background in sync. Android uses it to
/// decide between routing an accepted call into the Flutter UI and launching its own
/// full-screen call activity.
class _FlutterTwilioLifecycleObserver extends WidgetsBindingObserver {
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    FlutterTwilio.setForeground(state == AppLifecycleState.resumed);
  }
}

class FlutterTwilio {
  static const MethodChannel _channel = MethodChannel('flutter_twilio');
  static const MethodChannel _eventChannel =
      MethodChannel('flutter_twilio_response');

  static final StreamController<FlutterTwilioEvent> _streamController = StreamController<FlutterTwilioEvent>.broadcast();
  static FlutterTwilioEvent? _event;

  static final _FlutterTwilioLifecycleObserver _lifecycleObserver =
      _FlutterTwilioLifecycleObserver();
  static bool _lifecycleObserverRegistered = false;

  static FlutterTwilioEvent? get event => _event;

  /// Registers the app-lifecycle observer that drives [setForeground]. Without this the
  /// native foreground flag stays at its default of `false` forever, so Android always
  /// launches its own call screen over the Flutter UI.
  static void _registerLifecycleObserver() {
    if (kIsWeb) return;
    if (_lifecycleObserverRegistered) return;
    _lifecycleObserverRegistered = true;

    final binding = WidgetsFlutterBinding.ensureInitialized();
    binding.addObserver(_lifecycleObserver);

    // Seed the native side with the current state rather than waiting for the first
    // transition, which may not arrive until well after the first call.
    final state = binding.lifecycleState;
    setForeground(state == null || state == AppLifecycleState.resumed);
  }

  static void init() {
    _registerLifecycleObserver();

    _eventChannel.setMethodCallHandler((event) async {
      log("Call event: ${event.method} . Arguments: ${event.arguments}");

      try {
        if (event.method == "registrationFailed") {
          final twilioEvent = FlutterTwilioEvent(
            FlutterTwilioStatus.registerError,
            null,
          );
          _event = twilioEvent;
          _streamController.add(twilioEvent);
          return;
        }

        if (event.method == "registrationSuccess") {
          final twilioEvent = FlutterTwilioEvent(
            FlutterTwilioStatus.registerSuccess,
            null,
          );
          _event = twilioEvent;
          _streamController.add(twilioEvent);
          return;
        }

        final eventType = getEventType(event.method);

        FlutterTwilioCall? call;

        // Android historically sent "" here rather than a map; guard on the type instead
        // of on null so a bad payload is reported rather than silently swallowed.
        if (event.arguments is Map) {
          try {
            call = FlutterTwilioCall.fromMap(
              Map<String, dynamic>.from(event.arguments as Map),
            );
          } catch (error, stack) {
            log("Twilio: could not parse call payload for ${event.method}",
                error: error, stackTrace: stack);
          }
        }

        final twilioEvent = FlutterTwilioEvent(eventType, call);

        _event = twilioEvent;

        _streamController.add(twilioEvent);
      } catch (e, stack) {
        log("Twilio stream error",
            error: e,
            stackTrace: stack);
      }
    });
  }

  static FlutterTwilioStatus getEventType(String event) {
    log("Twilio event: $event");
    if (event == "callIncoming") return FlutterTwilioStatus.incoming;
    if (event == "callConnecting") return FlutterTwilioStatus.connecting;
    if (event == "callDisconnected") return FlutterTwilioStatus.disconnected;
    if (event == "missedCall") return FlutterTwilioStatus.missedCall;
    if (event == "callRinging") return FlutterTwilioStatus.ringing;
    if (event == "callConnected") return FlutterTwilioStatus.connected;
    if (event == "callReconnecting") return FlutterTwilioStatus.reconnecting;
    if (event == "callReconnected") return FlutterTwilioStatus.reconnected;
    return FlutterTwilioStatus.unknown;
  }

  static Stream<FlutterTwilioEvent> get onCallEvent {
    return _streamController.stream.asBroadcastStream();
  }

  static Stream<FlutterTwilioEvent> get onCallConnecting {
    return _streamController.stream
        .asBroadcastStream()
        .where(
            (event) => event.status == FlutterTwilioStatus.connecting);
  }

  /// Fires when an invite arrives and starts ringing, before it is answered. Only
  /// delivered while the Dart isolate is alive; a call that wakes the app from a
  /// terminated state is handled natively instead.
  static Stream<FlutterTwilioEvent> get onCallIncoming {
    return _streamController.stream
        .asBroadcastStream()
        .where((event) => event.status == FlutterTwilioStatus.incoming);
  }

  static Future<FlutterTwilioCall> makeCall({
    required String to,
    Map<String, dynamic> data = const <String, dynamic>{},
  }) async {
    final args = <String, Object>{
      "to": to,
      "data": data,
    };

    final result = await _channel.invokeMethod('makeCall', args);
    return FlutterTwilioCall.fromMap(Map<String, dynamic>.from(result));
  }

  static Future<void> hangUp() async {
    await _channel.invokeMethod('hangUp');
  }

  static Future<void> sendDigits(String digits) async {
    final args = <String, Object>{
      "digits": digits,
    };
    await _channel.invokeMethod('sendDigits', args);
  }

  static Future<void> register({
    required String identity,
    required String accessToken,
    required String fcmToken,
  }) async {
    final args = <String, Object>{
      "identity": identity,
      "accessToken": accessToken,
      "fcmToken": fcmToken,
    };

    try {
      await _channel.invokeMethod('register', args);
    } catch (e, stack) {
      // Rethrow: swallowing this left callers believing registration had succeeded while
      // the device silently received no incoming calls at all.
      log("Twilio register failed", error: e, stackTrace: stack);
      rethrow;
    }
  }

  static Future<void> unregister() async {
    await _channel.invokeMethod('unregister');
  }

  static Future<bool> toggleMute() async {
    return await _channel.invokeMethod('toggleMute');
  }

  static Future<bool> isMuted() async {
    return await _channel.invokeMethod('isMuted');
  }

  static Future<bool> toggleSpeaker() async {
    return await _channel.invokeMethod('toggleSpeaker');
  }

  static Future<bool> isSpeaker() async {
    return await _channel.invokeMethod('isSpeaker');
  }

  static Future<FlutterTwilioCall?> getActiveCall() async {
    try {
      final data = await _channel.invokeMethod('activeCall');
      if (data == null || data == "") return null;
      return FlutterTwilioCall.fromMap(Map<String, dynamic>.from(data));
    } catch (error, stack) {
      log("Error parsing call", error: error, stackTrace: stack);
      return null;
    }
  }

  static Future<void> setContactData(
    List<FlutterTwilioContactData> data, {
    String defaultDisplayName = "Unknown number",
  }) async {
    final args = <String, dynamic>{};
    for (var element in data) {
      args[element.phoneNumber] = {
        "displayName": element.displayName.trim(),
        "photoURL": element.photoURL.trim(),
      };
    }
    await _channel.invokeMethod(
      'setContactData',
      {
        "contacts": args,
        "defaultDisplayName": defaultDisplayName,
      },
    );
  }

  static Future<void> setAndroidCallStyle({
    String? backgroundColor,
    String? textColor,
    String? buttonColor,
    String? buttonIconColor,
    String? buttonFocusColor,
    String? buttonFocusIconColor,
  }) async {
    if (kIsWeb) return;
    if (!Platform.isAndroid) return;

    await _channel.invokeMethod(
      'setCallStyle',
      {
        "backgroundColor": backgroundColor,
        "textColor": textColor,
        "buttonColor": buttonColor,
        "buttonIconColor": buttonIconColor,
        "buttonFocusColor": buttonFocusColor,
        "buttonFocusIconColor": buttonFocusIconColor,
      },
    );
  }

  static Future<void> resetAndroidCallStyle() async {
    if (kIsWeb) return;
    if (!Platform.isAndroid) return;
    await _channel.invokeMethod('resetCallStyle', {});
  }

  static Future<void> setForeground(bool foreground) async {
    if (kIsWeb) return;
    if (!Platform.isAndroid) return;
    await _channel.invokeMethod('setForeground', {"foreground": foreground});
  }
}
