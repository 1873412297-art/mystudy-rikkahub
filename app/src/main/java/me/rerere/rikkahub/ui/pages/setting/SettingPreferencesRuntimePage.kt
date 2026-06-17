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
                    item(
                        headlineContent = { Text("允许脚本运行") },
                        supportingContent = {
                            Text("关闭后，兼容运行时会直接拒绝脚本调用，保留只读预览路径。")
                        },
                        trailingContent = {
                            Switch(
                                checked = runtimePermissions.allowScripts,
                                onCheckedChange = {
                                    updateRuntimePermissions(
                                        runtimePermissions.copy(allowScripts = it)
                                    )
                                }
                            )
                        },
                    )
                    if (runtimePermissions.allowScripts) {
                        item(
                            headlineContent = { Text("允许写入世界书") },
                            supportingContent = {
                                Text("允许脚本增删改世界书条目。默认关闭。")
                            },
                            trailingContent = {
                                Switch(
                                    checked = runtimePermissions.allowWorldWrite,
                                    onCheckedChange = {
                                        updateRuntimePermissions(
                                            runtimePermissions.copy(allowWorldWrite = it)
                                        )
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("允许写入消息") },
                            supportingContent = {
                                Text("允许脚本修改当前消息内容。默认关闭。")
                            },
                            trailingContent = {
                                Switch(
                                    checked = runtimePermissions.allowMessageWrite,
                                    onCheckedChange = {
                                        updateRuntimePermissions(
                                            runtimePermissions.copy(allowMessageWrite = it)
                                        )
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("允许网络访问") },
                            supportingContent = {
                                Text("为后续网络类脚本接口预留。默认关闭。")
                            },
                            trailingContent = {
                                Switch(
                                    checked = runtimePermissions.allowNetwork,
                                    onCheckedChange = {
                                        updateRuntimePermissions(
                                            runtimePermissions.copy(allowNetwork = it)
                                        )
                                    }
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
