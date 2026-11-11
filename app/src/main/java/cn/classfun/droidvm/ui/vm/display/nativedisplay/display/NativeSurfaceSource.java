// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import java.util.function.Supplier;

import cn.classfun.droidvm.ui.vm.display.base.DisplaySource;

/**
 * {@link DisplaySource} for the native display path: crosvm renders straight into the
 * SurfaceView's Surface via the per-VM ICrosvmAndroidDisplayService. Wraps {@link DisplayProvider}
 * behind the pluggable source interface, so a future source (e.g. zero-copy AHardwareBuffer)
 * slots into the same console. Frames start flowing as soon as the display-binder supplier can
 * resolve the service, so {@link #start()} is a no-op. No guest-resize channel on this path yet.
 */
public final class NativeSurfaceSource implements DisplaySource {
    private final DisplayProvider provider;
    private final Handler mainHandler;
    /** Sends a size to the VM (vm_gpu_resize); null on screens without a resizable display. */
    private java.util.function.BiConsumer<Integer, Integer> guestResizer;
    private int pendingW, pendingH, sentW, sentH;
    private final Runnable sendResize = this::flushResize;
    /** Area changes arrive per layout pass while a window is dragged; send the size it settles on. */
    private static final long RESIZE_SETTLE_MS = 350;

    /**
     * @param displayBinderSupplier resolves the per-VM display binder (blocking, called off the
     *                              main thread); null while the daemon broker isn't attached yet.
     */
    public NativeSurfaceSource(@NonNull SurfaceView surfaceView, int guestWidth, int guestHeight,
                               @NonNull Supplier<IBinder> displayBinderSupplier,
                               @NonNull Handler mainHandler, @NonNull Callbacks callbacks) {
        this.mainHandler = mainHandler;
        provider = new DisplayProvider(surfaceView, guestWidth, guestHeight, displayBinderSupplier,
            connected -> mainHandler.post(() -> callbacks.onStateChanged(
                connected ? State.CONNECTED : State.CONNECTING)),
            config -> mainHandler.post(() -> callbacks.onContentSize(config.width, config.height)));
    }

    /**
     * Follow the guest cursor. Positions are GUEST framebuffer coordinates, delivered on the main
     * thread, and arrive only on this display path -- the VNC path has no such channel.
     *
     * The caller decides what to do with them, and in particular whether to act at all: a cursor
     * move caused by a remote desktop session is indistinguishable here from one the user made,
     * so gating on "the user is currently driving the pointer" belongs to the consumer.
     */
    @Override
    public void setCursorView(android.view.SurfaceView cursorView) {
        provider.setCursorView(cursorView);
    }

    @Override
    public void setCursorListener(java.util.function.BiConsumer<Integer, Integer> listener) {
        provider.setCursorListener(listener == null ? null : listener::accept);
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
        mainHandler.removeCallbacks(sendResize);
        provider.shutdown();
    }

    /** Lets the guest display follow the window (virtio-gpu screens only). */
    public void setGuestResizer(java.util.function.BiConsumer<Integer, Integer> resizer) {
        guestResizer = resizer;
    }

    @Override
    public boolean supportsGuestResize() {
        return guestResizer != null;
    }

    @Override
    public void requestGuestResize(int width, int height) {
        if (guestResizer == null || width <= 0 || height <= 0) return;
        // Even sizes in steps of 8: what guests' mode tables and scanout strides like best.
        pendingW = Math.max(640, width & ~7);
        pendingH = Math.max(480, height & ~7);
        mainHandler.removeCallbacks(sendResize);
        mainHandler.postDelayed(sendResize, RESIZE_SETTLE_MS);
    }

    private void flushResize() {
        if (guestResizer == null || (pendingW == sentW && pendingH == sentH)) return;
        sentW = pendingW;
        sentH = pendingH;
        guestResizer.accept(sentW, sentH);
    }
}
