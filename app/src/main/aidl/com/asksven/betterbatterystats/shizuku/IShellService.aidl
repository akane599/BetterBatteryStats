package com.asksven.betterbatterystats.shizuku;

/**
 * Runs shell commands inside the Shizuku user-service process, which has adb-shell privileges.
 *
 * <p>This is what makes `dumpsys batterystats` reachable on Android 14 and later, where the
 * reflective BatteryStatsImpl path no longer exists: dumpsys needs android.permission.DUMP, which
 * a normal app cannot hold, but the shell user does.</p>
 */
interface IShellService {

    /** Terminates the user-service process. */
    void destroy() = 16777114;

    /**
     * Runs a command and returns its standard output, one entry per line.
     *
     * @param command       the command, run through `sh -c`
     * @param timeoutMillis how long to wait before giving up and killing the process
     */
    List<String> exec(String command, long timeoutMillis) = 1;

    /** @return the uid the service is running as; 2000 for shell, 0 for root. */
    int getUid() = 2;
}
