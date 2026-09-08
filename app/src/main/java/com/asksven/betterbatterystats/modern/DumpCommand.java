package com.asksven.betterbatterystats.modern;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed, read-only commands. No user input is interpolated into a privileged shell. */
public final class DumpCommand {
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bbs-command-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private DumpCommand() { }

    public static String read(boolean root) throws IOException {
        ProcessBuilder builder = root
                ? new ProcessBuilder("su", "-c", "/system/bin/dumpsys -t 15 batterystats -c --charged")
                : new ProcessBuilder("/system/bin/dumpsys", "-t", "15", "batterystats", "-c", "--charged");
        java.lang.Process process = builder.redirectErrorStream(true).start();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = TIMEOUTS.schedule(() -> {
            timedOut.set(true);
            process.destroy();
        }, 20, TimeUnit.SECONDS);
        try {
            process.getOutputStream().close();
            String text;
            try (InputStream stream = process.getInputStream()) { text = readLimited(stream); }
            int exit = process.waitFor();
            if (timedOut.get()) throw new IOException("Battery collection timed out. Check access and try again.");
            if (exit != 0) throw new IOException("Battery command failed (exit " + exit + "). Check Shizuku or ADB access.");
            if (text.contains("DUMP TIMEOUT") || text.contains("was the duration of dumpsys")) {
                throw new IOException("Android returned an incomplete battery dump. Try again.");
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Battery collection cancelled.", e);
        } finally {
            timeout.cancel(false);
            process.destroy();
        }
    }

    public static String readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Read cancelled.");
            if (output.size() + count > CheckinParser.MAX_CHARS) throw new IOException("Report exceeds 16 MiB.");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
