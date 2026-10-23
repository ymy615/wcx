package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.view.MenuItem
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.ui.WeConversationContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.VisibilityOffIcon
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.baseActivity
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 添加密友组 28 / 29：会话列表长按添加、通讯录长按添加。
 * 菜单文字共用 [HiddenContactAddonState.addMenuText]。
 */
private const val TAG_ADD = "SecretFriend.Add"

// ─────────────────────── 28. 会话列表长按添加 ───────────────────────

/**
 * 会话列表长按添加（浮云「会话列表长按添加」语义）：主页会话长按菜单追加「加入密友」。
 * 实现走工程已验证的 [WeConversationContextMenuApi]（ConversationLongClickListener 锚点），
 * talker 直接来自菜单上下文，注入与点击都由该 API 完整托管。
 */
@Feature(
    name = "会话列表长按添加",
    categories = ["密友功能"],
    description = "主页会话列表长按菜单追加「加入密友/取消密友」，菜单文字可在「菜单显示文字」自定义"
)
object LongPressAddFromChat : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {
    override val defaultEnabled = true

    private const val TAG = "LongPressAddFromChat"

    private val MENU_ID = "secret_friend_conv_add".hashCode()

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            MENU_ID,
            HiddenContactAddonState.addMenuText,
            VisibilityOffIcon,
            { context, _ ->
                val talker = context.talker
                isActive && talker.isNotEmpty() && !talker.endsWith("@chatroom")
            },
            textForContext = { context -> secretMenuText(context.talker) },
        ) { context ->
            if (isActive) toggleSecret(context.activity, context.talker)
        }
    )
}

/**
 * 密友名单增删（两个添加开关共用）：已在名单内则移除，否则加入。
 * 显式用户操作，直接 Toast（需要操作反馈，不受「操作提示」总开关影响）。
 */
internal fun toggleSecret(activity: Activity, wxId: String) {
    if (wxId.isEmpty()) return
    // 增删名单走「存储名单」：主控开关关闭时也应能维护名单（下次开启直接生效）
    if (HiddenContactAddonState.isStoredSecret(wxId)) {
        HiddenContactAddonState.setWxIds(HiddenContactAddonState.getStoredWxIds() - wxId)
        HiddenContactAddonState.showToast(activity, HiddenContactAddonState.toastRemoved)
        WeLogger.i(TAG_ADD, "removed a contact from upstream hidden list via long-press menu")
    } else {
        HiddenContactAddonState.setWxIds(HiddenContactAddonState.getStoredWxIds() + wxId)
        HiddenContactAddonState.showToast(activity, HiddenContactAddonState.toastAdded)
        WeLogger.i(TAG_ADD, "added a contact to upstream hidden list via long-press menu")
    }
}

internal fun secretMenuText(wxId: String, fromContacts: Boolean = false): String {
    val added = HiddenContactAddonState.isStoredSecret(wxId)
    val shared = if (added) HiddenContactAddonState.removeMenuText else HiddenContactAddonState.addMenuText
    val override = if (fromContacts) {
        if (added) HiddenContactAddonState.contactRemoveMenuText else HiddenContactAddonState.contactAddMenuText
    } else ""
    return override.ifBlank { shared }.ifBlank { if (added) "取消密友" else "加入密友" }
}

// ─────────────── 29. 通讯录长按添加 ───────────────

/**
 * 在微信原生通讯录长按菜单中追加密友操作。
 *
 * 8.0.79 的 MvvmAddressUIFragment 使用 RecyclerView，不会调用
 * AdapterView.setOnItemLongClickListener。改为在 MMPopupMenu.showMenu 调用前
 * 包装创建/点击回调，既覆盖 MVVM 列表，也能在列表创建后即时启停。
 * 联系人只从本次菜单捕获的 Contact 或列表行读取，不依赖混淆字段名或全局上次点击状态。
 */
@Feature(
    name = "通讯录长按添加",
    categories = ["密友功能"],
    description = "通讯录原生长按菜单追加「加入密友/取消密友」，保留微信原有选项；支持自定义菜单文字"
)
object LongPressAddFromContacts : SwitchFeature(), IResolveDex {
    override val defaultEnabled = true

    private const val TAG = "LongPressAddFromContacts"
    private val MENU_ID = "secret_friend_contact_add".hashCode()
    private const val CONTACT_PACKAGE = "com.tencent.mm.ui.contact."
    private const val STORAGE_PACKAGE = "com.tencent.mm.storage."

    // 与 ConversationAggregation 使用同一原生菜单入口，按签名和日志标签定位。
    private val methodShowPopupMenu by dexMethod(allowFailure = true) {
        matcher {
            declaredClass { usingStrings("MicroMsg.MMPopupMenu") }
            paramTypes(
                "android.view.View", "int", "long",
                $$"android.view.View$OnCreateContextMenuListener", null, "int", "int"
            )
            returnType("void")
        }
    }

    override fun onEnable() {
        if (methodShowPopupMenu.isPlaceholder) {
            WeLogger.w(TAG, "native contact popup not resolved; menu entry unavailable")
            return
        }
        val parameters = methodShowPopupMenu.method.parameterTypes
        if (parameters.size != 7) {
            WeLogger.w(TAG, "native contact popup signature changed; skipping")
            return
        }
        val callbackType = parameters[4]
        if (!callbackType.isInterface) {
            WeLogger.w(TAG, "native menu selection callback is not an interface")
            return
        }

        methodShowPopupMenu.hookBefore {
            val view = args[0] as? View ?: return@hookBefore
            val create = args[3] as? View.OnCreateContextMenuListener ?: return@hookBefore
            if (!isContactMenu(create)) return@hookBefore
            val select = args[4] ?: return@hookBefore
            if (!callbackType.isInstance(select)) return@hookBefore
            val position = args[1] as? Int ?: return@hookBefore
            val wxId = resolveContact(create, view, position) ?: return@hookBefore
            val activity = view.context.baseActivity ?: return@hookBefore

            // 先准备两个回调，再一起替换；非本功能菜单项原样交给微信处理。
            val wrappedSelect = Proxy.newProxyInstance(
                callbackType.classLoader, arrayOf(callbackType)
            ) { _, method, params ->
                val values = params ?: emptyArray()
                val item = values.firstOrNull() as? MenuItem
                if (method.name == "onMMMenuItemSelected" && item?.itemId == MENU_ID) {
                    // 菜单打开后关闭开关，也不能继续执行增删名单。
                    if (isActive && isEnabled) toggleSecret(activity, wxId)
                    null
                } else {
                    method.invoke(select, *values)
                }
            }
            args[3] = View.OnCreateContextMenuListener { menu, target, info ->
                create.onCreateContextMenu(menu, target, info)
                if (isActive && isEnabled && menu.findItem(MENU_ID) == null) {
                    menu.add(position, MENU_ID, 0, secretMenuText(wxId, fromContacts = true))
                }
            }
            args[4] = wrappedSelect
        }
        WeLogger.i(TAG, "native contact menu hook installed")
    }

    private fun isContactMenu(listener: Any): Boolean =
        classHierarchy(listener.javaClass).any { it.name.startsWith(CONTACT_PACKAGE) } ||
            instanceFields(listener.javaClass).any { field ->
                classHierarchy(field.type).any { it.name.startsWith(CONTACT_PACKAGE) }
            }

    private fun classHierarchy(type: Class<*>): Sequence<Class<*>> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }

    private fun instanceFields(type: Class<*>) = classHierarchy(type)
        .flatMap { it.declaredFields.asSequence() }
        .filterNot { Modifier.isStatic(it.modifiers) }

    private fun readUsername(contact: Any): String? {
        val fields = instanceFields(contact.javaClass).toList()
        if (fields.any { it.name == "field_conversationTime" }) return null
        val field = fields.firstOrNull { it.name == "field_username" } ?: return null
        field.isAccessible = true
        return (field.get(contact) as? String)?.takeIf {
            it.isNotBlank() && !it.endsWith("@chatroom") && !it.endsWith("@app") &&
                !it.startsWith("gh_")
        }
    }

    private fun resolveContact(listener: Any, view: View, position: Int): String? {
        // MVVM: create callback -> captured row model -> storage.Contact.
        // 仅检查一层模型字段，禁止在 Fragment/Activity 对象图中递归猜测联系人。
        val candidates = linkedSetOf<String>()
        for (field in instanceFields(listener.javaClass)) {
            if (field.type.name.startsWith(STORAGE_PACKAGE)) {
                field.isAccessible = true
                field.get(listener)?.let { readUsername(it) }?.let(candidates::add)
                continue
            }
            if (field.type.isPrimitive || field.type.name.startsWith("java.") ||
                field.type.name.startsWith("android.") ||
                field.type.name.startsWith("androidx.") ||
                field.type.name.startsWith(CONTACT_PACKAGE)) continue
            val contactFields = instanceFields(field.type)
                .filter { it.type.name.startsWith(STORAGE_PACKAGE) }.toList()
            if (contactFields.isEmpty()) continue
            field.isAccessible = true
            val row = field.get(listener) ?: continue
            for (contactField in contactFields) {
                contactField.isAccessible = true
                contactField.get(row)?.let { readUsername(it) }?.let(candidates::add)
            }
        }
        if (candidates.isNotEmpty()) return candidates.singleOrNull()

        // 旧版 ListView：使用本次弹窗的行位置，支持 HeaderViewListAdapter。
        var parent = view.parent
        while (parent is View) {
            if (parent is AdapterView<*>) {
                if (position !in 0 until parent.count) return null
                return parent.getItemAtPosition(position)?.let { readUsername(it) }
            }
            parent = parent.parent
        }
        return null
    }
}
