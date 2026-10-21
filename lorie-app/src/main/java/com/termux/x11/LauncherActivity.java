// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app icon. If the browser window itself is on an external display, opening the app
 * returns input focus to it there instead of moving it to the phone.
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
