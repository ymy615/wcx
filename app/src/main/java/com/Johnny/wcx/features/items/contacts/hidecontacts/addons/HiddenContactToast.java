package com.Johnny.wcx.features.items.contacts.hidecontacts.addons;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/** Only the secret-friend operation feedback uses this shorter lifetime. */
public final class HiddenContactToast {
    private static final String TAG = "WCXToastTime";
    private static final long VISIBLE_MS = 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Toast active;
    private static Runnable pending;

    public static void show(final Context context, final String message) {
        if (context == null || message == null) return;
        Runnable action = new Runnable() {
            public void run() {
                if (pending != null) MAIN.removeCallbacks(pending);
                if (active != null) active.cancel();
                final Toast toast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
                active = toast;
                pending = null;
                if (Build.VERSION.SDK_INT >= 30) Api30.attach(toast);
                toast.show();
                if (Build.VERSION.SDK_INT < 30) schedule(toast);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else MAIN.post(action);
    }

    private static void schedule(final Toast toast) {
        if (active != toast) return;
        pending = new Runnable() {
            public void run() {
                if (active != toast) return;
                Log.i(TAG, "cancel requested after 1000ms visible timer");
                toast.cancel();
                pending = null;
            }
        };
        MAIN.postDelayed(pending, VISIBLE_MS);
    }

    /** Isolate the API 30 callback class for older supported devices. */
    private static final class Api30 {
        static void attach(final Toast toast) {
            toast.addCallback(new Toast.Callback() {
                private long shownAt;
                public void onToastShown() {
                    shownAt = SystemClock.uptimeMillis();
                    Log.i(TAG, "toast shown; visible target=1000ms");
                    schedule(toast);
                }
                public void onToastHidden() {
                    if (shownAt != 0) Log.i(TAG, "toast hidden after " + (SystemClock.uptimeMillis() - shownAt) + "ms");
                    if (active != toast) return;
                    if (pending != null) MAIN.removeCallbacks(pending);
                    pending = null;
                    active = null;
                }
            });
        }
    }
}
