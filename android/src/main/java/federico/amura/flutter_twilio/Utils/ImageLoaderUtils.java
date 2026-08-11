package federico.amura.flutter_twilio.Utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal avatar loader. Deliberately dependency free - a previous change removed the
 * image loading library from this module, so this covers just what the call screen needs:
 * fetch a bitmap off the main thread, downsample it, and cache a few of them.
 */
public class ImageLoaderUtils {

    private static final String TAG = "ImageLoaderUtils";

    /** Avatars render into a 120dp circle; 320px is plenty and bounds memory use. */
    private static final int TARGET_SIZE_PX = 320;
    private static final int TIMEOUT_MS = 8000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8);

    public interface Callback {
        /** Always invoked on the main thread. Never invoked when loading fails. */
        void onLoaded(Bitmap bitmap);
    }

    private ImageLoaderUtils() {
    }

    public static void load(final String url, final Callback callback) {
        if (url == null || url.trim().isEmpty() || callback == null) return;

        final String key = url.trim();

        final Bitmap cached = CACHE.get(key);
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = download(key);
                if (bitmap == null) return;

                CACHE.put(key, bitmap);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onLoaded(bitmap);
                    }
                });
            }
        });
    }

    private static Bitmap download(String url) {
        byte[] bytes = readBytes(url);
        if (bytes == null) return null;

        try {
            // Measure first so a large remote image is not decoded at full size.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding image " + url + ". Error: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readBytes(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.e(TAG, "Error loading image " + url + ". HTTP status " + status);
                return null;
            }

            InputStream input = connection.getInputStream();
            try {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } finally {
                input.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading image " + url + ". Error: " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int calculateSampleSize(int width, int height) {
        int sampleSize = 1;
        if (width <= 0 || height <= 0) return sampleSize;

        int largest = Math.max(width, height);
        while (largest / (sampleSize * 2) >= TARGET_SIZE_PX) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
