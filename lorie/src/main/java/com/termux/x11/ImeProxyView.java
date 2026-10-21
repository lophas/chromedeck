package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * While the display view is on a monitor, this tiny view in the phone window holds input
 * focus for the keyboards and the captured mouse, and passes on-screen keyboard input to
 * the display view's input connection.
 */
@SuppressLint("ViewConstructor")
public class ImeProxyView extends View {
    private final LorieView target;

    public ImeProxyView(Context context, LorieView target) {
        super(context);
        this.target = target;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return target.onCreateInputConnection(outAttrs);
    }
}
