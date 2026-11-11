// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
 */
public final class ClipboardBridge implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardBridge";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final ClipboardManager clipboard;
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
                main.post(this::pushAndroidClipboard);
                var in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
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

    private void closeSocket() {
        var s = socket;
        out = null;
        if (s != null) try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
