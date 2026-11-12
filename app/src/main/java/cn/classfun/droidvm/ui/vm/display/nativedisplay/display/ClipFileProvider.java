// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import androidx.core.content.FileProvider;

/**
 * Serves clipboard images from the guest (cache/clip/) to the apps that paste them. A separate
 * subclass because a manifest cannot declare the same provider class under two authorities.
 */
public final class ClipFileProvider extends FileProvider {
}
