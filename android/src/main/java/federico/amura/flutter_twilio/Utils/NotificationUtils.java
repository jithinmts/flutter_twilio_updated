package federico.amura.flutter_twilio.Utils;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;

import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;


import java.util.Map;

import federico.amura.flutter_twilio.BackgroundCallJavaActivity;
import federico.amura.flutter_twilio.IncomingCallNotificationService;
import federico.amura.flutter_twilio.R;

import androidx.lifecycle.ProcessLifecycleOwner;

public class NotificationUtils {

    public static Notification createIncomingCallNotification(Context context, CallInvite callInvite, boolean showHeadsUp) {
        if (callInvite == null) return null;

        String fromDisplayName = null;
        for (Map.Entry<String, String> entry : callInvite.getCustomParameters().entrySet()) {
            if (entry.getKey().equals("fromDisplayName")) {
                fromDisplayName = entry.getValue();
            }
        }
        if (fromDisplayName == null || fromDisplayName.trim().isEmpty()) {
            final String contactName = PreferencesUtils.getInstance(context).findContactName(callInvite.getFrom());
            if (contactName != null && !contactName.trim().isEmpty()) {
                fromDisplayName = contactName;
            } else {
                fromDisplayName = "Unknown name";
            }
        }

        Log.d(" call getFrom 2", callInvite.getFrom());
        Log.d(" call getFrom 3", callInvite.getCustomParameters().entrySet().toString());
        if (fromDisplayName.equals("Unknown number"))
            fromDisplayName = callInvite.getFrom();
        Log.d(" fromDisplayName", fromDisplayName);
        String notificationTitle = context.getString(R.string.notification_incoming_call_title);
        String notificationText = fromDisplayName;

        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        Log.d(" call Invite 2", callInvite.getCallSid());
        extras.putString(TwilioConstants.CALL_SID_KEY, callInvite.getCallSid());

        // Click intent
        Intent intent = new Intent(context, BackgroundCallJavaActivity.class);
//        intent.setAction(Intent.ACTION_MAIN);
//        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setAction(TwilioConstants.ACTION_INCOMING_CALL);
        intent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        Log.d(" call Invite 3", callInvite.getCallSid());
        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        );
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );


        //Reject intent
        Intent rejectIntent = new Intent(context, IncomingCallNotificationService.class);
//        rejectIntent.setAction(Intent.ACTION_MAIN);
//        rejectIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        rejectIntent.setAction(TwilioConstants.ACTION_REJECT);
        rejectIntent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent piRejectIntent = PendingIntent.getService(
                context,
                0,
                rejectIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Accept intent
        Intent acceptIntent = new Intent(context, IncomingCallNotificationService.class);
//        acceptIntent.setAction(Intent.ACTION_MAIN);
//        acceptIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        acceptIntent.setAction(TwilioConstants.ACTION_ACCEPT);
        acceptIntent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, callInvite);
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent piAcceptIntent = PendingIntent.getService(
                context,
                0,
                acceptIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, createChannel(context, showHeadsUp));
        builder.setSmallIcon(R.drawable.ic_phone_call);
        builder.setContentTitle(notificationTitle);
        builder.setContentText(notificationText);
        builder.setCategory(NotificationCompat.CATEGORY_CALL);
        builder.setAutoCancel(true);
        builder.setExtras(extras);
        builder.setVibrate(new long[]{0, 400, 400, 400, 400, 400, 400, 400});
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
//        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) || isAppVisible())
        builder.addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.btn_reject), piRejectIntent);
//        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) || isAppVisible())
        builder.addAction(android.R.drawable.ic_menu_call, context.getString(R.string.btn_accept), piAcceptIntent);
        builder.setFullScreenIntent(pendingIntent, true);
        builder.setColor(Color.rgb(20, 10, 200));
        builder.setOngoing(true);
        builder.setPriority(NotificationCompat.PRIORITY_MAX);
        builder.setContentIntent(pendingIntent);
        return builder.build();
    }

    public static Notification createMissedCallNotification(Context context, CancelledCallInvite cancelledCallInvite, boolean showHeadsUp) {
        String fromDisplayName = null;
        for (Map.Entry<String, String> entry : cancelledCallInvite.getCustomParameters().entrySet()) {
            if (entry.getKey().equals("fromDisplayName")) {
                fromDisplayName = entry.getValue();
            }
        }
        if (fromDisplayName == null || fromDisplayName.trim().isEmpty()) {
            final String contactName = PreferencesUtils.getInstance(context).findContactName(cancelledCallInvite.getFrom());
            if (contactName != null && !contactName.trim().isEmpty()) {
                fromDisplayName = contactName;
            } else {
                fromDisplayName = "Unknown name";
            }
        }

        Log.d(" call getFrom 2", cancelledCallInvite.getFrom());
        Log.d(" call getFrom 3", cancelledCallInvite.getCustomParameters().entrySet().toString());
        if (fromDisplayName.equals("Unknown number"))
            fromDisplayName = cancelledCallInvite.getFrom();
        Log.d(" fromDisplayName", fromDisplayName);
        String notificationText = fromDisplayName;

        Intent returnCallIntent = new Intent(context, BackgroundCallJavaActivity.class);
        returnCallIntent.setAction(TwilioConstants.ACTION_RETURN_CALL);
        returnCallIntent.putExtra(cancelledCallInvite.getTo(), "to");
        returnCallIntent.putExtra(cancelledCallInvite.getFrom(), "callerId");
        returnCallIntent.putExtra(TwilioConstants.EXTRA_INCOMING_CALL_INVITE, cancelledCallInvite);
//        returnCallIntent.setFlags(
//                Intent.FLAG_ACTIVITY_NEW_TASK |
//                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
//                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
//                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
//        );
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent piReturnCallIntent = PendingIntent.getActivity(
                context,
                0,
                returnCallIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent LaunchIntent = context.getPackageManager().getLaunchIntentForPackage("com.tch.crm");
        LaunchIntent.setAction(TwilioConstants.ACTION_MISSED_CALL);
        LaunchIntent.putExtra("TwilioConstant", "cancelledCallInvite");
        LaunchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        @SuppressLint("UnspecifiedImmutableFlag")
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                LaunchIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, createChannel(context, false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setSmallIcon(R.drawable.ic_call_end);
            builder.setContentTitle("Missed Call");
            builder.setCategory(Notification.CATEGORY_CALL);
            builder.setAutoCancel(true);
            builder.addAction(android.R.drawable.ic_menu_call, "Call Back", piReturnCallIntent);
            builder.setPriority(NotificationCompat.PRIORITY_HIGH);
            builder.setContentText(notificationText);
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            builder.setContentIntent(pendingIntent);
            builder.setOngoing(false);
//            return builder.build();
        } else {
//            notification = new NotificationCompat.Builder(context)
            builder.setSmallIcon(R.drawable.ic_call_end);
            builder.setContentTitle("Missed Call");
            builder.setContentText(notificationText);
            builder.setAutoCancel(true);
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            builder.setPriority(NotificationCompat.PRIORITY_MAX);
            builder.addAction(android.R.drawable.ic_menu_call, "Call Back", piReturnCallIntent);
            builder.setColor(Color.rgb(20, 10, 200));
            builder.setContentIntent(pendingIntent);
            builder.setOngoing(false);
//            return  builder.build();
        }
        return builder.build();
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

    public static void cancel(Context context, int id) {
        NotificationManagerCompat.from(context).cancel(id);
    }

    private static boolean isAppVisible() {
        return ProcessLifecycleOwner
                .get()
                .getLifecycle()
                .getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
    }
}
