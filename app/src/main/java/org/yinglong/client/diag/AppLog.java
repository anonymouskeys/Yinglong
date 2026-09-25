package org.yinglong.client.diag;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Persistent + in-memory diagnostics.
 *
 * File logging is primary. A RAM ring is always kept as a fallback so the hidden
 * diagnostics window can still show useful information even if file I/O fails.
 */
public final class AppLog {
    private static final String TAG = "Yinglong";
    private static final String FILE_NAME = "yinglong.log";
    private static final String OLD_FILE_NAME = "yinglong.log.1";
    private static final long MAX_BYTES = 768L * 1024L;
    private static final long MAX_READ_BYTES = 512L * 1024L;
    private static final int RAM_LINES = 700;

    private static final Object LOCK = new Object();
    private static final Deque<String> RING = new ArrayDeque<>();
    private static volatile Context appContext;
    private static volatile String lastWriteFailure = "";
    private static volatile boolean crashHandlerInstalled;

    private AppLog() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static void installCrashHandler(Context context) {
        init(context);
        synchronized (LOCK) {
            if (crashHandlerInstalled) return;
            crashHandlerInstalled = true;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                e("crash", "UNCAUGHT on thread=" + (thread == null ? "?" : thread.getName()), throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    public static void i(String area, String message) { write("I", area, message, null); }
    public static void w(String area, String message) { write("W", area, message, null); }
    public static void e(String area, String message, Throwable error) { write("E", area, message, error); }

    private static void write(String level, String area, String message, Throwable error) {
        String cleanArea = area == null ? "core" : area;
        String cleanMessage = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        String base = timestamp() + " " + level + "/" + cleanArea + " " + cleanMessage;

        if ("E".equals(level)) Log.e(TAG + "/" + cleanArea, cleanMessage, error);
        else if ("W".equals(level)) Log.w(TAG + "/" + cleanArea, cleanMessage);
        else Log.i(TAG + "/" + cleanArea, cleanMessage);

        StringBuilder payload = new StringBuilder(base).append('\n');
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String[] stack = sw.toString().split("\\r?\\n");
            for (String line : stack) {
                payload.append(timestamp()).append(" ").append(level).append("/")
                        .append(cleanArea).append(" | ").append(line).append('\n');
            }
        }

        synchronized (LOCK) {
            for (String line : payload.toString().split("\\n")) {
                if (!line.isEmpty()) addRamLine(line);
            }
            Context ctx = appContext;
            if (ctx == null) {
                lastWriteFailure = "appContext == null";
                return;
            }
            try {
                File file = new File(ctx.getFilesDir(), FILE_NAME);
                rotateIfNeeded(ctx, file);
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                lastWriteFailure = "";
            } catch (Throwable t) {
                lastWriteFailure = t.getClass().getSimpleName() + ": " + safe(t.getMessage());
                Log.e(TAG + "/log", "file log write failed", t);
            }
        }
    }

    private static void addRamLine(String line) {
        RING.addLast(line);
        while (RING.size() > RAM_LINES) RING.removeFirst();
    }

    private static void rotateIfNeeded(Context ctx, File file) {
        try {
            if (!file.isFile() || file.length() < MAX_BYTES) return;
            File old = new File(ctx.getFilesDir(), OLD_FILE_NAME);
            if (old.exists()) old.delete();
            if (!file.renameTo(old)) file.delete();
        } catch (Throwable ignored) {}
    }

    public static String read(Context context) {
        init(context);
        synchronized (LOCK) {
            StringBuilder out = new StringBuilder();
            out.append("Yinglong diagnostics\n")
                    .append("Android ").append(Build.VERSION.RELEASE)
                    .append(" API ").append(Build.VERSION.SDK_INT)
                    .append(" / ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');

            File file = new File(context.getFilesDir(), FILE_NAME);
            out.append("logFile=").append(file.getAbsolutePath())
                    .append(" exists=").append(file.isFile())
                    .append(" bytes=").append(file.isFile() ? file.length() : 0).append('\n');
            if (!lastWriteFailure.isEmpty()) out.append("lastWriteFailure=").append(lastWriteFailure).append('\n');
            out.append("\n--- FILE ---\n");

            String fileText = readTail(file, MAX_READ_BYTES);
            if (fileText.isEmpty()) out.append("(file log empty)\n");
            else out.append(fileText);

            out.append("\n--- RAM RING ---\n");
            if (RING.isEmpty()) out.append("(RAM log empty)\n");
            else for (String line : RING) out.append(line).append('\n');
            return out.toString();
        }
    }

    private static String readTail(File file, long maxBytes) {
        try {
            if (!file.isFile()) return "";
            long skip = Math.max(0L, file.length() - maxBytes);
            try (FileInputStream in = new FileInputStream(file)) {
                long remaining = skip;
                while (remaining > 0) {
                    long n = in.skip(remaining);
                    if (n <= 0) break;
                    remaining -= n;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                if (skip > 0) br.readLine();
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
                return out.toString();
            }
        } catch (Throwable t) {
            return "<read failed: " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()) + ">\n";
        }
    }

    public static void clear(Context context) {
        init(context);
        synchronized (LOCK) {
            try { new File(context.getFilesDir(), FILE_NAME).delete(); } catch (Throwable ignored) {}
            try { new File(context.getFilesDir(), OLD_FILE_NAME).delete(); } catch (Throwable ignored) {}
            RING.clear();
            lastWriteFailure = "";
        }
        i("log", "diagnostic log cleared");
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
