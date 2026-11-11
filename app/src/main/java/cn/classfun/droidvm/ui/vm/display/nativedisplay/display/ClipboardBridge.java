// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shares text between the Android clipboard and a guest clipboard agent (Windows: clipsync.exe)
 * over TCP on the VM's NAT network. One line per clipboard text in each direction:
 * {@code "T " + base64(UTF-8) + "\n"}.
 *
 * <p>Android only lets the focused app read the clipboard, which is exactly when this console is
 * in use: the Android clipboard goes to the guest when the window gains focus and whenever it
 * changes while focused. Guest copies arrive any time the connection is up and are put on the
 * Android clipboard. Each side remembers what it last received so an update never echoes back.</p>
 *
 * <p>Two more line types share the connection: {@code "S <battery%> <charging 0|1> <temp-C>"} goes
 * to the guest every 30 s (the guest agent shows the phone's state in its tray, as the Linux top
 * bar did), and {@code "O " + base64(url)} from the guest opens that link on the phone.</p>
 */
public final class ClipboardBridge implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardBridge";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final ClipboardManager clipboard;
    private final Context context;
    private final Runnable statusTick = this::sendStatusAndReschedule;
    private static final long STATUS_PERIOD_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final String host;
    private final int port;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream out;
    private Thread reader;
    /** Last text that came from the guest (main thread). */
    @Nullable private String lastFromGuest;
    /** Last text sent to the guest (main thread). */
    @Nullable private String lastSent;

    public ClipboardBridge(@NonNull Context context, @NonNull String host, int port) {
        this.context = context;
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.host = host;
        this.port = port;
    }

    public void start() {
        if (running || clipboard == null) return;
        running = true;
        clipboard.addPrimaryClipChangedListener(this);
        reader = new Thread(this::loop, "ClipboardBridge");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(statusTick);
        clipboard.removePrimaryClipChangedListener(this);
        closeSocket();
        if (reader != null) reader.interrupt();
    }

    /** The console window gained focus: the Android clipboard is readable now. */
    public void onFocus() {
        pushAndroidClipboard();
    }

    @Override
    public void onPrimaryClipChanged() {
        pushAndroidClipboard();
    }

    private void pushAndroidClipboard() {
        if (!running || clipboard == null) return;
        String text;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence cs = clip.getItemAt(0).coerceToText(null);
            if (cs == null) return;
            text = cs.toString();
        } catch (SecurityException e) {
            return; // not focused after all
        }
        if (text.equals(lastFromGuest) || text.equals(lastSent)) return;
        if (text.getBytes(UTF_8).length > MAX_BYTES) return;
        // Not connected yet: leave lastSent alone, the connect sends the clipboard again.
        if (out == null) return;
        lastSent = text;
        final String line = "T " + Base64.encodeToString(text.getBytes(UTF_8), Base64.NO_WRAP) + "\n";
        sender.execute(() -> {
            var o = out;
            if (o == null) return;
            try {
                o.write(line.getBytes(UTF_8));
                o.flush();
            } catch (Exception e) {
                closeSocket();
            }
        });
    }

    private void loop() {
        while (running) {
            try (var s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                socket = s;
                out = s.getOutputStream();
                Log.i(TAG, "connected to guest clipboard at " + host + ":" + port);
                main.post(() -> {
                    lastSent = null; // a fresh guest session has nothing of ours yet
                    pushAndroidClipboard();
                    main.removeCallbacks(statusTick);
                    sendStatusAndReschedule();
                });
                var in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("O ") && line.length() < 16384) {
                        openOnPhone(new String(Base64.decode(line.substring(2), Base64.DEFAULT), UTF_8));
                        continue;
                    }
                    if (!line.startsWith("T ") || line.length() > MAX_BYTES * 2) continue;
                    final String text = new String(Base64.decode(line.substring(2), Base64.DEFAULT), UTF_8);
                    main.post(() -> {
                        lastFromGuest = text;
                        lastSent = text;
                        clipboard.setPrimaryClip(ClipData.newPlainText("Windows", text));
                    });
                }
            } catch (Exception e) {
                // guest agent not up (yet): try again in a moment
            } finally {
                out = null;
                socket = null;
            }
            if (!running) break;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Battery and temperature to the guest, then again in 30 s while connected. */
    private void sendStatusAndReschedule() {
        if (!running) return;
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0); // tenths of a degree
            int pct = scale > 0 && level >= 0 ? level * 100 / scale : -1;
            sendLine(String.format(java.util.Locale.ROOT, "S %d %d %.1f\n", pct, plugged != 0 ? 1 : 0, temp / 10.0));
        }
        main.postDelayed(statusTick, STATUS_PERIOD_MS);
    }

    private void sendLine(@NonNull String line) {
        sender.execute(() -> {
            var o = out;
            if (o == null) return;
            try {
                o.write(line.getBytes(UTF_8));
                o.flush();
            } catch (Exception e) {
                closeSocket();
            }
        });
    }

    /** A link the guest asked to open on the phone; only web and mail links, never other schemes. */
    private void openOnPhone(@NonNull String url) {
        var u = Uri.parse(url.trim());
        var scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            Log.w(TAG, "guest asked to open a non-web link; ignored");
            return;
        }
        main.post(() -> {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "no app to open " + scheme + " link", e);
            }
        });
    }

    private void closeSocket() {
        var s = socket;
        out = null;
        if (s != null) try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
