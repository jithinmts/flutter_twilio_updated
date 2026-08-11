import 'package:equatable/equatable.dart';
import 'package:flutter_twilio/flutter_twilio.dart';
import 'package:flutter_twilio/model/status.dart';

class FlutterTwilioCall extends Equatable {
  final String id;
  final String fromDisplayName;
  final String toDisplayName;
  final bool outgoing;
  final FlutterTwilioStatus status;
  final bool mute;
  final bool speaker;
  final String to;

  /// Custom SIP parameters supplied by the server for incoming calls. Both platforms
  /// already send these; they were previously dropped on the floor here.
  final Map<String, String> customParameters;

  FlutterTwilioCall({
    required this.id,
    required this.fromDisplayName,
    required this.toDisplayName,
    required this.mute,
    required this.speaker,
    required this.status,
    required this.outgoing,required this.to,
    this.customParameters = const <String, String>{},
  });

  factory FlutterTwilioCall.fromMap(Map<String, dynamic> data) {
    final rawParams = data["customParameters"];

    return FlutterTwilioCall(
      id: data["id"] ?? "",
      fromDisplayName: data["fromDisplayName"] ?? "",
      toDisplayName: data["toDisplayName"] ?? "",
      outgoing: data["outgoing"] ?? false,
      mute: data["mute"] ?? false,
      speaker: data["speaker"] ?? false,
      status: FlutterTwilio.getEventType(data["status"] ?? ""),
      to: data["to"] ?? "",
      customParameters: rawParams is Map
          ? rawParams.map(
              (key, value) => MapEntry(key.toString(), value?.toString() ?? ""),
            )
          : const <String, String>{},
    );
  }

  @override
  List<Object?> get props => [
        id,
        fromDisplayName,
        toDisplayName,
        outgoing,
        mute,
        speaker,
        status,
        to,
        customParameters,
      ];
}
