// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that darkens the phone screen while the browser is on a monitor. */
public class PhoneScreenOffTile extends TileService {
    @Override public void onStartListening() {
        MainActivity window = MainActivity.getFocusedOrAnyInstance();
        Tile tile = getQsTile();
        if (tile == null)
            return;
        tile.setState(window != null && window.isOnExternalDisplay() ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    @Override public void onClick() {
        Intent intent = new Intent(this, PhoneScreenOffActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        else
            startActivityAndCollapse(intent);
    }
}
