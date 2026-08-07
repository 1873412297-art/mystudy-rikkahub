package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class QuickMessageTest {

    @Test
    fun `legacy json without new fields deserializes with defaults`() {
        val id = Uuid.random()
        val legacy = """{"id":"$id","title":"打招呼","content":"你好，很高兴见到你"}"""

        val decoded = JsonInstant.decodeFromString<QuickMessage>(legacy)

        assertEquals(id, decoded.id)
        assertEquals("打招呼", decoded.title)
        assertEquals("你好，很高兴见到你", decoded.content)
        assertFalse(decoded.autoSend)
        assertEquals(QuickMessageMode.APPEND, decoded.mode)
        assertEquals(0, decoded.order)
    }

    @Test
    fun `new fields round trip through json`() {
        val quickMessage = QuickMessage(
            title = "总结",
            content = "请总结上文",
            autoSend = true,
            mode = QuickMessageMode.REPLACE,
            order = 7,
        )

        val encoded = JsonInstant.encodeToString(quickMessage)
        val decoded = JsonInstant.decodeFromString<QuickMessage>(encoded)

        assertEquals(quickMessage, decoded)
        assertTrue(encoded.contains("\"autoSend\":true"))
        assertTrue(encoded.contains("\"mode\":\"replace\""))
        assertTrue(encoded.contains("\"order\":7"))
    }

    @Test
    fun `resolveVisibleQuickMessages filters unbound and hidden entries then sorts by order`() {
        val bound1 = QuickMessage(title = "一", order = 2)
        val bound2 = QuickMessage(title = "二", order = 0)
        val hidden = QuickMessage(title = "隐藏", order = -1)
        val unbound = QuickMessage(title = "未绑定", order = -2)
        val all = listOf(bound1, bound2, hidden, unbound)

        val visible = resolveVisibleQuickMessages(
            quickMessages = all,
            quickMessageIds = setOf(bound1.id, bound2.id, hidden.id),
            hiddenQuickMessageIds = setOf(hidden.id),
        )

        assertEquals(listOf(bound2, bound1), visible)
    }

    @Test
    fun `resolveVisibleQuickMessages keeps stable order for equal order values`() {
        val first = QuickMessage(title = "先", order = 1)
        val second = QuickMessage(title = "后", order = 1)
        val all = listOf(first, second)

        val visible = resolveVisibleQuickMessages(
            quickMessages = all,
            quickMessageIds = setOf(first.id, second.id),
            hiddenQuickMessageIds = emptySet(),
        )

        assertEquals(listOf(first, second), visible)
    }

    @Test
    fun `resolveVisibleQuickMessages returns empty when assistant binds nothing`() {
        val visible = resolveVisibleQuickMessages(
            quickMessages = listOf(QuickMessage(title = "A")),
            quickMessageIds = emptySet(),
            hiddenQuickMessageIds = emptySet(),
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `sanitizeQuickMessageRefs drops deleted ids from bindings and hidden set`() {
        val kept = QuickMessage(title = "保留")
        val deleted = QuickMessage(title = "已删除")
        val assistant = Assistant(
            quickMessageIds = setOf(kept.id, deleted.id),
            hiddenQuickMessageIds = setOf(deleted.id),
        )

        val sanitized = assistant.sanitizeQuickMessageRefs(validQuickMessageIds = setOf(kept.id))

        assertEquals(setOf(kept.id), sanitized.quickMessageIds)
        assertTrue(sanitized.hiddenQuickMessageIds.isEmpty())
    }

    @Test
    fun `sanitizeQuickMessageRefs keeps assistant unchanged when all references valid`() {
        val kept = QuickMessage(title = "保留")
        val assistant = Assistant(
            name = "测试助手",
            quickMessageIds = setOf(kept.id),
            hiddenQuickMessageIds = setOf(kept.id),
        )

        val sanitized = assistant.sanitizeQuickMessageRefs(validQuickMessageIds = setOf(kept.id))

        assertEquals(assistant, sanitized)
    }

    @Test
    fun `assistant hiddenQuickMessageIds defaults to empty and survives legacy assistant json`() {
        val id = Uuid.random()
        val legacy = """{"id":"$id","name":"旧助手","quickMessageIds":[]}"""

        val decoded = JsonInstant.decodeFromString<Assistant>(legacy)

        assertEquals(id, decoded.id)
        assertTrue(decoded.hiddenQuickMessageIds.isEmpty())
    }
}
