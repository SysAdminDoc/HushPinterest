/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;
import app.hushpinterest.extension.shared.settings.BooleanSetting;

/**
 * Hide closeup buttons: the back arrow and the visual search (lens) button that float over a pin's
 * closeup. The patch hands each button over where Pinterest finds or builds it, and sends every
 * visibility change of those two view classes through {@link #visibility}. A handed-over button
 * whose switch is on stays GONE, so it takes no room and TalkBack skips it. Off or paused,
 * Pinterest's own visibility goes through, and the next closeup's button shows as usual. The
 * lens's label has a switch of its own, see {@link #searchLabel}.
 */
public final class CloseupButtons {
    private CloseupButtons() {}

    /** Each button handed over, and whether it's the back arrow (true) or the lens (false). */
    private static final Map<View, Boolean> MARKED = Collections.synchronizedMap(new WeakHashMap<>());

    /** The closeup's back arrow, right after Pinterest finds it in the closeup's layout. */
    public static void backArrow(View button) {
        mark(button, true);
    }

    /** The visual search button, right after Pinterest builds it and gives it its id. */
    public static void lens(View button) {
        mark(button, false);
    }

    private static void mark(View button, boolean back) {
        if (button == null) return;
        try {
            MARKED.put(button, back);
            if (UiHooks.enabled(FamilyNames.HIDE_CLOSEUP_BUTTONS, setting(back))) {
                button.setVisibility(View.GONE);
                HookStatus.counted(FamilyNames.HIDE_CLOSEUP_BUTTONS, back ? "back arrow hidden" : "visual search hidden");
            }
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.HIDE_CLOSEUP_BUTTONS, back ? "back arrow" : "visual search", failure);
        }
    }

    /**
     * Prepended to setVisibility of both button classes. Pinterest shows the back arrow again as the
     * closeup scrolls, and GestaltIconButton serves every icon button in the app, so only a button
     * handed over above is held at GONE, and only while its switch is on.
     */
    public static int visibility(View view, int requested) {
        if (requested == View.GONE || MARKED.isEmpty()) return requested;
        Boolean back = MARKED.get(view);
        return back != null && staysHidden(back) ? View.GONE : requested;
    }

    /**
     * The lens's Search image label, read where the lens decides to wear it and where it plays the
     * animation that shows it, and the lens's glow, read where it's built. Pinterest raises the label
     * flag on the first two eligible pins of a day and drops it after, so with the switch on every
     * pin's lens looks the way the third one does: unlabeled, still there and still searching. The
     * glow has a flag of its own, answered the same way.
     */
    public static boolean searchLabel(boolean shown) {
        if (!shown) return false;
        try {
            if (!UiHooks.enabled(FamilyNames.HIDE_CLOSEUP_BUTTONS, Settings.HIDE_CLOSEUP_SEARCH_LABEL)) return true;
            HookStatus.counted(FamilyNames.HIDE_CLOSEUP_BUTTONS, "search image label left off");
            return false;
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.HIDE_CLOSEUP_BUTTONS, "search image label", failure);
            return true;
        }
    }

    private static BooleanSetting setting(boolean back) {
        return back ? Settings.HIDE_CLOSEUP_BACK : Settings.HIDE_CLOSEUP_LENS;
    }

    /** The switch read again for a visibility change, which isn't counted as another call. */
    private static boolean staysHidden(boolean back) {
        try {
            return Utils.settingsReady() && setting(back).get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CLOSEUP_BUTTONS, "switch read", failure);
            return false;
        }
    }
}
