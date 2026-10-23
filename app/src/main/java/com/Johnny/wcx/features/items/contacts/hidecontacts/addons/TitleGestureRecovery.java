package com.Johnny.wcx.features.items.contacts.hidecontacts.addons;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/** Local repair for APK 260927213229. Does not replace its gesture/state logic. */
public final class TitleGestureRecovery {
    private static final String TAG = "WCXTitleFix";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> activity = new WeakReference<Activity>(null);
    private static WeakReference<View> title = new WeakReference<View>(null);
    private static View.OnTouchListener listener;
    private static boolean registered;
    private static boolean enabled;
    private static boolean resumed;
    private static boolean touching;
    private static long downAt;
    private static Field listenerInfo;
    private static Field onTouchListener;
    private static boolean reflectionFailed;

    private static final Runnable CHECK = new Runnable() {
        public void run() {
            if (!enabled || !resumed) return;
            Activity a = activity.get();
            if (a == null || a.isFinishing()) return;
            if (a.getWindow() != null && a.getWindow().getDecorView().hasWindowFocus()
                    && a.getWindow().getDecorView().isShown()) {
                try {
                    HiddenContactTitleGesture.INSTANCE.attach(a);
                } catch (RuntimeException e) {
                    Log.w(TAG, "foreground attach check failed", e);
                }
            }
            MAIN.removeCallbacks(this);
            MAIN.postDelayed(this, 1000);
        }
    };

    private static boolean isHome(Activity a) {
        return a != null && "com.tencent.mm.ui.LauncherUI".equals(a.getClass().getName());
    }

    private static final Application.ActivityLifecycleCallbacks LIFECYCLE =
            new Application.ActivityLifecycleCallbacks() {
        public void onActivityResumed(Activity a) {
            if (!isHome(a)) return;
            watch(a);
            if (enabled) {
                // Run after onResume and pending UI work, including feature enable while open.
                MAIN.removeCallbacks(CHECK);
                MAIN.post(CHECK);
            }
        }
        public void onActivityPaused(Activity a) {
            if (activity.get() != a) return;
            resumed = false;
            MAIN.removeCallbacks(CHECK);
            cancelTouch();
        }
        public void onActivityDestroyed(Activity a) {
            if (activity.get() != a) return;
            resumed = false;
            MAIN.removeCallbacks(CHECK);
            clear();
            activity.clear();
        }
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) {}
        public void onActivityStopped(Activity a) {}
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    };

    public static void bootstrap() {
        enabled = true;
        if (!registered) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Application app = (Application) at.getMethod("currentApplication").invoke(null);
                if (app != null) register(app);
            } catch (Exception e) {
                // attach(Activity) registers through a public API as a fallback.
                Log.w(TAG, "early lifecycle registration deferred until activity attach");
            }
        }
        if (resumed) { MAIN.removeCallbacks(CHECK); MAIN.post(CHECK); }
    }

    private static void register(Application app) {
        if (registered) return;
        app.registerActivityLifecycleCallbacks(LIFECYCLE);
        registered = true;
        Log.i(TAG, "recovery v1 lifecycle registered");
    }

    public static void watch(Activity a) {
        if (!isHome(a)) return;
        ConversationReturnRecovery.watch(a);
        HiddenContactIndicators.watch(a);
        register(a.getApplication());
        if (activity.get() != a) {
            clear();
            activity = new WeakReference<Activity>(a);
        }
        resumed = true;
        MAIN.removeCallbacks(CHECK);
        if (enabled) MAIN.postDelayed(CHECK, 1000);
    }

    public static boolean healthy(View v) {
        if (v != title.get() || listener == null) return false;
        if (touching && SystemClock.uptimeMillis() - downAt < 5500) return true;
        if (!v.isClickable() || !v.isLongClickable()) return false;
        if (reflectionFailed) return true;
        try {
            if (listenerInfo == null) {
                listenerInfo = View.class.getDeclaredField("mListenerInfo");
                listenerInfo.setAccessible(true);
            }
            Object info = listenerInfo.get(v);
            if (info == null) return false;
            if (onTouchListener == null) {
                onTouchListener = info.getClass().getDeclaredField("mOnTouchListener");
                onTouchListener.setAccessible(true);
            }
            return onTouchListener.get(info) == listener;
        } catch (Exception e) {
            reflectionFailed = true;
            Log.w(TAG, "listener inspection unavailable; view/lifecycle recovery remains active");
            return true;
        }
    }

    public static void track(final View v, final View.OnTouchListener original) {
        cancelTouch();
        title = new WeakReference<View>(v);
        listener = new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent event) {
                if (!enabled || title.get() != view) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touching = true;
                    downAt = SystemClock.uptimeMillis();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touching = false;
                    if (action == MotionEvent.ACTION_CANCEL) Log.d(TAG, "title touch cancelled");
                }
                return original.onTouch(view, event);
            }
        };
        v.setOnTouchListener(listener);
        Log.i(TAG, "title listener installed/recovered view=" + System.identityHashCode(v));
    }

    private static void cancelTouch() {
        View v = title.get();
        if (listener != null && v != null) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try { listener.onTouch(v, cancel); } finally { cancel.recycle(); }
        }
        touching = false;
    }

    public static void clear() {
        cancelTouch();
        title.clear();
        listener = null;
    }

    public static void stop() {
        // Cancel the original timer while the wrapper is still enabled.
        clear();
        enabled = false;
        MAIN.removeCallbacks(CHECK);
        Log.i(TAG, "recovery suspended: no gesture sources");
    }
}
