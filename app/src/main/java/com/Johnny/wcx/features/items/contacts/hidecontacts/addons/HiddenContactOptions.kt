package com.Johnny.wcx.features.items.contacts.hidecontacts.addons

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "离开对话恢复隐藏", categories = ["密友功能"],
    description = "从任意对话返回微信主页时恢复隐藏；进入对话、查看联系人资料不触发")
object AutoRestoreOnLeave : SwitchFeature() {
    override val defaultEnabled = true
    override fun onEnable() = HiddenContactAddonState.refresh()
    override fun onDisable() = HiddenContactAddonState.refresh()
}

@Feature(name = "离开主页/切走微信恢复隐藏", categories = ["密友功能"],
    description = "离开主页进入其他页面或按 HOME、切换应用时恢复隐藏；进入对话、联系人资料页除外")
object AutoRestoreOnAppLeave : SwitchFeature() {
    override val defaultEnabled = true
    override fun onEnable() = HiddenContactAddonState.refresh()
    override fun onDisable() = HiddenContactAddonState.refresh()
}

@Feature(name = "圆点提示", categories = ["密友功能"],
    description = "隐藏状态下有密友未读消息时在搜索入口旁显示红点；打开对应对话后清除")
object RedDotPrompt : SwitchFeature() {
    override val defaultEnabled = true
    var dotSizeDp by prefOption("secret_friend_dot_size", 6)
    override fun onEnable() = HiddenContactAddonState.refresh()
    override fun onDisable() = HiddenContactAddonState.refresh()
    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "圆点大小(dp)", value = dotSizeDp.toString(),
            onValueChange = { it.toIntOrNull()?.let { size -> dotSizeDp = size.coerceIn(4, 12) } },
            dialogTitle = "圆点大小（4–12 dp）", confirmLabel = "确定", dismissLabel = "取消")
    }
}

@Feature(name = "底栏字体加粗", categories = ["密友功能"],
    description = "隐藏状态下有密友未读消息时加粗底栏微信文字；与红点使用同一未读状态")
object BottomBarBold : SwitchFeature() {
    var boldStrength by prefOption("secret_friend_bold_strength", 2)
    override fun onEnable() = HiddenContactAddonState.refresh()
    override fun onDisable() = HiddenContactAddonState.refresh()
    @Composable override fun Ui() {
        TextFieldDialogWidget(title = "加粗强度", value = boldStrength.toString(),
            onValueChange = { it.toIntOrNull()?.let { strength -> boldStrength = strength.coerceIn(1, 5) } },
            dialogTitle = "加粗强度（1–5）", confirmLabel = "确定", dismissLabel = "取消")
    }
}

@Feature(name = "密友显示状态提示", categories = ["密友功能"],
    description = "临时显示密友时，在原红点位置显示绿点；绿点不清除未读记录，恢复隐藏后按未读状态显示红点")
object SecretFriendShownPrompt : SwitchFeature() {
    override val defaultEnabled = true
    override fun onEnable() = HiddenContactAddonState.refresh()
    override fun onDisable() = HiddenContactAddonState.refresh()
    @Composable override fun Ui() = RedDotPrompt.Ui()
}

@Feature(name = "操作提示", categories = ["密友功能"],
    description = "显示密友操作提示；可自定义显示、隐藏、加入和移除时的文字，提示约 1 秒")
object SecretFriendToastToggle : SwitchFeature() {
    override val defaultEnabled = true
    @Composable override fun Ui() {
        Column {
            EditText("显示密友提示", HiddenContactAddonState.toastTempShown) { HiddenContactAddonState.toastTempShown = it }
            EditText("恢复隐藏提示", HiddenContactAddonState.toastTempOff) { HiddenContactAddonState.toastTempOff = it }
            EditText("加入密友提示", HiddenContactAddonState.toastAdded) { HiddenContactAddonState.toastAdded = it }
            EditText("移除密友提示", HiddenContactAddonState.toastRemoved) { HiddenContactAddonState.toastRemoved = it }
        }
    }
}

@Feature(name = "菜单显示文字", categories = ["密友功能"],
    description = "自定义会话列表和通讯录长按菜单中加入、移除密友的文字")
object SecretFriendMenuTextConfig : ClickableFeature() {
    override val alwaysEnabled = true
    override val noSwitchWidget = true
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(title = { Text("菜单显示文字") }, text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    EditText("会话：加入密友", HiddenContactAddonState.addMenuText) { HiddenContactAddonState.addMenuText = it.trim().take(24) }
                    EditText("会话：移除密友", HiddenContactAddonState.removeMenuText) { HiddenContactAddonState.removeMenuText = it.trim().take(24) }
                    EditText("通讯录：加入密友（留空跟随会话）", HiddenContactAddonState.contactAddMenuText) { HiddenContactAddonState.contactAddMenuText = it.trim().take(24) }
                    EditText("通讯录：移除密友（留空跟随会话）", HiddenContactAddonState.contactRemoveMenuText) { HiddenContactAddonState.contactRemoveMenuText = it.trim().take(24) }
                }
            }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
        }
    }
}

@Composable
private fun EditText(title: String, value: String, update: (String) -> Unit) {
    TextFieldDialogWidget(title = title, value = value, onValueChange = { update(it.take(80)) },
        dialogTitle = title, confirmLabel = "保存", dismissLabel = "取消")
}
