// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.vm.display.nativedisplay.display.VMNativeDisplayActivity;

/**
 * Samsung DeX / Android take Alt+Tab, Alt+F4, Ctrl+Esc, Alt+Esc and Meta chords before an app can
 * see them. An accessibility service with key-event filtering sees every hardware key first; while
 * the VM console window has focus it consumes the key here and feeds it to the console, which
 * sends it to the guest through the same path as dispatchKeyEvent. A consumed key never reaches
 * the activity, so nothing is sent twice. Without a focused, connected console (or with the
 * "display_key_intercept" pref off) every key is passed on untouched.
 */
public final class GuestKeyInterceptor extends AccessibilityService {
    public static final String PREF_KEY = "display_key_intercept";
    private static final String PREFS = "droidvm_prefs";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        var activity = VMNativeDisplayActivity.getFocusedConsole();
        if (activity == null || !activity.acceptsInterceptedKeys()) return false;
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_KEY, true)) return false;
        int code = event.getKeyCode();
        // Keys Android must keep: volume, Back (also synthesized from mouse right-click), power.
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
            || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
            || code == KeyEvent.KEYCODE_POWER) return false;
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) != 0) return false;
        var copy = new KeyEvent(event);
        main.post(() -> deliver(copy));
        return true;
    }

    private static void deliver(@NonNull KeyEvent event) {
        var activity = VMNativeDisplayActivity.getFocusedConsole();
        if (activity != null) activity.injectInterceptedKey(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
