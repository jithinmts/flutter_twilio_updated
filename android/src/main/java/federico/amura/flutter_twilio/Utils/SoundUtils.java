package federico.amura.flutter_twilio.Utils;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import federico.amura.flutter_twilio.R;

public class SoundUtils {

    private static SoundUtils instance;

    private MediaPlayer mediaPlayer;
    private boolean isRinging = false;

    private Vibrator vibrator;
    private AudioManager audioManager;
    private Context appContext;

    private SoundUtils(Context context) {
        this.appContext = context.getApplicationContext();
        this.vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static synchronized SoundUtils getInstance(Context context) {
        if (instance == null) {
            instance = new SoundUtils(context);
        }
        return instance;
    }

    public synchronized void playRinging() {

        if (isRinging) {
            return; // 🔒 already ringing
        }

        try {
            stopRinging(); // extra safety

            mediaPlayer = MediaPlayer.create(appContext, R.raw.incoming);

            if (mediaPlayer == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                );
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
            }

            mediaPlayer.setLooping(true);
            mediaPlayer.start();

            vibrate();

            isRinging = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void vibrate() {
        if (vibrator == null) return;

        long[] pattern = new long[]{0, 500, 500};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    public synchronized void stopRinging() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {}

        try {
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception ignored) {}

        isRinging = false;
    }

    public boolean isRinging() {
        return isRinging;
    }

    public synchronized void playDisconnect() {
        try {
            MediaPlayer mp = MediaPlayer.create(appContext, R.raw.disconnect);
            if (mp == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                );
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_NOTIFICATION);
            }

            mp.setOnCompletionListener(MediaPlayer::release);
            mp.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}