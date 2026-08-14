# 酒馆脚本 API 兼容性 Implementation Plan（子项目 B2b：宏与命令）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MacroHelper/registerMacro（宿主 QuickJS 同步展开）、SlashCommandParser/registerSlashCommand（内建+磁盘+WebView 注册）、内建斜杠命令（/setvar 等 chat 变量）、getRequestHeaders（独立权限位）、MESSAGE_SENDING mutate（宏同步 + 异步钩子 best-effort）；并入 B2a 遗留首修（主路径 tavern 参数链、流式快照键降级）。

**Architecture:** 宿主 `TavernScriptRegistry`（Koin 单例，独立 QuickJS executor）持久注册宏/命令，发送管线 `preprocessUserInputParts` 同步展开（宏即 mutate 通道）；`SlashCommandInterceptor` 前置分发内建命令（StatusVariableStoreAccessor 读写 chat 变量）；`TavernRuntimeController` 扩展 RPC（macros./slash.register/requestHeaders.get/sendHook.register）+ 新权限位；JS 侧 MacroHelper/SlashCommandParser 垫片。

**Tech Stack:** Kotlin/Compose、QuickJS（com.whl.quickjs）、kotlinx.serialization、JUnit（TDD）。

**Spec:** `docs/superpowers/specs/2026-08-14-tavern-script-api-compat-b2b-design.md`

## Global Constraints

- 工作区：`C:\Users\18734\Desktop\HTML\rikkahub-source`（rikkahub，1 个 h）
- 验证：`.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
- Kotlin 4 空格、行宽 120；JS 模板 2 空格
- **绝对不要 `git add .`**——每次 commit 只 add 任务文件
- 兼容不变式：既有 606 测试不回归；磁盘斜杠脚本行为不变；USER 正则先于宏展开
- 安全不变式：宏/命令源码 ≤64KB、注册数 ≤64；执行超时 2s（宏）/500ms（异步钩子）；QuickJS 上下文不注入宿主对象；权限位默认 false
- 每任务 commit；commit message 遵循 repo 风格

---

### Task 1: 主路径 tavern 参数链修复（B2a 遗留首修）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/MultiCharacterStatusView.kt`

**Interfaces:**
- Produces: `MarkdownBlock(..., tavernConversationId: Uuid? = null, tavernCurrentMessage: JsonElement? = null, tavernContextSnapshot: JsonObject? = null, tavernMessageRole: MessageRole? = null)`；`MultiCharacterStatusView(part, modifier, tavernConversationId, tavernCurrentMessage, tavernContextSnapshot, tavernMessageRole)`

- [ ] **Step 1: 定位现状（grep）**

```bash
rg -n "fun MarkdownBlock" app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
rg -n "MarkdownWebView\(" app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
rg -n "MarkdownBlock\(" app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
rg -n "MultiCharacterStatusView\(" app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
```

预期：MarkdownBlock 定义约 :267；内部 MarkdownWebView 5 处（STABLE_DOM 分支、递归内 2 处、STATUS/JSON、HTML_DOCUMENT、hasStatusBlock 整段）；ChatMessage 3 处 MarkdownBlock 调用；MultiCharacterStatusView 调用 1 处。

- [ ] **Step 2: MarkdownBlock 加参并透传**

签名加 4 参数（默认 null，import `kotlin.uuid.Uuid`、`kotlinx.serialization.json.JsonElement/JsonObject`、`me.rerere.ai.core.MessageRole` 按需补）。5 处内部 MarkdownWebView 调用加参数：

```kotlin
MarkdownWebView(
    content = ...,
    modifier = ...,
    isRawHtml = ...,
    tavernConversationId = tavernConversationId,
    tavernCurrentMessage = tavernCurrentMessage,
    tavernContextSnapshot = tavernContextSnapshot,
    tavernMessageRole = tavernMessageRole,
    // 既有参数保留（streaming/streamSegments/tavernStyleVersionKey 等）
)
```

递归 `MarkdownBlock(...)`（分段分支内）同步透传 4 参数 + 既有 roleName/stableRole/tavernCardStyle/streaming 参数透传（检查现状递归是否已透传既有参数，未透传则一并补）。

- [ ] **Step 3: ChatMessage 3 处调用补传**

```kotlin
MarkdownBlock(
    content = ...,
    onClickCitation = ...,
    roleName = ...,
    stableRole = ...,
    tavernCardStyle = ...,
    streaming = loading,
    tavernConversationId = tavernConversationId,
    tavernCurrentMessage = tavernCurrentMessage,
    tavernContextSnapshot = tavernContextSnapshot,
    tavernMessageRole = role,
)
```

（`tavernConversationId/tavernCurrentMessage/tavernContextSnapshot` 已在该作用域——ChatMessage 参数与 :136-138 计算值；`role` 为 MessagePartsBlock 的 displayRole。）

- [ ] **Step 4: MultiCharacterStatusView 加参透传**

签名加 4 参数；内部 MarkdownWebView（:115 附近）补传；ChatMessage.kt:723 调用点补传（参数同样已在作用域）。

- [ ] **Step 5: 验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全绿（606 基线 + 编译）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/MultiCharacterStatusView.kt
git commit -m "fix: thread tavern context params through markdown block main path"
```

---

### Task 2: ScriptVariableAccessor 接口化 + StatusVariableStoreAccessor

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/SlashScriptEngine.kt`（accessor 抽象）
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/StatusVariableStoreAccessor.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/slash/StatusVariableStoreAccessorTest.kt`

**Interfaces:**
- Produces: `interface ScriptVariableAccessor { fun get(key: String): String?; fun set(key: String, value: String); fun delete(key: String); fun all(): Map<String, String> }`（原具体类改实现该接口，字段不变）；`StatusVariableStoreAccessor(conversationId: Uuid?, store: StatusVariableStore)`（Task 3/4 消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class StatusVariableStoreAccessorTest {

    @Test
    fun `reads and writes single keys against chat variables`() {
        val store = StatusVariableStore()
        val conversationId = Uuid.random()
        store.set(conversationId, buildJsonObject { put("hp", 100) })
        val accessor = StatusVariableStoreAccessor(conversationId, store)

        assertEquals("100", accessor.get("hp"))
        accessor.set("mp", "50")
        assertEquals("50", accessor.get("mp"))
        accessor.delete("hp")
        assertNull(accessor.get("hp"))
        assertEquals(1, accessor.all().size)
    }

    @Test
    fun `returns null for missing key and missing conversation`() {
        val store = StatusVariableStore()
        val accessor = StatusVariableStoreAccessor(null, store)
        assertNull(accessor.get("anything"))
        assertEquals(0, accessor.all().size)
    }

    @Test
    fun `numeric values survive round trip as strings`() {
        val store = StatusVariableStore()
        val conversationId = Uuid.random()
        val accessor = StatusVariableStoreAccessor(conversationId, store)
        accessor.set("gold", "42")
        assertEquals("42", accessor.get("gold"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.StatusVariableStoreAccessorTest"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

`SlashScriptEngine.kt` 中 `ScriptVariableAccessor` 改为接口 + 原实现类（保持字段/构造不变，实现接口）。`StatusVariableStoreAccessor.kt`：

```kotlin
package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import kotlin.uuid.Uuid

/**
 * 以酒馆 chat 作用域变量（StatusVariableStore）为后端的变量访问器。
 * 单键操作 = getValue 后 copy JsonObject 再 set（StatusVariableStore 无单键 API）。
 */
class StatusVariableStoreAccessor(
    private val conversationId: Uuid?,
    private val store: StatusVariableStore,
) : ScriptVariableAccessor {

    override fun get(key: String): String? {
        val conversationId = conversationId ?: return null
        return store.getValue(conversationId)[key]?.let { element ->
            when (element) {
                is JsonNull -> null
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }
    }

    override fun set(key: String, value: String) {
        val conversationId = conversationId ?: return
        val current = store.getValue(conversationId).toMutableMap()
        current[key] = JsonPrimitive(value)
        store.set(conversationId, JsonObject(current))
    }

    override fun delete(key: String) {
        val conversationId = conversationId ?: return
        val current = store.getValue(conversationId).toMutableMap()
        current.remove(key)
        store.set(conversationId, JsonObject(current))
    }

    override fun all(): Map<String, String> {
        val conversationId = conversationId ?: return emptyMap()
        return store.getValue(conversationId).entries.mapNotNull { (key, element) ->
            val value = when (element) {
                is JsonNull -> null
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
            value?.let { key to it }
        }.toMap()
    }
}
```

注意：原 `ScriptVariableAccessor` 是 class（`SlashScriptEngine.kt:297-304`，绑定 ScriptVariableStore）——改造为接口时检查其调用点（SlashCommandInterceptor :50-57 构造、SlashScriptEngine 内使用）保持行为不变。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.*" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/slash/SlashScriptEngine.kt app/src/main/java/me/rerere/rikkahub/data/ai/slash/StatusVariableStoreAccessor.kt app/src/test/java/me/rerere/rikkahub/data/ai/slash/StatusVariableStoreAccessorTest.kt
git commit -m "feat: abstract slash variable accessor and add chat-variable backed impl"
```

---

### Task 3: TavernScriptRegistry（宿主宏/命令注册表 + QuickJS 执行）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistry.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`（Koin single 注册）
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistryTest.kt`

**Interfaces:**
- Produces: `class TavernScriptRegistry`：`registerMacro(name, source): Boolean`、`registerSlashCommand(name, callbackSource, aliases, helpString): Boolean`、`removeMacro(name)`、`removeSlashCommand(name)`、`listMacros(): List<String>`、`listSlashCommands(): List<SlashCommandInfo>`、`expandMacros(text, context: MacroExpandContext): String`、`executeSlashCommand(name, args, context): SlashCommandResult`（Task 4/5/7 消费）

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.slash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernScriptRegistryTest {

    private fun registry() = TavernScriptRegistry()

    @Test
    fun `registers and expands a macro`() {
        val registry = registry()
        // 宏约定：function macro(args) { return ...; }（args 为参数字符串）
        assertTrue(registry.registerMacro("greet", "function macro(args){ return 'Hello ' + args; }"))
        val out = registry.expandMacros(
            "{{greet::world}} and {{greet::there}}",
            MacroExpandContext(userName = "U", charName = "C"),
        )
        assertEquals("Hello world and Hello there", out)
    }

    @Test
    fun `reregistering replaces existing macro`() {
        val registry = registry()
        registry.registerMacro("m", "function macro(args){ return 'a'; }")
        registry.registerMacro("m", "function macro(args){ return 'b'; }")
        assertEquals("b", registry.expandMacros("{{m::}}", MacroExpandContext("U", "C")))
    }

    @Test
    fun `macro expansion falls back to original text on failure`() {
        val registry = registry()
        registry.registerMacro("bad", "function macro(args){ throw new Error('boom'); }")
        assertEquals("{{bad::x}}", registry.expandMacros("{{bad::x}}", MacroExpandContext("U", "C")))
    }

    @Test
    fun `unregistered macro syntax stays untouched`() {
        val registry = registry()
        assertEquals("{{nope::x}}", registry.expandMacros("{{nope::x}}", MacroExpandContext("U", "C")))
    }

    @Test
    fun `registers slash command with aliases and help`() {
        val registry = registry()
        assertTrue(registry.registerSlashCommand("flip", "function callback(args){ return 'flipped'; }", listOf("f"), "flip text"))
        val info = registry.listSlashCommands().single()
        assertEquals("flip", info.name)
        assertEquals(listOf("f"), info.aliases)
        assertEquals("flip text", info.helpString)
    }

    @Test
    fun `rejects oversized macro source`() {
        val registry = registry()
        val big = "function macro(args){ return '" + "x".repeat(70 * 1024) + "'; }"
        assertFalse(registry.registerMacro("big", big))
    }

    @Test
    fun `enforces registration count limit`() {
        val registry = registry()
        repeat(64) { index -> registry.registerMacro("m$index", "function macro(args){ return ''; }") }
        assertFalse(registry.registerMacro("overflow", "function macro(args){ return ''; }"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.TavernScriptRegistryTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.ai.slash

import com.whl.quickjs.wrapper.QuickJSContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 宏展开上下文（注入 QuickJS 的数据面） */
data class MacroExpandContext(
    val userName: String = "",
    val charName: String = "",
    val conversationId: String? = null,
)

data class SlashCommandInfo(
    val name: String,
    val aliases: List<String>,
    val helpString: String,
)

/** 宏/命令执行结果（与 SlashScriptEngine 的 Result 语义对齐） */
data class SlashCommandResult(
    val text: String? = null,
    val html: String? = null,
    val error: String? = null,
)

/** 宏源码体积上限（UTF-8 字节） */
private const val MAX_SOURCE_BYTES = 64 * 1024

/** 注册上限 */
private const val MAX_REGISTRATIONS = 64

/** 宏展开单次执行超时 */
private const val MACRO_EXECUTION_TIMEOUT_MS = 2_000L

/**
 * 宿主侧酒馆脚本注册表（应用级，WebView 重载不丢）。
 * 宏与斜杠命令源码在独立 QuickJS 单线程 executor 中执行（与 SlashScriptEngine 隔离）。
 */
class TavernScriptRegistry {

    private class MacroEntry(val name: String, val source: String)

    private class SlashEntry(val info: SlashCommandInfo, val source: String)

    private val macros = ConcurrentHashMap<String, MacroEntry>()
    private val slashCommands = ConcurrentHashMap<String, SlashEntry>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TavernScriptRegistry").apply { isDaemon = true }
    }

    private val macroFunctions = ConcurrentHashMap<String, Any?>()
    private val slashFunctions = ConcurrentHashMap<String, Any?>()

    private val contextRef = AtomicReference<QuickJSContext?>()

    private fun getOrCreateContext(): QuickJSContext {
        contextRef.get()?.let { return it }
        val context = QuickJSContext.create()
        contextRef.set(context)
        return context
    }

    /** 从源码求值出函数句柄并缓存（源码变化时重新求值） */
    private fun loadFunction(cache: ConcurrentHashMap<String, Any?>, name: String, source: String, wrapper: (String) -> String): Any? {
        cache[name]?.let { return it }
        val context = getOrCreateContext()
        val fn = try {
            context.evaluate("(${wrapper(source)})")
        } catch (e: Exception) {
            return null
        }
        if (fn is String) return null // 求值返回字符串说明是表达式值，非法
        cache[name] = fn
        return fn
    }

    fun registerMacro(name: String, source: String): Boolean {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return false
        if (macros.size >= MAX_REGISTRATIONS && !macros.containsKey(name)) return false
        macros[name] = MacroEntry(name, source)
        macroFunctions.remove(name) // 重新求值
        return true
    }

    fun removeMacro(name: String) {
        macros.remove(name)
        macroFunctions.remove(name)
    }

    fun listMacros(): List<String> = macros.keys.toList()

    fun registerSlashCommand(name: String, callbackSource: String, aliases: List<String>, helpString: String): Boolean {
        if (callbackSource.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return false
        if (slashCommands.size >= MAX_REGISTRATIONS && !slashCommands.containsKey(name)) return false
        slashCommands[name] = SlashEntry(SlashCommandInfo(name, aliases, helpString), callbackSource)
        slashFunctions.remove(name)
        return true
    }

    fun removeSlashCommand(name: String) {
        slashCommands.remove(name)
        slashFunctions.remove(name)
    }

    fun listSlashCommands(): List<SlashCommandInfo> = slashCommands.values.map { it.info }

    /**
     * 同步展开注册宏：`{{name::args}}` 形态（大小写不敏感名匹配）。
     * 无注册宏/执行失败时保留原文。
     */
    fun expandMacros(text: String, context: MacroExpandContext): String {
        if (macros.isEmpty()) return text
        val macroRegex = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)(?:::([^}]*))?}}")
        return macroRegex.replace(text) { match ->
            val name = match.groupValues[1]
            val args = match.groupValues[2]
            val entry = macros[name] ?: return@replace match.value
            val fn = loadFunction(macroFunctions, name, entry.source) { source ->
                // wrapper：包成返回 macro 函数的表达式（source 本身是 function 声明）
                "($source)"
            } ?: return@replace match.value
            val result = runOnExecutor {
                try {
                    (fn as com.whl.quickjs.wrapper.JSFunction).call(args)?.toString()
                } catch (e: Exception) {
                    null
                }
            } ?: return@replace match.value
            result
        }
    }

    fun executeSlashCommand(name: String, args: String, context: MacroExpandContext): SlashCommandResult? {
        val entry = slashCommands[name] ?: return null
        val fn = loadFunction(slashFunctions, name, entry.source) { source ->
            "(function(){ var callback = ($source); return callback; })()"
        } ?: return SlashCommandResult(error = "callback evaluation failed")
        val result = runOnExecutor {
            try {
                (fn as com.whl.quickjs.wrapper.JSFunction).call(args)?.toString()
            } catch (e: Exception) {
                null
            }
        }
        if (result == null) return SlashCommandResult(error = "callback execution failed")
        return SlashCommandResult(text = result)
    }

    private fun runOnExecutor(block: () -> String?): String? {
        val future = executor.submit<String>(block)
        return try {
            future.get(MACRO_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            null
        }
    }

    fun clear() {
        macros.clear()
        slashCommands.clear()
        macroFunctions.clear()
        slashFunctions.clear()
    }
}
```

注意：QuickJS 的 `JSFunction`/`evaluate` 返回类型按 com.whl.quickjs 实际 API 调整（先读 SlashScriptEngine.kt 的实际用法——:65-79 的 evaluate/call 形态）。loadFunction 的 wrapper 形态按实测调整（函数声明求值返回函数句柄的机制以 SlashScriptEngine 为参考）。

`AppModule.kt` 注册：

```kotlin
    single {
        TavernScriptRegistry()
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.TavernScriptRegistryTest" :app:compileDebugKotlin`
Expected: PASS（按实际 QuickJS API 微调后）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistry.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt app/src/test/java/me/rerere/rikkahub/data/ai/slash/TavernScriptRegistryTest.kt
git commit -m "feat: add host-side tavern script registry with quickjs execution"
```

---

### Task 4: 内建斜杠命令（/setvar /getvar /add /sub /random /roll /echo）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/HostSlashCommands.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/slash/SlashCommandInterceptor.kt`（前置分发）
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/slash/HostSlashCommandsTest.kt`

**Interfaces:**
- Produces: `HostSlashCommands.execute(command, args, conversationId, variableStore): SlashCommandResult?`（null = 非内建命令）；`SlashCommandInterceptor` 在脚本匹配前先查询

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HostSlashCommandsTest {

    private val conversationId = Uuid.random()
    private val store = StatusVariableStore()

    @Test
    fun `setvar and getvar round trip`() {
        store.set(conversationId, JsonObject(emptyMap()))
        val r1 = HostSlashCommands.execute("setvar", "gold 42", conversationId, store)!!
        assertTrue(r1.text!!.contains("42"))
        val r2 = HostSlashCommands.execute("getvar", "gold", conversationId, store)!!
        assertTrue(r2.text!!.contains("42"))
    }

    @Test
    fun `add and sub mutate numeric chat variables`() {
        store.set(conversationId, JsonObject(emptyMap()))
        HostSlashCommands.execute("setvar", "hp 10", conversationId, store)
        HostSlashCommands.execute("add", "hp 5", conversationId, store)
        assertEquals("15", StatusVariableStoreAccessor(conversationId, store).get("hp"))
        HostSlashCommands.execute("sub", "hp 3", conversationId, store)
        assertEquals("12", StatusVariableStoreAccessor(conversationId, store).get("hp"))
    }

    @Test
    fun `random picks one of the options`() {
        val result = HostSlashCommands.execute("random", "a,b,c", conversationId, store)!!
        assertTrue(listOf("a", "b", "c").any { result.text!!.contains(it) })
    }

    @Test
    fun `roll returns a number within range`() {
        val result = HostSlashCommands.execute("roll", "2d6", conversationId, store)!!
        val total = result.text!!.trim().toIntOrNull()
        assertTrue(total != null && total in 2..12)
    }

    @Test
    fun `echo returns the input`() {
        assertEquals("hello world", HostSlashCommands.execute("echo", "hello world", conversationId, store)!!.text)
    }

    @Test
    fun `unknown command returns null`() {
        assertNull(HostSlashCommands.execute("nonexistent", "x", conversationId, store))
    }

    @Test
    fun `getvar for missing key reports missing`() {
        store.set(conversationId, JsonObject(emptyMap()))
        val result = HostSlashCommands.execute("getvar", "missing", conversationId, store)!!
        assertTrue(result.error != null || result.text!!.contains("not set", ignoreCase = true))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.HostSlashCommandsTest"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package me.rerere.rikkahub.data.ai.slash

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.status.StatusVariableStore
import kotlin.math.abs
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 宿主内建斜杠命令（SillyTavern 风格），变量目标为 chat 作用域 StatusVariableStore。
 * execute 返回 null 表示不是内建命令（调用方继续走磁盘脚本/透传）。
 */
object HostSlashCommands {

    private val SUPPORTED = setOf("setvar", "getvar", "add", "sub", "random", "pick", "roll", "echo", "th")

    fun execute(command: String, args: String, conversationId: Uuid?, variableStore: StatusVariableStore): SlashCommandResult? {
        if (command !in SUPPORTED) return null
        val accessor = StatusVariableStoreAccessor(conversationId, variableStore)
        return when (command) {
            "setvar" -> {
                val (key, value) = splitTwo(args) ?: return SlashCommandResult(error = "Usage: /setvar <key> <value>")
                accessor.set(key, value)
                SlashCommandResult(text = "$key = $value")
            }
            "getvar" -> {
                val key = args.trim()
                if (key.isEmpty()) return SlashCommandResult(error = "Usage: /getvar <key>")
                val value = accessor.get(key)
                if (value == null) SlashCommandResult(error = "$key is not set")
                else SlashCommandResult(text = "$key = $value")
            }
            "add", "sub" -> {
                val (key, raw) = splitTwo(args) ?: return SlashCommandResult(error = "Usage: /$command <key> <number>")
                val delta = raw.toDoubleOrNull() ?: return SlashCommandResult(error = "Not a number: $raw")
                val current = accessor.get(key)?.toDoubleOrNull()
                    ?: return SlashCommandResult(error = "$key is not a number")
                val next = if (command == "add") current + delta else current - delta
                val formatted = if (next % 1.0 == 0.0) next.toLong().toString() else next.toString()
                accessor.set(key, formatted)
                SlashCommandResult(text = "$key = $formatted")
            }
            "random", "pick" -> {
                val options = args.split(',', '|', ':').map { it.trim() }.filter { it.isNotEmpty() }
                if (options.isEmpty()) return SlashCommandResult(error = "Usage: /$command <option1>,<option2>,...")
                SlashCommandResult(text = options[Random.nextInt(options.size)])
            }
            "roll" -> {
                val result = rollDice(args) ?: return SlashCommandResult(error = "Usage: /roll <NdM> (n<=100, M<=1000)")
                SlashCommandResult(text = result.toString())
            }
            "echo" -> SlashCommandResult(text = args)
            "th" -> SlashCommandResult(
                text = "/setvar /getvar /add /sub /random /pick /roll /echo\n" +
                    "  /setvar <key> <value> - set a chat variable\n" +
                    "  /getvar <key> - get a chat variable\n" +
                    "  /add <key> <number> / /sub <key> <number> - numeric variable math\n" +
                    "  /random <a>,<b>,... - pick a random option\n" +
                    "  /roll <NdM> - roll dice\n" +
                    "  /echo <text> - reply with the text"
            )
            else -> null
        }
    }

    private fun splitTwo(args: String): Pair<String, String>? {
        val index = args.indexOfFirst { it.isWhitespace() }
        if (index <= 0) return null
        val key = args.substring(0, index).trim()
        val value = args.substring(index + 1).trim()
        if (key.isEmpty() || value.isEmpty()) return null
        return key to value
    }

    private fun rollDice(args: String): Int? {
        val trimmed = args.trim()
        val match = Regex("^(\\d{1,3})d(\\d{1,4})$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
        val count = match.groupValues[1].toInt()
        val sides = match.groupValues[2].toInt()
        if (count !in 1..100 || sides !in 1..1000) return null
        return (1..count).sumOf { Random.nextInt(sides) + 1 }
    }
}
```

`SlashCommandInterceptor` 修改：transform 流程中（脚本匹配前，约 :40）加：

```kotlin
        // 宿主内建命令优先
        val hostResult = HostSlashCommands.execute(command, args, conversationId, statusVariableStore)
        if (hostResult != null) {
            return buildSlashOutput(messages, hostResult, command)
        }
```

注意：`SlashCommandInterceptor` 需注入 `StatusVariableStore`（构造参数新增，ChatService 组装处同步——`SlashCommandInterceptor(scriptManager)` 现有构造，加参数并 grep 所有构造点）；`buildSlashOutput` 是既有私有方法（:85-106），复用其输出语义。`conversationId` 从哪来——interceptor 的 TransformerContext（`ctx.conversationId`，Transformer.kt 有该字段）。执行时按实际上下文字段名调整。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.ai.slash.*" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/slash/HostSlashCommands.kt app/src/main/java/me/rerere/rikkahub/data/ai/slash/SlashCommandInterceptor.kt app/src/test/java/me/rerere/rikkahub/data/ai/slash/HostSlashCommandsTest.kt
git commit -m "feat: add host built-in slash commands against chat variables"
```

---

### Task 5: RPC 方法扩展 + 权限位

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt`（新方法）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/TavernRuntimePermissions.kt`（新权限位）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`（持久化）与 `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesRuntimePage.kt`（UI 开关）
- Test: `TavernRuntimeControllerTest.kt` 增补

**Interfaces:**
- Produces: RPC 方法 `macros.register/remove/list`、`slash.register/unregister`、`requestHeaders.get`、`sendHook.register`；权限 `allowMacroRegister`/`allowRequestHeaders`（默认 false）

- [ ] **Step 1: 权限模型**

`TavernRuntimePermissions.kt` 加：

```kotlin
    /** 允许脚本注册宿主宏与斜杠命令（默认 false） */
    val allowMacroRegister: Boolean = false,
    /** 允许脚本读取当前模型请求头（含 API key 等敏感信息，默认 false） */
    val allowRequestHeaders: Boolean = false,
```

PreferencesStore 读（:243 附近 settings 组装）加两字段（key `tavern_allow_macro_register`/`tavern_allow_request_headers`，按现有 `WEB_SERVER_ENABLED` 等模式定义 + 读 + 写）。SettingPreferencesRuntimePage 加两个 Switch（按现有六开关模式）。

- [ ] **Step 2: controller 新方法（失败测试先行）**

`TavernRuntimeControllerTest.kt` 增补用例：

```kotlin
    @Test
    fun `macros register requires allowMacroRegister permission`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = false)
            ),
            scriptRegistry = TavernScriptRegistry(),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.register",
                params = buildJsonObject {
                    put("name", "m")
                    put("source", "function macro(args){ return ''; }")
                },
            )
        )
        assertFalse(response.ok)
        assertEquals("PERMISSION_DENIED", response.error?.code)
    }

    @Test
    fun `macros register succeeds with permission`() {
        val registry = TavernScriptRegistry()
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowMacroRegister = true)
            ),
            scriptRegistry = registry,
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(
                id = "1", method = "macros.register",
                params = buildJsonObject {
                    put("name", "m")
                    put("source", "function macro(args){ return 'ok'; }")
                },
            )
        )
        assertTrue(response.ok)
        assertEquals(listOf("m"), registry.listMacros())
    }

    @Test
    fun `requestHeaders get requires allowRequestHeaders`() {
        val controller = TavernRuntimeController(
            conversationId = Uuid.random(),
            permissionStore = TavernRuntimePermissionStore(
                TavernRuntimePermissions().copy(allowScripts = true, allowRequestHeaders = false)
            ),
        )
        val response = controller.dispatch(
            TavernRuntimeRequest(id = "1", method = "requestHeaders.get", params = JsonObject(emptyMap()))
        )
        assertFalse(response.ok)
    }
```

- [ ] **Step 3: 实现 controller 方法**

controller 构造加参数：

```kotlin
    private val scriptRegistry: TavernScriptRegistry = TavernScriptRegistry(),
    private val headerSource: (() -> List<Pair<String, String>>)? = null,
```

dispatch when 加：

```kotlin
                "macros.register" -> registerMacro(request)
                "macros.remove" -> removeMacro(request)
                "macros.list" -> listMacros(request)
                "slash.register" -> registerSlashCommand(request)
                "slash.unregister" -> unregisterSlashCommand(request)
                "requestHeaders.get" -> getRequestHeaders(request)
                "sendHook.register" -> registerSendHook(request)
```

实现（模式同既有方法）：

```kotlin
    private fun registerMacro(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Macro registration is disabled for this script")
        }
        val name = request.params.getString("name") ?: return badRequest(request, "macros.register requires params.name")
        val source = request.params.getString("source") ?: return badRequest(request, "macros.register requires params.source")
        val ok = scriptRegistry.registerMacro(name, source)
        return TavernRuntimeResponse.success(request.id, JsonPrimitive(ok))
    }

    // ... remove/list/slash.register/unregister 同模式 ...

    private fun getRequestHeaders(request: TavernRuntimeRequest): TavernRuntimeResponse {
        if (!permissionStore.current().allowRequestHeaders) {
            return permissionDenied(request, "Request header access is disabled for this script")
        }
        val headers = headerSource?.invoke() ?: emptyList()
        return TavernRuntimeResponse.success(
            request.id,
            JsonArray(headers.map { (name, value) ->
                buildJsonObject { put("name", name); put("value", value) }
            }),
        )
    }

    private fun registerSendHook(request: TavernRuntimeRequest): TavernRuntimeResponse {
        // best-effort 发送前钩子：源码注册后由 ChatService 发送管线经 controller 问询执行
        if (!permissionStore.current().allowMacroRegister) {
            return permissionDenied(request, "Send hook registration is disabled for this script")
        }
        val source = request.params.getString("source") ?: return badRequest(request, "sendHook.register requires params.source")
        return registerSendHookInternal(request, source)
    }
```

sendHook 注册/问询的具体机制：controller 存 `sendHookSource: AtomicReference<String?>`；加方法 `suspend fun mutateOutgoing(text: String, timeoutMs: Long = 500): String`——把源码交 scriptRegistry（注册临时宏）同步展开，best-effort 超时原样（Task 7 消费）。实现按此语义，`registerSendHookInternal` 存入 registry 的一个特殊宏名（如 `__rikkahub_send_hook`）。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.*" --tests "me.rerere.rikkahub.data.ai.slash.*" :app:compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeController.kt app/src/main/java/me/rerere/rikkahub/data/model/TavernRuntimePermissions.kt app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesRuntimePage.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeControllerTest.kt
git commit -m "feat: add macro/command/header rpc methods with new permission bits"
```

---

### Task 6: JS 侧 API（MacroHelper / SlashCommandParser 垫片）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt`
- Test: `TavernRuntimeScriptTest.kt` 增补

**Interfaces:**
- Produces: `window.MacroHelper`（registerMacro/getMacro/getMacros）、`window.SlashCommandParser`（add）、`SillyTavern` 上挂 getRequestHeaders（Promise）

- [ ] **Step 1: 写失败测试（追加断言用例）**

```kotlin
    @Test
    fun scriptExposesMacroHelperAndSlashCommandParserShims() {
        val script = buildTavernRuntimeScript()
        assertTrue(script.contains("window.MacroHelper"))
        assertTrue(script.contains("registerMacro"))
        assertTrue(script.contains("window.SlashCommandParser"))
        assertTrue(script.contains("'add'"))
        assertTrue(script.contains("requestHeaders.get"))
        assertTrue(script.contains("sendHook.register"))
    }
```

- [ ] **Step 2: 运行确认失败 → Step 3: 实现**

`TavernRuntimeScript.kt` IIFE 内（event_types/SillyTavern 块之后）插入：

```js
  // ── MacroHelper：ST 兼容宏注册（fn 序列化为源码经 RPC 注册到宿主表） ──
  var macroStore = {};
  window.MacroHelper = {
    registerMacro: function(name, fn){
      if (typeof fn !== 'function') return Promise.resolve(false);
      macroStore[name] = fn;
      return call('macros.register', { name: name, source: String(fn) }).then(function(ok){
        if (!ok) { delete macroStore[name]; }
        return ok;
      });
    },
    getMacro: function(name){
      if (typeof macroStore[name] === 'function') { return macroStore[name]; }
      return function(args){ return '{{' + name + '::' + (args === undefined ? '' : args) + '}}'; };
    },
    getMacros: function(){ return Object.keys(macroStore); }
  };

  // ── SlashCommandParser：ST 兼容命令注册垫片 ──
  window.SlashCommandParser = {
    add: function(definition){
      var name = definition && definition.name;
      var callback = definition && definition.callback;
      if (typeof name !== 'string' || typeof callback !== 'function') { return Promise.resolve(false); }
      return call('slash.register', {
        name: name,
        source: String(callback),
        aliases: definition.aliases || [],
        helpString: definition.helpString || ''
      });
    }
  };

  window.SillyTavern.getRequestHeaders = function(){
    return call('requestHeaders.get', {});
  };
```

（若既有 SillyTavern 对象挂载方式是 `window.SillyTavern = window.SillyTavern || {...}`——getRequestHeaders 挂到该对象；按现有代码形态调整。）

- [ ] **Step 4: 验证 + Commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.runtime.*" :app:compileDebugKotlin`
Expected: PASS

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScript.kt app/src/test/java/me/rerere/rikkahub/ui/components/richtext/runtime/TavernRuntimeScriptTest.kt
git commit -m "feat: expose MacroHelper and SlashCommandParser shims to tavern scripts"
```

---

### Task 7: 发送管线接线（宏展开 + sendHook + headers 注入）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`（preprocessUserInputParts 扩展、sendHook 调用、headerSource 注入点）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt`（controller 构造传 scriptRegistry/headerSource——若 controller 需要共享 registry 单例则 koinInject）
- Test: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt` 或新测试（宏展开入库）

**Interfaces:**
- Consumes: `TavernScriptRegistry.expandMacros`（Task 3）、controller `mutateOutgoing`/`headerSource`（Task 5）

- [ ] **Step 1: preprocessUserInputParts 扩展**

`ChatService.kt`（:731-747 附近）修改：

```kotlin
    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<UIMessagePart> {
        return parts.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            val regexApplied = part.text.replaceRegexes(
                assistant = assistant,
                scope = AssistantAffectScope.USER,
                visual = false,
            )
            // 酒馆脚本注册宏：发送前同步展开（mutate 通道；失败保留原文）
            val macroExpanded = tavernScriptRegistry.expandMacros(
                regexApplied,
                MacroExpandContext(
                    userName = settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "User" },
                    charName = assistant.name,
                    conversationId = conversationId.toString(),
                ),
            )
            part.copy(text = macroExpanded)
        }
    }
```

（签名变化 → 3 个调用点同步传 conversationId：sendMessage/appendUserMessage/editMessage——grep `preprocessUserInputParts(` 定位全部。）

- [ ] **Step 2: sendHook 接线（best-effort）**

`sendMessage` 中 `preprocessUserInputParts` 之后（:613-617 区域）加：

```kotlin
                // 酒馆脚本 sendHook（best-effort：无活跃 WebView 时跳过，超时默认原样）
                val hookedParts = tavernRuntimeHook?.mutateOutgoing(processedContent, timeoutMs = 500)
                    ?: processedContent
```

`tavernRuntimeHook`：新轻量单例（Koin）——`TavernSendHookStore`（存活跃 controller 弱引用 + mutateOutgoing 委托）。实现：`object/class TavernSendHookStore { @Volatile var activeController: TavernRuntimeController?; suspend fun mutateOutgoing(parts, timeoutMs): List<UIMessagePart> }`；MarkdownWebView 在 controller 创建后 `sendHookStore.activeController = runtimeController`（onDispose 清空若同一实例）。

controller 的 `mutateOutgoing`：把 sendHook 源码注册为临时宏（`__rikkahub_send_hook`），对文本调用 registry.expandMacros 的变体（单宏调用而非扫描全文——或直接全文 `{{__rikkahub_send_hook::text}}` 包装展开），超时兜底。

- [ ] **Step 3: headerSource 注入**

`MarkdownWebView` 的 controller 构造处加：

```kotlin
            headerSource = {
                val settings = settingsStore.settingsFlow.value
                val conversationId = tavernConversationId
                val conversation = conversationId?.let { id -> runCatching { chatService?.getConversationFlow(id)?.value }.getOrNull() }
                val assistant = conversation?.let { conv -> settings.assistants.firstOrNull { a -> a.id == conv.assistantId } }
                val model = assistant?.let { a -> settings.providers.flatMap { p -> p.models }.firstOrNull { m -> m.id == a.chatModelId } }
                (assistant?.customHeaders.orEmpty() + model?.customHeaders.orEmpty()).map { it.name to it.value }
            },
```

注意：MarkdownWebView 无 ChatService 引用——注入 assistant/model 数据的更简单路径：**扩展 context 快照**（Task 2 快照加可选 `requestHeaders` 字段由 ChatList 组装——但 spec §7 说「不进快照，按需 RPC 拉取」）。折中：controller 的 headerSource 由 ChatMessage/ChatList 传入（新参数 `tavernHeaderSource: (() -> List<Pair<String, String>>)? = null` 透传链，与 tavernContextSnapshot 同路）。执行时选此方案（透传链已就绪）。若透传成本高，备选：SettingsStore + 只查 assistant（不查 model）——实现者按实际选择并记录。

- [ ] **Step 4: 测试**

`ChatServiceTest.kt` 增补（若存在发送管线测试模式）或新测试类：注册宏后 sendMessage 持久化的用户消息文本为展开后文本（mock 生成管线）。若无现成 ChatService 发送测试基建，降级为 `preprocessUserInputParts` 级测试（提取该函数可见性或经 registry 直测），并在报告中说明。

- [ ] **Step 5: 验证 + Commit**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全绿

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWebView.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
git commit -m "feat: wire macro expansion and send hook into send pipeline with header source"
```

（按实际改动文件清单 add。）

---

### Task 8: 流式快照键降级 + 全量验证 + 冒烟 + 文档

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`（remember 键降级）
- Modify: `AGENTS.md`

- [ ] **Step 1: 快照键降级**

ChatList 快照 remember 键从 `conversation.messageNodes` 改为：

```kotlin
    val tavernContextSnapshot = remember(
        conversation.id,
        conversation.messageNodes.size,
        conversation.messageNodes.lastOrNull()?.selectIndex,
        assistant,
        settings.displaySetting.userNickname,
        loading,
    ) { ... 原构建体不变 ... }
```

（流式期间 messageNodes.size 与 selectIndex 不变 → 不重建；完成时 size 变触发重建。构建体内部仍读 conversation.messageNodes 最新状态。）

- [ ] **Step 2: 全量验证**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug`
Expected: 全绿（606 + 新增，0 失败）

- [ ] **Step 3: 模拟器冒烟**

1. 安装 APK、启动
2. 打开酒馆对话（Yes, My Liege），**主路径复验**：普通 markdown 消息 WebView 的 getContext 非空（B2a 合成路径修复后复验——用 logcat/TavernRuntimeSmokeActivity 临时脚本，不提交临时代码）
3. /setvar 内建命令：发送 `/setvar hp 42` → 状态面板 hp 更新
4. registerMacro：临时脚本注册宏 → 发送含 `{{macro::x}}` 消息 → 持久化文本为展开后（DB 校验）
5. MESSAGE_SENDING preview 为展开后文本（脚本订阅验证）
6. getRequestHeaders：权限开时返回 headers、关时 PERMISSION_DENIED
7. 结论写入 AGENTS.md（含未覆盖项）

- [ ] **Step 4: AGENTS.md**

在 Current Status 最新块之上加：

```markdown
**2026-08-14：酒馆脚本 API 兼容（子项目 B2b：宏与命令）。**

- 主路径 tavern 参数链修复（B2a 首修）：MarkdownBlock/MultiCharacterStatusView 透传 4 参数——
  普通 markdown/STABLE_DOM 消息 WebView 获得完整脚本上下文（getContext/事件/getCurrent/variables）
- TavernScriptRegistry（Koin 单例 + 独立 QuickJS executor）：registerMacro/registerSlashCommand 宿主持久注册
  （重载不丢、64KB/64 上限、2s 超时）；发送管线 preprocessUserInputParts 同步展开宏（展开入库 = mutate 通道）
- 内建斜杠命令：/setvar /getvar /add /sub /random /pick /roll /echo（chat 变量 StatusVariableStore，
  StatusVariableStoreAccessor）；优先级：内建 → 磁盘脚本 → WebView 注册
- RPC 扩展：macros.register/remove/list、slash.register/unregister、requestHeaders.get、sendHook.register；
  新权限位 allowMacroRegister/allowRequestHeaders（默认 false，UI 开关）
- JS 垫片：window.MacroHelper（registerMacro/getMacro/getMacros）、window.SlashCommandParser.add、
  SillyTavern.getRequestHeaders
- 流式快照键降级（messageNodes.size + selectIndex + isGenerating）：消除每 token 全量重建
- 验证：`:app:testDebugUnitTest`/`:app:compileDebugKotlin`/`:app:assembleDebug` 全绿；模拟器冒烟结果见下
- 偏差文档化：MESSAGE_SENDING 严格同步阻塞语义未实现（宏同步 + sendHook best-effort 500ms）
- 计划/设计：`docs/superpowers/specs/2026-08-14-tavern-script-api-compat-b2b-design.md`、
  `docs/superpowers/plans/2026-08-14-tavern-script-api-compat-b2b.md`
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt AGENTS.md
git commit -m "feat: downscale streaming snapshot rebuild key and record B2b status"
```

---

## Self-Review

1. **Spec coverage**：§2→Task 1；§3→Task 3；§4→Task 2/4；§5→Task 5/6；§6→Task 7；§7→Task 5/7；§8→Task 8；§9→各任务测试 + Task 8；§10→风险对策在各任务注记。无缺口。
2. **Placeholder scan**：无 TBD；QuickJS API 形态注记为「按 SlashScriptEngine 实际用法调整」——执行时以仓库既有代码为准。
3. **Type consistency**：`MacroExpandContext`（userName/charName/conversationId）在 Task 3/7 一致；`SlashCommandResult`（text/html/error）在 Task 3/4 一致；权限位名 allowMacroRegister/allowRequestHeaders 在 Task 5 三处一致；RPC 方法名（macros.register/slash.register/requestHeaders.get/sendHook.register）在 Task 5/6 一致。
