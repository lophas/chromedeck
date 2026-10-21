// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * The app icon. On the first start with Preferences → Browser → Phone storage on (the
 * default), it asks for the storage permission before Chrome starts, and turns the
 * setting off if it is declined. If the browser window itself is on an external
 * display, opening the app returns input focus to it there.
 */
public class LauncherActivity extends Activity {
    private static final int STORAGE_REQUEST = 1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs prefs = ((LorieApp) getApplication()).builtInPrefs;
        if (prefs.phoneStorage.get()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
            return;
        }
        openBrowser();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != STORAGE_REQUEST)
            return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            ((LorieApp) getApplication()).builtInPrefs.phoneStorage.put(false);
        openBrowser();
    }

    private void openBrowser() {
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
