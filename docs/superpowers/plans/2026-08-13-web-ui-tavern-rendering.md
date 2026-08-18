# web-ui 酒馆渲染栈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 web-ui（React）完整渲染酒馆对话内容：状态块解析、StatusPlaceholder 部件渲染、状态 HUD、角色卡 renderStatus 沙箱实时重渲染、变量树只读同步。

**Architecture:** 后端（app web 模块）新增角色卡渲染数据端点 + ConversationDto 内嵌变量树 + 对话 SSE 流新增 `status_variables` 事件；web-ui 移植 Kotlin 解析器为 TS 纯函数（vitest 覆盖），统一用 sandboxed iframe 渲染所有酒馆 HTML（无脚本展示模式 / allow-scripts 重渲染模式），zustand 存储变量树与角色卡。

**Tech Stack:** Kotlin (Ktor routes, kotlinx.serialization)、React 19 + TypeScript、zustand、vitest、Tailwind v4（shadcn/ui new-york）。

**Spec:** `docs/superpowers/specs/2026-08-13-web-ui-tavern-rendering-design.md`

## Global Constraints

- 工作区：`C:\Users\18734\Desktop\HTML\rikkahub-source`（`rikkahub`，1 个 h）；web-ui 子目录 `web-ui/`
- Android 验证：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`；web-ui 验证（workdir `web-ui`）：`pnpm test`、`pnpm typecheck`、`pnpm lint`、`pnpm build`
- 路径别名 `~/*` → `web-ui/app/*`；Kotlin 风格 4 空格、TS 2 空格
- 不修改 Android 端既有酒馆渲染行为；抽取共享工具时保持行为一致
- 未明确要求不做本地化（组件内英文硬编码文案）
- 每任务最后给出 commit 命令，由执行者执行提交（AGENTS.md 工作流要求频繁提交）

---

### Task 1: 抽取 `TavernCardCssExtractor` 共享工具

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernCardCssExtractor.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernCardCssExtractorTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt:462-481`

**Interfaces:**
- Produces: `object TavernCardCssExtractor { fun extract(cardJson: String): String? }`（Task 2 端点消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernCardCssExtractorTest {

    @Test
    fun `extracts css from v2 data extensions css`() {
        val card = """{"data":{"extensions":{"css":"body { color: red; }"}}}"""
        assertEquals("body { color: red; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from status_css key`() {
        val card = """{"data":{"extensions":{"status_css":"h1 { font-size: 20px; }"}}}"""
        assertEquals("h1 { font-size: 20px; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from nested status object`() {
        val card = """{"data":{"extensions":{"status":{"css":".row { padding: 2px; }"}}}}"""
        assertEquals(".row { padding: 2px; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `extracts css from v1 top-level extensions`() {
        val card = """{"extensions":{"status":{"status_css":"div { margin: 0; }"}}}"""
        assertEquals("div { margin: 0; }", TavernCardCssExtractor.extract(card))
    }

    @Test
    fun `returns null for invalid json`() {
        assertNull(TavernCardCssExtractor.extract("{not json"))
    }

    @Test
    fun `returns null when no css keys present`() {
        assertNull(TavernCardCssExtractor.extract("""{"data":{"extensions":{"other":"x"}}}"""))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.status.TavernCardCssExtractorTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.ai.status

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 从 SillyTavern 角色卡原始 JSON（V2/V3）提取状态渲染 CSS。
 * SillyTavern 卡片的 CSS 可能位于 extensions.css / extensions.status_css / extensions.status.css 等位置。
 * 供 web tavern-render 端点与 StatusPlaceholderTransformer 共用。
 */
object TavernCardCssExtractor {

    fun extract(cardJson: String): String? {
        return try {
            val root = JsonInstant.parseToJsonElement(cardJson)
            val extensions = root.jsonObject["data"]?.jsonObject?.get("extensions")?.jsonObject
            val topExtensions = root.jsonObject["extensions"]?.jsonObject
            val ext = extensions ?: topExtensions ?: return null

            ext["css"]?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status_css"]?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("css")?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            ext["status"]?.jsonObject?.get("status_css")?.let { p -> if (p is JsonPrimitive && p.isString) return p.content }
            null
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 4: 修改 `StatusPlaceholderTransformer` 复用（行为不变）**

将 `extractCssFromCard`（:462-481）改为：

```kotlin
    /**
     * Extract CSS from the character card's extensions data.
     * SillyTavern cards may have CSS in extensions.css or extensions.status_css.
     * 逻辑已抽取到 TavernCardCssExtractor 供 web 端点共用。
     */
    private fun extractCssFromCard(ctx: TransformerContext): String? {
        val cardJson = ctx.assistant.tavernCardJson ?: return null
        return TavernCardCssExtractor.extract(cardJson)
    }
```

并在文件 import 区添加 `import me.rerere.rikkahub.data.ai.status.TavernCardCssExtractor`。若文件内私有 `json` 变量不再被使用则删除其声明（检查文件内其他 `json.` 引用；`buildCharacterPages`/`buildWorldHtml` 不使用它）。

- [ ] **Step 5: 全量单测 + 编译**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: 全部通过（含新增 6 用例）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/status/TavernCardCssExtractor.kt app/src/test/java/me/rerere/rikkahub/data/ai/status/TavernCardCssExtractorTest.kt app/src/main/java/me/rerere/rikkahub/data/ai/transformers/StatusPlaceholderTransformer.kt
git commit -m "feat: extract TavernCardCssExtractor shared util for web tavern-render endpoint"
```

---

### Task 2: `GET /api/assistant/{id}/tavern-render` 端点

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt`（加 `TavernRenderDto`）
- Create: `app/src/main/java/me/rerere/rikkahub/web/routes/TavernRoutes.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt:168-186`（挂载两处）
- Test: `app/src/test/java/me/rerere/rikkahub/web/dto/TavernRenderDtoTest.kt`

**Interfaces:**
- Consumes: `TavernCardCssExtractor.extract`（Task 1）
- Produces: `GET /api/assistant/{id}/tavern-render` → `{"statusRenderJs": string|null, "css": string|null}`（Task 10 web-ui 消费）

- [ ] **Step 1: 写失败测试（DTO 序列化）**

```kotlin
package me.rerere.rikkahub.web.dto

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class TavernRenderDtoTest {

    @Test
    fun `serializes full payload`() {
        val dto = TavernRenderDto(statusRenderJs = "function renderStatus(){return ''}", css = "body{}")
        val json = JsonInstant.encodeToString(dto)
        assertEquals(
            """{"statusRenderJs":"function renderStatus(){return ''}","css":"body{}"}""",
            json
        )
    }

    @Test
    fun `serializes nulls`() {
        val json = JsonInstant.encodeToString(TavernRenderDto())
        assertEquals("""{"statusRenderJs":null,"css":null}""", json)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.web.dto.TavernRenderDtoTest"`
Expected: 编译失败

- [ ] **Step 3: 加 DTO**

在 `WebDto.kt` Response DTOs 区（`WebAuthTokenResponse` 后）加：

```kotlin
@Serializable
data class TavernRenderDto(
    val statusRenderJs: String? = null,
    val css: String? = null,
)
```

- [ ] **Step 4: 建路由**

先读取 `app/src/main/java/me/rerere/rikkahub/web/routes/RouteUtils.kt` 确认 `toUuid` 的实际 package 与签名（`ConversationRoutes.kt` 中通过什么 import 引用它，沿用同样 import）。然后创建：

```kotlin
package me.rerere.rikkahub.web.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.ai.status.TavernCardCssExtractor
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.TavernRenderDto
import me.rerere.rikkahub.web.dto.toUuid

/**
 * 酒馆渲染数据端点：供 web-ui 获取角色卡 renderStatus JS 与 CSS，
 * 用于 sandboxed iframe 实时重渲染状态 HTML。
 */
fun Route.tavernRoutes(settingsStore: SettingsStore) {
    route("/assistant") {
        // GET /api/assistant/{id}/tavern-render
        get("/{id}/tavern-render") {
            val assistantId = call.parameters["id"].toUuid("assistant id")
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                ?: throw NotFoundException("Assistant not found")
            call.respond(
                TavernRenderDto(
                    statusRenderJs = assistant.statusRenderJs,
                    css = assistant.tavernCardJson?.let { TavernCardCssExtractor.extract(it) },
                )
            )
        }
    }
}
```

注意：`toUuid` import 以 `RouteUtils.kt` 实际 package 为准（若与 `me.rerere.rikkahub.web.dto` 不同则修正）。

- [ ] **Step 5: 挂载路由**

`WebApiModule.kt`：import 加 `me.rerere.rikkahub.web.routes.tavernRoutes`；`authenticate("auth-jwt")` 块内（`conversationRoutes(...)` 之后）与 else 块内（`conversationRoutes(...)` 之后）各加一行 `tavernRoutes(settingsStore)`。

- [ ] **Step 6: 测试通过 + 编译**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS（若 `toUuid` import 报错，按 Step 4 注记修正后重跑）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt app/src/main/java/me/rerere/rikkahub/web/routes/TavernRoutes.kt app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt app/src/test/java/me/rerere/rikkahub/web/dto/TavernRenderDtoTest.kt
git commit -m "feat: add GET /api/assistant/{id}/tavern-render endpoint"
```

---

### Task 3: `ConversationDto.statusVariables` 字段

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt:186-202, 328-344`
- Test: `app/src/test/java/me/rerere/rikkahub/web/dto/ConversationDtoVariablesTest.kt`

**Interfaces:**
- Produces: `snapshot`/`node_update` 事件 JSON 携带 `statusVariables` 字段（Task 10 web-ui 消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDtoVariablesTest {

    @Test
    fun `serializes statusVariables in conversation dto`() {
        val variables = buildJsonObject {
            put("hp", 42)
            put("mood", "happy")
        }
        val dto = ConversationDto(
            id = "conv-1",
            assistantId = "assistant-1",
            title = "t",
            messages = emptyList(),
            chatSuggestions = emptyList(),
            isPinned = false,
            createAt = 0L,
            updateAt = 0L,
            statusVariables = variables,
        )
        val json = JsonInstant.encodeToString(ConversationSnapshotEvent(seq = 1, conversation = dto))
        assertTrue(json.contains("\"statusVariables\""))
        assertTrue(json.contains("\"hp\":42"))
    }

    @Test
    fun `serializes null statusVariables`() {
        val dto = ConversationDto(
            id = "conv-1",
            assistantId = "assistant-1",
            title = "t",
            messages = emptyList(),
            chatSuggestions = emptyList(),
            isPinned = false,
            createAt = 0L,
            updateAt = 0L,
        )
        val json = JsonInstant.encodeToString(ConversationSnapshotEvent(seq = 1, conversation = dto))
        assertTrue(json.contains("\"statusVariables\":null"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.web.dto.ConversationDtoVariablesTest"`
Expected: 编译失败（无 statusVariables 参数）

- [ ] **Step 3: 实现**

`WebDto.kt` 修改两处：

```kotlin
@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val customSystemPrompt: String? = null,
    val modeInjectionIds: List<String> = emptyList(),
    val lorebookIds: List<String> = emptyList(),
    val workspaceCwd: String? = null,
    val folderId: String? = null,
    val authorNote: AuthorNote? = null,
    val statusVariables: kotlinx.serialization.json.JsonObject? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)
```

`toDto`（:328）加映射：

```kotlin
fun Conversation.toDto(isGenerating: Boolean = false) = ConversationDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    messages = messageNodes.map { it.toDto() },
    chatSuggestions = chatSuggestions,
    isPinned = isPinned,
    customSystemPrompt = customSystemPrompt,
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    workspaceCwd = workspaceCwd,
    folderId = folderId?.toString(),
    authorNote = authorNote,
    statusVariables = statusVariables,
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)
```

- [ ] **Step 4: 测试通过 + 全量编译**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt app/src/test/java/me/rerere/rikkahub/web/dto/ConversationDtoVariablesTest.kt
git commit -m "feat: include statusVariables in ConversationDto"
```

---

### Task 4: 对话 stream 新增 `status_variables` SSE 事件

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:283`（加 getter 方法）
- Modify: `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt`（加 `ConversationStatusVariablesEvent`）
- Modify: `app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt:366-450`
- Test: `app/src/test/java/me/rerere/rikkahub/web/dto/StatusVariablesEventTest.kt`

**Interfaces:**
- Consumes: `StatusVariableStore.getState`（既有）
- Produces: `ChatService.getStatusVariablesFlow(conversationId: Uuid): StateFlow<JsonObject>`；SSE 事件 `{"type":"status_variables","seq":N,"conversationId":"...","variables":{...},"serverTime":...}`（Task 10 web-ui 消费）

- [ ] **Step 1: 写失败测试（事件 DTO 序列化）**

```kotlin
package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusVariablesEventTest {

    @Test
    fun `serializes status_variables event`() {
        val event = ConversationStatusVariablesEvent(
            seq = 7,
            conversationId = "conv-1",
            variables = buildJsonObject { put("hp", 42) },
            serverTime = 1000L,
        )
        val json = JsonInstant.encodeToString(event)
        assertEquals(
            """{"type":"status_variables","seq":7,"conversationId":"conv-1","variables":{"hp":42},"serverTime":1000}""",
            json
        )
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.web.dto.StatusVariablesEventTest"`
Expected: 编译失败

- [ ] **Step 3: 加事件 DTO + ChatService getter**

`WebDto.kt` SSE Event DTOs 区加：

```kotlin
@Serializable
data class ConversationStatusVariablesEvent(
    val type: String = "status_variables",
    val seq: Long,
    val conversationId: String,
    val variables: kotlinx.serialization.json.JsonObject,
    val serverTime: Long = System.currentTimeMillis()
)
```

`ChatService.kt` 在 `errors` 属性附近加：

```kotlin
    /** 供 web 层订阅每会话状态变量变化（status_variables SSE 事件）。 */
    fun getStatusVariablesFlow(conversationId: Uuid): StateFlow<JsonObject> =
        statusVariableStore.getState(conversationId)
```

（`statusVariableStore` 保持 private；`Uuid`/`StateFlow`/`JsonObject` 已在文件 import 中。）

- [ ] **Step 4: stream 合并变量流**

`ConversationRoutes.kt` sse 块内（`conversationEvents` 定义后）加：

```kotlin
                val statusVariableEvents = chatService
                    .getStatusVariablesFlow(uuid)
                    .distinctUntilChanged()
                    .map { variables ->
                        ConversationStreamPayload.StatusVariables(variables)
                    }
```

> **实施注记（Task 4 已执行）：** 本项目 kotlinx-coroutines 1.11.0 将 `StateFlow.distinctUntilChanged()` 声明为 ERROR 级 deprecation（对 StateFlow 无效果），照抄将编译失败。**实现时删除该调用**——StateFlow 自带 equality 去重，语义等价。

`merge(conversationEvents, errorEvents)` 改为 `merge(conversationEvents, errorEvents, statusVariableEvents)`；when 分支加：

```kotlin
                        is ConversationStreamPayload.StatusVariables -> {
                            sequence += 1
                            val json = JsonInstant.encodeToString(
                                ConversationStatusVariablesEvent(
                                    seq = sequence,
                                    conversationId = uuid.toString(),
                                    variables = payload.value
                                )
                            )
                            send(data = json, event = "status_variables")
                        }
```

sealed 接口加成员：

```kotlin
private sealed interface ConversationStreamPayload {
    data class Conversation(val value: ConversationDto) : ConversationStreamPayload
    data class BatchErrors(val messages: List<String>) : ConversationStreamPayload
    data class StatusVariables(val value: kotlinx.serialization.json.JsonObject) : ConversationStreamPayload
}
```

import 加 `me.rerere.rikkahub.web.dto.ConversationStatusVariablesEvent`。

- [ ] **Step 5: 测试通过 + 全量编译**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt app/src/test/java/me/rerere/rikkahub/web/dto/StatusVariablesEventTest.kt
git commit -m "feat: push status_variables events on conversation stream"
```

---

### Task 5: web-ui 引入 vitest

**Files:**
- Modify: `web-ui/package.json`（scripts + devDependency）
- Create: `web-ui/vitest.config.ts`

- [ ] **Step 1: 安装依赖**

Run（workdir `web-ui`）: `pnpm add -D vitest`
Expected: 安装成功，`package.json` devDependencies 出现 `vitest`

- [ ] **Step 2: 建配置**

```ts
import { defineConfig } from "vitest/config";
import tsconfigPaths from "vite-tsconfig-paths";

export default defineConfig({
  plugins: [tsconfigPaths()],
  test: {
    environment: "node",
    include: ["app/**/*.test.ts"],
  },
});
```

- [ ] **Step 3: 加 scripts**

`package.json` scripts 加：`"test": "vitest run"`

- [ ] **Step 4: 验证**

Run: `pnpm test`
Expected: 通过（无测试文件时输出 "No test files found"；如 exit 非 0 则加 `--passWithNoTests` 参数）

- [ ] **Step 5: Commit**

```bash
git add web-ui/package.json web-ui/pnpm-lock.yaml web-ui/vitest.config.ts
git commit -m "chore: add vitest to web-ui"
```

---

### Task 6: `status-tags.ts` 移植

**Files:**
- Create: `web-ui/app/lib/tavern/status-tags.ts`
- Test: `web-ui/app/lib/tavern/status-tags.test.ts`

**Interfaces:**
- Produces: `STATUS_TAG_NAMES`、`openTagRegex()`、`closeTagRegex()`、`segmentRegex()`、`wrapperRegex()`（Task 7 消费）

- [x] **Step 1: 写失败测试（样例与 Kotlin `StatusTagsTest` 对齐）**

```ts
import { describe, expect, it } from "vitest";
import { closeTagRegex, openTagRegex, segmentRegex, wrapperRegex } from "./status-tags";

describe("status-tags", () => {
  it("matches all tag family variants (case-insensitive)", () => {
    const variants = ["status_block", "statusblock", "statusbar", "status", "status!", "状态栏"];
    for (const v of variants) {
      expect(openTagRegex().test(`<${v}>`)).toBe(true);
      expect(closeTagRegex().test(`</${v}>`)).toBe(true);
    }
  });

  it("matches tags with inner whitespace", () => {
    expect(openTagRegex().test("< status_block >")).toBe(true);
    expect(openTagRegex().test("<STATUS_BLOCK>")).toBe(true);
  });

  it("segmentRegex extends to end of text when close tag missing", () => {
    const m = segmentRegex().exec("<status_block>hello world");
    expect(m).not.toBeNull();
    expect(m![0]).toContain("hello world");
  });

  it("wrapperRegex extracts inner text", () => {
    const m = wrapperRegex().exec("<statusbar>\nline\n</statusbar>");
    expect(m).not.toBeNull();
    expect(m![1]).toContain("line");
  });
});
```

- [x] **Step 2: 运行确认失败**

Run: `pnpm test status-tags`
Expected: FAIL（模块不存在）

- [x] **Step 3: 实现**

```ts
/**
 * 状态块标签族的单一事实来源（与 Kotlin StatusTags 对齐）。
 * 标签族（大小写不敏感）：status_block / statusblock / statusbar / status / status! / 状态栏。
 */
export const STATUS_TAG_NAMES = "status_block|statusblock|statusbar|status!?|状态栏";

/** 开标签：`<status_block>` 等，标签名两侧允许空白。 */
export function openTagRegex(): RegExp {
  return new RegExp(`<\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "i");
}

/** 闭标签：`</status_block>` 等。 */
export function closeTagRegex(): RegExp {
  return new RegExp(`</\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "i");
}

/** 段落级：从开标签开始匹配，缺失闭标签时延伸到文本末尾。 */
export function segmentRegex(): RegExp {
  return new RegExp(`<(?:${STATUS_TAG_NAMES})>[\\s\\S]*?(?:</(?:${STATUS_TAG_NAMES})>|$)`, "i");
}

/** 整块包裹：整段内容恰好由一个状态块包裹（用于提取展示文本）。 */
export function wrapperRegex(): RegExp {
  return new RegExp(
    `^\\s*<(?:${STATUS_TAG_NAMES})>\\s*([\\s\\S]*?)(?:</(?:${STATUS_TAG_NAMES})>\\s*)?$`,
    "i",
  );
}
```

- [x] **Step 4: 运行确认通过**

Run: `pnpm test status-tags`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add web-ui/app/lib/tavern/status-tags.ts web-ui/app/lib/tavern/status-tags.test.ts
git commit -m "feat: port StatusTags to web-ui"
```

---

### Task 7: `status-extractor.ts` 移植

**Files:**
- Create: `web-ui/app/lib/tavern/status-extractor.ts`
- Test: `web-ui/app/lib/tavern/status-extractor.test.ts`

**Interfaces:**
- Consumes: `STATUS_TAG_NAMES`（Task 6）
- Produces: `StatusSection`、`StatusOption`、`StatusExtraction` 类型 + `extractStatusBlock(text): StatusExtraction`（Task 12/13 消费）

- [ ] **Step 1: 写失败测试（样例与 Kotlin `StatusBlockExtractorTest` 对齐）**

```ts
import { describe, expect, it } from "vitest";
import { extractStatusBlock } from "./status-extractor";

describe("status-extractor", () => {
  it("extracts status_block with header, details section and options", () => {
    const text = [
      "正文第一段。",
      "<status_block>",
      "『当前状态』",
      "<details><summary>角色</summary>HP 42/50</details>",
      "1. [继续] 推开大门",
      "2. [撤退] 返回营地",
      "</status_block>",
      "正文第二段。",
    ].join("\n");
    const result = extractStatusBlock(text);
    expect(result.cleanedText).toContain("正文第一段");
    expect(result.cleanedText).toContain("正文第二段");
    expect(result.cleanedText).not.toContain("status_block");
    expect(result.headerLine).toBe("『当前状态』");
    expect(result.sections).toHaveLength(1);
    expect(result.sections[0]!.title).toBe("角色");
    expect(result.sections[0]!.content).toContain("HP 42/50");
    expect(result.options).toHaveLength(2);
    expect(result.options[0]).toEqual({ label: "继续", text: "推开大门" });
    expect(result.rawStatusText).toContain("status_block");
  });

  it("strips maintext tags keeping content", () => {
    const result = extractStatusBlock("<maintext>你好</maintext>");
    expect(result.cleanedText).toBe("你好");
    expect(result.rawStatusText).toBeNull();
  });

  it("bare details fallback: consecutive details blocks at end", () => {
    const text = [
      "剧情正文。",
      "<details><summary>A</summary>内容A</details>",
      "<details><summary>B</summary>内容B</details>",
    ].join("\n");
    const result = extractStatusBlock(text);
    expect(result.cleanedText).toBe("剧情正文。");
    expect(result.sections).toHaveLength(2);
    expect(result.sections[0]!.title).toBe("A");
    expect(result.rawStatusText).not.toBeNull();
  });

  it("unclosed open tag extends status region to end of text", () => {
    const result = extractStatusBlock("开头\n<statusbar>\n剩余都是状态");
    expect(result.cleanedText).toBe("开头");
    expect(result.rawStatusText).toContain("剩余都是状态");
  });

  it("returns original text when no status markers", () => {
    const result = extractStatusBlock("普通消息");
    expect(result.cleanedText).toBe("普通消息");
    expect(result.rawStatusText).toBeNull();
    expect(result.sections).toHaveLength(0);
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test status-extractor`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现**（逐函数移植 `StatusBlockExtractor.kt`）

```ts
import { STATUS_TAG_NAMES } from "./status-tags";

/** 状态区域中的一个分节（对应 `<details><summary>T</summary>body</details>`，或未被 details 包裹的剩余成段文字——此时 title 为空串）。 */
export interface StatusSection {
  title: string;
  content: string;
  isHtml: boolean;
}

/** 状态区域末尾的编号选项（如 `1. [最佳] 冒险潜入……`）。 */
export interface StatusOption {
  label: string;
  text: string;
}

/** 一次状态块提取的结果。 */
export interface StatusExtraction {
  cleanedText: string;
  headerLine: string | null;
  sections: StatusSection[];
  options: StatusOption[];
  rawStatusText: string | null;
}

const maintextTagRegex = /<\/?\s*maintext\s*>/gi;
const detailsRegex = /<\s*details\s*>\s*<\s*summary\s*>(.*?)<\/\s*summary\s*>(.*?)<\/\s*details\s*>/gis;
const optionLineRegex = /^\s*(\d+)\s*[.、）)]\s*(?:\[([^\]]+)])?\s*(.+)$/;
const cornerTitleLineRegex = /^\s*『.*』\s*$/;
const fenceLineRegex = /^\s*```[A-Za-z0-9_-]*\s*$/;
const allowedHtmlRegex = /<\/?\s*(details|summary|br)\b[^>]*>/gi;
const htmlTagRegex = /<\s*\/?\s*[A-Za-z][^>]*>/;
const multiBlankLinesRegex = /\n[ \t]*(?:\n[ \t]*)+/g;

function findAll(regex: RegExp, input: string): RegExpExecArray[] {
  const matches: RegExpExecArray[] = [];
  let match = regex.exec(input);
  while (match !== null) {
    matches.push(match);
    match = regex.exec(input);
  }
  return matches;
}

function stripFenceLines(body: string): string {
  return body
    .split("\n")
    .filter((line) => !fenceLineRegex.test(line))
    .join("\n")
    .trim();
}

function containsHtml(content: string): boolean {
  return htmlTagRegex.test(content.replace(allowedHtmlRegex, ""));
}

export function extractStatusBlock(text: string): StatusExtraction {
  if (text.length === 0) {
    return { cleanedText: "", headerLine: null, sections: [], options: [], rawStatusText: null };
  }
  const input = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  // 1. 定位所有状态区域（未闭合的延伸到文末）。
  const spans: Array<{ start: number; end: number }> = [];
  const contents: string[] = [];
  let searchFrom = 0;
  while (searchFrom < input.length) {
    const openRe = new RegExp(`<\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "gi");
    openRe.lastIndex = searchFrom;
    const open = openRe.exec(input);
    if (open === null) break;
    const openEnd = open.index + open[0].length;
    const closeRe = new RegExp(`</\\s*(?:${STATUS_TAG_NAMES})\\s*>`, "gi");
    closeRe.lastIndex = openEnd;
    const close = closeRe.exec(input);
    const contentEnd = close === null ? input.length : close.index;
    const spanEnd = close === null ? input.length : close.index + close[0].length;
    spans.push({ start: open.index, end: spanEnd });
    contents.push(input.slice(openEnd, contentEnd));
    searchFrom = spanEnd;
  }

  if (spans.length === 0) {
    return (
      extractBareDetailsFallback(input) ?? {
        cleanedText: input.replace(maintextTagRegex, ""),
        headerLine: null,
        sections: [],
        options: [],
        rawStatusText: null,
      }
    );
  }

  // 2. cleanedText：移除状态区域 + 剥 maintext 标签 + trim + 压缩空行。
  let narrative = "";
  let cursor = 0;
  for (const span of spans) {
    narrative += input.slice(cursor, span.start);
    cursor = span.end;
  }
  narrative += input.slice(cursor);
  const cleanedText = narrative
    .replace(maintextTagRegex, "")
    .replace(multiBlankLinesRegex, "\n\n")
    .trim();

  // 3. rawStatusText。
  const rawStatusText = spans.map((span) => input.slice(span.start, span.end)).join("\n");

  // 4. 逐区域解析（跨区域保持文档顺序）。
  const sections: StatusSection[] = [];
  const options: StatusOption[] = [];
  let headerLine: string | null = null;
  for (const content of contents) {
    headerLine = parseRegion(content, sections, options, headerLine);
  }

  return { cleanedText, headerLine, sections, options, rawStatusText };
}

function extractBareDetailsFallback(input: string): StatusExtraction | null {
  const matches = findAll(detailsRegex, input);
  if (matches.length === 0) return null;

  const runs: RegExpExecArray[][] = [];
  let previousEnd = -1;
  for (const match of matches) {
    const matchStart = match.index;
    if (runs.length === 0 || input.slice(previousEnd, matchStart).trim() !== "") {
      runs.push([match]);
    } else {
      runs[runs.length - 1]!.push(match);
    }
    previousEnd = matchStart + match[0].length;
  }

  const run = [...runs]
    .reverse()
    .find((candidates) => {
      const last = candidates[candidates.length - 1]!;
      return candidates.length >= 2 || input.slice(last.index + last[0].length).trim() === "";
    });
  if (run === undefined) return null;

  const runStart = run[0]!.index;
  const runEnd = run[run.length - 1]!.index + run[run.length - 1]![0].length;

  const narrative = input.slice(0, runStart) + input.slice(runEnd);
  const cleanedText = narrative
    .replace(maintextTagRegex, "")
    .replace(multiBlankLinesRegex, "\n\n")
    .trim();

  const sections = run.map((match) => {
    const title = (match[1] ?? "").trim();
    const body = stripFenceLines(match[2] ?? "");
    return { title, content: body, isHtml: containsHtml(body) };
  });

  return {
    cleanedText,
    headerLine: null,
    sections,
    options: [],
    rawStatusText: input.slice(runStart, runEnd),
  };
}

function parseRegion(
  content: string,
  sections: StatusSection[],
  options: StatusOption[],
  headerLine: string | null,
): string | null {
  let header = headerLine;
  let cursor = 0;
  for (const match of findAll(detailsRegex, content)) {
    header = processPlainSegment(content.slice(cursor, match.index), sections, options, header);
    const title = (match[1] ?? "").trim();
    const body = stripFenceLines(match[2] ?? "");
    sections.push({ title, content: body, isHtml: containsHtml(body) });
    cursor = match.index + match[0].length;
  }
  header = processPlainSegment(content.slice(cursor), sections, options, header);
  return header;
}

function processPlainSegment(
  segment: string,
  sections: StatusSection[],
  options: StatusOption[],
  headerLine: string | null,
): string | null {
  let header = headerLine;
  const lines = segment.split("\n");
  const consumed = new Array<boolean>(lines.length).fill(false);

  if (header === null) {
    const idx = lines.findIndex((line) => cornerTitleLineRegex.test(line));
    if (idx >= 0) {
      header = lines[idx]!.trim();
      consumed[idx] = true;
    }
  }

  let i = 0;
  while (i < lines.length) {
    if (!optionLineRegex.test(lines[i] ?? "")) {
      i += 1;
      continue;
    }
    if (i > 0 && !consumed[i - 1] && cornerTitleLineRegex.test(lines[i - 1] ?? "")) {
      consumed[i - 1] = true;
    }
    while (i < lines.length) {
      const m = optionLineRegex.exec(lines[i] ?? "");
      if (m === null) break;
      consumed[i] = true;
      options.push({ label: (m[2] ?? "").trim(), text: (m[3] ?? "").trim() });
      i += 1;
    }
  }

  const rest = lines
    .filter((_, index) => !consumed[index])
    .join("\n")
    .trim();
  if (rest.length > 0) {
    sections.push({ title: "", content: rest, isHtml: containsHtml(rest) });
  }
  return header;
}
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test status-extractor`
Expected: PASS（5 用例）

- [ ] **Step 5: 从 Kotlin `StatusBlockExtractorTest.kt` 补充对齐样例**

将 Kotlin 测试中额外的高价值样例移植为 TS 用例（至少补：标签族全变体逐条提取、`<details>` 内 ``` 围栏剥离、选项块前『…』标题行消费、多个状态区域拼接 rawStatusText、无标题 section 合并、isHtml 判定）。每补一个跑 `pnpm test status-extractor` 确认。

- [ ] **Step 6: Commit**

```bash
git add web-ui/app/lib/tavern/status-extractor.ts web-ui/app/lib/tavern/status-extractor.test.ts
git commit -m "feat: port StatusBlockExtractor to web-ui"
```

---

### Task 8: `fallback-html.ts` 移植

**Files:**
- Create: `web-ui/app/lib/tavern/fallback-html.ts`
- Test: `web-ui/app/lib/tavern/fallback-html.test.ts`

**Interfaces:**
- Produces: `buildFallbackHtml(variables: Record<string, unknown>, metadata: Record<string, string>): string`、`escapeHtml(s: string): string`、`appendRows`（Task 11 重渲染降级路径消费）

- [ ] **Step 1: 写失败测试（样例与 Kotlin `StatusFallbackHtmlTest` 对齐）**

```ts
import { describe, expect, it } from "vitest";
import { buildFallbackHtml, escapeHtml } from "./fallback-html";

describe("fallback-html", () => {
  it("escapes html in keys and values", () => {
    expect(escapeHtml("<a>&</a>")).toBe("&lt;a&gt;&amp;&lt;/a&gt;");
  });

  it("renders expression header when present", () => {
    const html = buildFallbackHtml({ hp: 42 }, { expression: "战斗" });
    expect(html).toContain("战斗");
    expect(html).toContain("hp");
    expect(html).toContain("42");
  });

  it("escapes variable values against injection", () => {
    const html = buildFallbackHtml({ evil: "<script>alert(1)</script>" }, {});
    expect(html).not.toContain("<script>");
    expect(html).toContain("&lt;script&gt;");
  });

  it("renders nested map and list values", () => {
    const html = buildFallbackHtml(
      { char: { name: "A", tags: ["x", "y"] }, count: 3 },
      {},
    );
    expect(html).toContain("char");
    expect(html).toContain("name");
    expect(html).toContain("x, y");
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test fallback-html`
Expected: FAIL

- [ ] **Step 3: 实现**

```ts
/**
 * 状态变量 fallback HTML 的统一构建器（与 Kotlin StatusFallbackHtml 对齐）。
 * 安全约定：所有来自状态变量的 key/value 一律做 HTML 转义（& < >）。
 */
const ROOT_STYLE = "font-family:sans-serif;font-size:13px;line-height:1.5;";

export function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function appendRows(html: string[], map: Record<string, unknown>, indent = 0): void {
  for (const [key, value] of Object.entries(map)) {
    if (isRecord(value)) {
      html.push(`<div style="font-weight:600;margin-top:4px;">${escapeHtml(key)}</div>`);
      html.push(`<div style="margin-left:${8 + indent * 8}px;">`);
      appendRows(html, value, indent + 1);
      html.push("</div>");
    } else if (Array.isArray(value)) {
      const joined = value.map((item) => item?.toString() ?? "—").join(", ");
      html.push(`<div><b>${escapeHtml(key)}:</b> ${escapeHtml(joined)}</div>`);
    } else {
      const displayValue = value?.toString() ?? "—";
      html.push(`<div><b>${escapeHtml(key)}:</b> ${escapeHtml(displayValue)}</div>`);
    }
  }
}

export function buildFallbackHtml(
  variables: Record<string, unknown>,
  metadata: Record<string, string>,
): string {
  const html: string[] = [];
  html.push(`<div style="${ROOT_STYLE}">`);
  const expression = metadata["expression"];
  if (expression != null && expression.trim() !== "") {
    html.push(
      `<div style="font-size:16px;font-weight:600;margin-bottom:4px;">${escapeHtml(expression)}</div>`,
    );
  }
  if (Object.keys(variables).length > 0) {
    appendRows(html, variables);
  }
  html.push("</div>");
  return html.join("");
}
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test fallback-html`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web-ui/app/lib/tavern/fallback-html.ts web-ui/app/lib/tavern/fallback-html.test.ts
git commit -m "feat: port StatusFallbackHtml to web-ui"
```

---

### Task 9: web-ui 类型扩展

**Files:**
- Modify: `web-ui/app/types/parts.ts`
- Modify: `web-ui/app/types/dto.ts`

**Interfaces:**
- Produces: `StatusPlaceholderPart`、`CharacterStatusPage`、`TextPart.renderMode`、`ConversationDto.statusVariables`、`StatusVariablesEventDto`、`TavernRenderDto`（Task 10-13 消费）

- [ ] **Step 1: 修改 `parts.ts`**

`TextPart` 加字段（序列化值小写 `markdown`/`html`，见 `Message.kt` `@SerialName`）：

```ts
export interface TextPart extends BaseMessagePart {
  type: "text";
  text: string;
  renderMode?: "markdown" | "html";
}
```

文件末尾 union 前加：

```ts
export interface CharacterStatusPage {
  name: string;
  html: string;
}

export interface StatusPlaceholderPart extends BaseMessagePart {
  type: "status_placeholder";
  htmlContent: string;
  characterPages?: CharacterStatusPage[];
}
```

union 加成员：

```ts
export type UIMessagePart =
  | TextPart
  | ImagePart
  | VideoPart
  | AudioPart
  | DocumentPart
  | ReasoningPart
  | ToolPart
  | StatusPlaceholderPart;
```

- [ ] **Step 2: 修改 `dto.ts`**

`ConversationDto` 加：

```ts
  statusVariables?: Record<string, unknown> | null;
```

文件末尾加事件 DTO：

```ts
export interface StatusVariablesEventDto {
  type: "status_variables";
  seq: number;
  conversationId: string;
  variables: Record<string, unknown>;
  serverTime: number;
}
```

- [ ] **Step 3: typecheck**

Run: `pnpm typecheck`
Expected: PASS（若 `message-part.tsx` switch 出现「not all code paths return」类报错，属预期，Task 12 加分支后消除；其他报错需修复）

> **实施注记（Task 9 已执行）：** typecheck 实际出现 3 处 TS2366 穷尽性报错，位置为 `chat-message.tsx`（`hasRenderablePart`、`formatPartForCopy` 两处 switch）与 `conversations.tsx`（`getQuickJumpPreview` switch），而非计划预期的 `message-part.tsx`（其 `renderContentPart` 返回推断类型不触发 TS2366）。**Task 12 需额外给这三处 switch 补 `status_placeholder` case**（见 Task 12 注记）。`TavernRenderDto` 类型按计划由 Task 10 在 `services/tavern.ts` 中定义，不放入 types/。

- [ ] **Step 4: Commit**

```bash
git add web-ui/app/types/parts.ts web-ui/app/types/dto.ts
git commit -m "feat: add status_placeholder part and statusVariables types to web-ui"
```

---

### Task 10: 酒馆数据 store + 服务层 + SSE 接线

**Files:**
- Create: `web-ui/app/stores/tavern.ts`
- Modify: `web-ui/app/stores/index.ts`（导出）
- Create: `web-ui/app/services/tavern.ts`
- Modify: `web-ui/app/routes/conversations.tsx:58-61, 336-399`

**Interfaces:**
- Consumes: `TavernRenderDto`、`StatusVariablesEventDto`（Task 9）；`GET /api/assistant/{id}/tavern-render`（Task 2）；`status_variables` SSE 事件（Task 4）
- Produces: `useTavernStore`（`setVariables(conversationId, variables)`、`variablesByConversation`、`ensureCardLoaded(assistantId)`、`cardsByAssistant`、`cardOf(assistantId)`）（Task 12-13 消费）

- [ ] **Step 1: 建 store**

```ts
import { create } from "zustand";

import type { TavernRenderDto } from "~/services/tavern";

interface TavernCardEntry {
  statusRenderJs: string | null;
  css: string | null;
}

interface TavernState {
  variablesByConversation: Record<string, Record<string, unknown>>;
  cardsByAssistant: Record<string, TavernCardEntry>;
  loadingAssistantIds: Record<string, boolean>;
  setVariables: (conversationId: string, variables: Record<string, unknown>) => void;
  ensureCardLoaded: (assistantId: string) => Promise<void>;
  cardOf: (assistantId: string) => TavernCardEntry | undefined;
}

export const useTavernStore = create<TavernState>()((set, get) => ({
  variablesByConversation: {},
  cardsByAssistant: {},
  loadingAssistantIds: {},

  setVariables: (conversationId, variables) => {
    set((state) => ({
      variablesByConversation: { ...state.variablesByConversation, [conversationId]: variables },
    }));
  },

  ensureCardLoaded: async (assistantId) => {
    if (get().cardsByAssistant[assistantId] || get().loadingAssistantIds[assistantId]) return;
    set((state) => ({
      loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: true },
    }));
    try {
      const { fetchTavernRender } = await import("~/services/tavern");
      const data: TavernRenderDto = await fetchTavernRender(assistantId);
      set((state) => ({
        cardsByAssistant: {
          ...state.cardsByAssistant,
          [assistantId]: {
            statusRenderJs: data.statusRenderJs ?? null,
            css: data.css ?? null,
          },
        },
        loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: false },
      }));
    } catch {
      set((state) => ({
        cardsByAssistant: {
          ...state.cardsByAssistant,
          [assistantId]: { statusRenderJs: null, css: null },
        },
        loadingAssistantIds: { ...state.loadingAssistantIds, [assistantId]: false },
      }));
    }
  },

  cardOf: (assistantId) => get().cardsByAssistant[assistantId],
}));
```

`stores/index.ts` 加：`export { useTavernStore } from "~/stores/tavern";`

- [ ] **Step 2: 建服务**

```ts
import api from "./api";

export interface TavernRenderDto {
  statusRenderJs?: string | null;
  css?: string | null;
}

export function fetchTavernRender(assistantId: string): Promise<TavernRenderDto> {
  return api.get<TavernRenderDto>(`assistant/${assistantId}/tavern-render`);
}
```

- [ ] **Step 3: SSE 接线（conversations.tsx）**

import 区：`import { useTavernStore } from "~/stores";`（store 经 index 导出）与 `import type { StatusVariablesEventDto } from "~/types";`

`ConversationStreamEvent` union（:58-61）加成员：

```ts
type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto
  | ConversationErrorEventDto
  | StatusVariablesEventDto;
```

初始 GET（:338-341）里在 `setDetail(data)` 后加：

```ts
        if (data.statusVariables) {
          useTavernStore.getState().setVariables(data.id, data.statusVariables);
        }
        useTavernStore.getState().ensureCardLoaded(data.assistantId);
```

`onMessage`（:356-387）在 error 分支之后、snapshot 分支之前加：

```ts
          if (event === "status_variables" && data.type === "status_variables") {
            useTavernStore.getState().setVariables(data.conversationId, data.variables);
            return;
          }
```

snapshot 分支（:364-370）`setDetail(data.conversation)` 后加：

```ts
            if (data.conversation.statusVariables) {
              useTavernStore.getState().setVariables(data.conversation.id, data.conversation.statusVariables);
            }
            useTavernStore.getState().ensureCardLoaded(data.conversation.assistantId);
```

- [ ] **Step 4: typecheck + 测试**

Run: `pnpm typecheck && pnpm test`
Expected: PASS（既有测试不受影响）

- [ ] **Step 5: Commit**

```bash
git add web-ui/app/stores/tavern.ts web-ui/app/stores/index.ts web-ui/app/services/tavern.ts web-ui/app/routes/conversations.tsx
git commit -m "feat: add tavern variable/card stores and status_variables SSE wiring"
```

---

### Task 11: `HtmlFrame` 组件（sandboxed iframe 统一渲染）

**Files:**
- Create: `web-ui/app/components/tavern/html-frame.tsx`

**Interfaces:**
- Consumes: `buildFallbackHtml`（Task 8）
- Produces: `<HtmlFrame html className maxHeightPx minHeightPx? />` 与 `<RenderStatusFrame statusRenderJs variables metadata css fallbackHtml onResult />`（Task 12/13 消费）

**设计要点（spec §4.1）：**
- 展示模式：`sandbox="allow-same-origin"`（无 `allow-scripts`）——同源可读 `contentDocument` 测量高度，脚本被禁。
- 重渲染模式：`sandbox="allow-scripts"`（无 `allow-same-origin`，opaque origin）——执行 renderStatus，`postMessage` 回传 HTML + 高度。
- 懒加载：IntersectionObserver 进入视口才注入 srcdoc。
- 高度自适应：展示模式在 iframe `onLoad` 后读 `contentDocument.documentElement.scrollHeight`，1s×10 次轮询兜底图片/字体加载后的高度变化。
- `</script>` 逃逸清洗：注入 renderStatus 源码前替换 `</script` → `<\/script`。

- [ ] **Step 1: 实现**

```tsx
import * as React from "react";

import { cn } from "~/lib/utils";

interface HtmlFrameProps {
  html: string;
  className?: string;
  maxHeightPx?: number;
  minHeightPx?: number;
}

/**
 * 沙箱 iframe 展示模式：渲染不受信 HTML（无脚本执行），
 * 高度由父页面读取 contentDocument 自适应。
 */
export function HtmlFrame({ html, className, maxHeightPx, minHeightPx = 40 }: HtmlFrameProps) {
  const containerRef = React.useRef<HTMLDivElement>(null);
  const frameRef = React.useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = React.useState(minHeightPx);
  const [visible, setVisible] = React.useState(false);
  const [srcdoc, setSrcdoc] = React.useState<string | null>(null);

  React.useEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true);
        observer.disconnect();
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  React.useEffect(() => {
    if (!visible) return;
    setSrcdoc(html);
    setHeight(minHeightPx);
  }, [visible, html, minHeightPx]);

  const syncHeight = React.useCallback(() => {
    const doc = frameRef.current?.contentDocument;
    if (!doc) return;
    const measured = doc.documentElement.scrollHeight;
    setHeight((prev) => (measured !== prev ? measured : prev));
  }, []);

  return (
    <div
      ref={containerRef}
      className={cn("overflow-hidden rounded-md border border-border/60 bg-background", className)}
    >
      {visible && srcdoc !== null && (
        <iframe
          ref={frameRef}
          title="Tavern status"
          sandbox="allow-same-origin"
          srcDoc={srcdoc}
          onLoad={() => {
            syncHeight();
            let attempts = 0;
            const timer = window.setInterval(() => {
              syncHeight();
              attempts += 1;
              if (attempts >= 10) window.clearInterval(timer);
            }, 1000);
          }}
          style={{
            width: "100%",
            height: maxHeightPx !== undefined ? Math.min(height, maxHeightPx) : height,
            border: "none",
            display: "block",
          }}
        />
      )}
    </div>
  );
}

const RENDER_RESULT_MESSAGE = "rikkahub:render-result";

/** 清洗 renderStatus 源码，防 `</script>` 逃逸出 script 标签。 */
function sanitizeScriptSource(source: string): string {
  return source.replace(/<\/script/gi, "<\\/script");
}

/**
 * 沙箱重渲染模式：sandbox="allow-scripts"（opaque origin），
 * 执行角色卡 renderStatus(variables, metadata)，postMessage 回传 HTML 与高度。
 */
export function RenderStatusFrame({
  statusRenderJs,
  variables,
  metadata,
  css,
  fallbackHtml,
  onResult,
}: {
  statusRenderJs: string;
  variables: Record<string, unknown>;
  metadata: Record<string, string>;
  css?: string | null;
  fallbackHtml: string;
  onResult: (html: string) => void;
}) {
  const frameRef = React.useRef<HTMLIFrameElement>(null);
  const [done, setDone] = React.useState(false);

  const srcdoc = React.useMemo(() => {
    const variablesJson = JSON.stringify(variables).replace(/</g, "\\u003c");
    const metadataJson = JSON.stringify(metadata).replace(/</g, "\\u003c");
    return [
      "<!doctype html><html><head>",
      css != null ? `<style>${css}</style>` : "",
      "</head><body><script>",
      "window.addEventListener('error', function() {",
      `parent.postMessage({type:'${RENDER_RESULT_MESSAGE}', error:true}, '*');`,
      "});",
      sanitizeScriptSource(statusRenderJs),
      "try {",
      "  var result = (typeof renderStatus === 'function') ? renderStatus(" +
        variablesJson +
        ", " +
        metadataJson +
        ") : null;",
      "  var html = (result == null) ? '' : String(result);",
      "  parent.postMessage({type:'" + RENDER_RESULT_MESSAGE + "', html: html}, '*');",
      "} catch (e) {",
      `  parent.postMessage({type:'${RENDER_RESULT_MESSAGE}', error: true}, '*');`,
      "}",
      "</scr" + "ipt></body></html>",
    ].join("\n");
  }, [statusRenderJs, variables, metadata, css]);

  React.useEffect(() => {
    const handler = (event: MessageEvent) => {
      if (frameRef.current && event.source !== frameRef.current.contentWindow) return;
      const data = event.data as { type?: string; html?: string; error?: boolean };
      if (data?.type !== RENDER_RESULT_MESSAGE) return;
      setDone(true);
      onResult(typeof data.html === "string" ? data.html : fallbackHtml);
    };
    window.addEventListener("message", handler);
    return () => window.removeEventListener("message", handler);
  }, [fallbackHtml, onResult]);

  React.useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!done) onResult(fallbackHtml);
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [done, fallbackHtml, onResult]);

  return (
    <iframe
      ref={frameRef}
      title="Tavern renderStatus"
      sandbox="allow-scripts"
      srcDoc={srcdoc}
      style={{ display: "none" }}
    />
  );
}
```

- [ ] **Step 2: typecheck + lint**

Run: `pnpm typecheck && pnpm lint`
Expected: PASS

> **实施注记（Task 11 审查遗留，Task 12 修复）：**
> 1. `RenderStatusFrame` 消息 handler 需加 done 守卫：`if (done) return;` 后再处理（防异步定时器异常经 window error 发 error 消息触发二次 `onResult(fallbackHtml)` 覆盖成功结果）。
> 2. `StatusPlaceholderView` 挂载 `RenderStatusFrame` 时必须 `key={renderNonce}`（变量变化强制重挂载，重置 done，否则超时降级只对第一次渲染有效）。

- [ ] **Step 3: Commit**

```bash
git add web-ui/app/components/tavern/html-frame.tsx
git commit -m "feat: add sandboxed HtmlFrame and RenderStatusFrame components"
```

---

### Task 12: `StatusPlaceholderView` + 消息渲染接线

**Files:**
- Create: `web-ui/app/components/tavern/status-placeholder-view.tsx`
- Modify: `web-ui/app/components/message/message-part.tsx`
- Modify: `web-ui/app/components/message/parts/text-part.tsx`
- Modify: `web-ui/app/components/message/chat-message.tsx`（若需透传 tavernContext）

**Interfaces:**
- Consumes: `HtmlFrame`、`RenderStatusFrame`（Task 11）；`useTavernStore`（Task 10）；`extractStatusBlock`（Task 7）
- Produces: `StatusPlaceholderView`（消息渲染用）

- [ ] **Step 1: 实现 `StatusPlaceholderView`**

```tsx
import * as React from "react";

import type { StatusPlaceholderPart } from "~/types";
import { useTavernStore } from "~/stores";
import { buildFallbackHtml } from "~/lib/tavern/fallback-html";
import { HtmlFrame, RenderStatusFrame } from "./html-frame";

/**
 * StatusPlaceholder 部件渲染：
 * - characterPages >= 2：Tabs 多角色分页
 * - 单页：展示服务端 htmlContent；若角色卡 renderStatus JS 与最新变量树可用，
 *   用 sandboxed iframe 实时重渲染，成功后替换显示。
 */
export function StatusPlaceholderView({
  part,
  conversationId,
  assistantId,
}: {
  part: StatusPlaceholderPart;
  conversationId: string;
  assistantId: string;
}) {
  const variables = useTavernStore((state) => state.variablesByConversation[conversationId]);
  const card = useTavernStore((state) => state.cardsByAssistant[assistantId]);
  const [reRenderedHtml, setReRenderedHtml] = React.useState<string | null>(null);
  const [renderNonce, setRenderNonce] = React.useState(0);

  React.useEffect(() => {
    void useTavernStore.getState().ensureCardLoaded(assistantId);
  }, [assistantId]);

  React.useEffect(() => {
    if (variables) setRenderNonce((n) => n + 1);
  }, [variables]);

  const pages = part.characterPages ?? [];

  if (pages.length >= 2) {
    const [activeIndex, setActiveIndex] = React.useState(0);
    return (
      <div className="w-full">
        <div className="flex flex-wrap gap-1 pb-2">
          {pages.map((page, index) => (
            <button
              key={page.name}
              type="button"
              onClick={() => setActiveIndex(index)}
              className={
                index === activeIndex
                  ? "rounded-full bg-primary px-3 py-1 text-xs font-medium text-primary-foreground"
                  : "rounded-full bg-muted px-3 py-1 text-xs font-medium text-muted-foreground"
              }
            >
              {page.name}
            </button>
          ))}
        </div>
        <HtmlFrame html={pages[activeIndex]?.html ?? ""} maxHeightPx={420} />
      </div>
    );
  }

  const displayHtml = reRenderedHtml ?? part.htmlContent;
  const shouldReRender = card?.statusRenderJs && variables && renderNonce > 0;

  return (
    <div className="w-full">
      <HtmlFrame html={displayHtml} maxHeightPx={560} />
      {shouldReRender && card?.statusRenderJs && (
        <RenderStatusFrame
          statusRenderJs={card.statusRenderJs}
          variables={variables}
          metadata={{ expression: extractExpression(variables) }}
          css={card.css}
          fallbackHtml={buildFallbackHtml(variables, {})}
          onResult={(html) => setReRenderedHtml(html)}
        />
      )}
    </div>
  );
}

function extractExpression(variables: Record<string, unknown>): string {
  const value = variables["_expression"];
  return typeof value === "string" ? value : "";
}
```

- [ ] **Step 2: 接线 `message-part.tsx`**

import 加：`import { StatusPlaceholderView } from "~/components/tavern/status-placeholder-view";`

`renderContentPart`（:73-99）签名加参数并加分支：

```tsx
function renderContentPart(
  part: UIMessagePart,
  t: (key: string, options?: Record<string, unknown>) => string,
  loading?: boolean,
  onClickCitation?: (id: string) => void,
  tavernContext?: { conversationId: string; assistantId: string },
) {
  switch (part.type) {
    case "text":
      return (
        <TextPart
          text={part.text}
          renderMode={part.renderMode}
          isAnimating={loading}
          onClickCitation={onClickCitation}
        />
      );
    // ... 其余现有 case 不变 ...
    case "status_placeholder":
      return tavernContext ? (
        <StatusPlaceholderView
          part={part}
          conversationId={tavernContext.conversationId}
          assistantId={tavernContext.assistantId}
        />
      ) : null;
  }
}
```

`MessagePartsProps` 加可选 `tavernContext?: { conversationId: string; assistantId: string };`，`renderContentPart` 调用处（:164）透传：`renderContentPart(block.part, t, loading, onClickCitation, tavernContext)`。

- [ ] **Step 3: `text-part.tsx` 支持 renderMode + 状态标签剥离**

```tsx
import Markdown from "~/components/markdown/markdown";
import { extractStatusBlock } from "~/lib/tavern/status-extractor";
import { HtmlFrame } from "~/components/tavern/html-frame";

interface TextPartProps {
  text: string;
  renderMode?: "markdown" | "html";
  isAnimating?: boolean;
  onClickCitation?: (id: string) => void;
}

export function TextPart({ text, renderMode, isAnimating, onClickCitation }: TextPartProps) {
  if (!text) return null;
  if (renderMode === "html") {
    return (
      <div data-part="text">
        <HtmlFrame html={text} maxHeightPx={560} />
      </div>
    );
  }
  // 状态块由 HUD 展示：气泡文本剥离状态区域与 maintext 标签（对齐 Android ChatMessage.kt）
  const cleaned = extractStatusBlock(text).cleanedText;
  if (!cleaned) return null;
  return (
    <div data-part="text">
      <Markdown content={cleaned} isAnimating={isAnimating} onClickCitation={onClickCitation} />
    </div>
  );
}
```

- [ ] **Step 4: 检查 `chat-message.tsx` 调用链并透传 tavernContext**

读取 `web-ui/app/components/message/chat-message.tsx`，找到 `MessageParts` 调用处。若其 props 中已有 `assistant`（含 id）与 conversation 信息，则在其 props 上加可选 `tavernContext` 并从 `conversations.tsx:654` 传 `tavernContext={{ conversationId: detail.id, assistantId: detail.assistantId }}`；若无现成信息，给 `ChatMessage` props 加可选 `tavernContext?: { conversationId: string; assistantId: string }` 并透传到 `MessageParts`。

> **实施注记（Task 9 遗留，本步骤顺带修复）：** Task 9 后 typecheck 有 3 处 TS2366，本任务修复：
> - `web-ui/app/components/message/chat-message.tsx` `hasRenderablePart` switch → `case "status_placeholder": return true;`
> - `chat-message.tsx` `formatPartForCopy` switch → `case "status_placeholder": return null;`（HTML 不进复制文本）
> - `web-ui/app/routes/conversations.tsx` `getQuickJumpPreview` 的 `fallbackPart.type` switch → `case "status_placeholder": return t("conversations.preview.empty_message");`（复用既有 i18n key）
> 修复后 Step 5 的 `pnpm typecheck` 应为 0 报错。

- [ ] **Step 5: typecheck + lint + test**

Run: `pnpm typecheck && pnpm lint && pnpm test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add web-ui/app/components/tavern/status-placeholder-view.tsx web-ui/app/components/message/message-part.tsx web-ui/app/components/message/parts/text-part.tsx web-ui/app/components/message/chat-message.tsx web-ui/app/routes/conversations.tsx
git commit -m "feat: render status_placeholder parts in web-ui messages"
```

（按实际改动文件清单提交，只 add 真正修改的文件。）

---

### Task 13: 状态 HUD 栏 + 选项点击发送

**Files:**
- Create: `web-ui/app/components/tavern/status-hud.tsx`
- Modify: `web-ui/app/routes/conversations.tsx`（挂载 + 选项发送）

**Interfaces:**
- Consumes: `extractStatusBlock`（Task 7）、`HtmlFrame`（Task 11）
- Produces: `<StatusHudBar messages onOptionClick />`

- [ ] **Step 1: 实现 HUD**

```tsx
import * as React from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import type { MessageDto } from "~/types";
import { extractStatusBlock, type StatusExtraction } from "~/lib/tavern/status-extractor";
import { HtmlFrame } from "./html-frame";

interface HudSource {
  extraction: StatusExtraction;
}

/** 从尾部往前找最近一条含状态块的 assistant 消息（对齐 Android StatusHudBar.findLatestStatusHud）。 */
function findLatestStatusHud(messages: MessageDto[]): HudSource | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!message || message.role !== "ASSISTANT") continue;
    const text = message.parts
      .filter((part) => part.type === "text")
      .map((part) => (part.type === "text" ? part.text : ""))
      .join("\n");
    if (!text.trim()) continue;
    const extraction = extractStatusBlock(text);
    if (extraction.rawStatusText != null) {
      return { extraction };
    }
  }
  return null;
}

export function StatusHudBar({
  messages,
  onOptionClick,
}: {
  messages: MessageDto[];
  onOptionClick: (optionText: string) => void;
}) {
  const [expanded, setExpanded] = React.useState(false);
  const hud = React.useMemo(() => findLatestStatusHud(messages), [messages]);
  if (!hud) return null;

  const { extraction } = hud;

  return (
    <div className="rounded-xl border border-border/60 bg-muted/40 px-4 py-3">
      <button
        type="button"
        className="flex w-full items-center justify-between text-sm font-medium text-foreground/80"
        onClick={() => setExpanded((value) => !value)}
      >
        <span>{extraction.headerLine ?? "Status"}</span>
        {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
      </button>

      {expanded && (
        <div className="flex flex-col gap-3 pt-3">
          {extraction.sections.map((section, index) => (
            <div key={`${section.title}-${index}`}>
              {section.title && (
                <div className="pb-1 text-xs font-semibold text-foreground/70">{section.title}</div>
              )}
              {section.isHtml ? (
                <HtmlFrame html={section.content} maxHeightPx={300} />
              ) : (
                <pre className="whitespace-pre-wrap text-xs leading-5 text-foreground/80">
                  {section.content}
                </pre>
              )}
            </div>
          ))}

          {extraction.options.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {extraction.options.map((option, index) => (
                <button
                  key={`${option.label}-${index}`}
                  type="button"
                  className="rounded-full border border-border bg-background px-3 py-1 text-xs text-foreground/80 transition-colors hover:bg-accent hover:text-accent-foreground"
                  onClick={() => onOptionClick(option.text)}
                >
                  {option.label ? `[${option.label}] ` : ""}
                  {option.text}
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {!expanded && extraction.options.length > 0 && (
        <div className="flex flex-wrap gap-2 pt-2">
          {extraction.options.map((option, index) => (
            <button
              key={`${option.label}-${index}`}
              type="button"
              className="rounded-full border border-border bg-background px-3 py-1 text-xs text-foreground/80 transition-colors hover:bg-accent hover:text-accent-foreground"
              onClick={() => onOptionClick(option.text)}
            >
              {option.label ? `[${option.label}] ` : ""}
              {option.text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: 挂载 + 发送（conversations.tsx）**

import 加：`import { StatusHudBar } from "~/components/tavern/status-hud";`

`<ConversationContent>`（:613）内、消息 map（:645）之前加：

```tsx
          {activeId && detail && (
            <StatusHudBar
              messages={selectedNodeMessages.map(({ message }) => message)}
              onOptionClick={(optionText) => {
                void api
                  .post<{ status: string }>(`conversations/${activeId}/messages`, {
                    parts: [{ type: "text", text: optionText }],
                  })
                  .catch(() => undefined);
              }}
            />
          )}
```

- [ ] **Step 3: typecheck + lint + test**

Run: `pnpm typecheck && pnpm lint && pnpm test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add web-ui/app/components/tavern/status-hud.tsx web-ui/app/routes/conversations.tsx
git commit -m "feat: add status HUD bar with option chips to web conversations"
```

---

### Task 14: 全量验证 + 文档

**Files:**
- Modify: `AGENTS.md`（Current Status 块更新）

- [ ] **Step 1: Android 全量验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全部通过（74 类 + 新增 4 类，0 失败）

- [ ] **Step 2: web-ui 全量验证**

Run（workdir `web-ui`）: `pnpm test && pnpm typecheck && pnpm lint && pnpm build`
Expected: 全部通过

- [ ] **Step 3: 冒烟（可选，需要本地 `pnpm dev` + 运行中的 app web 服务器）**

1. 启动 app web 服务器（模拟器 + 设置开启 web 服务）与 `pnpm dev`
2. 用酒馆角色卡对话触发状态块：确认消息气泡内 `status_placeholder` 渲染（无原始标签泄漏）、HUD 出现且可折叠、多角色分页 tabs、HTML section 高度自适应
3. 点击 HUD 选项：确认发送新消息并生成
4. 生成期间观察变量变化：确认 `status_variables` 事件驱动重渲染（角色卡带 renderStatus JS 时）
5. 无角色卡 JS 场景：确认直接展示服务端 htmlContent（降级路径）
6. 安全抽查：状态内容含 `<script>` 时不执行（展示模式无脚本）

- [ ] **Step 4: 更新 AGENTS.md Current Status**

在文件顶部 Current Status 最新块之上加新块：

```markdown
**2026-08-13：web-ui 酒馆渲染栈（子项目 A）。**

- 后端：`GET /api/assistant/{id}/tavern-render` 端点（TavernCardCssExtractor 共享抽取）；
  `ConversationDto.statusVariables`；对话 stream 新增 `status_variables` SSE 事件（订阅 StatusVariableStore StateFlow）
- web-ui：TS 移植 StatusTags/StatusBlockExtractor/StatusFallbackHtml（vitest 覆盖，与 Kotlin 测试样例对齐）；
  sandboxed iframe 统一渲染（HtmlFrame 展示模式 + RenderStatusFrame 重渲染模式）；
  StatusPlaceholder 部件渲染（多角色 tabs）、文本剥离状态标签、StatusHudBar + 选项点击发送；
  zustand 变量树/角色卡 store + SSE 接线
- 验证：`:app:testDebugUnitTest` 全绿（+4 类）、`:app:assembleDebug` 通过；
  `pnpm test/typecheck/lint/build` 全绿
- 待办：子项目 B（Android 渲染器提升：样式/性能/主题/脚本 API）
```

- [ ] **Step 5: Commit**

```bash
git add AGENTS.md
git commit -m "docs: record web-ui tavern rendering stack status"
```

---

## Self-Review

1. **Spec coverage**：spec §2.1→Task 1-2；§2.2→Task 3；§2.3→Task 4；§3.1→Task 9；§3.2→Task 6-8；§3.3→Task 10；§3.4→Task 10；§4.1→Task 11；§4.2→Task 12；§4.3→Task 13；§4.4（降级）→Task 11 超时回退 + Task 12 条件渲染 + Task 14 冒烟；§5→Task 14。无缺口。
2. **Placeholder scan**：无 TBD；所有代码步骤含完整代码。
3. **Type consistency**：`StatusExtraction` 字段名在 Task 7/12/13 一致；`useTavernStore` 的 `setVariables/ensureCardLoaded/cardOf/variablesByConversation/cardsByAssistant` 在 Task 10/11/12 一致；事件 DTO `status_variables` 名称 Kotlin/TS 两侧一致。
