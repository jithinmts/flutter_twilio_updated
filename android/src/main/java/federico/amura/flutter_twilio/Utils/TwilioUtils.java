package federico.amura.flutter_twilio.Utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import androidx.annotation.NonNull;

import android.media.AudioDeviceInfo;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;
import com.twilio.voice.Voice;

import android.os.Build;

import java.util.List;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import federico.amura.flutter_twilio.Utils.SoundUtils;
import federico.amura.flutter_twilio.IncomingCallNotificationService;
public class TwilioUtils {
    private static final String TAG = "TwilioUtils";

    @SuppressLint("StaticFieldLeak")
    private static TwilioUtils instance;

    public static TwilioUtils getInstance(Context context) {
        if (instance == null) {
            instance = new TwilioUtils();
            instance.context = context.getApplicationContext();
        }
        return instance;
    }

    private static Call activeCall;
    private static CallInvite callInvite;
    private String fromDisplayName;
    private String toDisplayName;
    private Context context;
    private String status;

    public void register(String identity, String accessToken, String fcmToken, TwilioRegistrationListener listener) {
        PreferencesUtils.getInstance(this.context).storeAccess(identity, accessToken, fcmToken);
        Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, new RegistrationListener() {
            @Override
            public void onRegistered(@NonNull String accessToken, @NonNull String fcmToken) {
                Log.d(TAG, "Successfully registered");
                if (listener != null) {
                    listener.onRegistered();
                }
            }

            @Override
            public void onError(@NonNull RegistrationException error, @NonNull String accessToken, @NonNull String fcmToken
            ) {
                String message = String.format(
                        Locale.US,
                        "Registration Error: %d, %s",
                        error.getErrorCode(),
                        error.getMessage());

                Log.d(TAG, "Error registering. " + message);

                if (listener != null) {
                    listener.onError();
                }

            }
        });
    }

    public void unregister() {
        String accessToken = PreferencesUtils.getInstance(this.context).getAccessToken();
        if (accessToken == null) return;

        String fcmToken = PreferencesUtils.getInstance(this.context).getFcmToken();
        if (fcmToken == null) return;

        PreferencesUtils.getInstance(this.context).clearAccess();
        Voice.unregister(accessToken, Voice.RegistrationChannel.FCM, fcmToken, new UnregistrationListener() {
            @Override
            public void onUnregistered(String s, String s1) {
                Log.d(TAG, "Successfully unregistered");
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format(
                        Locale.US,
                        "Registration Error: %d, %s",
                        error.getErrorCode(),
                        error.getMessage());

                Log.d(TAG, "Error unregistering. " + message);
            }
        });
    }

    public void makeCall(String to, Map<String, Object> data, Call.Listener listener) {
        if (activeCall != null) {
            throw new RuntimeException("There is a call in progress");
        }

        String accessToken = PreferencesUtils.getInstance(this.context).getAccessToken();
        if (accessToken == null) {
            throw new RuntimeException("No access token");
        }

         HashMap<String, String> params = new HashMap<>();
        params.put("To", to);
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                params.put(entry.getKey(), entry.getValue().toString());
            }
        }

        ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                .params(params)
                .build();


        String fromDisplayName = null;
        String toDisplayName = null;
        if (data != null && data.containsKey("fromDisplayName")) {
            if (data.containsKey("fromDisplayName") && data.get("fromDisplayName") != null) {
                fromDisplayName = Objects.requireNonNull(data.get("fromDisplayName")).toString();
            }

            if (data.containsKey("toDisplayName") && data.get("toDisplayName") != null) {
                toDisplayName = Objects.requireNonNull(data.get("toDisplayName")).toString();
            }
        }

        this.status = "callConnecting";
        callInvite = null;
        this.fromDisplayName = fromDisplayName;
        this.toDisplayName = toDisplayName;
        activeCall = Voice.connect(this.context, connectOptions, getCallListener(listener));
        }

    public void sendDigits(String digit) {
        if (activeCall != null) {
            Log.i(TAG, "sending digit: " + digit);
            activeCall.sendDigits(digit);
            Log.i(TAG, "digit sent: ");
        } else {
            Log.i(TAG, "Error sending digits, no active call");
        }
    }

    public void acceptInvite(CallInvite invite, Call.Listener listener) {
        SoundUtils.getInstance(this.context).stopRinging();

        if (activeCall != null) {
            throw new RuntimeException("There is a call in progress");
        }
        if (invite == null) {
            throw new RuntimeException("No call invite");
        }

        this.status = "callConnecting";
        this.fromDisplayName = null;
        this.toDisplayName = null;

        callInvite = invite;
        activeCall = invite.accept(this.context, getCallListener(listener));
    }

    public void rejectInvite(CallInvite invite) {
        if (invite == null) {
            throw new RuntimeException("No call invite");
        }
        Log.e(TAG, "REJECTING INVITE SID = " + invite.getCallSid());
        invite.reject(this.context);
        status = "callDisconnected";
        activeCall = null;
        callInvite = null;
        this.fromDisplayName = null;
        this.toDisplayName = null;
        SoundUtils.getInstance(this.context).playDisconnect();
    }

    public synchronized void disconnect() {
        Log.i(TAG, "INSIDE DISCONNECT");

        if (activeCall == null) return;
        Call call = activeCall;
        call.disconnect();
        SoundUtils.getInstance(context).stopRinging();
    }

    public boolean toggleMute() {
        if (activeCall == null) {
            throw new RuntimeException("No active call");
        }

        boolean mute = !activeCall.isMuted();
        activeCall.mute(mute);
        return mute;
    }

    public boolean isMuted() {
        if (activeCall == null) {
            throw new RuntimeException("No active call");
        }

        return activeCall.isMuted();
    }

    public boolean toggleSpeaker() {
        if (activeCall == null) {
            throw new RuntimeException("No active call");
        }
        AudioManager audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );
        boolean isSpeaker = !audioManager.isSpeakerphoneOn();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isSpeaker)
                setCommunicationDevice(this.context, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
            else
                setCommunicationDevice(this.context, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        } else
            audioManager.setSpeakerphoneOn(isSpeaker);
        return isSpeaker;
    }

    public void setCommunicationDevice(Context context, Integer targetDeviceType) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );
        List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == targetDeviceType) {
                boolean result = audioManager.setCommunicationDevice(device);
                Log.d("result: ", "" + result);
            }
        }
    }

    public void setSpeaker(boolean speaker) {
        if (activeCall == null) {
            throw new RuntimeException("No active call");
        }

        AudioManager audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );
        audioManager.setSpeakerphoneOn(speaker);
    }

    public boolean isSpeaker() {
        AudioManager audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );
        return audioManager.isSpeakerphoneOn();
    }

    public Call getActiveCall() {
        return activeCall;
    }

    public HashMap<String, Object> getCallDetails() {
        HashMap<String, Object> map = new HashMap<>();

        if (activeCall == null) {
            map.put("id", "");
            map.put("mute", false);
            map.put("speaker", false);
        } else {
            map.put("id", activeCall.getSid());
            map.put("mute", isMuted());
            map.put("speaker", isSpeaker());
        }

        if (callInvite != null) {
            map.put("customParameters", callInvite.getCustomParameters());
        }

        map.put("fromDisplayName", this.getFromDisplayName());
        map.put("toDisplayName", this.getToDisplayName());
        map.put("outgoing", callInvite == null);
        map.put("status", this.status);
        return map;
    }

    private String getFromDisplayName() {
        String result = null;

        if (callInvite != null) {
            for (Map.Entry<String, String> entry : callInvite.getCustomParameters().entrySet()) {
                if (entry.getKey().equals("fromDisplayName")) {
                    result = entry.getValue();
                }
            }

            if (result == null || result.trim().isEmpty()) {
                final String contactName = PreferencesUtils.getInstance(context).findContactName(callInvite.getFrom());
                if (contactName != null && !contactName.trim().isEmpty()) {
                    result = contactName;
                }
            }
        } else {
            result = this.fromDisplayName;
        }

        if (result == null || result.trim().isEmpty()) {
            result = "Unknown name";
        }

        return result;
    }

    private String getToDisplayName() {
        String result = null;

        if (callInvite != null) {
            for (Map.Entry<String, String> entry : callInvite.getCustomParameters().entrySet()) {
                if (entry.getKey().equals("toDisplayName")) {
                    result = entry.getValue();
                }
            }

            if (result == null || result.trim().isEmpty()) {
                final String contactName = PreferencesUtils.getInstance(context).findContactName(callInvite.getTo());
                if (contactName != null && !contactName.trim().isEmpty()) {
                    result = contactName;
                }
            }
        } else {
            result = this.toDisplayName;
        }

        if (result == null || result.trim().isEmpty()) {
            result = "Unknown name";
        }

        return result;
    }


    public String getCallStatus() {
        if (activeCall == null) return null;
        return this.status;
    }

    private Call.Listener getCallListener(Call.Listener listener) {
        return new Call.Listener() {
            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException e) {
                e.printStackTrace();
                SoundUtils.getInstance(context).stopRinging();
                Log.i(TAG, "onConnectFailure. Error: " + e.getMessage());

                status = "callDisconnected";
                if (listener != null) {
                    listener.onConnectFailure(call, e);
                }

                activeCall = null;
            }

            @Override
            public void onRinging(@NonNull Call call) {
                Log.i(TAG, "onRinging");
                status = "callRinging";
                activeCall = call;

                if (listener != null) {
                    listener.onRinging(call);
                }
            }

            @Override
            public void onConnected(@NonNull Call call) {
                Log.i(TAG, "onConnected");
                Log.e(TAG, "CONNECTED SID = " + call.getSid());

                // ✅ STOP RINGING HERE
                SoundUtils.getInstance(context).stopRinging();

                activeCall = call;
                status = "callConnected";

                if (listener != null) {
                    listener.onConnected(call);
                }
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException e) {
                e.printStackTrace();

                Log.i(TAG, "onReconnecting. Error: " + e.getMessage());
                activeCall = call;
                status = "callReconnecting";

                if (listener != null) {
                    listener.onReconnecting(call, e);
                }
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.i(TAG, "onReconnected");
                activeCall = call;
                status = "callReconnected";

                if (listener != null) {
                    listener.onReconnected(call);
                }
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException e) {
                Log.i(TAG, "onDisconnected");

                // ⭐ NOW clear everything safely
                activeCall = null;
                callInvite = null;
                fromDisplayName = null;
                toDisplayName = null;
                status = "callDisconnected";

                SoundUtils.getInstance(context).stopRinging();

                // ⭐ Stop service AFTER signaling finished
                context.stopService(
                        new Intent(context, IncomingCallNotificationService.class)
                );
            }

            @Override
            public void onCallQualityWarningsChanged(
                    @NonNull Call call,
                    @NonNull Set<Call.CallQualityWarning> currentWarnings,
                    @NonNull Set<Call.CallQualityWarning> previousWarnings
            ) {
                Log.i(TAG, "onCallQualityWarningsChanged");
                activeCall = call;

                if (listener != null) {
                    listener.onCallQualityWarningsChanged(call, currentWarnings, previousWarnings);
                }
            }
        };
    }

    public void setCallInvite(CallInvite invite) {
        callInvite = invite;
    }

    public CallInvite getCallInvite() {
        return callInvite;
    }

    public void forceTerminateCall() {
        try {
            Log.i(TAG, "*******forceTerminateCall*******");
            Log.i(TAG, "*******forceTerminateCall*******");

            if (activeCall != null) {

                Call call = activeCall;

                activeCall = null;
                callInvite = null;
                status = "callDisconnected";

                call.disconnect();
            }

            Voice.unregister(
                    PreferencesUtils.getInstance(context).getAccessToken(),
                    Voice.RegistrationChannel.FCM,
                    PreferencesUtils.getInstance(context).getFcmToken(),
                    null
            );

            SoundUtils.getInstance(context).stopRinging();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
