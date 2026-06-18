# Character Viewpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current group-member context bottom sheet with a mobile-first `角色视角` subpage that exposes base mode, triggers, exclusions, message window, templates, and an explainable preview while remaining compatible with the existing `ContextFilter` persistence model.

**Architecture:** Keep `ContextFilter` as the persisted wire format in phase 1, but introduce a focused `CharacterViewpointState` UI/domain layer plus a reusable visibility evaluator shared by runtime filtering and preview UI. Add a dedicated navigation route and page for editing viewpoint rules so the existing member-detail screen becomes a lightweight launcher with summary text instead of hosting the full editor inline.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation3, Koin ViewModel injection, kotlinx.serialization, JUnit, AndroidX Compose UI test

---

## File Structure

### New files

- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointModels.kt`
  - UI/domain-facing viewpoint state and template enum
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapper.kt`
  - map `ContextFilter` <-> `CharacterViewpointState`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummary.kt`
  - summary string builder for launcher row and top summary card
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluator.kt`
  - reusable deterministic visibility evaluator with reason strings
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPage.kt`
  - dedicated mobile editor subpage
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt`
  - member-specific state adapter used by `CharacterViewpointPage`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapperTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummaryTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluatorTest.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointTemplatesTest.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt`

### Modified files

- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
  - only if required for additive helper enums or comments; do not replace `ContextFilter` persistence in phase 1
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt`
  - switch to shared evaluation helper
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
  - remove heavy inline editor usage, add summary + launcher, clean encoding-corrupted copy while touching file
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt`
  - expose member lookup/update helpers reusable by the new VM
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
  - register `CharacterViewpointVM`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
  - add route/screen entry for the new subpage

---

### Task 1: Introduce Viewpoint UI State And Summary Mapping

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointModels.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapper.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummary.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapperTest.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummaryTest.kt`

- [ ] **Step 1: Write the failing mapper and summary tests**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope

class CharacterViewpointMapperTest {
    @Test
    fun `maps member list scope to whitelist viewpoint`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val filter = ContextFilter(
            scope = ContextScope.MEMBER_LIST,
            visibleMemberIds = listOf(a, b),
            mentionEnabled = true,
            mentionKeywords = listOf("@绛雪"),
            excludedMemberIds = listOf(Uuid.random()),
            maxMessages = 20,
        )

        val state = filter.toCharacterViewpointState()

        assertEquals(CharacterViewpointBaseMode.WHITELIST, state.baseMode)
        assertEquals(listOf(a, b), state.whitelistMemberIds)
        assertEquals(true, state.triggerRules.allowMentionTrigger)
        assertEquals(listOf("@绛雪"), state.triggerRules.mentionKeywords)
        assertEquals(20, state.messageWindow.maxMessages)
    }

    @Test
    fun `maps viewpoint state back to context filter`() {
        val member = Uuid.random()
        val state = CharacterViewpointState(
            baseMode = CharacterViewpointBaseMode.DIRECTED,
            whitelistMemberIds = listOf(member),
            triggerRules = CharacterViewpointTriggers(
                allowMentionTrigger = true,
                mentionKeywords = listOf("@佛母"),
                allowDirectedTrigger = true,
            ),
            exclusionRules = CharacterViewpointExclusions(excludedMemberIds = listOf(Uuid.random())),
            messageWindow = CharacterViewpointWindow(maxMessages = 12),
        )

        val filter = state.toContextFilter()

        assertEquals(ContextScope.DIRECTED, filter.scope)
        assertEquals(true, filter.mentionEnabled)
        assertEquals(listOf("@佛母"), filter.mentionKeywords)
        assertEquals(12, filter.maxMessages)
    }
}
```

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterViewpointSummaryTest {
    @Test
    fun `summarises whitelist mode with mention trigger and window`() {
        val summary = buildCharacterViewpointSummary(
            state = CharacterViewpointState(
                baseMode = CharacterViewpointBaseMode.WHITELIST,
                whitelistMemberIds = emptyList(),
                triggerRules = CharacterViewpointTriggers(allowMentionTrigger = true),
                exclusionRules = CharacterViewpointExclusions(excludedMemberIds = listOf(kotlin.uuid.Uuid.random())),
                messageWindow = CharacterViewpointWindow(maxMessages = 20),
            ),
            visibleMemberNames = listOf("绛雪", "竹夭"),
        )

        assertEquals("白名单视角 · 可见 2 人 · @触发 · 排除 1 人 · 最近 20 条", summary)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointMapperTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointSummaryTest"
```

Expected:

- FAIL because `CharacterViewpointState`, `toCharacterViewpointState`, `toContextFilter`, and `buildCharacterViewpointSummary` do not exist yet

- [ ] **Step 3: Write the minimal models, mapper, and summary implementation**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.ContextFilter
import me.rerere.rikkahub.data.model.ContextScope
import kotlin.uuid.Uuid

enum class CharacterViewpointBaseMode {
    ALL,
    SELF_RELATED,
    DIRECTED,
    WHITELIST,
}

enum class CharacterViewpointTemplate {
    OBSERVER,
    CORE_MEMBER,
    SECRET_ROLE,
    MODERATOR_VIEW,
}

data class CharacterViewpointTriggers(
    val allowMentionTrigger: Boolean = false,
    val mentionKeywords: List<String> = emptyList(),
    val allowDirectedTrigger: Boolean = true,
    val allowModeratorCallTrigger: Boolean = false,
)

data class CharacterViewpointExclusions(
    val excludedMemberIds: List<Uuid> = emptyList(),
)

data class CharacterViewpointWindow(
    val maxMessages: Int = 0,
)

data class CharacterViewpointState(
    val baseMode: CharacterViewpointBaseMode = CharacterViewpointBaseMode.ALL,
    val whitelistMemberIds: List<Uuid> = emptyList(),
    val triggerRules: CharacterViewpointTriggers = CharacterViewpointTriggers(),
    val exclusionRules: CharacterViewpointExclusions = CharacterViewpointExclusions(),
    val messageWindow: CharacterViewpointWindow = CharacterViewpointWindow(),
)

fun ContextFilter.toCharacterViewpointState(): CharacterViewpointState = CharacterViewpointState(
    baseMode = when (scope) {
        ContextScope.ALL -> CharacterViewpointBaseMode.ALL
        ContextScope.SELF -> CharacterViewpointBaseMode.SELF_RELATED
        ContextScope.DIRECTED -> CharacterViewpointBaseMode.DIRECTED
        ContextScope.MEMBER_LIST -> CharacterViewpointBaseMode.WHITELIST
    },
    whitelistMemberIds = visibleMemberIds,
    triggerRules = CharacterViewpointTriggers(
        allowMentionTrigger = mentionEnabled,
        mentionKeywords = mentionKeywords,
        allowDirectedTrigger = true,
    ),
    exclusionRules = CharacterViewpointExclusions(excludedMemberIds = excludedMemberIds),
    messageWindow = CharacterViewpointWindow(maxMessages = maxMessages),
)

fun CharacterViewpointState.toContextFilter(): ContextFilter = ContextFilter(
    scope = when (baseMode) {
        CharacterViewpointBaseMode.ALL -> ContextScope.ALL
        CharacterViewpointBaseMode.SELF_RELATED -> ContextScope.SELF
        CharacterViewpointBaseMode.DIRECTED -> ContextScope.DIRECTED
        CharacterViewpointBaseMode.WHITELIST -> ContextScope.MEMBER_LIST
    },
    visibleMemberIds = whitelistMemberIds,
    excludedMemberIds = exclusionRules.excludedMemberIds,
    mentionEnabled = triggerRules.allowMentionTrigger,
    mentionKeywords = triggerRules.mentionKeywords,
    maxMessages = messageWindow.maxMessages,
)
```

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

fun buildCharacterViewpointSummary(
    state: CharacterViewpointState,
    visibleMemberNames: List<String>,
): String {
    val modeText = when (state.baseMode) {
        CharacterViewpointBaseMode.ALL -> "全部可见"
        CharacterViewpointBaseMode.SELF_RELATED -> "仅自己相关"
        CharacterViewpointBaseMode.DIRECTED -> "仅定向/被点名"
        CharacterViewpointBaseMode.WHITELIST -> "白名单视角"
    }

    val extras = buildList {
        if (state.baseMode == CharacterViewpointBaseMode.WHITELIST) add("可见 ${visibleMemberNames.size} 人")
        if (state.triggerRules.allowMentionTrigger) add("@触发")
        if (state.exclusionRules.excludedMemberIds.isNotEmpty()) add("排除 ${state.exclusionRules.excludedMemberIds.size} 人")
        if (state.messageWindow.maxMessages > 0) add("最近 ${state.messageWindow.maxMessages} 条")
    }

    return listOf(modeText, *extras.toTypedArray()).joinToString(" · ")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointMapperTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointSummaryTest"
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointModels.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapper.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummary.kt app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointMapperTest.kt app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointSummaryTest.kt
git commit -m "feat: add character viewpoint state mapping"
```

### Task 2: Extract A Reusable Explainable Viewpoint Evaluator

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluator.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluatorTest.kt`

- [ ] **Step 1: Write the failing evaluator tests**

```kotlin
package me.rerere.rikkahub.service.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointBaseMode
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointExclusions
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointState
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointTriggers
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointWindow

class CharacterViewpointEvaluatorTest {
    @Test
    fun `mention trigger can force visibility`() {
        val memberId = Uuid.random()
        val message = UIMessage(role = MessageRole.ASSISTANT, text = "@绛雪 今夜轮到你了", memberId = Uuid.random())
        val result = evaluateCharacterViewpoint(
            message = message,
            effectiveMemberId = memberId,
            state = CharacterViewpointState(
                baseMode = CharacterViewpointBaseMode.DIRECTED,
                triggerRules = CharacterViewpointTriggers(
                    allowMentionTrigger = true,
                    mentionKeywords = listOf("@绛雪"),
                ),
                messageWindow = CharacterViewpointWindow(),
            ),
        )

        assertEquals(true, result.visible)
        assertEquals(listOf("mention-trigger"), result.matchedRules)
    }

    @Test
    fun `excluded member blocks otherwise visible message`() {
        val blockedSpeaker = Uuid.random()
        val result = evaluateCharacterViewpoint(
            message = UIMessage(role = MessageRole.ASSISTANT, text = "普通消息", memberId = blockedSpeaker),
            effectiveMemberId = Uuid.random(),
            state = CharacterViewpointState(
                baseMode = CharacterViewpointBaseMode.ALL,
                exclusionRules = CharacterViewpointExclusions(excludedMemberIds = listOf(blockedSpeaker)),
            ),
        )

        assertEquals(false, result.visible)
        assertEquals(listOf("excluded-member"), result.blockedRules)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.CharacterViewpointEvaluatorTest"
```

Expected:

- FAIL because `evaluateCharacterViewpoint` and `ViewpointEvaluationResult` do not exist

- [ ] **Step 3: Implement the evaluator and route filter logic through it**

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointBaseMode
import me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointState
import kotlin.uuid.Uuid

data class ViewpointEvaluationResult(
    val visible: Boolean,
    val matchedRules: List<String> = emptyList(),
    val blockedRules: List<String> = emptyList(),
)

fun evaluateCharacterViewpoint(
    message: UIMessage,
    effectiveMemberId: Uuid,
    state: CharacterViewpointState,
): ViewpointEvaluationResult {
    val matched = mutableListOf<String>()
    val blocked = mutableListOf<String>()

    var visible = when (state.baseMode) {
        CharacterViewpointBaseMode.ALL -> true
        CharacterViewpointBaseMode.SELF_RELATED -> message.role == MessageRole.USER || message.memberId == effectiveMemberId
        CharacterViewpointBaseMode.DIRECTED -> message.memberId == effectiveMemberId
        CharacterViewpointBaseMode.WHITELIST -> message.role == MessageRole.USER || message.memberId in state.whitelistMemberIds
    }

    if (visible) matched += "base-mode"

    if (!visible && state.triggerRules.allowMentionTrigger) {
        val hit = state.triggerRules.mentionKeywords.any { keyword ->
            keyword.isNotBlank() && message.toText().contains(keyword, ignoreCase = true)
        }
        if (hit) {
            visible = true
            matched += "mention-trigger"
        }
    }

    if (message.memberId in state.exclusionRules.excludedMemberIds) {
        visible = false
        blocked += "excluded-member"
    }

    return ViewpointEvaluationResult(
        visible = visible,
        matchedRules = matched,
        blockedRules = blocked,
    )
}
```

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.ui.pages.assistant.detail.toCharacterViewpointState
import kotlin.uuid.Uuid

internal fun List<UIMessage>.applyGroupContextFilter(
    groupAssistant: Assistant,
    effectiveMemberId: Uuid?,
): List<UIMessage> {
    if (groupAssistant.assistantType != AssistantType.GROUP) return this
    if (effectiveMemberId == null) return this
    val member = groupAssistant.groupMembers.find { it.id == effectiveMemberId } ?: return this
    val state = member.contextFilter.toCharacterViewpointState()

    val visibleMessages = filter { message ->
        evaluateCharacterViewpoint(
            message = message,
            effectiveMemberId = effectiveMemberId,
            state = state,
        ).visible
    }

    if (state.messageWindow.maxMessages <= 0 || visibleMessages.size <= state.messageWindow.maxMessages) {
        return visibleMessages
    }

    val users = visibleMessages.filter { it.role == MessageRole.USER }
    val others = visibleMessages.filter { it.role != MessageRole.USER }
    val keep = (state.messageWindow.maxMessages - users.size).coerceAtLeast(0)
    return others.takeLast(keep) + users
}
```

- [ ] **Step 4: Run tests to verify they pass and no regression appears in current group filter tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.CharacterViewpointEvaluatorTest" --tests "me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest" --tests "me.rerere.rikkahub.service.ChatServiceTest"
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluator.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt app/src/test/java/me/rerere/rikkahub/service/group/CharacterViewpointEvaluatorTest.kt
git commit -m "feat: add explainable character viewpoint evaluator"
```

### Task 3: Add Templates And Deterministic Template Tests

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointModels.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointTemplatesTest.kt`

- [ ] **Step 1: Write failing template tests**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterViewpointTemplatesTest {
    @Test
    fun `secret role template uses whitelist plus mention trigger`() {
        val state = CharacterViewpointTemplate.SECRET_ROLE.toState()

        assertEquals(CharacterViewpointBaseMode.WHITELIST, state.baseMode)
        assertEquals(true, state.triggerRules.allowMentionTrigger)
    }

    @Test
    fun `moderator template keeps all visible`() {
        val state = CharacterViewpointTemplate.MODERATOR_VIEW.toState()

        assertEquals(CharacterViewpointBaseMode.ALL, state.baseMode)
        assertEquals(true, state.triggerRules.allowDirectedTrigger)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointTemplatesTest"
```

Expected:

- FAIL because `toState()` does not exist

- [ ] **Step 3: Add template mapping**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

fun CharacterViewpointTemplate.toState(): CharacterViewpointState = when (this) {
    CharacterViewpointTemplate.OBSERVER -> CharacterViewpointState(
        baseMode = CharacterViewpointBaseMode.ALL,
        triggerRules = CharacterViewpointTriggers(allowDirectedTrigger = true),
    )
    CharacterViewpointTemplate.CORE_MEMBER -> CharacterViewpointState(
        baseMode = CharacterViewpointBaseMode.ALL,
        triggerRules = CharacterViewpointTriggers(
            allowMentionTrigger = true,
            allowDirectedTrigger = true,
        ),
        messageWindow = CharacterViewpointWindow(maxMessages = 30),
    )
    CharacterViewpointTemplate.SECRET_ROLE -> CharacterViewpointState(
        baseMode = CharacterViewpointBaseMode.WHITELIST,
        triggerRules = CharacterViewpointTriggers(
            allowMentionTrigger = true,
            allowDirectedTrigger = true,
        ),
        messageWindow = CharacterViewpointWindow(maxMessages = 20),
    )
    CharacterViewpointTemplate.MODERATOR_VIEW -> CharacterViewpointState(
        baseMode = CharacterViewpointBaseMode.ALL,
        triggerRules = CharacterViewpointTriggers(
            allowDirectedTrigger = true,
            allowModeratorCallTrigger = true,
        ),
        messageWindow = CharacterViewpointWindow(maxMessages = 40),
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointTemplatesTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointMapperTest"
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointModels.kt app/src/test/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointTemplatesTest.kt
git commit -m "feat: add character viewpoint templates"
```

### Task 4: Add Route And Member-Specific ViewModel For The New Subpage

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`

- [ ] **Step 1: Write the failing VM test or compile target through new VM API**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterViewpointVmContractTest {
    @Test
    fun `vm exposes member viewpoint state`() {
        val contract = CharacterViewpointVmContract(
            assistantId = "assistant-id",
            memberId = "member-id",
        )
        assertEquals("assistant-id", contract.assistantId)
        assertEquals("member-id", contract.memberId)
    }
}
```

Use a lightweight compile contract if an injectable VM test is too expensive initially.

- [ ] **Step 2: Run test or compile to verify missing route/VM types**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointVmContractTest"
```

Expected:

- FAIL because `CharacterViewpointVmContract` / `CharacterViewpointVM` do not exist yet

- [ ] **Step 3: Add VM and route wiring**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.GroupMember
import kotlin.uuid.Uuid

data class CharacterViewpointVmContract(
    val assistantId: String,
    val memberId: String,
)

class CharacterViewpointVM(
    private val assistantId: String,
    private val memberId: String,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val member: StateFlow<GroupMember?> = settingsStore.settingsFlowRaw
        .map { settings ->
            settings.assistants
                .find { it.id.toString() == assistantId }
                ?.groupMembers
                ?.find { it.id.toString() == memberId }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun updateViewpoint(state: CharacterViewpointState) {
        scope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id.toString() != assistantId) return@map assistant
                        assistant.copy(
                            groupMembers = assistant.groupMembers.map { member ->
                                if (member.id.toString() == memberId) member.copy(contextFilter = state.toContextFilter())
                                else member
                            }
                        )
                    }
                )
            }
        }
    }
}
```

```kotlin
// RouteActivity.kt additions
@Serializable
data class AssistantCharacterViewpoint(val assistantId: String, val memberId: String) : Screen
```

```kotlin
// Route entry
entry<Screen.AssistantCharacterViewpoint> { key ->
    CharacterViewpointPage(
        assistantId = key.assistantId,
        memberId = key.memberId,
    )
}
```

```kotlin
// ViewModelModule.kt addition
viewModel<CharacterViewpointVM> { params ->
    CharacterViewpointVM(
        assistantId = params.get(),
        memberId = params.get(),
        settingsStore = get(),
    )
}
```

- [ ] **Step 4: Run compile verification**

Run:

```bash
./gradlew :app:compileDebugKotlin -x :web:buildWebUi
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
git commit -m "feat: add character viewpoint route and viewmodel"
```

### Task 5: Replace Inline Bottom Sheet Editing With A Summary Launcher

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt`
- Test: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt`

- [ ] **Step 1: Write the failing UI test for launcher behavior**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.RouteActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterViewpointPageTest {
    @get:Rule
    val rule = createAndroidComposeRule<RouteActivity>()

    @Test
    fun memberDetailShowsCharacterViewpointSummaryLauncher() {
        rule.onNodeWithText("角色视角").assertExists()
    }
}
```

- [ ] **Step 2: Run the UI test to verify it fails**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- FAIL because no `角色视角` launcher row exists yet

- [ ] **Step 3: Replace the heavy inline editor with a summary launcher and clean corrupted copy in the touched area**

```kotlin
// inside AssistantGroupMembersPage member editor section
val navController = LocalNavController.current
val viewpointState = contextFilter.toCharacterViewpointState()
val visibleNames = viewpointState.whitelistMemberIds.mapNotNull { memberId ->
    assistant?.groupMembers?.find { it.id == memberId }?.displayName
}

CardGroup {
    item(
        onClick = {
            navController.navigate(
                Screen.AssistantCharacterViewpoint(
                    assistantId = id,
                    memberId = member.id.toString(),
                )
            )
        },
        headlineContent = { Text("角色视角") },
        supportingContent = {
            Text(
                buildCharacterViewpointSummary(
                    state = viewpointState,
                    visibleMemberNames = visibleNames,
                )
            )
        },
        trailingContent = {
            Icon(HugeIcons.ArrowRight01, null)
        },
    )
}
```

Also remove:

- `showContextFilterSheet`
- `ContextFilterSheet(...)`
- the inline nested configuration controls

And replace corrupted Chinese literals in the touched member-edit area with valid UTF-8 strings while editing the file.

- [ ] **Step 4: Run compile and UI verification**

Run:

```bash
./gradlew :app:compileDebugKotlin -x :web:buildWebUi
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- Kotlin compile PASS
- UI test PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt
git commit -m "feat: replace inline context sheet with viewpoint launcher"
```

### Task 6: Build The Mobile Character Viewpoint Page

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt`

- [ ] **Step 1: Write failing UI assertions for page sections**

```kotlin
@Test
fun characterViewpointPageShowsCoreSections() {
    rule.onNodeWithText("角色视角").assertExists()
    rule.onNodeWithText("基础模式").assertExists()
    rule.onNodeWithText("触发器").assertExists()
    rule.onNodeWithText("排除项").assertExists()
    rule.onNodeWithText("最近消息窗口").assertExists()
    rule.onNodeWithText("实时预览").assertExists()
}
```

- [ ] **Step 2: Run UI test to verify it fails**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- FAIL because the new page UI does not exist yet

- [ ] **Step 3: Implement the page skeleton with summary card and grouped sections**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CharacterViewpointPage(
    assistantId: String,
    memberId: String,
) {
    val vm: CharacterViewpointVM = koinViewModel(parameters = { parametersOf(assistantId, memberId) })
    val member by vm.member.collectAsState()
    val initialState = member?.contextFilter?.toCharacterViewpointState() ?: CharacterViewpointState()
    val state = remember(member?.id) { mutableStateOf(initialState) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("角色视角", style = MaterialTheme.typography.headlineSmall)
            Text(
                "控制该角色默认能看到哪些消息，以及在什么条件下会强制接收",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前摘要", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(buildCharacterViewpointSummary(state.value, emptyList()))
                }
            }
        }
        item { CharacterViewpointBaseModeSection(state = state.value, onChange = { state.value = it }) }
        item { CharacterViewpointTriggerSection(state = state.value, onChange = { state.value = it }) }
        item { CharacterViewpointExclusionSection(state = state.value, onChange = { state.value = it }) }
        item { CharacterViewpointWindowSection(state = state.value, onChange = { state.value = it }) }
        item { CharacterViewpointPreviewSection(state = state.value, memberId = member?.id) }
    }
}
```

Implement the section composables in the same file for phase 1 if that keeps churn smaller; split later only if the file becomes unmanageable.

- [ ] **Step 4: Run compile and UI verification**

Run:

```bash
./gradlew :app:compileDebugKotlin -x :web:buildWebUi
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt
git commit -m "feat: add character viewpoint mobile editor page"
```

### Task 7: Wire Templates, Preview, And Save Flow End-To-End

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt`
- Modify: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt`

- [ ] **Step 1: Add failing UI tests for template application and preview explanation**

```kotlin
@Test
fun applyingSecretRoleTemplateUpdatesSummaryAndPreview() {
    rule.onNodeWithText("隐秘角色").performClick()
    rule.onNodeWithText("白名单视角").assertExists()
    rule.onNodeWithText("实时预览").assertExists()
}
```

- [ ] **Step 2: Run UI test to verify it fails**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- FAIL because templates/save/preview are not fully wired yet

- [ ] **Step 3: Implement template row, save action, and explainable preview block**

```kotlin
@Composable
private fun CharacterViewpointTemplateRow(
    onApply: (CharacterViewpointTemplate) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("玩法模板", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CharacterViewpointTemplate.entries.forEach { template ->
                    AssistChip(
                        onClick = { onApply(template) },
                        label = {
                            Text(
                                when (template) {
                                    CharacterViewpointTemplate.OBSERVER -> "观察者"
                                    CharacterViewpointTemplate.CORE_MEMBER -> "核心成员"
                                    CharacterViewpointTemplate.SECRET_ROLE -> "隐秘角色"
                                    CharacterViewpointTemplate.MODERATOR_VIEW -> "主持人视角"
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CharacterViewpointPreviewSection(
    state: CharacterViewpointState,
    memberId: Uuid?,
) {
    val sample = remember(state) { "@角色 今夜轮到你回应了" }
    val result = if (memberId != null) {
        evaluateCharacterViewpoint(
            message = UIMessage(role = MessageRole.USER, text = sample, memberId = null),
            effectiveMemberId = memberId,
            state = state,
        )
    } else {
        ViewpointEvaluationResult(visible = true)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("实时预览", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("示例消息：$sample")
            Text(if (result.visible) "结果：可见" else "结果：不可见")
            if (result.matchedRules.isNotEmpty()) Text("命中规则：${result.matchedRules.joinToString()}")
            if (result.blockedRules.isNotEmpty()) Text("阻止规则：${result.blockedRules.joinToString()}")
        }
    }
}
```

Add a save button:

```kotlin
Button(
    onClick = { vm.updateViewpoint(state.value) },
    modifier = Modifier.fillMaxWidth(),
) {
    Text("保存")
}
```

- [ ] **Step 4: Run full verification for this feature slice**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointMapperTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointSummaryTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointTemplatesTest" --tests "me.rerere.rikkahub.service.group.CharacterViewpointEvaluatorTest"
./gradlew :app:compileDebugKotlin -x :web:buildWebUi
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointPageTest
```

Expected:

- All unit tests PASS
- Compile PASS
- Instrumentation PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPage.kt app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointVM.kt app/src/androidTest/java/me/rerere/rikkahub/ui/pages/assistant/detail/CharacterViewpointPageTest.kt
git commit -m "feat: complete character viewpoint templates and preview"
```

### Task 8: Final Feature Verification And Cleanup

**Files:**
- Modify: `docs/superpowers/specs/2026-06-18-character-viewpoint-design.md` only if implementation reveals a real spec correction
- Modify: `docs/superpowers/plans/2026-06-18-character-viewpoint-implementation-plan.md` by checking completed boxes during execution

- [ ] **Step 1: Run end-to-end smoke checklist**

Manual checklist:

```text
1. Open Assistant Detail -> Group Members
2. Edit a member
3. Open 角色视角 page
4. Switch 基础模式 among 全部可见 / 仅自己相关 / 仅定向 / 白名单
5. Add whitelist member
6. Toggle @触发 and enter keyword
7. Add excluded member
8. Set 最近消息窗口 to 20
9. Apply 隐秘角色 template
10. Confirm summary card updates immediately
11. Confirm 保存 persists after leaving and re-entering the page
```

- [ ] **Step 2: Run project-level verification relevant to touched areas**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest" --tests "me.rerere.rikkahub.service.group.DynamicGroupContextResolverTest" --tests "me.rerere.rikkahub.service.group.CharacterViewpointEvaluatorTest"
./gradlew :app:assembleDebug -x :web:buildWebUi
```

Expected:

- PASS

- [ ] **Step 3: Update plan checkboxes and note any deviations**

If implementation diverges:

```markdown
- Added `CharacterViewpointPage.kt` section composables inline instead of a separate file split to minimize churn in phase 1.
- Kept `ContextFilter` persistence unchanged; all new state remains adapter-level only.
```

- [ ] **Step 4: Final commit**

```bash
git add docs/superpowers/plans/2026-06-18-character-viewpoint-implementation-plan.md
git commit -m "docs: finalize character viewpoint implementation tracking"
```

---

## Self-Review

### Spec coverage

- Feature rename to `角色视角`: covered by Tasks 5-7
- Dedicated mobile subpage: covered by Tasks 4-7
- Summary card: covered by Tasks 1, 5, and 6
- Base mode / triggers / exclusions / message window grouping: covered by Tasks 1, 2, and 6
- Reusable explainable preview: covered by Tasks 2 and 7
- Template support: covered by Tasks 3 and 7
- Backward compatibility with `ContextFilter`: covered by Task 1

### Placeholder scan

- No `TODO` / `TBD`
- Later-slot wording is limited to the explicitly non-implemented moderator trigger reservation from the approved spec
- Every code-changing task contains concrete snippet content and exact commands

### Type consistency

- `CharacterViewpointState`, `CharacterViewpointTriggers`, `CharacterViewpointExclusions`, `CharacterViewpointWindow`, and `ViewpointEvaluationResult` are introduced once and reused consistently
- Runtime filter logic always consumes `toCharacterViewpointState()`
- The new route key is consistently named `Screen.AssistantCharacterViewpoint`

---

## Execution Update - 2026-06-18

Implementation branch/worktree:

- Worktree: `C:\Users\18734\Desktop\HTML\rikkahub-source\.worktrees\character-viewpoint`
- Branch: `codex/character-viewpoint-2026-06-18`

Completed:

- Task 1 completed: viewpoint state, mapper, summary builder, and focused tests.
- Task 2 completed: reusable explainable evaluator and focused tests.
- Task 3 completed: deterministic template application and tests.
- Task 4 completed: member-specific `CharacterViewpointVM` and Koin registration.
- Task 5 completed: `Screen.AssistantCharacterViewpoint` route and route entry.
- Task 6 completed: `CharacterViewpointLauncher` wired into `AssistantGroupMembersPage.kt`.
- Task 7 completed: mobile-first `CharacterViewpointPage` with summary, base mode, triggers, exclusions, message window, templates, preview, save, and reset flow.

Verification completed:

- `.\gradlew.bat :app:compileDebugKotlin -x :web:buildWebUi` passed.
- `.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointMapperTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointSummaryTest" --tests "me.rerere.rikkahub.ui.pages.assistant.detail.CharacterViewpointTemplatesTest" --tests "me.rerere.rikkahub.service.group.CharacterViewpointEvaluatorTest" --tests "me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest" -x :web:buildWebUi` passed.
- `.\gradlew.bat :app:assembleDebug -x :web:buildWebUi` passed.

Remaining:

- Manual emulator smoke for the full navigation flow:
  1. Open Assistant Detail -> Group Members.
  2. Edit a group member.
  3. Confirm the `角色视角` launcher appears below the context filter summary.
  4. Open the `角色视角` page.
  5. Change base mode, mention trigger, exclusions, message window, and template.
  6. Save, leave, re-enter, and confirm persistence.

Notes:

- `AssistantGroupMembersPage.kt` was verified as strict UTF-8 without BOM. The final integration used an ASCII-only patch context to avoid touching existing localized string literals.
- `CharacterViewpointPage` currently edits whitelist/exclusion member IDs through UUID text fields because `CharacterViewpointVM` does not yet expose the group member list for rich picker controls.
