// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentCallbacks;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
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
 * <p>Images travel the same way as {@code "I " + base64(PNG) + "\n"} (max 16 MB decoded, larger
 * ones are skipped; a base64 line can thus be ~22 MB, so line limits are per record type). Phone to
 * guest: when the primary clip is an image content URI it is read, re-encoded to PNG if needed and
 * sent. Guest to phone: the PNG is written to {@code cache/clip/} and offered through
 * {@link ClipFileProvider} ({@code <applicationId>.clipprovider}) as a content-URI clip. A SHA-256
 * of the last image received/sent per side prevents echo loops.</p>
 *
 * <p>More line types share the connection:
 * {@code "S <battery%> <charging 0|1> <temp-C> <ram_avail_MiB> <ram_total_MiB>"} goes to the guest
 * every 30 s (the guest agent shows the phone's state in its tray; old guests accept the 4-field
 * form too), {@code "D <dark 0|1>"} goes to the guest on connect and whenever Android's night mode
 * changes (the guest switches Windows' light/dark theme; pref {@code display_theme_sync}), and
 * {@code "O " + base64(url)} opens that link on the phone when it comes from the guest. Phone to
 * guest, {@code O} carries a link or text shared from Android ("Openen in Windows").</p>
 */
public final class ClipboardBridge implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardBridge";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** Largest decoded image (PNG bytes) either way. */
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    /** Longest "I " line: base64 of MAX_IMAGE_BYTES plus prefix/slack. */
    private static final int MAX_IMAGE_LINE = (MAX_IMAGE_BYTES / 3 + 1) * 4 + 16;
    /** The live bridge of the open console, for the share activity (null when none). */
    @Nullable private static volatile ClipboardBridge active;

    @Nullable public static ClipboardBridge getActive() {
        return active;
    }

    private final boolean themeSync;
    private int lastDark = -1;
    private final ComponentCallbacks configWatcher = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(@NonNull Configuration c) {
            sendThemeIfChanged();
        }
        @Override public void onLowMemory() {
        }
    };

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
    /** SHA-256 (base64) of the last image from the guest / sent to the guest (any thread). */
    @Nullable private volatile String lastImageFromGuest;
    @Nullable private volatile String lastImageSent;

    public ClipboardBridge(@NonNull Context context, @NonNull String host, int port, boolean themeSync) {
        this.themeSync = themeSync;
        this.context = context;
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.host = host;
        this.port = port;
    }

    public void start() {
        if (running || clipboard == null) return;
        running = true;
        active = this;
        if (themeSync) context.registerComponentCallbacks(configWatcher);
        clipboard.addPrimaryClipChangedListener(this);
        reader = new Thread(this::loop, "ClipboardBridge");
        reader.setDaemon(true);
        reader.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (active == this) active = null;
        if (themeSync) context.unregisterComponentCallbacks(configWatcher);
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
            Uri imageUri = clip.getItemAt(0).getUri();
            if (imageUri != null && clip.getDescription() != null
                && clip.getDescription().hasMimeType("image/*")) {
                pushImage(imageUri);
                return;
            }
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

    /** Read the clipboard image off the main thread, normalise to PNG and send it as an "I " line. */
    private void pushImage(@NonNull Uri uri) {
        if (out == null) return; // the connect pushes again
        sender.execute(() -> {
            try {
                byte[] png = readImageAsPng(uri);
                if (png == null) return;
                String h = hash(png);
                if (h.equals(lastImageFromGuest) || h.equals(lastImageSent)) return;
                var o = out;
                if (o == null) return;
                lastImageSent = h;
                byte[] b64 = Base64.encode(png, Base64.NO_WRAP);
                try {
                    synchronized (o) {
                        o.write('I');
                        o.write(' ');
                        o.write(b64);
                        o.write('\n');
                        o.flush();
                    }
                } catch (Exception e) {
                    closeSocket();
                }
            } catch (SecurityException e) {
                // not focused after all
            } catch (Throwable e) {
                Log.w(TAG, "clipboard image not sent", e);
            }
        });
    }

    /** The image behind the URI as PNG bytes, or null when too large / unreadable. */
    @Nullable
    private byte[] readImageAsPng(@NonNull Uri uri) throws IOException {
        byte[] data;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            var bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_IMAGE_BYTES) return null; // too large
            }
            data = bos.toByteArray();
        }
        if (isPng(data, data.length)) return data;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) return null;
        try {
            var bos = new ByteArrayOutputStream();
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) return null;
            return bos.size() > MAX_IMAGE_BYTES ? null : bos.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    private static boolean isPng(@NonNull byte[] d, int len) {
        return len > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    @NonNull
    private static String hash(@NonNull byte[] d) {
        try {
            return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(d), Base64.NO_WRAP);
        } catch (Exception e) {
            return "len" + d.length;
        }
    }

    /** PNG from the guest: store under cache/clip/ and put a content-URI clip on the clipboard. */
    private void receiveImage(@NonNull byte[] png) {
        String h = hash(png);
        if (h.equals(lastImageFromGuest)) return;
        File dir = new File(context.getCacheDir(), "clip");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "windows-" + System.currentTimeMillis() + ".png");
        try (var fo = new FileOutputStream(f)) {
            fo.write(png);
        } catch (IOException e) {
            Log.w(TAG, "cannot store guest image", e);
            return;
        }
        final Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".clipprovider", f);
        lastImageFromGuest = h;
        lastImageSent = h;
        main.post(() -> {
            try {
                clipboard.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "Windows", uri));
            } catch (Exception e) {
                Log.w(TAG, "cannot set image clip", e);
            }
        });
    }

    /** One line (without the newline); empty array for an over-long line (skipped); null at EOF. */
    @Nullable
    private static byte[] readLine(@NonNull InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream(256);
        boolean overflow = false, any = false;
        int c;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (overflow) continue;
            if (buf.size() >= MAX_IMAGE_LINE) {
                overflow = true;
                buf.reset();
            } else buf.write(c);
        }
        if (!any) return null;
        return overflow ? new byte[0] : buf.toByteArray();
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
                    lastImageSent = null;
                    lastDark = -1;
                    pushAndroidClipboard();
                    main.removeCallbacks(statusTick);
                    sendStatusAndReschedule();
                });
                var in = new BufferedInputStream(s.getInputStream(), 65536);
                byte[] line;
                while (running && (line = readLine(in)) != null) {
                    int len = line.length;
                    if (len < 3 || line[1] != ' ') continue;
                    if (line[0] == 'O' && len < 16384) {
                        openOnPhone(new String(Base64.decode(line, 2, len - 2, Base64.DEFAULT), UTF_8));
                    } else if (line[0] == 'I') {
                        byte[] png = Base64.decode(line, 2, len - 2, Base64.DEFAULT);
                        if (png.length <= MAX_IMAGE_BYTES && isPng(png, png.length)) receiveImage(png);
                    } else if (line[0] == 'T' && len <= MAX_BYTES * 2) {
                        final String text = new String(Base64.decode(line, 2, len - 2, Base64.DEFAULT), UTF_8);
                        main.post(() -> {
                            lastFromGuest = text;
                            lastSent = text;
                            clipboard.setPrimaryClip(ClipData.newPlainText("Windows", text));
                        });
                    }
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
        sendThemeIfChanged();
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0); // tenths of a degree
            int pct = scale > 0 && level >= 0 ? level * 100 / scale : -1;
            long availMiB = 0, totalMiB = 0;
            var am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                var mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                availMiB = mi.availMem >> 20;
                totalMiB = mi.totalMem >> 20;
            }
            sendLine(String.format(java.util.Locale.ROOT, "S %d %d %.1f %d %d\n",
                pct, plugged != 0 ? 1 : 0, temp / 10.0, availMiB, totalMiB));
        }
        main.postDelayed(statusTick, STATUS_PERIOD_MS);
    }

    /** Android night mode to the guest, on connect and when it changes. Main thread. */
    private void sendThemeIfChanged() {
        if (!themeSync || !running) return;
        int dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES ? 1 : 0;
        if (dark == lastDark || out == null) return;
        lastDark = dark;
        sendLine("D " + dark + "\n");
    }

    /** A link or text shared from Android, for the Windows side ("O " line). */
    public void sendShared(@NonNull String text) {
        if (text.getBytes(UTF_8).length > 8000) return;
        sendLine("O " + Base64.encodeToString(text.getBytes(UTF_8), Base64.NO_WRAP) + "\n");
    }

    public boolean isConnected() {
        return out != null;
    }

    private void sendLine(@NonNull String line) {
        sender.execute(() -> {
            var o = out;
            if (o == null) return;
            try {
                synchronized (o) {
                    o.write(line.getBytes(UTF_8));
                    o.flush();
                }
            } catch (Exception e) {
                closeSocket();
            }
        });
    }

    /**
     * A link the guest asked to open on the phone: web and mail links, or android-app://&lt;package&gt;,
     * which starts that installed app's launcher activity (the Windows Start menu "Android apps" entries),
     * or droidvm://minimize, which sends the VM window to the back.
     * Never other schemes or arbitrary intents.
     */
    private void openOnPhone(@NonNull String url) {
        var u = Uri.parse(url.trim());
        var scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme.equals("droidvm") && "minimize".equals(u.getHost())) {
            // Windows shortcut "DroidVM minimaliseren": send the VM window to the back, like the
            // minimize-to-Android button of the Linux desktop. The VM keeps running.
            main.post(() -> {
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).moveTaskToBack(true);
                }
            });
            return;
        }
        if (scheme.equals("android-app")) {
            var pkg = u.getHost();
            if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return;
            main.post(() -> {
                try {
                    var launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch == null) {
                        Log.w(TAG, "no launcher activity for " + pkg);
                        return;
                    }
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    var opts = android.app.ActivityOptions.makeBasic();
                    try {
                        var d = context.getDisplay();
                        if (d != null) opts.setLaunchDisplayId(d.getDisplayId());
                    } catch (Exception ignored) {
                        // non-visual context: Android picks the display
                    }
                    context.startActivity(launch, opts.toBundle());
                } catch (Exception e) {
                    Log.w(TAG, "could not start " + pkg, e);
                }
            });
            return;
        }
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
