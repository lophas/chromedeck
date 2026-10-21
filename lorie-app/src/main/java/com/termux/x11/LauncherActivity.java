// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app icon. While the browser is on an external display, the phone keeps input
 * focus whenever it is used, and the display window loses the mouse and keyboard.
 * Opening the app then returns focus to that window instead of moving it to the phone.
 */
public class LauncherActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MainActivity window = MainActivity.getFocusedOrAnyInstance();
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (window != null && window.isOnExternalDisplay())
            startActivity(intent, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(window.getWindowManager().getDefaultDisplay().getDisplayId()).toBundle());
        else
            startActivity(intent);
        finish();
    }
}
