package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import android.app.Activity
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.tencent.mm.ui.LauncherUI
import java.lang.ref.WeakReference
import kotlin.math.abs

/** Bind when enabled as well as after home recreation; observe touch cancellation explicitly. */
object HiddenContactTitleGesture {
    private val main = Handler(Looper.getMainLooper())
    private var activity = WeakReference<Activity>(null)
    private var taps = 0
    private var lastTap = 0L
    var clickWindowMs by prefOption("secret_friend_unlock_click_window_ms", 1000)

    private fun enabled() = HiddenContactAddonState.isAvailable() && HideContacts.tripleClickTitle

    fun refresh() {
        main.post {
            taps = 0
            if (enabled()) {
                TitleGestureRecovery.bootstrap()
                val home = activity.get() ?: runCatching { LauncherUI.getInstance() }.getOrNull()
                if (home != null) watch(home)
            } else TitleGestureRecovery.stop()
        }
    }

    fun watch(home: Activity) {
        if (home.javaClass.name != "com.tencent.mm.ui.LauncherUI") return
        activity = WeakReference(home)
        TitleGestureRecovery.watch(home)
        home.window?.decorView?.post { attach(home) }
    }

    fun attach(home: Activity) {
        if (!enabled()) return
        val root = home.window?.decorView ?: return
        val title = findTitle(root) ?: return
        if (TitleGestureRecovery.healthy(title)) return
        taps = 0
        title.isClickable = true
        title.isLongClickable = true
        val slop = ViewConfiguration.get(title.context).scaledTouchSlop
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var cancelled = false
        TitleGestureRecovery.track(title) { view, event ->
            if (!enabled()) return@track false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    downX = event.x
                    downY = event.y
                    cancelled = false
                }
                MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    cancelled = true
                    taps = 0
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    if (!cancelled && now - downAt < 750) {
                        taps = if (now - lastTap > clickWindowMs.coerceIn(200, 5000)) 1 else taps + 1
                        lastTap = now
                        if (taps >= 3) {
                            taps = 0
                            HideContacts.toggleTemporarilyShown(view.context)
                        }
                    } else taps = 0
                }
                MotionEvent.ACTION_CANCEL -> { cancelled = true; taps = 0 }
            }
            true
        }
    }

    private fun findTitle(root: View): TextView? {
        val topBand = (root.resources.displayMetrics.heightPixels * .25f).toInt()
        val bounds = Rect()
        var best: TextView? = null
        var bestScore = -1
        fun visit(view: View, depth: Int) {
            if (depth > 20 || !view.isShown) return
            if (view is TextView && view.getGlobalVisibleRect(bounds) && bounds.top < topBand) {
                val text = view.text?.toString().orEmpty()
                if (view.id == android.R.id.text1 || text == "微信" || text == "WeChat" ||
                    (text.startsWith("微信(") && text.length <= 12)) {
                    val score = (if (view.id == android.R.id.text1) 20 else 0) + (if (view.isClickable) 40 else 0)
                    if (score >= bestScore) { best = view; bestScore = score }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        return best
    }
}
