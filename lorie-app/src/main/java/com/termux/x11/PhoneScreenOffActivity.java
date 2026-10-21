// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/**
 * A black cover for the phone screen while the browser is on an
 * external display, opened from the notification or the Quick Settings tile. The
 * external display sleeps together with the phone, so the device stays awake; this
 * keeps the phone's own screen dark meanwhile; black pixels stay off on an OLED
 * screen. Tap to close.
 */
public class PhoneScreenOffActivity extends Activity {
    private static WeakReference<PhoneScreenOffActivity> current = new WeakReference<>(null);
    private boolean touching;
    private float downX, downY, touchSlop;

    static void dismiss() {
        PhoneScreenOffActivity activity = current.get();
        if (activity != null)
            activity.finish();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        TextView hint = new TextView(this);
        hint.setText("ChromeDeck is on the external display.\nTap to turn the phone screen on.");
        hint.setTextColor(Color.rgb(40, 40, 40));
        hint.setBackgroundColor(Color.BLACK);
        hint.setGravity(Gravity.CENTER);
        hint.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    touching = false;
                    // A tap closes the cover; a swipe, such as opening the shade, does not.
                    if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) < touchSlop)
                        finish();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    break;
            }
            return true;
        });
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        setContentView(hint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    /**
     * Input focus comes here when the cover opens and when the notification shade
     * closes over it. Give it back to the browser window on the external display, so
     * that the mouse and keyboard return there.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus)
            return;
        // Touching the cover also brings focus here; wait so a tap can close it first.
        getWindow().getDecorView().postDelayed(() -> {
            int external = MainActivity.externalDisplayId(this);
            if (!touching && !isFinishing() && hasWindowFocus() && external != Display.INVALID_DISPLAY)
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ActivityOptions.makeBasic().setLaunchDisplayId(external).toBundle());
        }, 300);
    }

    @Override protected void onDestroy() {
        if (current.get() == this)
            current = new WeakReference<>(null);
        super.onDestroy();
    }
}
