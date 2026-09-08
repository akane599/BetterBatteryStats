package com.asksven.betterbatterystats.modern;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shizuku UserService: streams a single read-only dump without Binder's 1 MiB limit. */
@Keep
public final class BatteryReaderService extends IBatteryReader.Stub {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Keep
    public BatteryReaderService() { }

    @Override
    public ParcelFileDescriptor readBatteryStats() throws RemoteException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            worker.execute(() -> {
                try {
                    byte[] bytes = DumpCommand.read(false).getBytes(StandardCharsets.UTF_8);
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        output.write(bytes);
                    }
                } catch (Exception e) {
                    try { pipe[1].closeWithError("Battery collection failed: " + e.getMessage()); }
                    catch (IOException ignored) { }
                }
            });
            return pipe[0];
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
        System.exit(0);
    }
}
