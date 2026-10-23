package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Conversation notifications do not invalidate AddressLiveList's snapshot or its SQL data source.
 * Keep weak references even while the hiding switch is off so both enabling and disabling it can
 * reload an already-created address book. Never change contacts, native snapshots or adapter indices.
 */
@Feature(name = "密友通讯录刷新服务", categories = ["API"], description = "密友显示状态变化时重新加载通讯录")
object HiddenContactContactRefresh : ApiFeature(), IResolveDex {
    private const val TAG = "HiddenContact.Contacts"
    private const val MVVM_LIST = "com.tencent.mm.plugin.mvvmlist.MvvmList"
    private const val MAX_TRACKED_LISTS = 8
    private const val MAX_ATTEMPTS = 3

    private val addressPreprocess by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
            paramTypes("java.util.List")
        }
    }

    // Kotlin's default-argument bridge supplies the list's own configuration (mask = 1).
    // Verified on 8.0.78 (w) and 8.0.79 (v): RefreshAll -> dataSource.onCreate/load.
    private val submitRefreshAll by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = MVVM_LIST
            usingEqStrings("Super calls with default arguments not supported in this target, function: submitRefreshAll")
            paramCount = 4
            returnType = "void"
        }
    }

    // submitRefreshAll ignores requests while loading. Queue another refresh when loading ends,
    // including when SHOW/HIDE changes again before an older asynchronous query finishes.
    private val updateLoadingState by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = MVVM_LIST
            usingEqStrings("updateLoadingDataState:")
            paramTypes("int", "boolean")
            returnType = "void"
        }
    }

    private val submitChange by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = MVVM_LIST
            usingEqStrings("submitChange type:")
            paramCount = 4
            returnType = "void"
        }
    }

    private class TrackedList(value: Any) {
        val reference = WeakReference(value)
        var loading = false
        var requestedRevision = -1L
        var loadingRevision = -1L
        var completedRevision = -1L
        var loadId: Int? = null
        var submitted = false
        var attempts = 0
        val retryTask = Runnable { HiddenContactContactRefresh.flush(this) }
    }

    // All access to entries is confined to the main thread; no Activity or contact data is retained.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val entries = ArrayList<TrackedList>()
    private val revision = AtomicLong()
    private var tracksLoading = false
    private val refreshTask = Runnable {
        if (!isActive) return@Runnable
        entries.removeAll { it.reference.get() == null }
        entries.toList().forEach {
            val currentRevision = revision.get()
            if (it.requestedRevision != currentRevision) {
                it.requestedRevision = currentRevision
                it.attempts = 0
            }
            flush(it)
        }
    }

    override fun onEnable() {
        if (addressPreprocess.isPlaceholder || submitRefreshAll.isPlaceholder) {
            WeLogger.w(TAG, "address-book refresh unavailable: native targets were not resolved")
            return
        }
        val refresh = submitRefreshAll.method
        val parameters = refresh.parameterTypes
        if (!Modifier.isStatic(refresh.modifiers) || parameters.size != 4 ||
            parameters[0].name != MVVM_LIST || parameters[2] != Int::class.javaPrimitiveType ||
            parameters[3] != Any::class.java || refresh.returnType != Void.TYPE) {
            WeLogger.w(TAG, "address-book refresh unavailable: unsupported native bridge signature")
            return
        }

        addressPreprocess.method.declaringClass.declaredConstructors.forEach { constructor ->
            constructor.hookAfter { track(thisObject) }
        }
        // Both inspected versions have one AtomicInteger containing the current load ID.
        // A stale callback is ignored by WeChat itself and must not change our state either.
        val currentLoadId = refresh.declaringClass.declaredFields.singleOrNull {
            it.type == AtomicInteger::class.java
        }?.apply { isAccessible = true }
        fun isCurrentLoad(owner: Any, loadId: Int): Boolean =
            (currentLoadId?.get(owner) as? AtomicInteger)?.get() == loadId
        tracksLoading = !updateLoadingState.isPlaceholder && !submitChange.isPlaceholder && currentLoadId != null
        if (tracksLoading) {
            updateLoadingState.hookAfter {
                val owner = thisObject
                val loadId = args.getOrNull(0) as? Int ?: return@hookAfter
                val loading = args.getOrNull(1) as? Boolean ?: return@hookAfter
                if (!isCurrentLoad(owner, loadId)) return@hookAfter
                // Capture at the native load boundary, not later in the main-thread Runnable:
                // a SHOW/HIDE arriving during this load must remain pending until it finishes.
                val startedRevision = revision.get()
                onMain {
                    val entry = entries.firstOrNull { it.reference.get() === owner } ?: return@onMain
                    entry.loading = loading
                    if (loading) {
                        entry.loadId = loadId
                        entry.loadingRevision = startedRevision
                    } else {
                        entry.submitted = false
                        // The native collector sets loading=false BEFORE submitting its snapshot.
                        // Let submitChange acknowledge it first. Retry a missing completion only
                        // a bounded number of times (e.g. a data source emits no page).
                        mainHandler.removeCallbacks(entry.retryTask)
                        mainHandler.postDelayed(entry.retryTask, 700L)
                    }
                }
            }
            submitChange.hookAfter {
                val owner = thisObject
                if ((args.getOrNull(0) as? Enum<*>)?.name != "RefreshAll") return@hookAfter
                val loadId = args.getOrNull(2) as? Int ?: return@hookAfter
                if (!isCurrentLoad(owner, loadId)) return@hookAfter
                onMain {
                    val entry = entries.firstOrNull { it.reference.get() === owner } ?: return@onMain
                    // Loading an extra page or redrawing a cached snapshot does not restore
                    // missing contacts. Only acknowledge a full reload's submitted snapshot.
                    if (entry.loadId == loadId) {
                        entry.completedRevision = maxOf(entry.completedRevision, entry.loadingRevision)
                    }
                    flush(entry)
                }
            }
        }
        // Lifecycle method names/event enums are not obfuscated. Remove destroyed lists promptly.
        refresh.declaringClass.declaredMethods.firstOrNull {
            it.name == "onStateChanged" && it.parameterCount == 2
        }?.hookAfter {
            if ((args.getOrNull(1) as? Enum<*>)?.name == "ON_DESTROY") {
                val owner = thisObject
                onMain {
                    entries.filter { it.reference.get() == null || it.reference.get() === owner }.forEach {
                        mainHandler.removeCallbacks(it.retryTask)
                        entries.remove(it)
                    }
                }
            }
        }
        WeLogger.i(TAG, "native address-book refresh ready, loading notifications=$tracksLoading")
    }

    private fun track(owner: Any) = onMain {
        entries.removeAll { it.reference.get() == null }
        if (entries.any { it.reference.get() === owner }) return@onMain
        if (entries.size >= MAX_TRACKED_LISTS) mainHandler.removeCallbacks(entries.removeAt(0).retryTask)
        entries += TrackedList(owner)
    }

    /** Post once so all related preference/filter changes finish before the host starts its query. */
    fun requestRefresh() {
        revision.incrementAndGet()
        mainHandler.removeCallbacks(refreshTask)
        mainHandler.post(refreshTask)
    }

    private fun flush(entry: TrackedList) {
        mainHandler.removeCallbacks(entry.retryTask)
        if (entry !in entries) return
        if (entry.requestedRevision <= entry.completedRevision || entry.loading || entry.submitted || !isActive) return
        if (entry.attempts >= MAX_ATTEMPTS) return
        entry.attempts++
        val owner = entry.reference.get() ?: return
        entry.submitted = true
        val success = runCatching {
            submitRefreshAll.method.invoke(null, owner, null, 1, null)
        }.onFailure {
            entry.submitted = false
            WeLogger.w(TAG, "native address-book refresh failed", it)
        }.isSuccess
        if (!success) return
        WeLogger.d(TAG, "requested native address-book reload")
        if (!tracksLoading) {
            // Older unrecognised loading-state implementations: one bounded retry, never polling.
            mainHandler.postDelayed({
                entry.submitted = false
                if (!isActive || entry !in entries) return@postDelayed
                val current = entry.reference.get() ?: return@postDelayed
                entry.completedRevision = entry.requestedRevision
                runCatching { submitRefreshAll.method.invoke(null, current, null, 1, null) }
                    .onFailure { WeLogger.w(TAG, "deferred address-book refresh failed", it) }
            }, 700L)
        }
    }

    override fun onDisable() {
        mainHandler.removeCallbacks(refreshTask)
        onMain {
            entries.forEach { mainHandler.removeCallbacks(it.retryTask) }
            entries.clear()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }
}
