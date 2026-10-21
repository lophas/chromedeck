// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that turns the phone screen off while Chrome is on a monitor. */
public class PhoneScreenOffTile extends TileService {
    @Override public void onStartListening() {
        MainActivity window = MainActivity.getFocusedOrAnyInstance();
        Tile tile = getQsTile();
        if (tile == null)
            return;
        if (window == null || !(window.isOnExternalDisplay() || window.isPresenting()))
            tile.setState(Tile.STATE_UNAVAILABLE);
        else
            tile.setState(window.isPhoneScreenOff() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    @Override public void onClick() {
        MainActivity window = MainActivity.getFocusedOrAnyInstance();
        if (window != null && window.isPresenting()) {
            if (window.isPhoneScreenOff())
                window.togglePhoneScreenOff();
            else if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 1,
                        new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE));
            else
                startActivityAndCollapse(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent intent = new Intent(this, PhoneScreenOffActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        else
            startActivityAndCollapse(intent);
    }
}
