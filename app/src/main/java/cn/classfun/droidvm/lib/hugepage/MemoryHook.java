// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.hugepage;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Dynamic guest memory through a root module.
 *
 * <p>A KernelSU/Magisk module may ship an executable {@code droidvm-memory-hook} in its module
 * directory. When one is present (and the module is not disabled or being removed), every start of
 * a Gunyah VM first runs {@code hook prepare <vm_id> <memory_mb> <extra_mb>}: the module frees what
 * it can (background apps to zram, a fuller huge-page reserve) and prints {@code MEMORY_MB=<n>},
 * the most this VM can get right now. The VM then starts with that much RAM for this run only; the
 * configured {@code memory_mb} stays the ceiling and is never rewritten. Any other output line is
 * progress, copied into the VM's console. {@code MEMORY_MB=-1} means the module could not free the
 * minimum it is set to: the start is called off (see {@link #REFUSED}). No hook, a failing hook or no
 * answer: the configured size and the usual pool preflight apply, exactly as before.
 *
 * <p>After the VM has finally stopped (not on a guest reboot, which keeps its memory) the daemon
 * runs {@code hook release <vm_id>} so the module can hand the reserve back to Android at once.
 *
 * <p>All of this runs in the daemon (root). The UI only asks {@link #present} to skip the
 * "not enough huge-page memory" dialog, because the daemon will size the VM to fit.
 */
public final class MemoryHook {
    private static final String TAG = "MemoryHook";
    /** Shell snippet printing the first enabled module hook, or nothing. */
    private static final String FIND = "for f in /data/adb/modules/*/droidvm-memory-hook; do "
        + "[ -f \"$f\" ] || continue; d=${f%/*}; "
        + "[ -e \"$d/disable\" ] || [ -e \"$d/remove\" ] || { echo \"$f\"; break; }; done";
    private static final long PREPARE_TIMEOUT_S = 300;
    /** {@link #prepare}'s answer when the module refused: too little memory, do not start. */
    public static final long REFUSED = -1;
    private static final long RELEASE_TIMEOUT_S = 120;
    /** Per-VM size for the current run, set by {@link #prepare}; read by the backends. */
    private static final Map<String, Long> overrides = new ConcurrentHashMap<>();

    private MemoryHook() {
    }

    /** The enabled hook's path, or null. Shell I/O: call off the UI thread. */
    @Nullable
    public static String find() {
        try {
            var p = new ProcessBuilder("sh", "-c", FIND).redirectErrorStream(true).start();
            String line;
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return line != null && !line.trim().isEmpty() ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** For the app (not root): asks through the root shell like PoolPreflight does. */
    public static boolean present() {
        try {
            var r = cn.classfun.droidvm.lib.utils.RunUtils.runQuiet(FIND);
            return r.isSuccess() && !r.getOutString().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs the module's prepare step for a VM about to start. Returns the memory (MB) to give it,
     * 0 when the configured size should be used, or {@link #REFUSED}. Progress lines go to [log].
     */
    public static long prepare(@NonNull UUID vmId, @NonNull DataItem item, long extraMb,
                               @NonNull Consumer<String> log) {
        overrides.remove(vmId.toString());
        var hook = find();
        if (hook == null) return 0;
        long want = Math.max(item.optLong("memory_mb", 512), 64);
        long got = 0;
        Process p = null;
        try {
            p = new ProcessBuilder("sh", hook, "prepare", vmId.toString(),
                Long.toString(want), Long.toString(extraMb))
                .redirectErrorStream(true).start();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("MEMORY_MB=")) {
                        try {
                            got = Long.parseLong(line.substring(10).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if (!line.isEmpty()) {
                        log.accept(line);
                    }
                }
            }
            if (!p.waitFor(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.w(TAG, "prepare hook timed out");
                return 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "prepare hook failed", e);
            if (p != null) p.destroyForcibly();
            return 0;
        }
        if (got == REFUSED) {
            Log.w(TAG, fmt("VM %s: the memory hook refused the start (too little memory)", vmId));
            return REFUSED;
        }
        if (got < 256) {
            Log.w(TAG, fmt("prepare hook gave no usable size (%d); using %d MB", got, want));
            return 0;
        }
        got = Math.min(got, want);
        overrides.put(vmId.toString(), got);
        Log.i(TAG, fmt("VM %s: memory %d MB (configured %d MB)", vmId, got, want));
        return got;
    }

    /** Hands the VM's memory back, in the background. Safe to call when there is no hook. */
    public static void release(@NonNull UUID vmId) {
        overrides.remove(vmId.toString());
        var t = new Thread(() -> {
            var hook = find();
            if (hook == null) return;
            try {
                var p = new ProcessBuilder("sh", hook, "release", vmId.toString())
                    .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null"))
                    .start();
                if (!p.waitFor(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (Exception e) {
                Log.w(TAG, "release hook failed", e);
            }
        }, "MemoryHook-release");
        t.setDaemon(true);
        t.start();
    }

    /** The memory for this run: the hook's answer when there is one, else memory_mb. */
    public static long effectiveMb(@Nullable UUID vmId, @NonNull DataItem item) {
        long cfg = Math.max(item.optLong("memory_mb", 512), 64);
        if (vmId == null) return cfg;
        var o = overrides.get(vmId.toString());
        return o != null ? o : cfg;
    }

    /** Whether a hook sized this VM's current run. */
    public static boolean sized(@NonNull UUID vmId) {
        return overrides.containsKey(vmId.toString());
    }
}
