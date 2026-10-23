package com.Johnny.wcx.features.items.contacts.hidecontacts.addons;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.List;

/** Independent chat-return and home/app-leave switches; chat/profile navigation keeps visibility. */
public final class ConversationReturnRecovery {
    private static final String TAG = "WCXReturnFix";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Application app;
    private static WeakReference<Activity> foreground = new WeakReference<Activity>(null);
    private static boolean awaitingHome;
    private static boolean leavingHome;
    private static int checks;

    private static boolean chatEnabled() {
        return HiddenContactAddonState.INSTANCE.isAvailable() && AutoRestoreOnLeave.INSTANCE.isActive();
    }
    private static boolean appEnabled() {
        return HiddenContactAddonState.INSTANCE.isAvailable() && AutoRestoreOnAppLeave.INSTANCE.isActive();
    }
    private static boolean isHome(Activity a) {
        return a != null && "com.tencent.mm.ui.LauncherUI".equals(a.getClass().getName());
    }
    private static boolean isChat(Activity a) {
        return a != null && "com.tencent.mm.ui.chatting.ChattingUI".equals(a.getClass().getName());
    }
    private static boolean isContactProfile(Activity a) {
        return a != null && "com.tencent.mm.plugin.profile.ui.ContactInfoUI".equals(a.getClass().getName());
    }
    private static void restore(String reason) {
        awaitingHome = false;
        leavingHome = false;
        MAIN.removeCallbacks(CHECK);
        if (!HiddenContactAddonState.INSTANCE.isTemporarilyShown()) return;
        Log.i(TAG, reason);
        HiddenContactAddonState.INSTANCE.tempOff(null);
    }
    private static final Runnable CHECK = new Runnable() {
        public void run() {
            if (!chatEnabled()) awaitingHome = false;
            if (!appEnabled()) leavingHome = false;
            if (!chatEnabled() && !appEnabled()) return;
            Activity current = foreground.get();
            if (current != null) {
                if (isHome(current) && awaitingHome) {
                    awaitingHome = false;
                    if (chatEnabled()) restore("chat switch: returned from conversation to home; restore hiding");
                }
                return;
            }
            if (!appEnabled()) return;
            // A pause can be an intra-app transition. Confirm actual process background state.
            if (app != null) {
                ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
                if (processes != null) for (ActivityManager.RunningAppProcessInfo p : processes) {
                    if (p.pid == Process.myPid() && p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        restore("app switch: WeChat moved to background; restore hiding");
                        return;
                    }
                }
            }
            if (++checks < 20) MAIN.postDelayed(this, 250);
        }
    };
    private static void checkLater() {
        checks = 0;
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, 200);
    }
    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
        public void onActivityResumed(Activity a) {
            foreground = new WeakReference<Activity>(a);
            MAIN.removeCallbacks(CHECK);
            boolean homeDeparture = leavingHome;
            leavingHome = false;
            if (!chatEnabled()) awaitingHome = false;
            if (isHome(a)) {
                if (awaitingHome) checkLater();
            } else if (isChat(a)) {
                awaitingHome = false;
                Log.d(TAG, "conversation resumed; keep temporary visibility");
            } else if (isContactProfile(a)) {
                // LauncherUI also hosts the address book. Opening a contact's profile is part
                // of browsing contacts, not a reason to hide them again. Preserve awaitingHome
                // when coming from a chat so its eventual return home still obeys chatEnabled().
                Log.d(TAG, "contact profile resumed; keep temporary visibility");
            } else if (homeDeparture && appEnabled()) {
                restore("app switch: left home for a page outside chat/profile; restore hiding");
            }
        }
        public void onActivityPaused(Activity a) {
            if (foreground.get() == a) foreground.clear();
            if (!chatEnabled()) awaitingHome = false;
            if (isChat(a) && chatEnabled()) awaitingHome = true;
            if (isHome(a)) leavingHome = appEnabled();
            checkLater();
        }
        public void onActivityStopped(Activity a) {
            if (appEnabled() && foreground.get() == null) checkLater();
        }
        public void onActivityDestroyed(Activity a) {
            if (foreground.get() == a) foreground.clear();
        }
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) {}
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    };
    public static void bootstrap() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { bootstrap(); } });
            return;
        }
        if (app != null) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Application candidate = (Application) at.getMethod("currentApplication").invoke(null);
            if (candidate != null) register(candidate);
        } catch (Exception e) {
            Log.w(TAG, "return lifecycle registration deferred until activity available");
        }
    }
    public static void watch(Activity a) {
        if (a == null) return;
        register(a.getApplication());
        // Title binding may register after this Activity's resume callback has completed.
        if (foreground.get() == null && a.getWindow() != null && a.getWindow().getDecorView().hasWindowFocus()) {
            foreground = new WeakReference<Activity>(a);
        }
    }
    private static void register(Application candidate) {
        if (app != null) return;
        candidate.registerActivityLifecycleCallbacks(CALLBACKS);
        app = candidate;
        Log.i(TAG, "return recovery v7 lifecycle registered; chat/profile navigation keeps visibility");
    }
    public static void flagsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { flagsChanged(); } });
            return;
        }
        if (!chatEnabled()) awaitingHome = false;
        if (!appEnabled()) leavingHome = false;
        Log.i(TAG, "split switches chat=" + chatEnabled() + "; app=" + appEnabled());
    }
    public static void homePaused(Activity a) {
        watch(a);
        if (foreground.get() == a) foreground.clear();
        leavingHome = appEnabled();
        checkLater();
    }
    public static void chatPaused(Activity a) {
        watch(a);
        awaitingHome = chatEnabled();
        if (foreground.get() == a) foreground.clear();
        checkLater();
    }
}
