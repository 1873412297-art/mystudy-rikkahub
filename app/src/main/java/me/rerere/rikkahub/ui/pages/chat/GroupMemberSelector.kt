package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.GroupMemberCombo
import kotlin.uuid.Uuid

/**
 * 常用组合快捷选择条 —— 显示在 GroupMemberSelector 上方。
 * 点 chip 应用组合，长按弹「重命名/删除」菜单，末尾 "+ 保存当前" chip 把当前已选 ≥2 个成员存为组合。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupMemberComboBar(
    combos: List<GroupMemberCombo>,
    canSave: Boolean,
    onApplyCombo: (GroupMemberCombo) -> Unit,
    onSaveCurrent: (name: String) -> Unit,
    onDeleteCombo: (id: Uuid) -> Unit,
    onRenameCombo: (id: Uuid, newName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var menuComboId by remember { mutableStateOf<Uuid?>(null) }
    var renameComboId by remember { mutableStateOf<Uuid?>(null) }
    var renameText by remember { mutableStateOf("") }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = combos, key = { it.id.toString() }) { combo ->
            val idx = combos.indexOf(combo)
            androidx.compose.foundation.layout.Box {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onApplyCombo(combo) },
                                onLongClick = { menuComboId = combo.id },
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = combo.name.ifBlank { "组合${idx + 1}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "(${combo.memberIds.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuComboId == combo.id,
                    onDismissRequest = { menuComboId = null },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            renameComboId = combo.id
                            renameText = combo.name
                            menuComboId = null
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            onDeleteCombo(combo.id)
                            menuComboId = null
                        }
                    )
                }
            }
        }
        if (canSave) {
            item {
                Surface(
                    onClick = {
                        saveName = ""
                        showSaveDialog = true
                    },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text = "+ 保存当前",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存常用组合") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("组合名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveName.isNotBlank()) {
                        onSaveCurrent(saveName.trim())
                        showSaveDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            },
        )
    }

    if (renameComboId != null) {
        val targetId = renameComboId!!
        AlertDialog(
            onDismissRequest = { renameComboId = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("组合名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        onRenameCombo(targetId, renameText.trim())
                        renameComboId = null
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameComboId = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 群组手动模式下的成员选择条。
 *  - 点击 chip：toggle 选中（顺序就是发言顺序）
 *  - 长按已选中 chip：把它移到末尾，循环左移
 *  - 末尾的 X：清空选择
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupMemberSelector(
    members: List<GroupMember>,
    selectedMemberIds: List<Uuid>,
    settings: Settings,
    onToggle: (Uuid) -> Unit,
    onSelectionChange: (List<Uuid>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = members, key = { it.id.toString() }) { member ->
            val source = settings.assistants.find { it.id == member.assistantId }
            val name = member.displayName.ifBlank { source?.name ?: "?" }
            val isSelected = member.id in selectedMemberIds
            val orderIndex = if (isSelected) selectedMemberIds.indexOf(member.id) else -1
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Row(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onToggle(member.id) },
                            onLongClick = if (isSelected) {
                                {
                                    val cur = selectedMemberIds.toMutableList()
                                    val idx = cur.indexOf(member.id)
                                    if (idx >= 0) {
                                        cur.removeAt(idx)
                                        cur.add(member.id)
                                        onSelectionChange(cur)
                                    }
                                }
                            } else null,
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (isSelected) "${orderIndex + 1}" else name.take(1),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (selectedMemberIds.isNotEmpty()) {
            item {
                Surface(
                    onClick = { onSelectionChange(emptyList()) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "清空选择",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
