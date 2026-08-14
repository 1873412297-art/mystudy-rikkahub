package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesRuntimePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var runtimePermissions by remember(settings) { mutableStateOf(settings.runtimePermissions) }

    fun updateRuntimePermissions(value: TavernRuntimePermissions) {
        runtimePermissions = value
        vm.updateSettings(settings.copy(runtimePermissions = value))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("运行时权限")
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("Tavern Helper") },
                ) {
                    fun permissionSwitch(
                        title: String,
                        description: String,
                        checked: Boolean,
                        onCheckedChange: (Boolean) -> Unit,
                    ) = item(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(description) },
                        trailingContent = {
                            Switch(checked = checked, onCheckedChange = onCheckedChange)
                        },
                    )

                    permissionSwitch(
                        title = "允许脚本运行",
                        description = "关闭后，兼容运行时会直接拒绝脚本调用，保留只读预览路径。",
                        checked = runtimePermissions.allowScripts,
                        onCheckedChange = {
                            updateRuntimePermissions(runtimePermissions.copy(allowScripts = it))
                        },
                    )
                    if (runtimePermissions.allowScripts) {
                        permissionSwitch(
                            title = "允许写入世界书",
                            description = "允许脚本增删改世界书条目。默认关闭。",
                            checked = runtimePermissions.allowWorldWrite,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowWorldWrite = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许写入消息",
                            description = "允许脚本修改当前消息内容。默认关闭。",
                            checked = runtimePermissions.allowMessageWrite,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowMessageWrite = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许写入变量",
                            description = "允许脚本修改聊天/全局变量并持久化。默认关闭。",
                            checked = runtimePermissions.allowVariablesWrite,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowVariablesWrite = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许订阅宿主事件",
                            description = "允许脚本接收消息发送、生成完成、渲染完成等宿主事件推送。默认关闭。",
                            checked = runtimePermissions.allowEventSubscribe,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowEventSubscribe = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许网络访问",
                            description = "为后续网络类脚本接口预留。默认关闭。",
                            checked = runtimePermissions.allowNetwork,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowNetwork = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许注册宏与斜杠命令",
                            description = "允许脚本注册宿主宏（发送前文本展开）与斜杠命令。默认关闭。",
                            checked = runtimePermissions.allowMacroRegister,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowMacroRegister = it))
                            },
                        )
                        permissionSwitch(
                            title = "允许读取请求头",
                            description = "允许脚本读取当前模型请求头（可能包含 API Key 等敏感信息）。默认关闭。",
                            checked = runtimePermissions.allowRequestHeaders,
                            onCheckedChange = {
                                updateRuntimePermissions(runtimePermissions.copy(allowRequestHeaders = it))
                            },
                        )
                    }
                }
            }
        }
    }
}
