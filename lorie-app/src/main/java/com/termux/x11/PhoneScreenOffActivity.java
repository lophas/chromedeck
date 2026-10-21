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
import android.view.WindowManager;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/**
 * A black, minimum-brightness cover for the phone screen while the browser is on an
 * external display, opened from the notification or the Quick Settings tile. The
 * external display sleeps together with the phone, so the device stays awake; this
 * keeps the phone's own screen dark meanwhile. Tap to close.
 */
public class PhoneScreenOffActivity extends Activity {
    private static WeakReference<PhoneScreenOffActivity> current = new WeakReference<>(null);

    static void dismiss() {
        PhoneScreenOffActivity activity = current.get();
        if (activity != null)
            activity.finish();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        // Keep keyboard focus on the browser window on the external display.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = 0.0f;
        getWindow().setAttributes(attributes);
        TextView hint = new TextView(this);
        hint.setText("Desktop Chrome is on the external display.\nTap to turn the phone screen on.");
        hint.setTextColor(Color.rgb(40, 40, 40));
        hint.setBackgroundColor(Color.BLACK);
        hint.setGravity(Gravity.CENTER);
        hint.setOnClickListener(view -> finish());
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

    @Override protected void onResume() {
        super.onResume();
        // Starting this cover moves input focus to the phone screen; give it back.
        int external = MainActivity.externalDisplayId(this);
        if (external != Display.INVALID_DISPLAY)
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(external).toBundle());
    }

    @Override protected void onDestroy() {
        if (current.get() == this)
            current = new WeakReference<>(null);
        super.onDestroy();
    }
}
