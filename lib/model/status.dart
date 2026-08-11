enum FlutterTwilioStatus {
  /// An invite has arrived and is ringing, but has not been answered yet.
  incoming,
  connecting,
  disconnected,
  ringing,
  connected,
  reconnecting,
  reconnected,
  unknown,
  missedCall,
  registerError,
  registerSuccess
}