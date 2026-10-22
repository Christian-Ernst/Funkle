package io.github.christianernst.tav;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the stack trace of an uncaught exception, so the next launch can show it. The owner tests
 * on a phone without adb or logcat: without this, a crash is a black flash and nothing to go on.
 * The trace is written to the app's private files directory and taken (deleted) when shown.
 */
final class CrashLog {
    private static final String TAG = "TAV";
    private static final String FILE = "last-crash.txt";
    private static boolean installed;

    private CrashLog() {
    }

    /** Installs the handler once; the system's own handler (the "keeps stopping" dialog) still runs after it. */
    static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                write(app, format(app, thread, e));
            } catch (Throwable ignored) {
                // a failing crash log must not hide the real crash
            }
            if (next != null) next.uncaughtException(thread, e);
        });
    }

    static String format(Context ctx, Thread thread, Throwable e) {
        String version = "?";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("TAV " + version + " on Android " + android.os.Build.VERSION.RELEASE
                + " (" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "), thread " + thread.getName());
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static void write(Context ctx, String text) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Log.e(TAG, text);
    }

    /** The last crash's trace, or null; the file is deleted so it shows once. */
    static String take(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        String text = null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            text = new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /** The lines worth reading on a phone screen: the exception and the frames in TAV's own code first. */
    static String summary(String trace, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String line : trace.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            boolean frame = t.startsWith("at ");
            if (frame && !t.contains("io.github.christianernst.tav")) continue;   // framework frames: noise on a screen
            sb.append(frame ? t.substring(3).replace("io.github.christianernst.tav.", "") : t).append('\n');
            if (++lines >= maxLines) break;
        }
        return sb.toString().trim();
    }
}
