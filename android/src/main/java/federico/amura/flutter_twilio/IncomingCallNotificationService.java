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

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.i(TAG, "onStartCommand " + action);
        if (action != null) {
            switch (action) {
                case TwilioConstants.ACTION_INCOMING_CALL: {
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_INCOMING_CALL case");
                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    Log.e(TAG, "ACTION_INCOMING_CALL call Invite " + callInvite.getCallSid());
                    handleIncomingCall(callInvite);
                }
                break;

                case TwilioConstants.ACTION_ACCEPT: {
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_ACCEPT case");

                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    Log.e(TAG, "ACTION_ACCEPT call Invite " + callInvite.getCallSid());
                    accept(callInvite);
                }
                break;
                case TwilioConstants.ACTION_REJECT: {
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_REJECT case");
                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    reject(callInvite);
                }
                break;
                case TwilioConstants.ACTION_CANCEL_CALL: {
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_CANCEL_CALL case");

//                    CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE);
                    handleCancelledCall(intent);
//
//                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
//                    this.startServiceMissedCall(callInvite,cancelledCallInvite);
                }
                break;

                case TwilioConstants.ACTION_STOP_SERVICE: {
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_STOP_SERVICE case");

                    stopServiceIncomingCall();
                }
                break;

                case TwilioConstants.ACTION_RETURN_CALL:
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_RETURN_CALL case");
                    returnCall(intent);
                    break;

                case TwilioConstants.ACTION_MISSED_CALL:
                    Log.e("*Twilio onStartCommand ", "TwilioConstants.ACTION_MISSED_CALL case");
                    missedCall(intent);
                    break;
            }
        }
        return START_STICKY;
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

        Log.e(TAG, "Incoming call. App visible: " + isAppVisible() + ". Locked: " + isLocked());
        if (TwilioUtils.getInstance(this).getActiveCall() != null) {
            Log.i(TAG, "Incoming call. There is already an active call");
            return;
        }
        this.startServiceIncomingCall(callInvite);
    }

    private void accept(CallInvite callInvite) {
        Log.e(TAG, "Accept call invite. App visible: " + isAppVisible() + ". Locked: " + isLocked());
        this.stopServiceIncomingCall();
        if (!isLocked() && isAppVisible()) {
            // Inform call accepted
            Log.i(TAG, "Answering from APP");
            this.informAppAcceptCall(callInvite);
        } else {
            Log.i(TAG, "Answering from custom UI");
            this.openBackgroundCallActivityForAcceptCall(callInvite);
        }
    }

    private void reject(CallInvite callInvite) {
        Log.e(TAG, "Reject call invite. App visible: " + isAppVisible() + ". Locked: " + isLocked());
        this.stopServiceIncomingCall();

        // Reject call
        try {
            TwilioUtils.getInstance(this).rejectInvite(callInvite);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void handleCancelledCall(Intent intent) {
        SoundUtils.getInstance(this).stopRinging();
        CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE);
        Log.i(TAG, "Call canceled. App visible: " + isAppVisible() + ". Locked: " + isLocked());

//        this.stopServiceIncomingCall();

//        if (cancelledCallInvite == null) return;
//        if (cancelledCallInvite.getFrom() == null) return;
//
//        Log.i(TAG, "From: " + cancelledCallInvite.getFrom() + ". To: " + cancelledCallInvite.getTo());
//        this.informAppCancelCall();
        stopForeground(true);
        Notification notification = NotificationUtils.createMissedCallNotification(getApplicationContext(), cancelledCallInvite, false);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(100, notification);
//       startForeground(TwilioConstants.NOTIFICATION_MISSED_CALL, notification);
//        buildMissedCallNotification(cancelledCallInvite.getFrom(), cancelledCallInvite.getTo(),cancelledCallInvite);

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
        Log.e(TAG, "Start service incoming call");
        SoundUtils.getInstance(this).playRinging();
        Notification notification = NotificationUtils.createIncomingCallNotification(getApplicationContext(), callInvite, true);
        startForeground(TwilioConstants.NOTIFICATION_INCOMING_CALL, notification);
    }

    private void stopServiceIncomingCall() {
        Log.e(TAG, "Stop service incoming call");
        stopForeground(true);
        NotificationUtils.cancel(this, TwilioConstants.NOTIFICATION_INCOMING_CALL);
        SoundUtils.getInstance(this).stopRinging();
    }

    private void stopServiceMissedCall() {
        Log.e(TAG, "Stop service missed call");
        stopForeground(true);
        NotificationUtils.cancel(this, TwilioConstants.NOTIFICATION_MISSED_CALL);
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
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
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

    private void missedCall(Intent intents) {
        if (!isLocked() && isAppVisible()) {
            Intent intent = new Intent();
            intent.putExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE, intents);
            intent.setAction(TwilioConstants.ACTION_MISSED_CALL);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } else {
            stopForeground(true);
            Log.i(TAG, "missed Call!!!!");
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.cancelAll();
            Intent intent = new Intent();
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            );
            intent.putExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE, intents);
            intent.setAction(TwilioConstants.ACTION_MISSED_CALL);
            startActivity(intent);
        }
    }
}
