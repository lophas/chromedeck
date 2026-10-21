// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app icon. It opens the browser on a connected external display when that is
 * enabled. While the browser is on an external display, the phone takes input focus
 * whenever it is used; opening the app then returns focus to that window instead of
 * moving it to the phone.
 */
public class LauncherActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MainActivity window = MainActivity.getFocusedOrAnyInstance();
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int external = MainActivity.externalDisplayId(this);
        if (window != null && window.isOnExternalDisplay())
            startActivity(intent, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(window.getWindowManager().getDefaultDisplay().getDisplayId()).toBundle());
        else if (window == null && external != android.view.Display.INVALID_DISPLAY
                && ((LorieApp) getApplication()).builtInPrefs.autoExternalDisplay.get())
            startActivity(intent, MainActivity.launchOptions(external));
        else
            startActivity(intent);
        finish();
    }
}
