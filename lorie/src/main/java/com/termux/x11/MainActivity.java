package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.PictureInPictureParams;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.math.MathUtils;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.extrakeys.ExtraKeysInfo;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.ImeHeightProvider;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.TreeSet;

@Keep @SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    public static Handler handler = new Handler();
    private final Runnable connectRetry = this::tryConnect;
    FrameLayout frm;
    TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private DisplayManager displayManager;
    private boolean showIMEWhileExternalConnected = true;
    private boolean externalKeyboardConnected = false;
    // A manual toggle can override auto-hide until the hardware connection changes.
    private Boolean hardwareExtraKeysOverride = null;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;
    /** The display the system letterboxed us on instead of rotating, {@code null} until it does. */
    private Rect orientationDeniedAt = null;
    private String screenIdleTimeoutArmedMode = null; // numeric screenIdleTimeout mode the pending idle check reflects, or null if none pending
    private final Runnable screenIdleTimeoutCheck = this::checkScreenIdleTimeout;
    /** Aspect ratios outside of the range the device is configured with are rejected by the system. */
    private static final float MIN_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMinAspectRatio", 1.f / 2.39f);
    private static final float MAX_PIP_ASPECT_RATIO = getSystemDimenFloat("config_pictureInPictureMaxAspectRatio", 2.39f);

    public Prefs prefs;
    LorieApp app;

    private boolean oldFullscreen = false, oldHideCutout = false;
    private LorieView mLorieView;
    private Presentation presentation;
    private MonitorStatusBar monitorStatusBar;
    private ImeProxyView imeProxy;
    private OrientationEventListener orientationListener;

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!getLorieView().connected())
                return false;

            finishStartupDraw();
            return true;
        }
    };

    private void finishStartupDraw() {
        View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener);
        content.invalidate();
    }

    // "-tag <tag>" windows register here, keyed by tag ("" for the default window). Weakly
    // held so a window that dies without deregistering isn't pinned in memory.
    private static final Map<String, WeakReference<MainActivity>> instances = new HashMap<>();
    private String tag = "";
    private boolean redirected;

    /** Redirects a tagged intent that landed on plain MainActivity to MainActivityTagged. Returns the parsed tag, or null if it relaunched. */
    private String relaunchAsTaggedIfNeeded(Intent intent, boolean finishSelf) {
        // Tag comes from the launch Intent's data: either the host of a "termux-x11-window://<tag>"
        // URI, or a bare schemeless value ("-d <tag>") used as the tag directly.
        Uri docData = intent.getData();
        String tag = docData != null && docData.getHost() != null ? docData.getHost()
                : docData != null && docData.getScheme() == null ? docData.toString() : "";

        if (tag.isEmpty() || this instanceof MainActivityTagged)
            return tag;

        if (finishSelf)
            finish();
        startTaggedActivity(new Intent(intent));
        return null;
    }

    public String getTag() {
        return tag;
    }

    /** The window registered under the given "-tag" argument, or the default window for "". */
    public static MainActivity getInstance(String tag) {
        WeakReference<MainActivity> ref = instances.get(tag == null ? "" : tag);
        return ref != null ? ref.get() : null;
    }

    /** Whichever window currently has input focus, or null if none does. */
    public static MainActivity getFocusedInstance() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && a.hasWindowFocus())
                return a;
        }
        return null;
    }

    /** All windows that are currently alive. */
    public static List<MainActivity> getInstances() {
        List<MainActivity> result = new ArrayList<>();
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                result.add(a);
        }
        return result;
    }

    private void showDisplaysDialog() {
        Set<String> tagSet = new TreeSet<>();
        for (Map.Entry<String, WeakReference<MainActivity>> e : instances.entrySet()) {
            MainActivity a = e.getValue().get();
            if (a != null && a != this && !e.getKey().isEmpty())
                tagSet.add(e.getKey());
        }
        for (String pendingTag : app.pendingConnections.keySet())
            if (!pendingTag.isEmpty())
                tagSet.add(pendingTag);
        tagSet.remove(tag);
        if (tagSet.isEmpty()) {
            Toast.makeText(this, R.string.displays_dialog_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> tags = new ArrayList<>(tagSet);
        new AlertDialog.Builder(this)
                .setTitle(R.string.displays_dialog_title)
                .setItems(tags.toArray(new CharSequence[0]), (dialog, which) -> startTaggedActivity(new Intent().setData(Uri.parse(tags.get(which)))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Launches (or switches to) the MainActivityTagged window for the given intent's data. */
    private void startTaggedActivity(Intent intent) {
        intent.setClass(this, MainActivityTagged.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent, freeformActivityOptions());
    }

    /** ActivityOptions requesting freeform windowing mode. */
    private static Bundle freeformActivityOptions() {
        ActivityOptions opts = ActivityOptions.makeBasic();
        nativeSetLaunchWindowingModeFreeform(opts);
        return opts.toBundle();
    }

    /** The focused window, or any window that hasn't been garbage-collected yet, or null if none are alive. */
    public static MainActivity getFocusedOrAnyInstance() {
        MainActivity focused = getFocusedInstance();
        if (focused != null)
            return focused;
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                return a;
        }
        return null;
    }

    private final Set<Integer> pressedKeys = new LinkedHashSet<>();

    public void markKeyPressed(int keyCode) {
        pressedKeys.add(keyCode);
    }

    public void markKeyReleased(int keyCode) {
        pressedKeys.remove(keyCode);
    }

    /** The window that considers the given key code held down, if any. */
    public static MainActivity getInstanceWithPressedKey(int keyCode) {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && a.pressedKeys.contains(keyCode))
                return a;
        }
        return null;
    }

    /** Whether any window still has a key held down. */
    public static boolean anyInstanceHasPressedKeys() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null && !a.pressedKeys.isEmpty())
                return true;
        }
        return false;
    }

    public static void clearAllPressedKeys() {
        for (WeakReference<MainActivity> ref : instances.values()) {
            MainActivity a = ref.get();
            if (a != null)
                a.pressedKeys.clear();
        }
    }

    /** Unwraps the {@link MainActivity} a view's {@link Context} was inflated with, if any. */
    public static MainActivity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof MainActivity)
                return (MainActivity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = (LorieApp) getApplication();

        String parsedTag = relaunchAsTaggedIfNeeded(getIntent(), true);
        if (parsedTag == null) {
            redirected = true;
            return;
        }
        tag = parsedTag;

        instances.put(tag, new WeakReference<>(this));

        prefs = app.getPrefs(this);
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = fullscreenPreference();
        oldHideCutout = prefs.hideCutout.get();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);
        applyWindowSettings();

        frm = findViewById(R.id.frame);
        findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));
        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());
        findViewById(R.id.displays_button).setOnClickListener((l) -> showDisplaysDialog());

        LorieView lorieView = findViewById(R.id.lorieView);
        mLorieView = lorieView;
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(this, lorieView));
        mLorieKeyListener = (v, k, e) -> {
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        if (SDK_INT >= VERSION_CODES.O) {
            lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
            lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        }
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((screenWidth, screenHeight, inputTransform) ->
                mInputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(presentationDisplayListener, handler);
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF) {{
            addAction(Intent.ACTION_SCREEN_ON);
        }});
        handler.post(this::updatePresentation);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                setTerminalToolbarViewLayout();
            }
        };
        frm.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            String savedPos;
            int savedRotation;

            @Override
            public void onGlobalLayout() {
                Display d = frm.getDisplay();
                String pos = prefs.ekbarPosition.get();
                if ((d != null && savedRotation != d.getRotation()) || !Objects.equals(savedPos, pos)) {
                    savedRotation = d == null ? 0 : d.getRotation();
                    savedPos = pos;
                    setTerminalToolbarViewLayout();
                }
            }
        });

        ImeHeightProvider.assistActivity(this);

        IBinder pendingConnection = app.pendingConnections.remove(tag);
        if (pendingConnection != null)
            connectToService(pendingConnection);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        relaunchAsTaggedIfNeeded(intent, false);
    }

    private final DisplayManager.DisplayListener presentationDisplayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { updatePresentation(); }
        @Override public void onDisplayRemoved(int displayId) { updatePresentation(); }
        @Override public void onDisplayChanged(int displayId) { updatePresentation(); }
    };

    /** Set by the app to minimize Chrome while nobody can see it. */
    public static Consumer<Boolean> browserVisibilityListener;
    private boolean started;
    private Boolean browserVisible;
    private final Runnable hideBrowser = () -> setBrowserVisible(false);

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateBrowserVisibility();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        updateBrowserVisibility();
    }

    @Override
    protected void onStop() {
        started = false;
        updateBrowserVisibility();
        super.onStop();
    }

    /**
     * Chrome is visible while this window is on screen, or while it is on a monitor and
     * the screens are on. A minimized Chrome stops drawing pages, as it would on a phone.
     * Hiding waits a few seconds, so that quickly switching apps does not redraw Chrome.
     */
    void updateBrowserVisibility() {
        if (redirected)
            return;
        boolean interactive = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        handler.removeCallbacks(hideBrowser);
        if (interactive && (started || presentation != null))
            setBrowserVisible(true);
        else
            handler.postDelayed(hideBrowser, interactive ? 3000 : 0);
    }

    private void setBrowserVisible(boolean visible) {
        if (Boolean.valueOf(visible).equals(browserVisible))
            return;
        browserVisible = visible;
        if (browserVisibilityListener != null)
            browserVisibilityListener.accept(visible);
    }

    @Override
    protected void onDestroy() {
        if (!redirected)
            unregisterReceiver(screenStateReceiver);
        handler.removeCallbacks(hideBrowser);
        if (displayManager != null)
            displayManager.unregisterDisplayListener(presentationDisplayListener);
        if (presentation != null)
            presentation.dismiss();
        handler.removeCallbacks(screenIdleTimeoutCheck);
        if (mInputHandler != null)
            mInputHandler.onDestroy();
        instances.values().removeIf(ref -> ref.get() == this);
        super.onDestroy();
    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && getLorieView().connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        if (SDK_INT >= VERSION_CODES.O)
            overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            mInputHandler.mStylusInputHelperMode = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((mInputHandler.mStylusInputHelperMode == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((mInputHandler.mStylusInputHelperMode == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((mInputHandler.mStylusInputHelperMode == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = mInputHandler.mStylusInputHelperMode;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                RectF frmRect = getVisibleFrmRect();
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frmRect.right - 4 * left.getWidth();
                float maxY = frmRect.bottom - 4 * left.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, maxY));

                int m = mInputHandler.mStylusInputHelperMode;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        mInputHandler.mStylusInputHelperMode = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                RectF frmRect = getVisibleFrmRect();

                //Calculate screen border making sure btn is fully inside the view
                float minX = frmRect.left, minY = frmRect.top;
                float maxX = frmRect.right;
                float maxY = frmRect.bottom;

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() + visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(dY, frmRect.top, frmRect.bottom));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        overlay.setX(MathUtils.clamp(overlay.getX(), frmRect.left, frmRect.right));
                        overlay.setY(MathUtils.clamp(overlay.getY(), frmRect.top, frmRect.bottom));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (getLorieView().connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            mInputHandler.mStylusInputHelperMode = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private RectF getVisibleFrmRect() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final LorieView lorieView = getLorieView();
        final View sharedParent = (View) frm.getParent(); // also mouse/stylus aux buttons' parent

        // lorieView's available rect already excludes insets/caption/IME, just offset it to sharedParent's coordinates.
        RectF result = new RectF(lorieView.getAvailableRect());
        result.offset(frm.getLeft() + lorieView.getLeft(), frm.getTop() + lorieView.getTop());

        // availableRect only excludes the IME when Reseed is on, so clamp against it unconditionally too.
        result.bottom = Math.min(result.bottom, sharedParent.getHeight() - imeHeight);

        // With adjustHeightForEK on, availableRect already excludes the bar's own space.
        if (pager.getVisibility() == View.VISIBLE && !prefs.adjustHeightForEK.get()) {
            // getLayoutParams() is up to date immediately; getWidth()/getHeight() lag until the next layout pass.
            int barThickness = ((FrameLayout.LayoutParams) pager.getLayoutParams()).height;
            switch (getPagerPosition()) {
                case PAGER_POSITION_TOP:    result.top    = Math.max(result.top, barThickness); break;
                case PAGER_POSITION_BOTTOM: result.bottom = Math.min(result.bottom, sharedParent.getHeight() - imeHeight - barThickness); break;
                case PAGER_POSITION_LEFT:   result.left   = Math.max(result.left, barThickness); break;
                case PAGER_POSITION_RIGHT:  result.right  = Math.min(result.right, sharedParent.getWidth() - barThickness); break;
            }
        }
        return result;
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final RectF frmRect = getVisibleFrmRect();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frmRect.left, frmRect.right - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frmRect.top, frmRect.bottom - mouseAuxButtons.getHeight()));
        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frmRect.left, frmRect.right - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frmRect.top, frmRect.bottom - stylusAuxButtons.getHeight()));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((getLorieView().connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        primaryLayer.setOnHoverListener((v, e) -> true);
        primaryLayer.setOnGenericMotionListener((v, e) -> true);
        if (SDK_INT >= VERSION_CODES.O)
            primaryLayer.setOnCapturedPointerListener((v, e) -> true);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                final RectF frmRect = getVisibleFrmRect();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frmRect.left, frmRect.right - primaryLayer.getWidth()));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
            switch(e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    getLorieView().sendMouseEvent(0, 0, b, true, true);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    getLorieView().sendMouseEvent(0, 0, b, false, true);
                    v.setPressed(false);
                    break;
            }
            return true;
        }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startRaw = new float[2];
            final float[] startLayerPos = new float[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startRaw[0] = e.getRawX();
                        startRaw[1] = e.getRawY();
                        startLayerPos[0] = primaryLayer.getX();
                        startLayerPos[1] = primaryLayer.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final RectF frmRect = getVisibleFrmRect();
                        float newX = startLayerPos[0] + (e.getRawX() - startRaw[0]);
                        float newY = startLayerPos[1] + (e.getRawY() - startRaw[1]);
                        primaryLayer.setX(MathUtils.clamp(newX, frmRect.left, frmRect.right - primaryLayer.getWidth()));
                        primaryLayer.setY(MathUtils.clamp(newY, frmRect.top, frmRect.bottom - primaryLayer.getHeight()));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        pos.setPressed(false);
                        float deltaX = e.getRawX() - startRaw[0];
                        float deltaY = e.getRawY() - startRaw[1];

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        pos.setPressed(false);
                        break;
                }
                return true;
            }
        });
    }

    void connectToService(IBinder ibinder) {
        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("MainActivity", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    getLorieView().startLogcat(logcatOutput.detachFd());

                tryConnect();
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    void disconnectService() {
        service = null;

        Log.v("Lorie", "Disconnected");
        runOnUiThread(() -> { getLorieView().connect(-1); clientConnectedStateChanged(); });
    }

    boolean tryConnect() {
        if (getLorieView().connected()) {
            handler.removeCallbacks(connectRetry);
            return false;
        }

        if (service == null) {
            boolean sent = getLorieView().requestConnection();
            scheduleConnect();
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                getLorieView().connect(fd.detachFd());
                finishStartupDraw();
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                scheduleConnect();
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            scheduleConnect();
        }
        return false;
    }

    void scheduleConnect() {
        handler.removeCallbacks(connectRetry);
        handler.postDelayed(connectRetry, 250);
    }

    void onPreferencesChanged(String key) {
        if ("hideAdditionalKbdWithExternalKeyboard".equals(key)
                || "showAdditionalKbd".equals(key))
            hardwareExtraKeysOverride = null;
        if ("additionalKbdVisible".equals(key))
            return;

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs = app.getPrefs(this);

        // There is no way back to the normal size from picture-in-picture, so the window is closed.
        if (isInPictureInPictureMode && !prefs.PIP.get()) {
            finish();
            return;
        }

        applyWindowSettings();
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        if (mExtraKeys != null)
            mExtraKeys.reload();
        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && getLorieView().connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : extraKeysReserveSpace() ? 1.f : ((float) prefs.opacityEKBar.get())/100);

        applyMonitorStatusBar();
        lorieView.requestLayout();
        lorieView.invalidate();

        app.refreshNotificationIfShown();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (redirected)
            return;

        app.onActivityResumed(this);

        orientationListener.enable();
        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        if (redirected) {
            super.onPause();
            return;
        }

        getLorieView().setKeyboardVisible(false);

        orientationListener.disable();
        super.onPause();

        resumed = false;
        // While Chrome is on a monitor the notification stays, to turn the phone screen back off.
        if (presentation != null)
            app.onActivityResumed(this);
        else
            app.onActivityPaused();
    }

    public LorieView getLorieView() {
        // The view moves into a Presentation window while Chrome is shown on a monitor.
        return mLorieView != null ? mLorieView : findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    // We can not define function-static variables in Java, so we are defining them outside a function
    private final X11ToolbarViewPager.PageAdapter mPageAdapter =
            new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e));
    private final X11ToolbarViewPager.OnPageChangeListener mOnPageListener = new X11ToolbarViewPager.OnPageChangeListener(this);
    private boolean extraKeysReserveSpace() {
        // A temporarily revealed auto-hidden bar floats over the desktop.
        return prefs.adjustHeightForEK.get()
                && !(externalKeyboardConnected && prefs.hideAdditionalKbdWithExternalKeyboard.get());
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : extraKeysReserveSpace()
                ? 1.f : ((float) prefs.opacityEKBar.get()) / 100);

        boolean hideForHardwareKeyboard = externalKeyboardConnected
                && prefs.hideAdditionalKbdWithExternalKeyboard.get()
                && !Boolean.TRUE.equals(hardwareExtraKeysOverride);
        // While Chrome is on a monitor the phone is a touchpad, and the key bar there
        // stays visible: a three-finger swipe on the touchpad would otherwise hide it.
        boolean showNow = !isInPictureInPictureMode && getLorieView().connected()
                && prefs.showAdditionalKbd.get()
                && (presentation != null || (prefs.additionalKbdVisible.get() && !hideForHardwareKeyboard));

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            if (pager.getAdapter() != mPageAdapter)
                pager.setAdapter(mPageAdapter);
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(mOnPageListener);
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        setTerminalToolbarViewLayout();
        // Keys reach the X server through the frame while the view is on the monitor.
        (imeProxy != null ? imeProxy : getLorieView()).requestFocus();
    }

    // Keep in sync with Surface.ROTATION_*
    public static final int PAGER_POSITION_TOP = 0, PAGER_POSITION_LEFT = 1, PAGER_POSITION_BOTTOM = 2, PAGER_POSITION_RIGHT = 3;
    public int getPagerPosition() {
        String _pos = prefs.ekbarPosition.get();
        int pos = "top".equals(_pos) ? PAGER_POSITION_TOP : "left".equals(_pos) ? PAGER_POSITION_LEFT : "bottom".equals(_pos) ? PAGER_POSITION_BOTTOM : "right".equals(_pos) ? PAGER_POSITION_RIGHT : 0;
        if (prefs.ekbarPositionIgnoreOrientation.get()) {
            Display dpy = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (dpy != null)
                pos = (pos + dpy.getRotation()) % 4;
        }
        return pos;
    }

    @SuppressLint("RtlHardcoded")
    private void setTerminalToolbarViewLayout() {
        handler.post(() -> {
            final ViewPager pager = getTerminalToolbarViewPager();
            boolean showNow = pager.getVisibility() == View.VISIBLE;
            ExtraKeysInfo extraKeysInfo = mExtraKeys == null ? null : mExtraKeys.getExtraKeysInfo();
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) pager.getLayoutParams();
            int pos = getPagerPosition();

            // The window is not resized for the keyboard, so a bar along a side has to end above it.
            layoutParams.width = (pos == PAGER_POSITION_LEFT || pos == PAGER_POSITION_RIGHT) ? frm.getHeight() - imeHeight : frm.getWidth();
            layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                    (extraKeysInfo == null ? 0 : extraKeysInfo.getMatrix().length));

            switch (pos) {
                case PAGER_POSITION_TOP:
                case PAGER_POSITION_BOTTOM:
                    layoutParams.gravity = (pos == PAGER_POSITION_TOP ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT;
                    // reset everything we set for "left" and "right"
                    pager.setPivotX(layoutParams.width / 2f);
                    pager.setPivotY(layoutParams.width / 2f);
                    pager.setRotation(0f);
                    pager.setTranslationX(0);
                    break;
                case PAGER_POSITION_LEFT:
                case PAGER_POSITION_RIGHT:
                    layoutParams.gravity = (pos == PAGER_POSITION_LEFT ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
                    pager.setPivotX(pos == PAGER_POSITION_LEFT ? 0 : layoutParams.width);
                    pager.setPivotY(0f);
                    pager.setRotation(90f * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    pager.setTranslationX(layoutParams.height * (pos == PAGER_POSITION_LEFT ? 1 : -1));
                    break;
            }
            pager.setLayoutParams(layoutParams);

            ekbarContentInset = extraKeysReserveSpace() && showNow ? layoutParams.height : 0;
            applyContentInsets();
            makeSureHelpersAreVisibleAndInScreenBounds();
        });
    }

    private int ekbarContentInset = 0;
    private int imeHeight = 0;

    private void applyContentInsets() {
        int pos = getPagerPosition();
        // Only a bar at the bottom has to step aside for the keyboard.
        int bottomMargin = pos == PAGER_POSITION_BOTTOM ? imeHeight : 0;

        // A bottom bar reserving its own space always sits right above the keyboard via bottomMargin,
        // so content must stop there too, regardless of Reseed.
        boolean barReservesKeyboardSpace = pos == PAGER_POSITION_BOTTOM && ekbarContentInset > 0;
        int imeContentInset = barReservesKeyboardSpace ? bottomMargin : (prefs.Reseed.get() ? imeHeight : 0);

        if (presentation != null) {
            // The key bar and the keyboard stay on the phone; the monitor shows the whole screen.
            getLorieView().setContentInsets(0, 0, 0, 0);
            getLorieView().setObscuredBottom(0);
        } else {
            getLorieView().setContentInsets(pos == PAGER_POSITION_LEFT ? ekbarContentInset : 0,
                    pos == PAGER_POSITION_TOP ? ekbarContentInset : 0,
                    pos == PAGER_POSITION_RIGHT ? ekbarContentInset : 0,
                    imeContentInset + (pos == PAGER_POSITION_BOTTOM ? ekbarContentInset : 0));
            getLorieView().setObscuredBottom(imeHeight - imeContentInset);
        }

        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.MarginLayoutParams pagerParams = (ViewGroup.MarginLayoutParams) pager.getLayoutParams();
        if (pagerParams.bottomMargin != bottomMargin) {
            pagerParams.bottomMargin = bottomMargin;
            pager.setLayoutParams(pagerParams);
        }
    }

    public void setImeHeight(int height) {
        // Reported on every insets dispatch, but relaying out the bar for it must not become a loop.
        if (imeHeight == height)
            return;

        imeHeight = height;
        setTerminalToolbarViewLayout();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && getLorieView().connected() && saveState) {
            prefs.additionalKbdVisible.put(visible);
            if (externalKeyboardConnected && prefs.hideAdditionalKbdWithExternalKeyboard.get())
                hardwareExtraKeysOverride = visible;
        }

        setTerminalToolbarView();
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    int orientation, densityDpi;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (redirected)
            return;

        if (newConfig.orientation != orientation)
            getLorieView().setKeyboardVisible(false);

        if (newConfig.densityDpi != densityDpi)
            orientationDeniedAt = null;

        orientation = newConfig.orientation;
        densityDpi = newConfig.densityDpi;
        applyWindowSettings();
        setTerminalToolbarView();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();

        // The system bars come back when the window loses focus.
        if (hasFocus && !redirected && isOutputOnExternalDisplay()) {
            // The X connection may still be starting; retry until the pointer is captured.
            for (long delay : new long[] { 0, 1000, 3000, 6000 })
                handler.postDelayed(() -> {
                    if (hasWindowFocus() && (SDK_INT < VERSION_CODES.O || !getPointerCaptureView().hasPointerCapture()))
                        setCapturingEnabled(true);
                }, delay);
        }
        if (hasFocus && !redirected) {
            applyImmersiveMode();
            LorieView.markUserActivity();
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (redirected)
            return;

        LorieView.markUserActivity();
        handler.removeCallbacks(screenIdleTimeoutCheck);
        checkScreenIdleTimeout();
    }

    private void applyImmersiveMode() {
        boolean isFullscreen = fullscreenPreference();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(!isFullscreen);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (!isFullscreen)
                    controller.show(WindowInsets.Type.systemBars());
                else {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } else
            getWindow().getDecorView().setSystemUiVisibility(!isFullscreen ? 0 :
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setWindowFlag(int flag, boolean enabled) {
        if (((getWindow().getAttributes().flags & flag) != 0) == enabled)
            return;

        if (enabled)
            getWindow().addFlags(flag);
        else
            getWindow().clearFlags(flag);
    }

    /** Keeps or drops the screen-on flag based on elapsed idle time, rescheduling itself for the remaining time. */
    private void checkScreenIdleTimeout() {
        String mode = isOutputOnExternalDisplay() ? "never" : prefs.screenIdleTimeout.get();
        if (!getLorieView().connected() || "never".equals(mode) || "system".equals(mode)) {
            screenIdleTimeoutArmedMode = null;
            return;
        }

        long systemTimeoutMs = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        long timeoutMs = Math.max(Long.parseLong(mode) * 60_000L - systemTimeoutMs, 0);
        long elapsed = (System.nanoTime() / 1_000_000L) - LorieView.getLastInputTimestamp();
        if (elapsed >= timeoutMs) {
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, false);
        } else {
            screenIdleTimeoutArmedMode = mode;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, true);
            handler.postDelayed(screenIdleTimeoutCheck, timeoutMs - elapsed);
        }
    }

    /** Syncs screenIdleTimeout state/timer to the current preference without extending an already-scheduled check. */
    private void applyScreenIdleTimeout() {
        // An external display sleeps together with the phone, so keep it awake there.
        String mode = isOutputOnExternalDisplay() ? "never" : prefs.screenIdleTimeout.get();
        boolean connected = getLorieView().connected();
        if (!connected || "never".equals(mode) || "system".equals(mode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            screenIdleTimeoutArmedMode = null;
            setWindowFlag(FLAG_KEEP_SCREEN_ON, connected && "never".equals(mode));
        } else if (!mode.equals(screenIdleTimeoutArmedMode)) {
            handler.removeCallbacks(screenIdleTimeoutCheck);
            checkScreenIdleTimeout();
        }
    }

    void applyWindowSettings() {
        Window window = getWindow();
        boolean fullscreen = fullscreenPreference();
        boolean hideCutout = prefs.hideCutout.get();

        // Recreating would take the window out of picture-in-picture, so it waits for the normal size.
        if (!isInPictureInPictureMode && (oldHideCutout != hideCutout || oldFullscreen != fullscreen)) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (isInMultiWindowMode() ? "auto" : prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        // A display ignoring orientation requests letterboxes the window into the requested
        // proportions instead of rotating, leaving the rest of the screen unusable. The request is
        // retried once the display changes, the next one may well honour it.
        if (SDK_INT >= VERSION_CODES.R) {
            WindowManager wm = getWindowManager();
            Rect display = wm.getMaximumWindowMetrics().getBounds();
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED && !wm.getCurrentWindowMetrics().getBounds().equals(display))
                orientationDeniedAt = display;
            if (display.equals(orientationDeniedAt))
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (SDK_INT >= VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutoutMode = !hideCutout ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER :
                    (SDK_INT >= VERSION_CODES.R ? LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES);
            if (attributes.layoutInDisplayCutoutMode != cutoutMode) {
                attributes.layoutInDisplayCutoutMode = cutoutMode;
                window.setAttributes(attributes);
            }
        }

        setWindowFlag(FLAG_FULLSCREEN, fullscreen);
        applyScreenIdleTimeout();
        applyImmersiveMode();

        View contentChild = ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0);
        if (contentChild.getFitsSystemWindows() == fullscreen) {
            contentChild.setFitsSystemWindows(!fullscreen);
            ViewCompat.requestApplyInsets(contentChild);
        }
    }

    @Override
    public void onBackPressed() {
    }

    private static float getSystemDimenFloat(String name, float fallback) {
        Resources resources = Resources.getSystem();
        TypedValue value = new TypedValue();
        int id = resources.getIdentifier(name, "dimen", "android");
        if (id != 0)
            resources.getValue(id, value, true);
        return value.type == TypedValue.TYPE_FLOAT ? value.getFloat() : fallback;
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @RequiresApi(api = VERSION_CODES.O)
    @Override
    public void onUserLeaveHint() {
        if (redirected || !prefs.PIP.get() || !hasPipPermission(this) || !getLorieView().connected())
            return;

        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder();
        Rational aspectRatio = getLorieView().getScreenAspectRatio();
        if (aspectRatio != null) {
            float clamped = MathUtils.clamp(aspectRatio.floatValue(), MIN_PIP_ASPECT_RATIO, MAX_PIP_ASPECT_RATIO);
            if (clamped != aspectRatio.floatValue())
                // Truncating instead of rounding keeps the ratio from landing back outside of the range.
                aspectRatio = clamped > 1 ? new Rational((int) (clamped * 1000), 1000) : new Rational(1000, (int) (1000 / clamped));
            params.setAspectRatio(aspectRatio);
        }

        getLorieView().freezeDimensions(true);
        if (!enterPictureInPictureMode(params.build()))
            getLorieView().freezeDimensions(false);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        getLorieView().onPictureInPictureModeChanged(isInPictureInPictureMode);
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : extraKeysReserveSpace() ? 1.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        setTerminalToolbarView();
        if (!isInPictureInPictureMode)
            applyWindowSettings();

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     */
    public void toggleKeyboardVisibility() {
        Log.d("MainActivity", "Toggling keyboard visibility");
        LorieView view = getLorieView();
        if (imeProxy != null) {
            // The display view is on the monitor; the keyboard opens for the phone window.
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            WindowInsets insets = imeProxy.getRootWindowInsets();
            if (SDK_INT >= VERSION_CODES.R && insets != null && insets.isVisible(WindowInsets.Type.ime()))
                imm.hideSoftInputFromWindow(imeProxy.getWindowToken(), 0);
            else {
                imeProxy.requestFocus();
                imm.showSoftInput(imeProxy, 0);
            }
            return;
        }
        if (!externalKeyboardConnected || showIMEWhileExternalConnected)
            view.toggleKeyboardVisible();
        else
            view.setKeyboardVisible(false);
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = getLorieView().connected();
            if (!connected)
                setCapturingEnabled(false);

            // A picture-in-picture window has nothing to show without a client, and there is no way
            // back to the normal size from it, so the window is closed.
            if (!connected && isInPictureInPictureMode) {
                finish();
                return;
            }

            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected?View.INVISIBLE:View.VISIBLE);
            getLorieView().setVisibility(connected?View.VISIBLE:View.INVISIBLE);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
            else
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

            applyWindowSettings();
            if (connected)
                updatePresentation();
        });
    }

    public void getRealMetrics(DisplayMetrics m) {
        if (getLorieView() != null && getLorieView().getDisplay() != null)
            getLorieView().getDisplay().getRealMetrics(m);
    }

    public void setCapturingEnabled(boolean enabled) {
        if (mInputHandler != null)
            mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        boolean connectionChanged = externalKeyboardConnected != connected;
        externalKeyboardConnected = connected;
        if (connectionChanged)
            hardwareExtraKeysOverride = null;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            getLorieView().setKeyboardVisible(false);
        // Device discovery also runs inside TouchInputHandler's constructor.
        // Wait for its assignment before updating adapters and display insets.
        if (connectionChanged && mInputHandler != null)
            setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    private static native void nativeSetLaunchWindowingModeFreeform(Object activityOptions);

    /** The first external display that can show app windows, or Display.INVALID_DISPLAY. */
    public static int externalDisplayId(Context ctx) {
        for (Display d : ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE)).getDisplays())
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && (d.getFlags() & Display.FLAG_PRESENTATION) != 0
                    && (d.getFlags() & Display.FLAG_PRIVATE) == 0)
                return d.getDisplayId();
        return Display.INVALID_DISPLAY;
    }

    /**
     * Immersive mode on an external display would place the desktop window's caption
     * bar over the X screen, so the window there always fits its system bars.
     */
    private boolean fullscreenPreference() {
        return prefs.fullscreen.get() && !isOnExternalDisplay();
    }

    /** Chrome is shown on an external display, by this window or by its Presentation. */
    public boolean isOutputOnExternalDisplay() {
        return presentation != null || isOnExternalDisplay();
    }

    /** Whether the browser should be shown on an external display through a Presentation. */
    public static boolean presentationWanted(Context ctx, Prefs prefs) {
        return externalDisplayId(ctx) != Display.INVALID_DISPLAY && prefs.autoExternalDisplay.get();
    }

    /**
     * Without desktop mode, an external display is shown through a Presentation: the
     * X screen moves there without a window caption, while this window on the phone
     * keeps input focus for the keyboard and the captured mouse.
     */
    void updatePresentation() {
        boolean wanted = canPresent();
        // Move the view only once the X connection is up; moving it earlier lost the connection.
        if (wanted && presentation == null && getLorieView().connected()) {
            Display display = displayManager.getDisplay(externalDisplayId(this));
            if (display == null)
                return;
            LorieView view = getLorieView();
            presentation = new Presentation(this, display) {
                @Override public void dismiss() {
                    // Take the view back before the window goes away, also when Android
                    // dismisses the presentation because the display disconnected.
                    restoreLorieView();
                    super.dismiss();
                }
            };
            FrameLayout container = new FrameLayout(presentation.getContext());
            container.setBackgroundColor(android.graphics.Color.BLACK);
            view.movingBetweenWindows = true;
            ((ViewGroup) view.getParent()).removeView(view);
            container.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.movingBetweenWindows = false;
            monitorStatusBar = new MonitorStatusBar(presentation.getContext());
            container.addView(monitorStatusBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            applyMonitorStatusBar();
            presentation.setContentView(container);
            presentation.show();
            setTerminalToolbarView();
            applyContentInsets();
            app.refreshNotificationIfShown();
            // Keys, the captured mouse and the on-screen keyboard arrive through this window.
            imeProxy = new ImeProxyView(this, view);
            imeProxy.setOnKeyListener(mLorieKeyListener);
            if (SDK_INT >= VERSION_CODES.O)
                imeProxy.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(view, view, e));
            ((FrameLayout) findViewById(R.id.frame)).addView(imeProxy, new FrameLayout.LayoutParams(1, 1));
            imeProxy.requestFocus();
            applyScreenIdleTimeout();
            setCapturingEnabled(true);
            updateBrowserVisibility();
        } else if (!wanted && presentation != null) {
            presentation.dismiss();
        }
    }

    private void restoreLorieView() {
        if (presentation == null)
            return;
        // Give the mouse and the on-screen keyboard back to the display view.
        if (imeProxy != null) {
            if (SDK_INT >= VERSION_CODES.O)
                imeProxy.releasePointerCapture();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(imeProxy.getWindowToken(), 0);
            ((ViewGroup) imeProxy.getParent()).removeView(imeProxy);
            imeProxy = null;
        }
        presentation = null;
        monitorStatusBar = null;
        LorieView view = getLorieView();
        view.movingBetweenWindows = true;
        if (view.getParent() != null)
            ((ViewGroup) view.getParent()).removeView(view);
        ((FrameLayout) findViewById(R.id.frame)).addView(view, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        view.movingBetweenWindows = false;
        view.requestFocus();
        setTerminalToolbarView();
        applyContentInsets();
        applyScreenIdleTimeout();
        app.refreshNotificationIfShown();
        updateBrowserVisibility();
    }

    /** Shows or hides the battery and clock box on the monitor. */
    private void applyMonitorStatusBar() {
        if (monitorStatusBar == null)
            return;
        monitorStatusBar.setVisibility(prefs.monitorStatusBar.get() ? View.VISIBLE : View.GONE);
        monitorStatusBar.setHeightDp(prefs.monitorStatusBarHeight.get());
    }

    public boolean isPresenting() {
        return presentation != null;
    }

    /** A monitor is available for the Presentation. */
    public boolean canPresent() {
        return !isOnExternalDisplay() && presentationWanted(this, prefs);
    }

    private boolean resumed;

    /** The phone shows this window: a dark screen that works as a touchpad for the monitor. */
    public boolean isPhoneScreenOff() {
        return presentation != null && resumed;
    }

    /**
     * Phone screen off: brings this window to the front of the phone, where it is dark and
     * works as a touchpad, with the keyboard and mouse going to the monitor. Turning it off
     * moves it back so that other apps can be used; Chrome stays on the monitor.
     */
    public void togglePhoneScreenOff() {
        if (resumed)
            moveTaskToBack(true);
        else
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The view that holds pointer capture: the display view, or this window's frame while presenting. */
    public View getPointerCaptureView() {
        return imeProxy != null ? imeProxy : getLorieView();
    }

    public boolean isOnExternalDisplay() {
        return getWindowManager().getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY;
    }


    static {
        System.loadLibrary("Xlorie");
    }

    public static class MainActivityTagged extends MainActivity {
    }
}
