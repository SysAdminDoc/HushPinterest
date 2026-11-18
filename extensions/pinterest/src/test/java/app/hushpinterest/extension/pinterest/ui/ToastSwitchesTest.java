/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.SettingsContextRule;

/** The toast Pinterest shows after a download, and the black paint for every toast (#42). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ToastSwitchesTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @After public void restore() {
        Settings.HIDE_DOWNLOAD_TOASTS.resetToDefault();
        Settings.HIDE_SAVE_TOASTS.resetToDefault();
        Settings.BLACK_TOASTS.resetToDefault();
        UiHooks.downloadToastForTests = null;
        UiHooks.saveToastForTests = null;
    }

    @Test public void theDownloadToastGoesOnlyWithItsOwnSwitch() {
        UiHooks.downloadToastForTests = StringBuilder.class;
        assertFalse("starts off", UiHooks.hideSaveToast(new StringBuilder()));
        Settings.HIDE_SAVE_TOASTS.save(true);
        assertFalse("Hide save toasts leaves it", UiHooks.hideSaveToast(new StringBuilder()));
        Settings.HIDE_SAVE_TOASTS.save(false);
        Settings.HIDE_DOWNLOAD_TOASTS.save(true);
        assertTrue(UiHooks.hideSaveToast(new StringBuilder()));
        assertFalse("another toast stays", UiHooks.hideSaveToast("Moved Pin"));
    }

    @Test public void aSaveToastStaysWhenOnlyDownloadsAreHidden() {
        UiHooks.saveToastForTests = StringBuilder.class;
        UiHooks.downloadToastForTests = Integer.class;
        Settings.HIDE_DOWNLOAD_TOASTS.save(true);
        assertFalse(UiHooks.hideSaveToast(new StringBuilder()));
    }

    @Test public void aToastTurnsBlackWithWhiteWordsWhileAButtonAndPictureKeepTheirs() {
        Settings.BLACK_TOASTS.save(true);
        Toast toast = new Toast(true);
        AmoledBlack.toast(toast.root);
        assertEquals(Color.BLACK, toast.fill.getColor().getDefaultColor());
        assertEquals(Color.WHITE, toast.words.getCurrentTextColor());
        assertEquals("the action keeps its color", Color.RED, toast.action.getCurrentTextColor());
        assertNull("a plain picture isn't tinted", toast.picture.getImageTintList());
    }

    @Test public void offOrInLightModeTheToastStaysAsPinterestBuiltIt() {
        Toast off = new Toast(true);
        AmoledBlack.toast(off.root);
        assertEquals(Color.WHITE, off.fill.getColor().getDefaultColor());
        Settings.BLACK_TOASTS.save(true);
        Toast light = new Toast(false);
        AmoledBlack.toast(light.root);
        assertEquals(Color.WHITE, light.fill.getColor().getDefaultColor());
        assertEquals(Color.DKGRAY, light.words.getCurrentTextColor());
    }

    private static final class Toast {
        final LinearLayout root;
        final GradientDrawable fill = new GradientDrawable();
        final TextView words;
        final Button action;
        final ImageView picture;

        Toast(boolean night) {
            Context base = RuntimeEnvironment.getApplication();
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.uiMode = (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO)
                    | (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
            Context themed = base.createConfigurationContext(config);
            root = new LinearLayout(themed);
            fill.setColor(ColorStateList.valueOf(Color.WHITE));
            root.setBackground(fill);
            FrameLayout content = new FrameLayout(themed);
            words = new TextView(themed);
            words.setTextColor(Color.DKGRAY);
            action = new Button(themed);
            action.setTextColor(Color.RED);
            picture = new ImageView(themed);
            content.addView(words);
            content.addView(picture);
            root.addView(content);
            root.addView(action);
        }
    }
}
