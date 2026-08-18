package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.rikkahub.data.model.AuthorNote
import me.rerere.rikkahub.ui.components.ui.Select

/**
 * 会话级作者注释（Author's Note）配置入口。
 * 仅在助手开启 allowConversationAuthorNote 时由聊天列表展示；
 * 保存的注释在注入时优先于助手级作者注释生效。
 */
@Composable
fun ConversationAuthorNoteButton(
    authorNote: AuthorNote?,
    onAuthorNoteChange: (AuthorNote?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable(authorNote) { mutableStateOf(authorNote?.enabled ?: true) }
    var content by rememberSaveable(authorNote) { mutableStateOf(authorNote?.content ?: "") }
    var depthText by rememberSaveable(authorNote) { mutableStateOf((authorNote?.depth ?: DEFAULT_DEPTH).toString()) }
    var intervalText by rememberSaveable(authorNote) {
        mutableStateOf((authorNote?.interval ?: DEFAULT_INTERVAL).toString())
    }
    var roleName by rememberSaveable(authorNote) { mutableStateOf((authorNote?.role ?: DEFAULT_ROLE).name) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
        ) {
            Icon(
                imageVector = HugeIcons.QuillWrite01,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (authorNote?.enabled == true && authorNote.content.isNotBlank()) {
                    "作者注释 ✎"
                } else {
                    "作者注释"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "启用会话作者注释",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("注释正文") },
                    minLines = 3,
                    maxLines = 8,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberInputField(
                        value = depthText,
                        onValueChange = { depthText = it },
                        label = "注入深度",
                        modifier = Modifier.weight(1f),
                    )
                    NumberInputField(
                        value = intervalText,
                        onValueChange = { intervalText = it },
                        label = "间隔轮数",
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "注入角色",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Select(
                        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
                        selectedOption = parseMessageRole(roleName),
                        onOptionSelected = { roleName = it.name },
                        modifier = Modifier.width(160.dp),
                        optionToString = { it.displayLabel() },
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (authorNote != null) {
                        TextButton(
                            onClick = {
                                onAuthorNoteChange(null)
                                expanded = false
                            },
                        ) {
                            Text("清除")
                        }
                    }
                    TextButton(
                        onClick = {
                            onAuthorNoteChange(
                                buildAuthorNote(
                                    enabled = enabled,
                                    content = content,
                                    depthText = depthText,
                                    intervalText = intervalText,
                                    roleName = roleName,
                                )
                            )
                            expanded = false
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

// 与 data/model/Assistant.kt 中 AuthorNote 的字段默认值保持一致
private const val DEFAULT_DEPTH = 4
private const val DEFAULT_INTERVAL = 1
private val DEFAULT_ROLE = MessageRole.USER

/**
 * 解析角色名；无法识别时回退到默认角色。
 */
private fun parseMessageRole(name: String): MessageRole =
    runCatching { MessageRole.valueOf(name) }.getOrDefault(DEFAULT_ROLE)

/**
 * 注入角色的显示名。
 */
private fun MessageRole.displayLabel(): String = when (this) {
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    else -> name
}

/**
 * 纯计算：把表单状态组装成 AuthorNote（深度/间隔至少为 1，非法输入回退默认值）。
 */
private fun buildAuthorNote(
    enabled: Boolean,
    content: String,
    depthText: String,
    intervalText: String,
    roleName: String,
): AuthorNote = AuthorNote(
    enabled = enabled,
    content = content,
    depth = depthText.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_DEPTH,
    role = parseMessageRole(roleName),
    interval = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_INTERVAL,
)

/**
 * 数字输入框（注入深度 / 间隔轮数共用）。
 */
@Composable
private fun NumberInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}
