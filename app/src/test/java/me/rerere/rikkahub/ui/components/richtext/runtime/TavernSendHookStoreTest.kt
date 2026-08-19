package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TavernSendHookStoreTest {

    @Test
    fun `mutateOutgoing returns parts unchanged when no controller is active`() = runBlocking {
        val store = TavernSendHookStore()
        val parts = listOf<UIMessagePart>(UIMessagePart.Text(text = "hello"))
        assertEquals(parts, store.mutateOutgoing(kotlin.uuid.Uuid.random(), parts))
    }

    @Test
    fun `mutateOutgoing delegates text parts to active controller and passes through others`() = runBlocking {
        val store = TavernSendHookStore()
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
        )
        val conversationId = kotlin.uuid.Uuid.random()
        store.attach(conversationId, controller)
        try {
            val parts = listOf<UIMessagePart>(
                UIMessagePart.Text(text = "hello"),
                UIMessagePart.Image(url = "https://example.com/x.png"),
            )
            // 无 sendHook 注册 → controller 兜底原样；非文本 part 不动
            assertEquals(parts, store.mutateOutgoing(conversationId, parts))
        } finally {
            store.detach(conversationId, controller)
        }
    }

    @Test
    fun `controllers are isolated by conversation and replay cannot replace the owner`() {
        val store = TavernSendHookStore()
        val firstId = kotlin.uuid.Uuid.random()
        val secondId = kotlin.uuid.Uuid.random()
        val first = TavernRuntimeController(conversationId = firstId)
        val second = TavernRuntimeController(conversationId = secondId)
        val replay = TavernRuntimeController(conversationId = firstId)

        store.attach(firstId, first)
        store.attach(secondId, second)

        assertSame(first, store.controllerFor(firstId))
        assertSame(second, store.controllerFor(secondId))
        // A non-owner replay never calls attach and cannot displace the chat controller.
        store.detach(firstId, replay)
        assertSame(first, store.controllerFor(firstId))
    }

    @Test
    fun `committed opening hook remains fallback until an active controller registers one`() {
        val store = TavernSendHookStore()
        val conversationId = kotlin.uuid.Uuid.random()
        val committed = TavernRuntimeController(conversationId = conversationId)
        val activeWithoutHook = TavernRuntimeController(conversationId = conversationId)
        committed.installSendHook("() => 'chosen'")

        store.installCommitted(conversationId, committed)
        store.attach(conversationId, activeWithoutHook)

        assertSame(committed, store.controllerFor(conversationId))
    }

    @Test
    fun `conversation cleanup removes active and committed controllers`() {
        val store = TavernSendHookStore()
        val id = kotlin.uuid.Uuid.random()
        val controller = TavernRuntimeController(conversationId = id)
        store.attach(id, controller)
        store.installCommitted(id, controller)

        store.remove(id)

        assertEquals(null, store.controllerFor(id))
    }
}
