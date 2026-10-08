package com.herdmate.tagreapersentinel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Records Java failures, then delegates to Android's normal crash handling. */
public final class SentinelDiagnostics implements Thread.UncaughtExceptionHandler {
    private final File report;
    private final Thread.UncaughtExceptionHandler previous;

    private SentinelDiagnostics(Context context, Thread.UncaughtExceptionHandler previous) {
        report = new File(context.getFilesDir(), "sentinel-next-last-crash.txt");
        this.previous = previous;
    }

    public static void install(final Activity activity) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (!(previous instanceof SentinelDiagnostics)) {
            Thread.setDefaultUncaughtExceptionHandler(
                    new SentinelDiagnostics(activity.getApplicationContext(), previous));
        }
        final File report = new File(activity.getFilesDir(), "sentinel-next-last-crash.txt");
        if (!report.isFile()) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    final byte[] bytes = new byte[(int) Math.min(report.length(), 65536L)];
                    int length = 0;
                    try (FileInputStream input = new FileInputStream(report)) {
                        int count;
                        while (length < bytes.length && (count = input.read(bytes, length, bytes.length - length)) > 0) length += count;
                    }
                    final String details = new String(bytes, 0, length, StandardCharsets.UTF_8);
                    new AlertDialog.Builder(activity)
                            .setTitle("Sentinel Next crash report")
                            .setMessage("A previous Java crash was recorded. Export its report for diagnosis. Your saved processing records remain in local storage.")
                            .setPositiveButton("Export report", (dialog, which) -> {
                                Intent send = new Intent(Intent.ACTION_SEND);
                                send.setType("text/plain");
                                send.putExtra(Intent.EXTRA_SUBJECT, "Sentinel Next 0.7.1-next-r1 crash report");
                                send.putExtra(Intent.EXTRA_TEXT, details);
                                activity.startActivity(Intent.createChooser(send, "Save or share crash report"));
                            })
                            .setNegativeButton("Later", null).show();
                } catch (Exception ignored) { /* Do not let diagnostics prevent startup. */ }
            }
        });
    }

    @Override public void uncaughtException(Thread thread, Throwable error) {
        try {
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            writer.println("Sentinel Next 0.7.1-next-r1 / " + System.currentTimeMillis());
            writer.println("Thread: " + thread.getName());
            error.printStackTrace(writer);
            writer.flush();
            String value = text.toString();
            if (value.length() > 60000) value = value.substring(0, 60000);
            try (FileOutputStream output = new FileOutputStream(report)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Throwable ignored) { /* Preserve the original crash. */ }
        if (previous != null) previous.uncaughtException(thread, error);
        else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
    }
}
