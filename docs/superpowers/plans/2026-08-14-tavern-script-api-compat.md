# 酒馆脚本 API 兼容性 Implementation Plan（子项目 B2a：上下文与事件）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 酒馆脚本 API 与 SillyTavern 对齐（B2a）：`SillyTavern.getContext()` 宿主推送快照、`event_types` 常量表、宿主事件扩面（生成/编辑/删除/切换/渲染细分 + ST 命名）。

**Architecture:** 宿主（ChatList 层）构建上下文快照纯函数（chat 最近 50 条 + character/user/worldInfo/variables/onlineStatus）→ 经 ChatMessage/MarkdownWebView 传入 `TavernRuntimeController.setContext`（内容哈希去重）→ outbound `th:context_updated` DOM 事件 → JS 侧内部订阅缓存 → `SillyTavern.getContext()` 同步返回；`TavernHostEventType` 扩展 8 个 ST 事件类型，ChatService/MarkdownWebView 相应路径发射（旧事件名保留并列）。

**Tech Stack:** Kotlin/Compose、kotlinx.serialization、JUnit（TDD）。

**Spec:** `docs/superpowers/specs/2026-08-14-tavern-script-api-compat-design.md`

## Global Constraints

- 工作区：`C:\Users\18734\Desktop\HTML\rikkahub-source`（rikkahub，1 个 h）
- 验证：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
- Kotlin 4 空格、行宽 120；JS 模板 2 空格
- **绝对不要 `git add .`**——每次 commit 只 add 任务文件
- 安全不变式：getContext 受 `allowScripts` 总开关（dispatch 入口既有检查覆盖）；context_updated 是宿主→WebView 内部通道不经 RPC 桥；单条消息纯文本截断 2000 字符
- 兼容不变式：旧事件名（MESSAGE_SENDING/GENERATION_FINISHED/MESSAGE_RENDERED）与既有 API 行为完全不变
- 每任务 commit；commit message 遵循 repo 风格

---

### Task 1: TavernHostEventType 扩展 + 宿主事件发射点

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernHostEventBus.kt:14-18`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（:630-651 区域 + editMessage/selectMessageNode/deleteMessage + handleMessageComplete）
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernHostEventTypeTest.kt`

**Interfaces:**
- Produces: 新枚举值（Task 3 过滤器、Task 4 常量表消费）：`GENERATION_STARTED`、`MESSAGE_SENT`、`MESSAGE_RECEIVED`、`MESSAGE_EDITED`、`MESSAGE_DELETED`、`MESSAGE_SWIPED`、`CHARACTER_MESSAGE_RENDERED`、`USER_MESSAGE_RENDERED`

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernHostEventTypeTest {

    @Test
    fun `st aligned event names are present alongside legacy names`() {
        val expected = setOf(
            "GENERATION_STARTED", "MESSAGE_SENT", "MESSAGE_RECEIVED",
            "MESSAGE_EDITED", "MESSAGE_DELETED", "MESSAGE_SWIPED",
            "CHARACTER_MESSAGE_RENDERED", "USER_MESSAGE_RENDERED",
            // legacy（保留）
            "MESSAGE_SENDING", "GENERATION_FINISHED", "MESSAGE_RENDERED",
        )
        assertEquals(expected, TavernHostEventType.entries.map { it.name }.toSet())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.status.TavernHostEventTypeTest"`
Expected: 编译失败（新枚举值不存在）或断言失败

- [ ] **Step 3: 扩展枚举**

```kotlin
enum class TavernHostEventType {
    // SillyTavern event_types 对齐（B2a）
    GENERATION_STARTED,
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    MESSAGE_SWIPED,
    CHARACTER_MESSAGE_RENDERED,
    USER_MESSAGE_RENDERED,
    // 旧事件名（兼容保留）
    MESSAGE_SENDING,
    GENERATION_FINISHED,
    MESSAGE_RENDERED,
}
```

- [ ] **Step 4: ChatService 发射点**

1. `sendMessage` 中现有 MESSAGE_SENDING 发射（:630-637）处，**并列**加：

```kotlin
                // 酒馆脚本宿主事件：消息已发送（ST 命名）
                tavernHostEventBus.emit(
                    type = TavernHostEventType.MESSAGE_SENT,
                    conversationId = conversationId,
                    payload = buildJsonObject {
                        put("role", userMessage.role.name.lowercase())
                        put("preview", userMessage.toText().take(500))
                    },
                )
```

2. 现有 GENERATION_FINISHED 发射（:644-650）处，**并列**加 MESSAGE_RECEIVED（带 messageId——从 conversation 最新 assistant 消息取）：

```kotlin
                    // 酒馆脚本宿主事件：assistant 消息完成（ST 命名）
                    val latestAssistantId = getConversationFlow(conversationId).value.messageNodes
                        .lastOrNull { it.role == MessageRole.ASSISTANT }
                        ?.messages?.lastOrNull()?.id?.toString()
                    tavernHostEventBus.emit(
                        type = TavernHostEventType.MESSAGE_RECEIVED,
                        conversationId = conversationId,
                        payload = buildJsonObject {
                            put("role", "assistant")
                            latestAssistantId?.let { put("messageId", it) }
                        },
                    )
```

（`MessageRole` 已 import；`MessageNode.role` 存在——若字段名不同按实际调整。）

3. `handleMessageComplete` 函数体开头（grep 定位 `private suspend fun handleMessageComplete` 或类似签名，首个生成步骤前）加 GENERATION_STARTED：

```kotlin
        // 酒馆脚本宿主事件：生成开始（ST 命名）
        tavernHostEventBus.emit(
            type = TavernHostEventType.GENERATION_STARTED,
            conversationId = conversationId,
        )
```

注意：`handleMessageComplete` 可能被群聊多成员并发调用——发射幂等（每个成员生成各发一次，符合语义）。

4. `editMessage`（:1796）`saveConversation` 后加：

```kotlin
        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_EDITED,
            conversationId = conversationId,
            payload = buildJsonObject { put("messageId", messageId.toString()) },
        )
```

5. `selectMessageNode`（:1845）`saveConversation` 后加：

```kotlin
        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_SWIPED,
            conversationId = conversationId,
            payload = buildJsonObject {
                put("nodeId", nodeId.toString())
                put("selectIndex", selectIndex)
            },
        )
```

6. `deleteMessage(conversationId, messageId, failIfMissing)`（:1873）`saveConversationAfterRemovingMessages` 后加：

```kotlin
        tavernHostEventBus.emit(
            type = TavernHostEventType.MESSAGE_DELETED,
            conversationId = conversationId,
            payload = buildJsonObject { put("messageId", messageId.toString()) },
        )
```

- [ ] **Step 5: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.status.TavernHostEventTypeTest" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernHostEventBus.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernHostEventTypeTest.kt
git commit -m "feat: add ST-aligned host event types and emission points"
```

---

### Task 2: 上下文快照构建纯函数

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernContextSnapshot.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernContextSnapshotTest.kt`

**Interfaces:**
- Produces: `internal fun buildTavernContextSnapshot(context: TavernContextSnapshotInput): JsonObject`（Task 6 消费）；`TavernContextSnapshotInput` 数据类

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class TavernContextSnapshotTest {

    private fun textMessage(id: Uuid, role: MessageRole, text: String) = UIMessage(
        id = id,
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = Instant.now(),
    )

    private fun node(id: Uuid, vararg messages: UIMessage) = MessageNode(
        id = id,
        messages = messages.toList(),
        selectIndex = 0,
    )

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "t",
        messageNodes = nodes.toList(),
        chatSuggestions = emptyList(),
    )

    @Test
    fun `builds chat entries with current flag on last message`() {
        val m1 = textMessage(Uuid.random(), MessageRole.USER, "hello")
        val m2 = textMessage(Uuid.random(), MessageRole.ASSISTANT, "hi there")
        val input = TavernContextSnapshotInput(
            conversation = conversation(node(Uuid.random(), m1), node(Uuid.random(), m2)),
            assistant = Assistant(name = "Char"),
            userName = "User",
            isGenerating = true,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        val chat = snapshot["chat"]!!.jsonArray
        assertEquals(2, chat.size)
        assertEquals("hello", chat[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(false, chat[0].jsonObject["isCurrent"]!!.jsonPrimitive.boolean)
        assertTrue(chat[1].jsonObject["isCurrent"]!!.jsonPrimitive.boolean)
        assertEquals("Char", snapshot["character"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("User", snapshot["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(true, snapshot["onlineStatus"]!!.jsonPrimitive.boolean)
        assertEquals(input.conversation.id.toString(), snapshot["conversationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncates chat to 50 most recent messages`() {
        val nodes = (0 until 60).map { index ->
            node(Uuid.random(), textMessage(Uuid.random(), MessageRole.USER, "msg-$index"))
        }
        val input = TavernContextSnapshotInput(
            conversation = conversation(*nodes.toTypedArray()),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        val chat = snapshot["chat"]!!.jsonArray
        assertEquals(50, chat.size)
        assertEquals("msg-10", chat[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("msg-59", chat[49].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncates single message text to 2000 chars`() {
        val long = "x".repeat(2500)
        val input = TavernContextSnapshotInput(
            conversation = conversation(node(Uuid.random(), textMessage(Uuid.random(), MessageRole.USER, long))),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val text = buildTavernContextSnapshot(input)["chat"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(2000, text.length)
        assertTrue(text.startsWith("x"))
    }

    @Test
    fun `includes variables and world info`() {
        val input = TavernContextSnapshotInput(
            conversation = conversation(),
            assistant = Assistant(name = "C", description = "A card"),
            userName = "U",
            isGenerating = false,
            variables = buildJsonObject { put("hp", 42) },
            worldEntries = listOf("World" to "lore content"),
        )
        val snapshot = buildTavernContextSnapshot(input)
        assertEquals(42, snapshot["variables"]!!.jsonObject["hp"]!!.jsonPrimitive.int)
        assertEquals("lore content", snapshot["worldInfo"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `emits no world info when empty`() {
        val input = TavernContextSnapshotInput(
            conversation = conversation(),
            assistant = Assistant(name = "C"),
            userName = "U",
            isGenerating = false,
            variables = JsonObject(emptyMap()),
            worldEntries = emptyList(),
        )
        val snapshot = buildTavernContextSnapshot(input)
        assertFalse(snapshot.containsKey("worldInfo"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernContextSnapshotTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

/** 上下文快照中 chat 列表的最大消息数 */
private const val MAX_CHAT_ENTRIES = 50

/** 单条消息纯文本的截断长度 */
private const val MAX_MESSAGE_TEXT_LENGTH = 2000

/**
 * 上下文快照输入（宿主 ChatList 层组装）。
 *
 * @property worldEntries 世界书条目（名称 → 内容纯文本），按对话绑定顺序
 */
internal data class TavernContextSnapshotInput(
    val conversation: Conversation,
    val assistant: Assistant?,
    val userName: String,
    val isGenerating: Boolean,
    val variables: JsonObject,
    val worldEntries: List<Pair<String, String>>,
)

/**
 * 构建 SillyTavern.getContext() 风格上下文快照（实用子集）。
 * 纯函数，可 JVM 测试。
 */
internal fun buildTavernContextSnapshot(input: TavernContextSnapshotInput): JsonObject {
    val chat = input.conversation.currentMessages
        .takeLast(MAX_CHAT_ENTRIES)
        .map { message -> message.toChatEntry(isCurrent = message.id == input.conversation.currentMessages.lastOrNull()?.id) }
    val snapshot = buildJsonObject {
        put("chat", JsonArray(chat))
        if (input.assistant != null) {
            put("character", buildJsonObject {
                put("name", input.assistant.name)
                input.assistant.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                input.assistant.personality?.takeIf { it.isNotBlank() }?.let { put("personality", it) }
                input.assistant.scenario?.takeIf { it.isNotBlank() }?.let { put("scenario", it) }
            })
        }
        put("user", buildJsonObject { put("name", input.userName) })
        if (input.worldEntries.isNotEmpty()) {
            put("worldInfo", buildJsonArray {
                input.worldEntries.forEach { (name, content) ->
                    add(buildJsonObject { put("name", name); put("content", content) })
                }
            })
        }
        put("conversationId", input.conversation.id.toString())
        put("onlineStatus", input.isGenerating)
        put("variables", input.variables)
    }
    return snapshot
}

private fun UIMessage.toChatEntry(isCurrent: Boolean): JsonObject = buildJsonObject {
    put("role", role.name.lowercase())
    put("text", toText().take(MAX_MESSAGE_TEXT_LENGTH))
    put("messageId", id.toString())
    put("isCurrent", isCurrent)
}
```

注意：`Conversation.currentMessages` 扩展是否存在——若不存在，用 `messageNodes.map { it.messages[it.selectIndex] }` 或既有等价 API（先 grep `currentMessages` 定义，`StatusHudBar.kt` 用过 `conversation.currentMessages.asReversed()`，应已存在）；`Assistant.description/personality/scenario` 字段名以实际模型为准（grep 确认）；`UIMessage.toText()` 已存在（ChatService 用过）。若 `role.name.lowercase()` 与 web-ui 的 `"ASSISTANT"` 大写约定不一致——**快照 chat 内 role 用小写**（与 ST 一致，`"assistant"`/`"user"`），spec 样例即小写。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernContextSnapshotTest"`
Expected: PASS（若字段名/扩展不存在按 Step 3 注记修正）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernContextSnapshot.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernContextSnapshotTest.kt
git commit -m "feat: add tavern context snapshot builder"
```

---

### Task 3: controller setContext + messages.getCurrent 数据源切换 + context_updated 推送

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt`（扩展）

**Interfaces:**
- Produces: `setContext(context: JsonObject?)`（Task 6 消费）；outbound 事件名 `"context_updated"`（Task 4 JS 消费）；`messages.getCurrent` 语义变更（从快照 chat 取当前消息）

- [ ] **Step 1: 写失败测试（追加到 TavernRuntimeControllerTest）**

```kotlin
    @Test
    fun `setContext emits context_updated event and dedupes unchanged context`() = runBlocking {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                me.rerere.rikkahub.data.model.TavernRuntimePermissions().copy(allowScripts = true)
            ),
        )
        val received = mutableListOf<Pair<String, JsonElement?>>()
        val job = launch {
            controller.outboundEvents.collect { received.add(it) }
        }
        yield()
        val ctx = buildJsonObject {
            put("chat", JsonArray(emptyList()))
            put("conversationId", "c1")
        }
        controller.setContext(ctx)
        yield()
        controller.setContext(ctx) // 相同内容 → 去重，不再发
        yield()
        job.cancel()
        assertEquals(1, received.count { it.first == "context_updated" })
    }

    @Test
    fun `messages getCurrent returns current chat entry from context when set`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                me.rerere.rikkahub.data.model.TavernRuntimePermissions().copy(allowScripts = true)
            ),
        )
        val m1 = buildJsonObject {
            put("role", "user")
            put("text", "hello")
            put("messageId", "m1")
            put("isCurrent", false)
        }
        val m2 = buildJsonObject {
            put("role", "assistant")
            put("text", "hi")
            put("messageId", "m2")
            put("isCurrent", true)
        }
        controller.setContext(
            buildJsonObject {
                put("chat", JsonArray(listOf(m1, m2)))
                put("conversationId", "c1")
            }
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "messages.getCurrent", params = JsonObject(emptyMap()))
        )
        assertTrue(response.ok)
        assertEquals("m2", response.result!!.jsonObject["messageId"]!!.jsonPrimitive.content)
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeControllerTest"`
Expected: FAIL（setContext 不存在 / 断言失败）

- [ ] **Step 3: 实现**

`TavernRuntimeController.kt` 修改：

```kotlin
    // dispatch 在 WebView JavaBridge 线程上读，setContext/setCurrentMessage 在宿主线程上写
    @Volatile
    private var currentMessage: JsonElement = JsonNull

    /** 宿主推送的上下文快照（SillyTavern.getContext 数据源） */
    @Volatile
    private var contextSnapshot: JsonObject? = null

    /** 上次推送的上下文内容哈希（去重用） */
    @Volatile
    private var lastContextHash: Int? = null
```

加方法（在 `setCurrentMessage` 后）：

```kotlin
    /**
     * 宿主推送上下文快照（SillyTavern.getContext 数据源）。
     * 内容不变时跳过推送；变化时经 outbound 事件 th:context_updated 送达 WebView。
     */
    fun setContext(context: JsonObject?) {
        contextSnapshot = context
        val hash = context?.hashCode()
        if (hash != lastContextHash) {
            lastContextHash = hash
            if (context != null) {
                _outboundEvents.tryEmit("context_updated" to context)
            }
        }
    }
```

`messages.getCurrent` 分支改：

```kotlin
                "messages.getCurrent" -> getCurrentMessage(request)
```

加私有方法：

```kotlin
    private fun getCurrentMessage(request: TavernRuntimeRequest): TavernRuntimeResponse {
        val fromContext = contextSnapshot?.get("chat")?.jsonArray
            ?.lastOrNull { it.jsonObject["isCurrent"]?.jsonPrimitive?.boolean == true }
            ?: contextSnapshot?.get("chat")?.jsonArray?.lastOrNull()
        return TavernRuntimeResponse.success(request.id, fromContext ?: currentMessage)
    }
```

import 加 `kotlinx.serialization.json.jsonArray`、`kotlinx.serialization.json.jsonPrimitive`、`kotlinx.serialization.json.boolean`（若需）。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.*" :app:compileDebugKotlin`
Expected: PASS（既有 45 用例 + 新 2 用例；既有 messages.getCurrent 用例在无 context 时回退 currentMessage，行为不变）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
git commit -m "feat: host-pushed context snapshot with dedupe and getCurrent switch"
```

---

### Task 4: JS 侧 SillyTavern.getContext + event_types 常量

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt`（扩展）

**Interfaces:**
- Consumes: outbound `th:context_updated`（Task 3）
- Produces: `window.event_types`、`window.SillyTavern.getContext()`（同步缓存）、`window.SillyTavern.eventSource` 别名

- [ ] **Step 1: 写失败测试（追加到 TavernRuntimeScriptTest）

```kotlin
    @Test
    fun scriptExposesEventTypesAndSillyTavernGetContext() {
        val script = buildTavernRuntimeScript()
        assertTrue(script.contains("window.event_types"))
        assertTrue(script.contains("GENERATION_STARTED"))
        assertTrue(script.contains("MESSAGE_RECEIVED"))
        assertTrue(script.contains("window.SillyTavern"))
        assertTrue(script.contains("getContext"))
        assertTrue(script.contains("context_updated"))
    }
```

（注意：现有 TavernRuntimeScriptTest 的断言风格——先读该文件，追加风格一致的用例。）

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeScriptTest"`
Expected: FAIL

- [ ] **Step 3: 实现**

`TavernRuntimeScript.kt` 的 IIFE 内（`var eventSource = {...}` 之后、`var api = {...}` 之前）插入：

```js
  // ── SillyTavern.getContext()：宿主推送快照（th:context_updated 内部订阅，无需权限） ──
  var stContext = null;
  (function(){
    var listener = function(ev){ stContext = ev.detail; };
    document.addEventListener('th:context_updated', listener);
  })();

  window.event_types = {
    GENERATION_STARTED: 'GENERATION_STARTED',
    MESSAGE_SENT: 'MESSAGE_SENT',
    MESSAGE_RECEIVED: 'MESSAGE_RECEIVED',
    MESSAGE_EDITED: 'MESSAGE_EDITED',
    MESSAGE_DELETED: 'MESSAGE_DELETED',
    MESSAGE_SWIPED: 'MESSAGE_SWIPED',
    CHARACTER_MESSAGE_RENDERED: 'CHARACTER_MESSAGE_RENDERED',
    USER_MESSAGE_RENDERED: 'USER_MESSAGE_RENDERED',
    MESSAGE_RENDERED: 'MESSAGE_RENDERED'
  };

  window.SillyTavern = window.SillyTavern || {
    getContext: function(){ return stContext; },
    eventSource: eventSource,
    event_types: window.event_types
  };
```

注意：`window.TavernHelperCompat` 防重复注入守卫在 IIFE 顶部（`if (window.TavernHelperCompat) return;`）——`window.SillyTavern` 挂在守卫之后执行，同一文档只注入一次，OK。但若宿主多次注入同一文档（不会），SillyTavern 用 `||` 保护。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.*" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt
git commit -m "feat: expose SillyTavern.getContext and event_types constants to tavern scripts"
```

---

### Task 5: 渲染事件细分（tavernMessageRole）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`（新参数 + onPageFinished 发射细分）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（调用处传 role）

**Interfaces:**
- Produces: `tavernMessageRole: MessageRole? = null` 参数；onPageFinished 按 role 发 CHARACTER_MESSAGE_RENDERED/USER_MESSAGE_RENDERED（MESSAGE_RENDERED 保留并列）

- [ ] **Step 1: MarkdownWebView 修改**

参数区（`tavernConversationId` 附近）加：

```kotlin
    /** 消息角色（渲染事件细分：assistant → CHARACTER_MESSAGE_RENDERED，user → USER_MESSAGE_RENDERED） */
    tavernMessageRole: MessageRole? = null,
```

`onPageFinished` 中现有 MESSAGE_RENDERED 发射（:396-401 区域）改为：

```kotlin
                            tavernConversationId?.let { cid ->
                                tavernHostEventBus.emit(
                                    type = TavernHostEventType.MESSAGE_RENDERED,
                                    conversationId = cid,
                                )
                                when (tavernMessageRole) {
                                    MessageRole.USER -> tavernHostEventBus.emit(
                                        type = TavernHostEventType.USER_MESSAGE_RENDERED,
                                        conversationId = cid,
                                    )
                                    MessageRole.ASSISTANT -> tavernHostEventBus.emit(
                                        type = TavernHostEventType.CHARACTER_MESSAGE_RENDERED,
                                        conversationId = cid,
                                    )
                                    else -> Unit
                                }
                            }
```

import 补 `me.rerere.ai.core.MessageRole`（若未 import）。

- [ ] **Step 2: ChatMessage 三处调用传 role**

`ChatMessage.kt` 的 `MarkdownWebView(...)` 调用处（:488、:712 等，grep `MarkdownWebView(` 定位全部）加 `tavernMessageRole = role`（`role` 是 ChatMessage 作用域的消息角色变量——按实际变量名，可能需 `message.role`）。

- [ ] **Step 3: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
git commit -m "feat: emit role-specific message rendered host events"
```

---

### Task 6: 宿主快照组装接线（ChatList → ChatMessage → MarkdownWebView → controller）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`（构建快照）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（新参数透传）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`（新参数 + LaunchedEffect setContext）

**Interfaces:**
- Consumes: `buildTavernContextSnapshot`（Task 2）、`controller.setContext`（Task 3）

- [ ] **Step 1: ChatList 构建快照**

`ChatList.kt` 的 `itemsIndexed` 作用域内（`conversation`/`assistant`/`settings` 可用；`isGenerating` 变量需确认——ChatList 有 `loading` 参数或从 ChatVM 状态取，grep 确认）构建：

```kotlin
    val tavernContextSnapshot = remember(conversation.messageNodes, conversation.id, assistant, settings.displaySetting.userNickname, loading) {
        val worldEntries = settings.lorebooks
            .filter { lorebook -> conversation.lorebookIds.contains(lorebook.id) }
            .flatMap { lorebook -> lorebook.entries.map { it.name to it.text } }
        buildTavernContextSnapshot(
            TavernContextSnapshotInput(
                conversation = conversation,
                assistant = assistant,
                userName = settings.displaySetting.userNickname.ifBlank { "User" },
                isGenerating = loading && index == lastMessageIndex,
                variables = statusVariableStore.getValue(conversation.id),
                worldEntries = worldEntries,
            )
        )
    }
```

注意：
- `statusVariableStore` 需注入（koinInject，ChatList 若未注入则加）
- 快照是**会话级**而非消息级——每节点构建同份快照浪费；更好的做法：`itemsIndexed` 之前（LazyColumn 外）构建一次 `val conversationSnapshot = remember(...) {...}`，items 内引用。执行时按此优化（构建一次）。
- `lorebook.entries` 字段名以 `Lorebook` 模型为准（grep）；条目文本字段（`text`/`content`）以实际为准
- `loading && index == lastMessageIndex` 是单消息生成标志；会话级 `isGenerating` 应取 ChatList 的 `loading` 参数本身（代表会话生成中）——按实际语义取 `loading`

- [ ] **Step 2: 透传链**

`ChatMessage` 加参数：

```kotlin
    /** 酒馆上下文快照（SillyTavern.getContext 数据源，会话级由 ChatList 构建） */
    tavernContextSnapshot: kotlinx.serialization.json.JsonObject? = null,
```

`ChatMessage` 内把 `tavernCurrentMessage` 与 `tavernContextSnapshot` 一起传给 `MarkdownWebView`（现有 `tavernCurrentMessage` 传递点 :232/:492/:723 同步加参数）。

`MarkdownWebView` 加参数：

```kotlin
    /** 上下文快照（SillyTavern.getContext 数据源；null 时不推送） */
    tavernContextSnapshot: JsonObject? = null,
```

并把现有 `LaunchedEffect(runtimeController, tavernCurrentMessage)` 扩展：

```kotlin
    LaunchedEffect(runtimeController, tavernCurrentMessage, tavernContextSnapshot) {
        tavernContextSnapshot?.let { runtimeController.setContext(it) }
        tavernCurrentMessage?.let { runtimeController.setCurrentMessage(it) }
    }
```

（`setContext` 内部去重，LaunchedEffect 每次 key 变化调用即可。）

- [ ] **Step 3: ChatList 调用处传快照**

`ChatMessage(...)` 调用（:340 附近）加 `tavernContextSnapshot = conversationSnapshot`。

- [ ] **Step 4: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt
git commit -m "feat: wire tavern context snapshot from chat list into webviews"
```

---

### Task 7: 全量验证 + 冒烟 + 文档

**Files:**
- Modify: `AGENTS.md`（Current Status 更新）

- [ ] **Step 1: Android 全量验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全绿（592 + 新增用例，0 失败）

- [ ] **Step 2: 模拟器冒烟**

1. 安装 APK、启动（web 服务可关）
2. 打开含状态块的酒馆对话（现有测试对话）
3. 验证：进入对话后 WebView 脚本收到 context_updated（TavernRuntimeSmokeActivity debug 入口或 logcat 过滤 `TavernRuntime`/`th:` 日志）；getContext 返回非空（若无直接验证手段，用 SmokeActivity 加临时脚本测试——不提交临时代码，或依赖 logcat）
4. 发送消息触发 GENERATION_STARTED/MESSAGE_SENT（logcat 或脚本订阅验证）；编辑/删除消息触发对应事件
5. 结论写入 AGENTS.md（含未覆盖项）

- [ ] **Step 3: AGENTS.md**

在 Current Status 最新块之上加：

```markdown
**2026-08-14：酒馆脚本 API 兼容（子项目 B2a：上下文与事件）。**

- `SillyTavern.getContext()`：宿主推送快照（ChatList 构建 → controller.setContext 哈希去重 → th:context_updated → JS 缓存同步返回）；
  数据面：chat（最近 50 条/isCurrent/纯文本 2000 截断）+ character/user/worldInfo/variables/onlineStatus
- `window.event_types` 常量表（ST 命名）；`window.SillyTavern.getContext/eventSource/event_types`
- 宿主事件扩面：GENERATION_STARTED/MESSAGE_SENT/MESSAGE_RECEIVED/MESSAGE_EDITED/MESSAGE_DELETED/MESSAGE_SWIPED/
  CHARACTER_MESSAGE_RENDERED/USER_MESSAGE_RENDERED；旧事件名保留并列
- messages.getCurrent 数据源切换到快照 chat 当前消息（无快照回退单消息注入）
- 验证：`:app:testDebugUnitTest`/`:app:compileDebugKotlin`/`:app:assembleDebug` 全绿；模拟器冒烟结果见下
- 待办：子项目 B2b（MacroHelper/registerMacro、SlashCommandParser/registerSlashCommand、内建斜杠命令、
  getRequestHeaders、MESSAGE_SENDING mutate 语义）
```

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "docs: record B2a tavern script api compat status"
```

---

## Self-Review

1. **Spec coverage**：§2.1（推送通道）→Task 3/6；§2.2（数据面）→Task 2；§2.3（组装点）→Task 6；§3（常量表）→Task 4；§4（发射点表）→Task 1/5；§5（权限安全）→Task 3（dispatch 既有检查覆盖）+截断在 Task 2；§6（测试）→各任务；§7（风险）→去重在 Task 3、双发射文档化在 AGENTS.md。无缺口。
2. **Placeholder scan**：无 TBD；代码完整或含精确 grep 定位指令。
3. **Type consistency**：`TavernContextSnapshotInput` 字段在 Task 2/6 一致；事件名在 Task 1/3/4/5 一致（枚举 name 与 JS 常量串一致）；`context_updated` 名在 Task 3/4 一致；`tavernContextSnapshot` 参数在 Task 6 三处一致。
