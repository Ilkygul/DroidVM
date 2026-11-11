// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.vm;

import static android.net.LocalSocketAddress.Namespace.FILESYSTEM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/**
 * vm_gpu_resize: tell crosvm's virtio-gpu display a new size (GpuControlCommand::ResizeDisplay).
 * The guest gets a display config-change event and modesets to it itself; the native display
 * view uses this to make the guest follow the size of its window.
 */
@AutoService(RequestHandler.class)
public final class GpuResizeHandler extends RequestHandler {
    private static final String TAG = "GpuResizeHandler";

    @NonNull
    @Override
    public String getName() {
        return "vm_gpu_resize";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        int width = params.optInt("width", 0);
        int height = params.optInt("height", 0);
        int displayId = params.optInt("display_id", 0);
        // Density for the EDID physical size; the guest derives its UI scale from it (0 = keep).
        int dpi = params.optInt("dpi", 0);
        if (width < 320 || height < 200 || width > 7680 || height > 4320)
            throw new RequestException(fmt("unsupported size %dx%d", width, height));
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException(fmt("VM not found: %s", vmId));
        var socketPath = inst.getControlSocketPath();
        if (socketPath == null)
            throw new RequestException("VM has no control socket");
        var json = fmt(
            "{\"GpuCommand\":{\"ResizeDisplay\":{\"display_id\":%d,\"width\":%d,\"height\":%d,\"dpi\":%s}}}",
            displayId, width, height, dpi >= 48 && dpi <= 1200 ? String.valueOf(dpi) : "null");
        String response;
        try (var socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
            socket.connect(new LocalSocketAddress(socketPath, FILESYSTEM));
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(json.getBytes(UTF_8));
            var buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            response = n > 0 ? new String(buf, 0, n, UTF_8) : "";
        }
        Log.i(TAG, fmt("resize %s display %d to %dx%d @%ddpi -> %s", vmId, displayId, width, height, dpi, response));
        if (!response.contains("DisplaysUpdated"))
            throw new RequestException(fmt("crosvm refused the resize: %s", response));
        var res = request.res();
        res.put("width", width);
        res.put("height", height);
    }
}
