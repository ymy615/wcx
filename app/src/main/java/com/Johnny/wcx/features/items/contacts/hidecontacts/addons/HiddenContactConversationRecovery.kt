package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import android.content.ContentValues
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.features.items.contacts.HideContacts
import android.util.Base64
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import org.json.JSONObject

/** One-time migration from versions that removed conversation rows to hide them. */
internal object HiddenContactConversationRecovery {
    private const val TAG = "HiddenContactConversationRecovery"
    private const val COMPLETED = "secret_friend_conversation_recovery_v3"
    private const val SNAPSHOTS = "secret_friend_row_snapshots_v2"

    // Freeze the upgrade population once. Contacts added after this repair are never recreated
    // merely because the user chooses WeChat's "do not display this chat" or deletes a chat.
    private const val CANDIDATES = "hidden_contact_recovery_candidates_v1"
    private const val CAPTURED = "hidden_contact_recovery_candidates_captured_v1"
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var attempts = 0
    private val failures = HashMap<String, Int>()
    private val recoveryTask = object : Runnable {
        override fun run() {
            if (!HiddenContactAddonState.isAvailable() || !HideContacts.isTemporarilyShown) return
            if (!WeDatabaseApi.isReady) {
                if (++attempts < 3) handler.postDelayed(this, 700L)
                return
            }
            restore()
            WeConversationApi.reloadConversations()
        }
    }

    fun captureUpgradeCandidates() {
        if (WePrefs.getBoolOrFalse(CAPTURED)) return
        WePrefs.putStringSet(CANDIDATES, HideContacts.hiddenContacts)
        WePrefs.putBool(CAPTURED, true)
    }

    fun requestRecovery() {
        handler.removeCallbacks(recoveryTask)
        attempts = 0
        handler.post(recoveryTask)
    }

    private fun restore(): Int {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { restore() }
            return 0
        }
        if (!WeDatabaseApi.isReady) return 0
        val completed = WePrefs.getStringSetOrDef(COMPLETED, emptySet()).toMutableSet()
        val secrets = HideContacts.hiddenContacts
        val legacyHidden = WePrefs.getStringSetOrDef(CANDIDATES, emptySet())
        var existing = 0
        var restored = 0
        var revealed = 0
        var withoutHistory = 0
        var failed = 0
        val snapshots by lazy {
            runCatching { JSONObject(WePrefs.getStringOrDef(SNAPSHOTS, "{}")) }
                .getOrDefault(JSONObject())
        }
        // Completed migrations are never replayed, including after a later manual chat deletion.
        for (talker in secrets) {
            if (talker in completed || (failures[talker] ?: 0) >= 3) continue
            runCatching {
                val present = WeConversationApi.getConversationForRecovery(talker)
                if (present != null) {
                    existing++
                    if (hasLegacyParent(talker)) {
                        WeConversationApi.setConversationsVisibility(true, arrayOf(talker))
                        revealed++
                    }
                    completed.add(talker)
                    return@runCatching
                }
                val snapshot = snapshots.optJSONObject(talker)
                val validSnapshot = snapshot?.optJSONObject("username")?.optString("v") == talker
                // Only the frozen upgrade list or an actual legacy snapshot authorizes recovery.
                if (talker !in legacyHidden && !validSnapshot) return@runCatching
                val conversation = WeConversationApi.newConversationForRecovery(talker)
                if (validSnapshot && snapshot != null) {
                    applySnapshot(conversation, snapshot)
                } else if (!applyLastMessage(conversation, talker)) {
                    withoutHistory++
                    completed.add(talker)
                    return@runCatching
                }
                check(toValues(conversation).getAsString("username") == talker)
                // Native insertion computes sort flags and notifies the live list. It does not
                // replay message arrival, clear unread status, or replace an existing row.
                if (WeConversationApi.getConversationForRecovery(talker) != null) {
                    completed.add(talker)
                    return@runCatching
                }
                check(WeConversationApi.insertConversationForRecovery(conversation)) { "Native insertion failed" }
                check(WeConversationApi.getConversationForRecovery(talker) != null) { "Inserted conversation not readable" }
                completed.add(talker)
                restored++
            }.onFailure {
                failed++
                failures[talker] = (failures[talker] ?: 0) + 1
                WeLogger.w(TAG, "one legacy conversation could not be recovered", it)
            }
        }
        WePrefs.putStringSet(COMPLETED, completed)
        WeLogger.i(TAG, "configured=${secrets.size}, existing=$existing, restored=$restored, revealed=$revealed, withoutHistory=$withoutHistory, failed=$failed")
        return restored
    }

    private fun applyLastMessage(conversation: Any, talker: String): Boolean {
        val copyMessage = conversation.javaClass.declaredMethods.singleOrNull {
            it.returnType == Void.TYPE && it.parameterCount == 1 &&
                it.parameterTypes[0].name.startsWith("com.tencent.mm.storage.")
        } ?: error("Native conversation message converter unavailable")
        val messageClass = copyMessage.parameterTypes[0]
        val message = messageClass.getConstructor().newInstance()
        WeDatabaseApi.rawQuery(
            "SELECT * FROM message WHERE talker = ? ORDER BY createTime DESC, msgId DESC LIMIT 1",
            arrayOf<Any>(talker)
        ).use {
            if (!it.moveToFirst()) return false
            messageClass.getMethod("convertFrom", Cursor::class.java).invoke(message, it)
        }
        copyMessage.isAccessible = true
        copyMessage.invoke(conversation, message)
        val values = ContentValues().apply {
            put("msgType", messageClass.getMethod("getType").invoke(message).toString())
        }
        WeDatabaseApi.rawQuery(
            "SELECT COUNT(*) FROM message WHERE talker = ?",
            arrayOf<Any>(talker)
        ).use {
            if (it.moveToFirst()) {
                values.put("msgCount", it.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
        }
        // Delivery status is not an unread counter. Keep native defaults when no snapshot exists;
        // the independent secret-message indicators retain their own pending state.
        applyValues(conversation, values)
        return true
    }

    private fun applySnapshot(conversation: Any, snapshot: JSONObject) {
        val values = ContentValues()
        for (column in snapshot.keys()) {
            if (column == "rowid") continue
            val entry = snapshot.optJSONObject(column) ?: continue
            when (entry.optString("t")) {
                "s" -> values.put(column, entry.getString("v"))
                "l" -> values.put(column, entry.getLong("v"))
                "d" -> values.put(column, entry.getDouble("v"))
                "b" -> values.put(column, Base64.decode(entry.getString("v"), Base64.NO_WRAP))
            }
        }
        if (values.getAsString("parentRef") == "hidden_conv_parent") {
            values.put("parentRef", "")
        }
        applyValues(conversation, values)
    }

    private fun toValues(conversation: Any): ContentValues =
        conversation.javaClass.getMethod("convertTo").invoke(conversation) as ContentValues

    private fun applyValues(conversation: Any, values: ContentValues) {
        // Native conversion also marks changed columns for insertion; model fields are no longer
        // public in recent WeChat releases. Do not depend on their visibility or obfuscated names.
        conversation.javaClass.getMethod("convertFrom", ContentValues::class.java, Boolean::class.javaPrimitiveType)
            .invoke(conversation, values, true)
    }

    private fun hasLegacyParent(talker: String): Boolean = WeDatabaseApi.rawQuery(
        "SELECT parentRef FROM rconversation WHERE username = ? LIMIT 1", arrayOf<Any>(talker)
    ).use { it.moveToFirst() && it.getString(0) == "hidden_conv_parent" }
}
