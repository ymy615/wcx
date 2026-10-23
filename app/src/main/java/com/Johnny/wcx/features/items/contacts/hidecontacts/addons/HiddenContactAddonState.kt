package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.HostInfo
import com.tencent.mm.ui.LauncherUI

/** Add-ons share the upstream list and visibility flag; there is no second hiding engine. */
object HiddenContactAddonState : WeDatabaseListenerApi.IInsertListener {
    private val main = Handler(Looper.getMainLooper())
    private var listening = false

    fun isAvailable() = HideContacts.isActive && Preferences.hiddenContactsMasterEnabled
    fun getStoredWxIds(): Set<String> = HideContacts.hiddenContacts
    fun getWxIds(): Set<String> = if (isAvailable()) getStoredWxIds() else emptySet()
    fun isStoredSecret(id: String) = id in getStoredWxIds()
    fun isTemporarilyShown() = isAvailable() && HideContacts.isTemporarilyShown
    fun setWxIds(ids: Set<String>) { HideContacts.hiddenContacts = ids }
    fun tempOff(context: Context?) { HideContacts.restoreTemporaryVisibility(context) }
    @JvmStatic fun reminderPrefs() = WePrefs.default

    var toastTempShown by prefOption("secret_friend_toast_temp_shown", "已临时显示密友")
    var toastTempOff by prefOption("secret_friend_toast_temp_off", "密友已恢复隐藏")
    var toastAdded by prefOption("secret_friend_toast_added", "已加入密友")
    var toastRemoved by prefOption("secret_friend_toast_removed", "已取消密友")
    var addMenuText by prefOption("secret_friend_add_menu_text", "加入密友")
    var removeMenuText by prefOption("secret_friend_remove_menu_text", "取消密友")
    // Empty overrides keep the old shared menu label until the user customizes each entry.
    var contactAddMenuText by prefOption("hidden_contact_contact_add_text", "")
    var contactRemoveMenuText by prefOption("hidden_contact_contact_remove_text", "")

    fun showToast(context: Context?, message: String) {
        if (SecretFriendToastToggle.isActive && message.isNotBlank()) {
            HiddenContactToast.show(context ?: HostInfo.application, message)
        }
    }

    fun start() {
        if (!listening) { WeDatabaseListenerApi.addListener(this); listening = true }
        refresh()
    }

    fun stop() {
        if (listening) { WeDatabaseListenerApi.removeListener(this); listening = false }
        refresh()
    }

    fun refresh() {
        main.post {
            ConversationReturnRecovery.bootstrap()
            ConversationReturnRecovery.flagsChanged()
            HiddenContactIndicators.bootstrap()
            HiddenContactTitleGesture.refresh()
            val home = runCatching { LauncherUI.getInstance() }.getOrNull()
            if (home != null && home.window?.decorView?.hasWindowFocus() == true) watch(home)
            HiddenContactIndicators.stateChanged()
        }
    }

    fun watch(activity: Activity) {
        ConversationReturnRecovery.watch(activity)
        HiddenContactIndicators.watch(activity)
        HiddenContactTitleGesture.watch(activity)
    }

    fun tracksUnread() = isAvailable() && (RedDotPrompt.isActive || BottomBarBold.isActive)

    override fun onInsert(table: String, values: ContentValues) {
        if (!tracksUnread() || !table.equals("message", ignoreCase = true)) return
        if (values.getAsInteger("isSend") != 0) return
        val talker = values.getAsString("talker") ?: return
        if (talker in getWxIds()) HiddenContactIndicators.received(talker)
    }
}
