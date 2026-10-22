package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/**
 * ChromeDeck's toolbar on the phone screen, over Chrome on the phone and on the dark
 * touchpad while Chrome is on a monitor. The handle at its start drags it, by touch or
 * by an uncaptured mouse, and a tap on the handle collapses it to the handle alone.
 * Position and collapsed state are kept per mode. Touches on it do not reach Chrome.
 */
@SuppressLint("ViewConstructor")
public class FloatingToolbar extends LinearLayout {
    private static final float IDLE_ALPHA = 0.6f;
    private final SharedPreferences store;
    private final ImageButton handle;
    private final float dp;
    private String mode = "phone";
    private final Runnable fade = () -> animate().alpha(IDLE_ALPHA).setDuration(300);

    public FloatingToolbar(Context context, SharedPreferences store) {
        super(context);
        this.store = store;
        dp = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202124);
        background.setCornerRadius(24 * dp);
        setBackground(background);
        setAlpha(IDLE_ALPHA);
        handle = button(R.drawable.lorie_ic_drag, R.string.lorie_toolbar_handle);
        handle.setOnTouchListener(new HandleTouch());
        addView(handle);
        // Layout passes are reported also without a change; only a new width (folding,
        // unfolding, other buttons) places the toolbar again.
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol)
                place();
        });
    }

    private final OnLayoutChangeListener parentLayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol || b - t != ob - ot)
            place();
    };
    private boolean dragging;

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Also after the phone rotates, which does not change the toolbar's own layout.
        ((View) getParent()).addOnLayoutChangeListener(parentLayout);
    }

    @Override protected void onDetachedFromWindow() {
        ((View) getParent()).removeOnLayoutChangeListener(parentLayout);
        super.onDetachedFromWindow();
    }

    /** One of the toolbar's buttons. */
    public static final class Item {
        final int icon, description;
        final Runnable action;

        public Item(int icon, int description, Runnable action) {
            this.icon = icon;
            this.description = description;
            this.action = action;
        }
    }

    /** Shows these buttons, at the position and collapsed state kept for this mode. */
    public void show(String mode, Item... items) {
        this.mode = mode;
        removeViews(1, getChildCount() - 1);
        for (Item item : items) {
            if (item == null)
                continue;
            ImageButton button = button(item.icon, item.description);
            button.setOnClickListener(v -> {
                highlight();
                item.action.run();
            });
            addView(button);
        }
        applyCollapsed();
        place();
    }

    private ImageButton button(int icon, int description) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(getContext().getString(description));
        button.setFocusable(false);
        int pad = (int) (12 * dp);
        button.setPadding(pad, pad, pad, pad);
        return button;
    }

    private boolean collapsed() {
        return store.getBoolean("toolbarCollapsed_" + mode, false);
    }

    private void applyCollapsed() {
        for (int i = 1; i < getChildCount(); i++)
            getChildAt(i).setVisibility(collapsed() ? GONE : VISIBLE);
    }

    /**
     * Moves the toolbar to its place, kept inside its parent. The place is the handle's:
     * the toolbar unfolds from the handle, shifted only as far as the screen edge needs,
     * and the handle returns to its place when the toolbar folds. Until the user moves it,
     * it is centered, at the bottom over Chrome on the phone and at the top on the
     * touchpad.
     */
    private void place() {
        View parent = (View) getParent();
        if (parent == null || getWidth() == 0 || dragging)
            return;
        float x = store.contains("toolbarLeft_" + mode)
                ? store.getFloat("toolbarLeft_" + mode, 0) * parent.getWidth()
                : (parent.getWidth() - getWidth()) / 2f;
        float y = store.contains("toolbarTop_" + mode)
                ? store.getFloat("toolbarTop_" + mode, 0) * parent.getHeight()
                : ("phone".equals(mode) ? 0.9f : 0.06f) * parent.getHeight() - getHeight() / 2f;
        setTranslationX(clamp(x, parent.getWidth() - getWidth()));
        setTranslationY(clamp(y, parent.getHeight() - getHeight()));
    }

    private static float clamp(float value, float max) {
        return Math.max(0, Math.min(max, value));
    }

    private void highlight() {
        removeCallbacks(fade);
        animate().cancel();
        setAlpha(0.9f);
        postDelayed(fade, 2000);
    }

    private class HandleTouch implements OnTouchListener {
        private float downX, downY, startX, startY;

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouch(View v, MotionEvent e) {
            View parent = (View) getParent();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = getTranslationX();
                    startY = getTranslationY();
                    dragging = false;
                    highlight();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop())
                        dragging = true;
                    if (dragging && parent != null) {
                        setTranslationX(clamp(startX + dx, parent.getWidth() - getWidth()));
                        setTranslationY(clamp(startY + dy, parent.getHeight() - getHeight()));
                    }
                    highlight();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && parent != null) {
                        // Where the user puts it is the handle's place, also when unfolded.
                        store.edit()
                                .putFloat("toolbarLeft_" + mode, getTranslationX() / parent.getWidth())
                                .putFloat("toolbarTop_" + mode, getTranslationY() / parent.getHeight())
                                .apply();
                        dragging = false;
                    } else {
                        // The width change places it again: unfolded from the handle, or
                        // folded back to the handle's place.
                        dragging = false;
                        store.edit().putBoolean("toolbarCollapsed_" + mode, !collapsed()).apply();
                        applyCollapsed();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return false;
        }
    }
}
