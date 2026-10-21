package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Date;

/**
 * A small box in the top right corner of the monitor with the phone's battery level and
 * the time, because Chrome cannot read the battery in this environment and Android's
 * status bar is not shown on the monitor. It covers Chrome's window buttons, which have
 * no use while Chrome fills the screen.
 */
@SuppressLint("ViewConstructor")
public class MonitorStatusBar extends TextView {
    private int level = -1;
    private boolean charging;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                level = Math.round(100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) / Math.max(scale, 1));
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
            update();
        }
    };

    public MonitorStatusBar(Context context) {
        super(context);
        setTextColor(0xFFBDBDBD);
        setTypeface(Typeface.DEFAULT_BOLD);
        setGravity(Gravity.CENTER);
        setFocusable(false);
    }

    /**
     * Sets the height in dp of the display it is shown on; the text and the width follow,
     * so that it still covers Chrome's window buttons at another display scale.
     */
    public void setHeightDp(int heightDp) {
        float dp = getResources().getDisplayMetrics().density;
        float s = heightDp / 37f;
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF202124);
        background.setCornerRadii(new float[] { 0, 0, 0, 0, 0, 0, 10 * s * dp, 10 * s * dp });
        setBackground(background);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13 * s);
        // Wide enough to cover the minimize, maximize and close buttons.
        setMinWidth((int) (96 * s * dp));
        setPadding((int) (12 * s * dp), 0, (int) (12 * s * dp), 0);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != (int) (heightDp * dp)) {
            lp.height = (int) (heightDp * dp);
            setLayoutParams(lp);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // The battery broadcast is sticky, so the level is known right away.
        Intent battery = getContext().registerReceiver(receiver, filter);
        if (battery != null)
            receiver.onReceive(getContext(), battery);
        update();
    }

    @Override protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(receiver);
        super.onDetachedFromWindow();
    }

    private void update() {
        String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(new Date());
        if (level < 0) {
            setText(time);
            return;
        }
        setText((charging ? "⚡ " : "") + level + "%   " + time);
        // Low battery is shown in orange, very low in red, unless the phone is charging.
        setTextColor(charging || level > 20 ? 0xFFBDBDBD : level > 10 ? 0xFFFFA726 : 0xFFEF5350);
    }
}
