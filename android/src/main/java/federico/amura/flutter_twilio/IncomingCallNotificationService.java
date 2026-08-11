package federico.amura.flutter_twilio;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

import java.util.List;
import java.util.Map;

import federico.amura.flutter_twilio.Utils.AppForegroundStateUtils;
import federico.amura.flutter_twilio.Utils.NotificationUtils;
import federico.amura.flutter_twilio.Utils.PreferencesUtils;
import federico.amura.flutter_twilio.Utils.SoundUtils;
import federico.amura.flutter_twilio.Utils.TwilioConstants;
import federico.amura.flutter_twilio.Utils.TwilioUtils;

import androidx.lifecycle.ProcessLifecycleOwner;

public class IncomingCallNotificationService extends Service {
    private boolean isForegroundRunning = false;
    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "Service restarted with null intent");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.e(TAG, "onStartCommand " + action);
        if (action != null) {
            switch (action) {
                case TwilioConstants.ACTION_INCOMING_CALL: {
                    Log.e(TAG, "ACTION_INCOMING_CALL case");

                    CallInvite callInvite =
                            intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    if (callInvite == null) {
                        callInvite = TwilioUtils.getInstance(this).getCallInvite();
                    }
                    if (callInvite == null) {
                        Log.e(TAG, "Incoming call invite is NULL");
                        stopSelf();
                        break;
                    }

                    Log.e(TAG, "Incoming call SID = " + callInvite.getCallSid());
                    handleIncomingCall(callInvite);
                }
                break;

                case TwilioConstants.ACTION_ACCEPT: {
                    Log.e(TAG, "ACTION_ACCEPT case");

                    CallInvite callInvite =
                            intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    if (callInvite == null) {
                        callInvite = TwilioUtils.getInstance(this).getCallInvite();
                    }
                    if (callInvite == null) {
                        Log.e(TAG, "Accept failed: invite is NULL");
                        stopSelf();
                        break;
                    }

                    Log.e(TAG, "Accept call SID = " + callInvite.getCallSid());
                    accept(callInvite);
                }
                break;
                case TwilioConstants.ACTION_REJECT: {
                    Log.e(TAG, "ACTION_REJECT case");

                    CallInvite callInvite =
                            intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);

                    if (callInvite == null) {
                        callInvite = TwilioUtils.getInstance(this).getCallInvite();
                    }

                    if (callInvite == null) {
                        Log.e(TAG, "Reject failed: invite is NULL");
                        stopSelf();
                        break;
                    }

                    Log.e(TAG, "Reject call SID = " + callInvite.getCallSid());
                    reject(callInvite);
                }
                break;
                case TwilioConstants.ACTION_CANCEL_CALL: {
                    Log.e(TAG, "TwilioConstants.ACTION_CANCEL_CALL case");
                    handleCancelledCall(intent);
                }
                break;

                case TwilioConstants.ACTION_STOP_SERVICE: {
                    Log.e(TAG, "TwilioConstants.ACTION_STOP_SERVICE case");

                    stopServiceIncomingCall();
                    stopSelf();              // ⭐ REQUIRED
                }
                break;

                case TwilioConstants.ACTION_RETURN_CALL:
                    Log.e(TAG, "TwilioConstants.ACTION_RETURN_CALL case");
                    returnCall(intent);
                    break;

                case TwilioConstants.ACTION_MISSED_CALL:
                    Log.e(TAG, "TwilioConstants.ACTION_MISSED_CALL case");
                    missedCall(intent);
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void handleIncomingCall(CallInvite callInvite) {
        if (callInvite == null) {
            Log.e(TAG, "Incoming call. No call invite");
            return;
        }

        // 🔥 STORE INVITE GLOBALLY (VERY IMPORTANT)
        TwilioUtils.getInstance(getApplicationContext())
                .setCallInvite(callInvite);

        Log.e(TAG, "Incoming call. App visible: " + isAppVisible() + ". Locked: " + isLocked());

        if (TwilioUtils.getInstance(this).getActiveCall() != null) {
            Log.e(TAG, "Incoming call. There is already an active call");
            return;
        }

        // Let the Flutter layer know a call is ringing so it can show its own incoming UI.
        // Only reaches Dart when the engine is alive; otherwise the notification handles it.
        Intent informIntent = new Intent();
        informIntent.setAction(TwilioConstants.ACTION_INCOMING_CALL);
        informIntent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        LocalBroadcastManager.getInstance(this).sendBroadcast(informIntent);

        this.startServiceIncomingCall(callInvite);
    }

    private void accept(CallInvite callInvite) {
        SoundUtils.getInstance(this).stopRinging();
        stopServiceIncomingCall();

        TwilioUtils utils = TwilioUtils.getInstance(this);
        utils.setCallInvite(callInvite); // 🔥 ensure stored

        stopSelf();

        if (!isLocked() && isAppVisible()) {
            informAppAcceptCall(callInvite);
        } else {
            openBackgroundCallActivityForAcceptCall(callInvite);
        }
    }

    private void reject(CallInvite callInvite) {
        Log.e(TAG, "Reject call invite from service");

        stopServiceIncomingCall();

        if (callInvite != null) {
            try {
                Log.e(TAG, "Rejecting invite SID = " + callInvite.getCallSid());
                TwilioUtils.getInstance(this).rejectInvite(callInvite);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            Log.e(TAG, "Reject failed: invite from intent is null");
        }

        stopSelf();
    }

    private void handleCancelledCall(Intent intent) {
        Log.e(TAG, "Call canceled. App visible: " + isAppVisible() + ". Locked: " + isLocked());

        // Stop ringtone immediately
        SoundUtils.getInstance(this).stopRinging();

        // 🔥 DO NOT call disconnect() here
        // Cancel means call was never connected

        stopServiceIncomingCall();

        stopSelf();

        CancelledCallInvite cancelledCallInvite =
                intent.getParcelableExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE);

        if (cancelledCallInvite != null) {
            Notification notification =
                    NotificationUtils.createMissedCallNotification(
                            getApplicationContext(),
                            cancelledCallInvite,
                            false
                    );

            if (notification != null) {
                NotificationManagerCompat
                        .from(this)
                        .notify(100, notification);
            }
        }

        // Inform Flutter layer
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public static String getApplicationName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    private static String createChannel(Context context, boolean highPriority) {
        String id = highPriority ? TwilioConstants.VOICE_CHANNEL_HIGH_IMPORTANCE : TwilioConstants.VOICE_CHANNEL_LOW_IMPORTANCE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel;
            if (highPriority) {
                channel = new NotificationChannel(
                        TwilioConstants.VOICE_CHANNEL_HIGH_IMPORTANCE,
                        "Bivo high importance notification call channel",
                        NotificationManager.IMPORTANCE_HIGH
                );
            } else {
                channel = new NotificationChannel(
                        TwilioConstants.VOICE_CHANNEL_LOW_IMPORTANCE,
                        "Bivo low importance notification call channel",
                        NotificationManager.IMPORTANCE_LOW
                );
            }
            channel.setLightColor(Color.GREEN);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }

        return id;
    }

    private void startServiceIncomingCall(CallInvite callInvite) {
        if (isForegroundRunning) {
            Log.e(TAG, "Foreground already running");
            return;
        }
        Log.e(TAG, "Start service incoming call");

        isForegroundRunning = true;

        SoundUtils.getInstance(this).playRinging();
        Notification notification = NotificationUtils.createIncomingCallNotification(getApplicationContext(), callInvite, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    TwilioConstants.NOTIFICATION_INCOMING_CALL,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(
                    TwilioConstants.NOTIFICATION_INCOMING_CALL,
                    notification
            );
        }
    }

    private void stopServiceIncomingCall() {
        Log.e(TAG, "Stop service incoming call");
        if (!isForegroundRunning) return;

        isForegroundRunning = false;
        SoundUtils.getInstance(this).stopRinging();
        stopForeground(true);
        NotificationUtils.cancel(this, TwilioConstants.NOTIFICATION_INCOMING_CALL);
    }

    private boolean isLocked() {
        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return myKM.inKeyguardRestrictedInputMode();
    }

    private boolean isAppVisible() {
        return AppForegroundStateUtils.getInstance().isForeground();
    }

    // UTILS

    private void informAppAcceptCall(CallInvite callInvite) {
        Intent intent = new Intent();
        intent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        intent.setAction(TwilioConstants.ACTION_ACCEPT);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void informAppCancelCall() {
        Intent intent = new Intent();
        intent.setAction(TwilioConstants.ACTION_CANCEL_CALL);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void openBackgroundCallActivityForAcceptCall(CallInvite callInvite) {
        try {
            Log.e(TAG, "openBackgroundCallActivityForAcceptCall function inside");
            Intent intent = new Intent(getApplicationContext(), BackgroundCallJavaActivity.class);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            intent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);

            Log.e(TAG, "openBackgroundCallActivityForAcceptCall callInvite  " + callInvite.getCallSid());
            intent.setAction(TwilioConstants.ACTION_ACCEPT);
            startActivity(intent);

            Log.e(TAG, "openBackgroundCallActivityForAcceptCall function after startActivity");
        } catch (Exception e) {
            Log.e(TAG, "openBackgroundCallActivityForAcceptCall " + e.toString());
        }

    }

    private void returnCall(Intent intent) {
        stopForeground(true);
        Log.i(TAG, "returning call!!!!");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(100);
    }

    private void missedCall(Intent sourceIntent) {
        stopServiceIncomingCall();
        CancelledCallInvite cancelledCallInvite =
                sourceIntent.getParcelableExtra(
                        TwilioConstants.EXTRA_CANCELLED_CALL_INVITE
                );

        if (cancelledCallInvite == null) {
            Log.e(TAG, "missedCall: CancelledCallInvite is null");
            return;
        }

        if (!isLocked() && isAppVisible()) {

            Intent intent = new Intent();
            intent.setAction(TwilioConstants.ACTION_MISSED_CALL);
            intent.putExtra(
                    TwilioConstants.EXTRA_CANCELLED_CALL_INVITE,
                    cancelledCallInvite
            );

            LocalBroadcastManager
                    .getInstance(this)
                    .sendBroadcast(intent);

        } else {

            stopForeground(true);

            Log.i(TAG, "Missed call while app not visible");

            NotificationManagerCompat
                    .from(this)
                    .cancelAll();

            Intent intent = new Intent(
                    getApplicationContext(),
                    BackgroundCallJavaActivity.class
            );

            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            intent.setAction(TwilioConstants.ACTION_MISSED_CALL);
            intent.putExtra(
                    TwilioConstants.EXTRA_CANCELLED_CALL_INVITE,
                    cancelledCallInvite
            );

            startActivity(intent);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        SoundUtils.getInstance(this).stopRinging();

        try {
            stopForeground(true);
        } catch (Exception ignored) {}

        Log.e(TAG, "IncomingCallNotificationService destroyed");
    }
}
