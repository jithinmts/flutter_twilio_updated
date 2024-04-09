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

// Service to handle incoming call notifications for a Flutter Twilio application
public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action != null) {
            switch (action) {
                case TwilioConstants.ACTION_INCOMING_CALL: {
                    // Handle incoming call notification
                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    handleIncomingCall(callInvite);
                }
                break;
                case TwilioConstants.ACTION_ACCEPT: {
                    // Handle user action to accept call
                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    accept(callInvite);
                }
                break;
                case TwilioConstants.ACTION_REJECT: {
                    // Handle user action to reject call
                    CallInvite callInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE);
                    reject(callInvite);
                }
                break;
                case TwilioConstants.ACTION_CANCEL_CALL: {
                    // Handle a cancelled call
                    handleCancelledCall(intent);
                }
                break;

                case TwilioConstants.ACTION_STOP_SERVICE: {
                    // Stop the notification service
                    stopServiceIncomingCall();
                }
                break;

                case TwilioConstants.ACTION_RETURN_CALL:
                    // Handle user action to return a call
                    returnCall(intent);
                    break;

                case TwilioConstants.ACTION_MISSED_CALL:
                    // Handle a missed call notification
                    missedCall(intent);
                    break;
            }
        }
        return START_STICKY; // Make the service sticky so it continues running
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Handles the incoming call by starting the notification service
    private void handleIncomingCall(CallInvite callInvite) {
        if (callInvite == null) {
            return;
        }

        if (TwilioUtils.getInstance(this).getActiveCall() != null) {
            return;
        }
        this.startServiceIncomingCall(callInvite);
    }

    // Accepts the call and informs the app or opens a custom UI
    private void accept(CallInvite callInvite) {
        this.stopServiceIncomingCall();
        if (!isLocked() && isAppVisible()) {
            // Inform call accepted
            Log.i(TAG, "Answering from APP");
            this.informAppAcceptCall(callInvite);
        }
        else {
            Log.i(TAG, "Answering from custom UI");
            this.openBackgroundCallActivityForAcceptCall(callInvite);
        }
    }

    // Rejects the call invite
    private void reject(CallInvite callInvite) {
        this.stopServiceIncomingCall();
        // Reject call
        try {
            TwilioUtils.getInstance(this).rejectInvite(callInvite);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    // Handles a cancelled call by stopping the ringing sound and showing a missed call notification
    private void handleCancelledCall(Intent intent) {
        SoundUtils.getInstance(this).stopRinging();
        CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(TwilioConstants.EXTRA_CANCELLED_CALL_INVITE);
        stopForeground(true);
        Notification notification = NotificationUtils.createMissedCallNotification(getApplicationContext(), cancelledCallInvite, false);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(100, notification);
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

    // Starts the service for an incoming call notification
    private void startServiceIncomingCall(CallInvite callInvite) {
        SoundUtils.getInstance(this).playRinging();
        Notification notification = NotificationUtils.createIncomingCallNotification(getApplicationContext(), callInvite, true);
        startForeground(TwilioConstants.NOTIFICATION_INCOMING_CALL, notification);
    }

    // Stops the incoming call service and removes the notification
    private void stopServiceIncomingCall() {
        stopForeground(true);
        NotificationUtils.cancel(this, TwilioConstants.NOTIFICATION_INCOMING_CALL);
        SoundUtils.getInstance(this).stopRinging();
    }

    // Checks if the device screen is locked
    private void stopServiceMissedCall() {
        stopForeground(true);
        NotificationUtils.cancel(this, TwilioConstants.NOTIFICATION_MISSED_CALL);
    }

    // Checks if the device screen is locked
    private boolean isLocked() {
        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return myKM.inKeyguardRestrictedInputMode();
    }

    // Checks if the app is visible to the user
    private boolean isAppVisible() {
        return AppForegroundStateUtils.getInstance().isForeground();
    }

    // UTILS

    // Inform the app that a call has been accepted
    private void informAppAcceptCall(CallInvite callInvite) {
        Intent intent = new Intent();
        intent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        intent.setAction(TwilioConstants.ACTION_ACCEPT);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // Inform the app that a call has been cancel
    private void informAppCancelCall() {
        Intent intent = new Intent();
        intent.setAction(TwilioConstants.ACTION_CANCEL_CALL);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // Opens a background activity to accept the call when the app is not visible
    private void openBackgroundCallActivityForAcceptCall(CallInvite callInvite) {
        try {
            Intent intent = new Intent(getApplicationContext(), BackgroundCallJavaActivity.class);
            intent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            );
            intent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
            intent.setAction(TwilioConstants.ACTION_ACCEPT);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openBackgroundCallActivityForAcceptCall " + e.toString());
        }

    }

    private void returnCall(Intent intent) {
        stopForeground(true);
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
