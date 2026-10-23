package com.Johnny.wcx.features.items.contacts.hidecontacts.addons;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.Johnny.wcx.features.api.core.WeDatabaseApi;
import com.Johnny.wcx.features.api.core.WeConversationApi;
import com.Johnny.wcx.utils.HostInfo;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reconcile unread indicators with native read state, and show temporary visibility status. */
public final class HiddenContactIndicators {
    private static final String TAG = "WCXSecretUnread";
    private static final String PREF = "secret_friend_visual_pending_v5";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService READER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean READING = new AtomicBoolean();
    // All state below is owned by the main thread; the worker returns only immutable snapshots.
    private static final Set<String> pending = new HashSet<String>();
    private static final Map<String, Long> openedAt = new HashMap<String, Long>();
    private static final Map<String, Long> receivedAt = new HashMap<String, Long>();
    private static final WeakHashMap<TextView, OriginalPaint> paints = new WeakHashMap<TextView, OriginalPaint>();
    private static WeakReference<Activity> home = new WeakReference<Activity>(null);
    private static Application application;
    private static boolean loaded;
    private static boolean resumed;
    private static String currentChat;
    private static long queriedAt;
    private static FrameLayout dotRoot;
    private static View dot;
    private static int dotColor;
    private static String lastStatus = "";
    private static String lastDatabaseStatus = "";

    private static boolean isHome(Activity a) {
        return a != null && "com.tencent.mm.ui.LauncherUI".equals(a.getClass().getName());
    }
    private static boolean isChat(Activity a) {
        return a != null && "com.tencent.mm.ui.chatting.ChattingUI".equals(a.getClass().getName());
    }
    private static void load() {
        if (loaded) return;
        Set<String> stored = HiddenContactAddonState.reminderPrefs().getStringSet(PREF, Collections.<String>emptySet());
        if (stored != null) pending.addAll(stored);
        loaded = true;
    }
    private static void save() {
        HiddenContactAddonState.reminderPrefs().edit().putStringSet(PREF, new HashSet<String>(pending)).apply();
    }
    private static final Runnable TICK = new Runnable() {
        public void run() {
            if (!resumed) return;
            try { render(); } catch (RuntimeException e) { Log.w(TAG, "indicator refresh failed", e); }
            MAIN.removeCallbacks(this);
            if (resumed) MAIN.postDelayed(this, 1000);
        }
    };
    private static void refresh() {
        MAIN.removeCallbacks(TICK);
        if (resumed) MAIN.post(TICK);
    }
    public static void stateChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { stateChanged(); } });
            return;
        }
        refresh();
    }
    private static final Application.ActivityLifecycleCallbacks CALLBACKS = new Application.ActivityLifecycleCallbacks() {
        public void onActivityResumed(Activity a) {
            if (isHome(a)) { currentChat = null; watch(a); }
            else {
                resumed = false;
                MAIN.removeCallbacks(TICK);
                clearVisuals();
                currentChat = null;
                if (isChat(a)) acknowledge(a);
            }
        }
        public void onActivityPaused(Activity a) {
            if (home.get() == a) {
                resumed = false;
                MAIN.removeCallbacks(TICK);
                clearVisuals();
            }
            if (isChat(a)) currentChat = null;
        }
        public void onActivityDestroyed(Activity a) {
            if (home.get() == a) {
                resumed = false;
                home.clear();
                MAIN.removeCallbacks(TICK);
                clearVisuals();
                detachDot();
            }
        }
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) {}
        public void onActivityStopped(Activity a) {}
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    };
    public static void bootstrap() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { bootstrap(); } });
            return;
        }
        if (application != null) return;
        // HostInfo is initialized before features load. Register even when title gestures and
        // unread prompts are disabled, without waiting for ActivityThread's global application.
        register(HostInfo.INSTANCE.getApplication());
    }
    private static void register(Application app) {
        if (application != null) return;
        app.registerActivityLifecycleCallbacks(CALLBACKS);
        application = app;
        Log.i(TAG, "v6 unread lifecycle registered; reconcile confirmed native read state");
    }
    public static void watch(final Activity a) {
        if (!isHome(a)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { public void run() { watch(a); } });
            return;
        }
        register(a.getApplication());
        if (home.get() != a) { clearVisuals(); detachDot(); home = new WeakReference<Activity>(a); }
        resumed = true;
        refresh();
    }
    public static void received(final String talker) {
        if (talker == null) return;
        MAIN.post(new Runnable() {
            public void run() {
                load();
                if (talker.equals(currentChat) || !HiddenContactAddonState.INSTANCE.getWxIds().contains(talker)) return;
                openedAt.remove(talker);
                receivedAt.put(talker, SystemClock.uptimeMillis());
                if (pending.add(talker)) {
                    save();
                    Log.i(TAG, "hidden incoming message; pending chats=" + pending.size());
                }
                refresh();
            }
        });
    }
    private static void acknowledge(Activity a) {
        try {
            Intent intent = a.getIntent();
            String talker = intent == null ? null : intent.getStringExtra("Chat_User");
            if (talker == null && intent != null) talker = intent.getStringExtra("Contact_User");
            currentChat = talker;
            if (talker == null || !HiddenContactAddonState.INSTANCE.getWxIds().contains(talker)) return;
            load();
            openedAt.put(talker, SystemClock.uptimeMillis());
            receivedAt.remove(talker);
            boolean removed = pending.remove(talker);
            if (removed) save();
            Log.i(TAG, "matching secret chat opened; cleared=" + removed + "; remaining=" + pending.size());
        } catch (RuntimeException e) { Log.w(TAG, "chat identity unavailable; pending indicators retained", e); }
    }
    private static void queryUnread(final Set<String> secrets) {
        final long started = SystemClock.uptimeMillis();
        if (started - queriedAt < 3000 || !READING.compareAndSet(false, true)) return;
        queriedAt = started;
        READER.execute(new Runnable() {
            public void run() {
                final Map<String, Long> counts = new HashMap<String, Long>();
                int nativeReads = 0;
                int sqlReads = 0;
                int unread = 0;
                boolean ready = false;
                try {
                    ready = WeDatabaseApi.INSTANCE.isReady();
                    if (ready) for (String id : secrets) {
                        Long count = null;
                        try {
                            count = WeConversationApi.INSTANCE.getUnreadCountForIndicator(id);
                            nativeReads++;
                        } catch (Exception nativeUnavailable) {
                            // Read by column position: cursor column-name casing varies across
                            // WeChat versions. An absent/failed SQL row is unknown, never zero.
                            try (Cursor cursor = WeDatabaseApi.INSTANCE.rawQuery(
                                    "SELECT unReadCount, unReadMuteCount FROM rconversation WHERE username = ?",
                                    new Object[] { id })) {
                                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                                    count = Math.max(0L, Math.max(cursor.getLong(0), cursor.getLong(1)));
                                    sqlReads++;
                                }
                            } catch (Exception sqlUnavailable) {
                                // Retain insert tracking until this contact can be read reliably.
                            }
                        }
                        if (count != null) {
                            counts.put(id, count);
                            if (count > 0) unread++;
                        }
                    }
                } catch (RuntimeException e) { Log.w(TAG, "native unread lookup unavailable; insert tracking retained", e); }
                final String status = "ready=" + ready + "; confirmed=" + counts.size() + "; native=" + nativeReads
                        + "; sql=" + sqlReads + "; unknown=" + (secrets.size() - counts.size()) + "; unread=" + unread;
                MAIN.post(new Runnable() {
                    public void run() {
                        READING.set(false);
                        load();
                        Set<String> active = HiddenContactAddonState.INSTANCE.getWxIds();
                        boolean changed = false;
                        int added = 0;
                        int cleared = 0;
                        for (Map.Entry<String, Long> entry : counts.entrySet()) {
                            String id = entry.getKey();
                            if (!active.contains(id) || id.equals(currentChat)) continue;
                            Long opened = openedAt.get(id);
                            if (entry.getValue() > 0) {
                                // An in-flight snapshot must not reintroduce a chat just opened.
                                if ((opened == null || started > opened.longValue() + 3500) && pending.add(id)) {
                                    changed = true;
                                    added++;
                                }
                            } else {
                                Long received = receivedAt.get(id);
                                // Insert callbacks can precede the native unread update. Give it
                                // time to commit, and reject snapshots older than that callback.
                                if ((received == null || started > received.longValue() + 3500) && pending.remove(id)) {
                                    changed = true;
                                    cleared++;
                                    receivedAt.remove(id);
                                }
                            }
                        }
                        if (changed) {
                            save();
                            Log.i(TAG, "native unread reconciled; added=" + added + "; cleared=" + cleared
                                    + "; pending chats=" + pending.size());
                        }
                        if (!status.equals(lastDatabaseStatus)) {
                            lastDatabaseStatus = status;
                            Log.d(TAG, "native unread snapshot: " + status);
                        }
                        if (changed) refresh();
                    }
                });
            }
        });
    }
    private static final class OriginalPaint {
        final boolean fakeBold;
        final Paint.Style style;
        final float stroke;
        OriginalPaint(TextView v) {
            fakeBold = v.getPaint().isFakeBoldText();
            style = v.getPaint().getStyle();
            stroke = v.getPaint().getStrokeWidth();
        }
        void restore(TextView v) {
            v.getPaint().setFakeBoldText(fakeBold);
            v.getPaint().setStyle(style);
            v.getPaint().setStrokeWidth(stroke);
            v.invalidate();
        }
    }
    private static void clearVisuals() {
        for (Map.Entry<TextView, OriginalPaint> e : paints.entrySet()) if (e.getKey() != null) e.getValue().restore(e.getKey());
        paints.clear();
        if (dot != null) dot.setVisibility(View.INVISIBLE);
    }
    private static void detachDot() {
        if (dot != null && dot.getParent() instanceof ViewGroup) ((ViewGroup) dot.getParent()).removeView(dot);
        dot = null;
        dotRoot = null;
        dotColor = 0;
    }
    private static boolean visible(View v, Rect bounds) {
        return v.getVisibility() == View.VISIBLE && v.isShown() && v.getGlobalVisibleRect(bounds)
                && bounds.width() > 0 && bounds.height() > 0;
    }
    private static final class Targets {
        final ArrayList<TextView> labels = new ArrayList<TextView>();
        View search;
        int searchScore;
    }
    private static void collect(View v, Rect root, Targets targets, int depth) {
        if (v == dot || depth > 40 || v.getVisibility() != View.VISIBLE) return;
        Rect bounds = new Rect();
        if (visible(v, bounds)) {
            CharSequence desc = v.getContentDescription();
            String text = v instanceof TextView ? String.valueOf(((TextView) v).getText()) : "";
            if (v instanceof TextView && ("微信".equals(text) || "WeChat".equals(text))
                    && bounds.top > root.top + root.height() * 0.65f) targets.labels.add((TextView) v);
            if (bounds.bottom < root.top + root.height() * 0.3f) {
                int score = 0;
                if ("搜索".contentEquals(desc == null ? "" : desc) || "Search".contentEquals(desc == null ? "" : desc)) score = 3;
                else if ("搜索".equals(text) || "Search".equals(text)) score = 1;
                if (score > 0 && score >= targets.searchScore) { targets.search = v; targets.searchScore = score; }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), root, targets, depth + 1);
        }
    }
    private static boolean showDot(FrameLayout root, View search, int color, String description) {
        if (search == null) { if (dot != null) dot.setVisibility(View.INVISIBLE); return false; }
        if (dotRoot != root) { detachDot(); dotRoot = root; }
        int size = Math.max(1, Math.round(Math.max(4, Math.min(12, RedDotPrompt.INSTANCE.getDotSizeDp()))
                * root.getResources().getDisplayMetrics().density));
        if (dot == null) {
            dot = new View(root.getContext());
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            dot.setBackground(background);
            dot.setClickable(false);
            dot.setFocusable(false);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(dot, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        }
        if (dotColor != color) {
            ((GradientDrawable) dot.getBackground()).setColor(color);
            dotColor = color;
        }
        dot.setContentDescription(description);
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        Rect anchor = new Rect();
        if (!visible(search, anchor)) { dot.setVisibility(View.INVISIBLE); return false; }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dot.getLayoutParams();
        int x = Math.max(0, anchor.right - origin[0] - size - Math.round(7 * root.getResources().getDisplayMetrics().density));
        int y = Math.max(0, anchor.top - origin[1] + Math.round(7 * root.getResources().getDisplayMetrics().density));
        if (params.width != size || params.height != size || params.leftMargin != x || params.topMargin != y) {
            params.width = size; params.height = size; params.leftMargin = x; params.topMargin = y;
            dot.setLayoutParams(params);
        }
        dot.setVisibility(View.VISIBLE);
        dot.bringToFront();
        return true;
    }
    private static void render() {
        Activity a = home.get();
        if (a == null || a.isFinishing() || a.getWindow() == null) return;
        View decor = a.getWindow().getDecorView();
        if (!decor.hasWindowFocus() || !decor.isShown()) { clearVisuals(); return; }
        load();
        if (!HiddenContactAddonState.INSTANCE.isAvailable()) { clearVisuals(); return; }
        boolean boldEnabled = BottomBarBold.INSTANCE.isActive();
        boolean dotEnabled = RedDotPrompt.INSTANCE.isActive();
        boolean shownDotEnabled = SecretFriendShownPrompt.INSTANCE.isActive();
        Set<String> secrets = new HashSet<String>(HiddenContactAddonState.INSTANCE.getWxIds());
        // Removing a secret prunes its state; disabling the master only suspends visuals.
        Set<String> stored = HiddenContactAddonState.INSTANCE.getStoredWxIds();
        if (pending.retainAll(stored)) save();
        openedAt.keySet().retainAll(stored);
        receivedAt.keySet().retainAll(stored);
        if ((boldEnabled || dotEnabled) && !secrets.isEmpty()) queryUnread(secrets);
        boolean hidden = !HiddenContactAddonState.INSTANCE.isTemporarilyShown();
        boolean active = hidden && !Collections.disjoint(pending, secrets);
        Rect bounds = new Rect();
        Targets targets = new Targets();
        if (visible(decor, bounds)) collect(decor, bounds, targets, 0);
        boolean bold = active && boldEnabled;
        Iterator<Map.Entry<TextView, OriginalPaint>> previous = paints.entrySet().iterator();
        while (previous.hasNext()) {
            Map.Entry<TextView, OriginalPaint> e = previous.next();
            if (!bold || !targets.labels.contains(e.getKey())) { if (e.getKey() != null) e.getValue().restore(e.getKey()); previous.remove(); }
        }
        if (bold) for (TextView label : targets.labels) {
            if (!paints.containsKey(label)) paints.put(label, new OriginalPaint(label));
            label.getPaint().setFakeBoldText(true);
            label.getPaint().setStyle(Paint.Style.FILL_AND_STROKE);
            label.getPaint().setStrokeWidth(Math.max(1, Math.min(5, BottomBarBold.INSTANCE.getBoldStrength())) * 0.4f);
            label.invalidate();
        }
        // The shared overlay represents one state at a time. Switching its color never
        // acknowledges messages: pending unread state remains until the corresponding chat opens.
        boolean shownDot = shownDotEnabled && !hidden && !secrets.isEmpty();
        boolean unreadDot = active && dotEnabled;
        boolean dotted = (shownDot || unreadDot) && decor instanceof FrameLayout
                && showDot((FrameLayout) decor, targets.search, shownDot ? 0xff07c160 : 0xfffa5151,
                        shownDot ? "密友正在临时显示" : "密友未读提示");
        if (!dotted && dot != null) dot.setVisibility(View.INVISIBLE);
        String status = "pending=" + pending.size() + "; hidden=" + hidden + "; boldEnabled=" + boldEnabled
                + "; dotEnabled=" + dotEnabled + "; shownDotEnabled=" + shownDotEnabled + "; bottomLabels=" + targets.labels.size()
                + "; search=" + (targets.search != null) + "; bold=" + (bold && !targets.labels.isEmpty()) + "; dot=" + dotted
                + "; dotColor=" + (dotted ? (shownDot ? "green" : "red") : "none");
        if (!status.equals(lastStatus)) { lastStatus = status; Log.i(TAG, status); }
    }
}
