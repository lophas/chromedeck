package com.termux.x11;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** The Automatic choice of Preferences → Browser → Display scale. */
public final class AutoScale {
    private AutoScale() {}

    /**
     * On the phone: Android's density already sizes text for this phone and the user's
     * display size setting, and is how Chrome for Android scales pages; a little less
     * suits Chrome's desktop interface (250% at 480 dpi). Pages can be zoomed further
     * in Chrome.
     */
    public static float phone(Context context) {
        return round(context.getResources().getDisplayMetrics().density * 0.85f);
    }

    /**
     * On a monitor, from its resolution: 125% at 1080p, 150% at 1440p, 200% at 2160p.
     * Monitors often report wrong physical sizes, so their density is not used.
     */
    public static float monitor(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        return round(0.5f + 0.75f * Math.min(size.x, size.y) / 1080f);
    }

    /** The connected monitor, or null. */
    public static Display monitorDisplay(Context context) {
        int id = MainActivity.externalDisplayId(context);
        return id == Display.INVALID_DISPLAY ? null
                : ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(id);
    }

    /** As the setting shows it, such as 275%. */
    public static String percent(float scale) {
        return Math.round(scale * 100) + "%";
    }

    /** To the 25% steps of the setting, between 100% and 400%. */
    private static float round(float scale) {
        return Math.max(1f, Math.min(4f, Math.round(scale * 4) / 4f));
    }
}
