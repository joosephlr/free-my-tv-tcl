package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.StatFs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small snapshots used by the home panel and the system-usage screen. */
final class SystemStats {
    private SystemStats() {}

    static final class Snapshot {
        long ramTotal, ramUsed, storageTotal, storageUsed;
        String ramText = "", storageText = "";
        List<Process> processes = Collections.emptyList();
    }

    static final class Process {
        String packageName, label;
        int memoryMb;
    }

    static Snapshot read(Context context) {
        Snapshot out = new Snapshot();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            out.ramTotal = memory.totalMem;
            out.ramUsed = Math.max(0, memory.totalMem - memory.availMem);
            out.ramText = format(out.ramUsed) + " / " + format(out.ramTotal);
            List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
            if (running != null) {
                List<Process> processes = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ActivityManager.RunningAppProcessInfo info : running) {
                    if (info == null || info.pid <= 0) continue;
                    String packageName = packageOf(info);
                    if (packageName == null || !seen.add(packageName)) continue;
                    int[] pids = new int[] {info.pid};
                    Debug.MemoryInfo[] details = am.getProcessMemoryInfo(pids);
                    if (details.length == 0) continue;
                    int memoryMb = Math.max(0, details[0].getTotalPss() / 1024);
                    if (memoryMb <= 0) continue;
                    Process process = new Process();
                    process.packageName = packageName;
                    process.label = appLabel(context, packageName);
                    process.memoryMb = memoryMb;
                    processes.add(process);
                }
                Collections.sort(processes, (a, b) -> b.memoryMb - a.memoryMb);
                out.processes = processes;
            }
        }
        try {
            StatFs fs = new StatFs(context.getDataDir().getPath());
            out.storageTotal = fs.getBlockCountLong() * fs.getBlockSizeLong();
            out.storageUsed = out.storageTotal - fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
            out.storageText = format(out.storageUsed) + " / " + format(out.storageTotal);
        } catch (RuntimeException e) {
            AppLog.w("couldn't read storage stats: " + e.getMessage());
        }
        return out;
    }

    private static String format(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024d * 1024 * 1024));
        return String.format(java.util.Locale.US, "%.0f MB", bytes / (1024d * 1024));
    }

    private static String packageOf(ActivityManager.RunningAppProcessInfo info) {
        if (info.pkgList != null && info.pkgList.length > 0) return info.pkgList[0];
        return info.processName;
    }

    private static String appLabel(Context context, String packageName) {
        try {
            return String.valueOf(context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)));
        } catch (Exception e) {
            return packageName;
        }
    }
}
