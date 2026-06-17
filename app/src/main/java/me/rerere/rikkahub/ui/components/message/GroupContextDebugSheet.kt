package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.group.GroupRuntimeState

@Composable
fun GroupContextDebugSheet(
    runtimeState: GroupRuntimeState,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("群组运行状态")
            Spacer(Modifier.height(12.dp))
            Text("场景摘要")
            Text(runtimeState.scene.summary.ifBlank { "空" })
            Spacer(Modifier.height(12.dp))
            Text("场景紧张度：${runtimeState.scene.tension}")
            Spacer(Modifier.height(12.dp))
            Text("活跃秘密")
            Text(runtimeState.scene.activeSecrets.joinToString("\n").ifBlank { "空" })
            Spacer(Modifier.height(12.dp))
            Text("私有记忆数量：${runtimeState.privateNotes.size}")
            Text("关系记录数量：${runtimeState.relationships.size}")
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismissRequest) {
                Text("关闭")
            }
        }
    }
}
