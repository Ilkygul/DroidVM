// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

/**
 * Samsung DeX keeps the Meta key (Windows / Cmd) for itself: a tap opens the DeX menu and Meta
 * chords run DeX actions, so the guest never sees its Start key. Samsung's
 * {@code SemWindowManager.requestMetaKeyEvent} hands Meta and its chords to the requesting window
 * instead (Termux:X11 uses the same call). Requested while the console has focus, given back when
 * it loses it. Elsewhere than on a Samsung build the class is missing and this does nothing.
 */
final class DexMetaKey {
    private static final String TAG = "DexMetaKey";
    private static Object manager;
    private static Method request;

    static {
        try {
            Class<?> c = Class.forName("com.samsung.android.view.SemWindowManager");
            manager = c.getMethod("getInstance").invoke(null);
            request = c.getDeclaredMethod("requestMetaKeyEvent", android.content.ComponentName.class, boolean.class);
        } catch (Throwable t) {
            manager = null;
            request = null;
        }
    }

    private DexMetaKey() {
    }

    static void set(@NonNull Activity activity, boolean captured) {
        if (manager == null || request == null) return;
        try {
            request.invoke(manager, activity.getComponentName(), captured);
        } catch (Throwable t) {
            Log.w(TAG, "requestMetaKeyEvent(" + captured + ") failed", t);
        }
    }
}
