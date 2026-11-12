// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Share target "Openen in Windows": sends shared text or a link to the open Windows console. */
public final class ShareToWindowsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        CharSequence cs = i != null && Intent.ACTION_SEND.equals(i.getAction())
            ? i.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        String text = cs == null ? "" : cs.toString().trim();
        ClipboardBridge b = ClipboardBridge.getActive();
        if (text.isEmpty()) {
            // nothing to send
        } else if (b == null || !b.isConnected()) {
            Toast.makeText(this, "Windows-scherm is niet open", Toast.LENGTH_LONG).show();
        } else {
            b.sendShared(text);
            Toast.makeText(this, "Verstuurd naar Windows", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
