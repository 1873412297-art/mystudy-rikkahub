package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Test

class TavernSendHookStoreTest {

    @Test
    fun `mutateOutgoing returns parts unchanged when no controller is active`() = runBlocking {
        val store = TavernSendHookStore()
        val parts = listOf<UIMessagePart>(UIMessagePart.Text(text = "hello"))
        assertEquals(parts, store.mutateOutgoing(parts))
    }

    @Test
    fun `mutateOutgoing delegates text parts to active controller and passes through others`() = runBlocking {
        val store = TavernSendHookStore()
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
        )
        store.activeController = controller
        try {
            val parts = listOf<UIMessagePart>(
                UIMessagePart.Text(text = "hello"),
                UIMessagePart.Image(url = "https://example.com/x.png"),
            )
            // 无 sendHook 注册 → controller 兜底原样；非文本 part 不动
            assertEquals(parts, store.mutateOutgoing(parts))
        } finally {
            store.activeController = null
        }
    }
}
