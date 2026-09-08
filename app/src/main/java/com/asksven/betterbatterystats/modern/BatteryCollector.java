package com.asksven.betterbatterystats.modern;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import com.asksven.betterbatterystats.BuildConfig;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;

public final class BatteryCollector {
    public enum Access { SHIZUKU, ADB, ROOT }

    public static boolean shizukuReady() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }

    public String collect(Context context, Access access) throws Exception {
        if (access != Access.SHIZUKU) return DumpCommand.read(access == Access.ROOT);
        if (!shizukuReady()) throw new IOException("Start Shizuku, then tap Connect Shizuku and allow BBS access.");
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<IBatteryReader> remote = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(context, BatteryReaderService.class))
                .daemon(false).processNameSuffix("battery_reader").version(BuildConfig.VERSION_CODE);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(IBatteryReader.Stub.asInterface(binder));
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                remote.set(null);
                ready.countDown();
            }
        };
        main.post(() -> {
            if (finished.get()) return;
            try { Shizuku.bindUserService(args, connection); }
            catch (RuntimeException e) { error.set(e); ready.countDown(); }
        });
        try {
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("Shizuku connection timed out. Restart Shizuku and retry.");
            if (error.get() != null) throw error.get();
            IBatteryReader service = remote.get();
            if (service == null) throw new IOException("Shizuku disconnected. Reconnect and retry.");
            ParcelFileDescriptor descriptor = service.readBatteryStats();
            if (descriptor == null) throw new IOException("Shizuku returned no battery data.");
            // Closing the descriptor also interrupts a blocked read if the remote dies silently.
            Runnable deadline = () -> { try { descriptor.close(); } catch (IOException ignored) { } };
            main.postDelayed(deadline, 30000);
            try (ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                String text = DumpCommand.readLimited(input);
                descriptor.checkError();
                return text;
            } finally { main.removeCallbacks(deadline); }
        } finally {
            finished.set(true);
            main.post(() -> {
                try { Shizuku.unbindUserService(args, connection, true); }
                catch (RuntimeException ignored) { }
            });
        }
    }
}
