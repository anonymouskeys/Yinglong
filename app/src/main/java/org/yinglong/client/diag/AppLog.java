package org.yinglong.client.diag;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent diagnostic log with simple rotation. */
public final class AppLog {
    private static final String TAG = "Yinglong";
    private static final String FILE_NAME = "yinglong.log";
    private static final String OLD_FILE_NAME = "yinglong.log.1";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final long MAX_READ_BYTES = 256L * 1024L;
    private static final Object LOCK = new Object();
    private static volatile Context appContext;

    private AppLog() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static void i(String area, String message) { write("I", area, message, null); }
    public static void w(String area, String message) { write("W", area, message, null); }
    public static void e(String area, String message, Throwable error) { write("E", area, message, error); }

    private static void write(String level, String area, String message, Throwable error) {
        String cleanArea = area == null ? "core" : area;
        String cleanMessage = message == null ? "" : message.replace('\n', ' ');
        String line = timestamp() + " " + level + "/" + cleanArea + " " + cleanMessage;
        if (error != null) line += " | " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());

        if ("E".equals(level)) Log.e(TAG + "/" + cleanArea, cleanMessage, error);
        else if ("W".equals(level)) Log.w(TAG + "/" + cleanArea, cleanMessage);
        else Log.i(TAG + "/" + cleanArea, cleanMessage);

        Context ctx = appContext;
        if (ctx == null) return;
        synchronized (LOCK) {
            try {
                File file = new File(ctx.getFilesDir(), FILE_NAME);
                rotateIfNeeded(ctx, file);
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {
                // Diagnostics must never crash the VPN client.
            }
        }
    }

    private static void rotateIfNeeded(Context ctx, File file) {
        try {
            if (!file.isFile() || file.length() < MAX_BYTES) return;
            File old = new File(ctx.getFilesDir(), OLD_FILE_NAME);
            if (old.exists()) old.delete();
            if (!file.renameTo(old)) file.delete();
        } catch (Exception ignored) {}
    }

    public static String read(Context context) {
        init(context);
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), FILE_NAME);
                if (!file.isFile()) return "Журнал пока пуст.";
                long skip = Math.max(0L, file.length() - MAX_READ_BYTES);
                try (FileInputStream in = new FileInputStream(file)) {
                    if (skip > 0) {
                        long remaining = skip;
                        while (remaining > 0) {
                            long n = in.skip(remaining);
                            if (n <= 0) break;
                            remaining -= n;
                        }
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    StringBuilder out = new StringBuilder();
                    String line;
                    if (skip > 0) br.readLine(); // discard first potentially partial line
                    while ((line = br.readLine()) != null) out.append(line).append('\n');
                    return out.length() == 0 ? "Журнал пока пуст." : out.toString();
                }
            } catch (Exception e) {
                return "Не удалось прочитать журнал: " + safe(e.getMessage());
            }
        }
    }

    public static void clear(Context context) {
        init(context);
        synchronized (LOCK) {
            try { new File(context.getFilesDir(), FILE_NAME).delete(); } catch (Exception ignored) {}
            try { new File(context.getFilesDir(), OLD_FILE_NAME).delete(); } catch (Exception ignored) {}
        }
        i("log", "diagnostic log cleared");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
