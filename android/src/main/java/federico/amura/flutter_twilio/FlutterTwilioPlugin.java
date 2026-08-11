package federico.amura.flutter_twilio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import federico.amura.flutter_twilio.Utils.AppForegroundStateUtils;
import federico.amura.flutter_twilio.Utils.PreferencesUtils;
import federico.amura.flutter_twilio.Utils.TwilioConstants;
import federico.amura.flutter_twilio.Utils.TwilioRegistrationListener;
import federico.amura.flutter_twilio.Utils.TwilioUtils;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;

public class FlutterTwilioPlugin implements
        FlutterPlugin,
        MethodChannel.MethodCallHandler,
        ActivityAware,
        PluginRegistry.NewIntentListener {

    private static final String TAG = "FlutterTwilioPlugin";

    private Context context;
    private MethodChannel responseChannel;
    private CustomBroadcastReceiver broadcastReceiver;
    private boolean broadcastReceiverRegistered = false;

    public FlutterTwilioPlugin() {
    }

    private void setupMethodChannel(BinaryMessenger messenger, Context context) {
        this.context = context;
        MethodChannel channel = new MethodChannel(messenger, "flutter_twilio");
        channel.setMethodCallHandler(this);
        this.responseChannel = new MethodChannel(messenger, "flutter_twilio_response");
    }

    private void registerReceiver() {
        if (!this.broadcastReceiverRegistered) {
            this.broadcastReceiverRegistered = true;

            Log.e(TAG, "Registered broadcast");
            this.broadcastReceiver = new CustomBroadcastReceiver(this);
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(TwilioConstants.ACTION_ACCEPT);
            intentFilter.addAction(TwilioConstants.ACTION_MISSED_CALL);
            // Without this the service's cancelled-call broadcast was never delivered, so
            // an in-app incoming-call UI had no way to know the caller hung up.
            intentFilter.addAction(TwilioConstants.ACTION_CANCEL_CALL);
            intentFilter.addAction(TwilioConstants.ACTION_INCOMING_CALL);
            LocalBroadcastManager.getInstance(this.context).registerReceiver(this.broadcastReceiver, intentFilter);
        }
    }

    private void unregisterReceiver() {
        if (this.broadcastReceiverRegistered) {
            this.broadcastReceiverRegistered = false;

            Log.e(TAG, "Unregistered broadcast");
            LocalBroadcastManager.getInstance(this.context).unregisterReceiver(this.broadcastReceiver);
        }
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        Log.e(TAG, "onAttachedToActivity");
        activityPluginBinding.addOnNewIntentListener(this);
        this.registerReceiver();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        Log.e(TAG, "onDetachedFromActivityForConfigChanges");
        this.unregisterReceiver();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        Log.e(TAG, "onReattachedToActivityForConfigChanges");
        activityPluginBinding.addOnNewIntentListener(this);
        this.registerReceiver();
    }

    @Override
    public void onDetachedFromActivity() {
        Log.e(TAG, "onDetachedFromActivity");
        this.unregisterReceiver();
    }

    @Override
    public boolean onNewIntent(Intent intent) {
        Log.e(TAG, "onNewIntent");
        this.handleIncomingCallIntent(intent);
        return false;
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.e(TAG, "onReceive. Action: " + action);

            if (TwilioConstants.ACTION_ACCEPT.equals(action)) {
                CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                answer(callInvite);
            }
            if (TwilioConstants.ACTION_INCOMING_CALL.equals(action)) {
                if (responseChannel != null) {
                    CallInvite callInvite =
                            intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    if (callInvite != null) {
                        responseChannel.invokeMethod("callIncoming", buildInviteDetails(
                                callInvite.getFrom(),
                                callInvite.getTo(),
                                callInvite.getCustomParameters(),
                                "callIncoming"
                        ));
                    }
                }
            }
            if (TwilioConstants.ACTION_MISSED_CALL.equals(action)
                    || TwilioConstants.ACTION_CANCEL_CALL.equals(action)) {
                if (responseChannel != null) {
                    CancelledCallInvite cancelledCallInvite =
                            intent.getParcelableExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE);
                    responseChannel.invokeMethod("missedCall", cancelledCallInvite == null
                            ? buildInviteDetails(null, null, null, "missedCall")
                            : buildInviteDetails(
                                    cancelledCallInvite.getFrom(),
                                    cancelledCallInvite.getTo(),
                                    cancelledCallInvite.getCustomParameters(),
                                    "missedCall"
                            ));
                }
            }
        }
    }

    /**
     * Builds the same payload shape as TwilioUtils.getCallDetails so the Dart model can
     * parse it. The missed-call event previously sent an empty string, which failed to
     * parse and left the Dart side with no idea who had called.
     */
    private Map<String, Object> buildInviteDetails(
            String from,
            String to,
            Map<String, String> params,
            String status
    ) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", "");
        map.put("mute", false);
        map.put("speaker", false);
        map.put("outgoing", false);
        map.put("status", status);
        map.put("to", to == null ? "" : to.replace("client:", ""));

        String fromDisplayName = null;
        String toDisplayName = null;

        if (params != null) {
            fromDisplayName = params.get("fromDisplayName");
            toDisplayName = params.get("toDisplayName");
            map.put("customParameters", params);
        }

        if (fromDisplayName == null || fromDisplayName.trim().isEmpty()) {
            fromDisplayName = PreferencesUtils.getInstance(this.context).findContactName(from);
        }

        if (fromDisplayName == null || fromDisplayName.trim().isEmpty()) {
            fromDisplayName = "Unknown name";
        }
        if (toDisplayName == null || toDisplayName.trim().isEmpty()) {
            toDisplayName = "Unknown name";
        }

        map.put("fromDisplayName", fromDisplayName);
        map.put("toDisplayName", toDisplayName);
        return map;
    }
    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {
        Log.e(TAG, "onMethodCall. Method: " + call.method);
        TwilioUtils twilioUtils = TwilioUtils.getInstance(this.context);

        switch (call.method) {
            case "register": {
                String identity = call.argument("identity");
                String accessToken = call.argument("accessToken");
                String fcmToken = call.argument("fcmToken");
                // Never log the access token or FCM token: the access token is a signed JWT
                // that can place calls on this account.
                Log.e(TAG, "register. identity: " + identity);

                // The listener may fire before register() returns, so track whether the
                // reply has already gone out to avoid "Reply already submitted".
                final boolean[] replied = {false};
                try {
                    twilioUtils.register(identity, accessToken, fcmToken, new TwilioRegistrationListener() {
                        @Override
                        public void onRegistered() {
                            Log.e(TAG, "in registered success");

                            if (responseChannel != null) {
                                responseChannel.invokeMethod("registrationSuccess", "");
                            }

                            if (!replied[0]) {
                                replied[0] = true;
                                result.success("");
                            }
                        }

                        @Override
                        public void onError() {
                            Log.e(TAG, "in registered error");

                            if (responseChannel != null) {
                                responseChannel.invokeMethod("registrationFailed", "");
                            }

                            if (!replied[0]) {
                                replied[0] = true;
                                result.error("REGISTER_ERROR", "Twilio registration failed", null);
                            }
                        }
                    });
                } catch (Exception exception) {
                    exception.printStackTrace();
                    if (responseChannel != null) {
                        responseChannel.invokeMethod("registrationFailed", "");
                    }
                    if (!replied[0]) {
                        replied[0] = true;
                        result.error("REGISTER_ERROR", "Twilio registration failed", exception.getMessage());
                    }
                }
            }
            break;

            case "unregister": {
                try {
                    twilioUtils.unregister();
                } catch (Exception exception) {
                    exception.printStackTrace();
                }

                result.success("");
            }
            break;

            case "makeCall": {
                try {
                    String to = call.argument("to");
                    Map<String, Object> data = call.argument("data");
                    twilioUtils.makeCall(to, data, getCallListener());
                    if (responseChannel != null) {
                        responseChannel.invokeMethod("callConnecting", twilioUtils.getCallDetails());
                    }
                    result.success(twilioUtils.getCallDetails());
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "toggleMute": {
                try {
                    boolean isMuted = twilioUtils.toggleMute();
                    if (responseChannel != null) {
                        responseChannel.invokeMethod(twilioUtils.getCallStatus(), twilioUtils.getCallDetails());
                    }
                    result.success(isMuted);
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "isMuted": {
                try {
                    boolean isMuted = twilioUtils.isMuted();
                    result.success(isMuted);
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "toggleSpeaker": {
                try {
                     boolean isSpeaker = twilioUtils.toggleSpeaker();
                    if (responseChannel != null) {
                        responseChannel.invokeMethod(twilioUtils.getCallStatus(), twilioUtils.getCallDetails());
                    }

                    result.success(isSpeaker);
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "sendDigits": {
                try {
                    String digits = call.argument("digits");
                    twilioUtils.sendDigits(digits);
                    result.success("");
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "isSpeaker": {
                try {
                    boolean isSpeaker = twilioUtils.isSpeaker();
                    result.success(isSpeaker);
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "hangUp": {
                try {
                    twilioUtils.disconnect();
                    result.success("");
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "activeCall": {
                if (twilioUtils.getActiveCall() == null) {
                    result.success("");
                } else {
                    result.success(twilioUtils.getCallDetails());
                }
            }
            break;

            case "setContactData": {
                Map<String, Object> data = call.argument("contacts");
                String defaultDisplayName = call.argument("defaultDisplayName");
                PreferencesUtils.getInstance(this.context).setContacts(data, defaultDisplayName);
                result.success("");
            }
            break;


            case "setCallStyle": {
                try {
                    final PreferencesUtils preferencesUtils = PreferencesUtils.getInstance(this.context);

                    // Background color
                    if (call.argument("backgroundColor") != null) {
                        String color = call.argument("backgroundColor");
                        preferencesUtils.storeCallBackgroundColor(color);
                    } else {
                        preferencesUtils.clearCallBackgroundColor();
                    }

                    // Text Color
                    if (call.argument("textColor") != null) {
                        String color = call.argument("textColor");
                        preferencesUtils.storeCallTextColor(color);
                    } else {
                        preferencesUtils.clearCallTextColor();
                    }

                    // Button
                    if (call.argument("buttonColor") != null) {
                        String color = call.argument("buttonColor");
                        preferencesUtils.storeCallButtonColor(color);
                    } else {
                        preferencesUtils.clearCallButtonColor();
                    }

                    // Button Icon
                    if (call.argument("buttonIconColor") != null) {
                        String color = call.argument("buttonIconColor");
                        preferencesUtils.storeCallButtonIconColor(color);
                    } else {
                        preferencesUtils.clearCallButtonIconColor();
                    }


                    // Button focus
                    if (call.argument("buttonFocusColor") != null) {
                        String color = call.argument("buttonFocusColor");
                        preferencesUtils.storeCallButtonFocusColor(color);
                    } else {
                        preferencesUtils.clearCallButtonFocusColor();
                    }

                    // Button focus icon
                    if (call.argument("buttonFocusIconColor") != null) {
                        String color = call.argument("buttonFocusIconColor");
                        preferencesUtils.storeCallButtonFocusIconColor(color);
                    } else {
                        preferencesUtils.clearCallButtonFocusIconColor();
                    }

                    result.success("");
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            case "resetCallStyle": {
                final PreferencesUtils preferencesUtils = PreferencesUtils.getInstance(this.context);
                preferencesUtils.clearCallBackgroundColor();
                preferencesUtils.clearCallTextColor();
                preferencesUtils.clearCallButtonColor();
                preferencesUtils.clearCallButtonIconColor();
                preferencesUtils.clearCallButtonFocusColor();
                preferencesUtils.clearCallButtonFocusIconColor();
                result.success("");
            }
            break;

            case "setForeground": {
                try {
                    AppForegroundStateUtils.getInstance().setForeground(call.argument("foreground"));
                    result.success("");
                } catch (Exception exception) {
                    exception.printStackTrace();
                    // Propagate a real code/message so Dart can distinguish "no active
                    // call" from "call in progress" from a network failure.
                    result.error(
                            exception.getClass().getSimpleName(),
                            exception.getMessage(),
                            null
                    );
                }
            }
            break;

            default:
                // Without this an unrecognised method leaves the Dart Future pending forever.
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onAttachedToEngine(FlutterPlugin.FlutterPluginBinding binding) {
        setupMethodChannel(binding.getBinaryMessenger(), binding.getApplicationContext());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
    }

    private void answer(CallInvite callInvite) {
        try {
            if (callInvite == null) return;
            Log.e(TAG, "answer CALL: ");
            TwilioUtils t = TwilioUtils.getInstance(this.context);
            t.acceptInvite(callInvite, getCallListener());
            if (responseChannel != null) {
                responseChannel.invokeMethod("callConnecting", t.getCallDetails());
            }
            Log.e(TAG, "answer CALL: ");
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    Call.Listener getCallListener() {
        TwilioUtils t = TwilioUtils.getInstance(this.context);

        return new Call.Listener() {
            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
                Log.e(TAG, "onConnectFailure. Error: " + error.getMessage());
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callDisconnected", "");
                }
            }

            @Override
            public void onRinging(@NonNull Call call) {
                Log.e(TAG, "onRinging");
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callRinging", t.getCallDetails());
                }
            }

            @Override
            public void onConnected(@NonNull Call call) {
                Log.e(TAG, "onConnected");
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callConnected", t.getCallDetails());
                }
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException e) {
                Log.e(TAG, "onReconnecting. Error: " + e.getMessage());
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callReconnecting", t.getCallDetails());
                }
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.d(TAG, "onReconnected");
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callReconnected", t.getCallDetails());
                }
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException e) {
                if (e != null) {
                    Log.d(TAG, "onDisconnected. Error: " + e.getMessage());
                } else {
                    Log.d(TAG, "onDisconnected");
                }

                Log.d(TAG, call.getState().toString());
                if (responseChannel != null) {
                    responseChannel.invokeMethod("callDisconnected", null);
                }
            }

            @Override
            public void onCallQualityWarningsChanged(
                    @NonNull Call call,
                    @NonNull Set<Call.CallQualityWarning> currentWarnings,
                    @NonNull Set<Call.CallQualityWarning> previousWarnings
            ) {
                Log.d(TAG, "onCallQualityWarningsChanged");
            }
        };
    }

    private static class CustomBroadcastReceiver extends BroadcastReceiver {

        private final FlutterTwilioPlugin plugin;

        private CustomBroadcastReceiver(FlutterTwilioPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            plugin.handleIncomingCallIntent(intent);
        }
    }


}
