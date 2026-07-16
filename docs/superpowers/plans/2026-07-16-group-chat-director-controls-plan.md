# Group Chat Director Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a conversation-scoped group-chat director console that preserves RikkaHub's existing visual language and supports graceful pause, one-round continuation, skip-next, one-shot speaker nomination, and per-conversation mode overrides.

**Architecture:** Persist director state inside `Conversation.groupRuntimeState`, make all scheduling decisions in a pure `GroupDirectorEngine`, and let `ChatService` serialize state transitions with group generation. `ChatVM` exposes commands and typed notices, while `ChatPage` renders a Material 3 floating action button and bottom sheet from a derived `GroupDirectorUiState`.

**Tech Stack:** Kotlin, kotlinx.serialization, Room 2.x migrations and exported schemas, coroutines/StateFlow/Mutex, Jetpack Compose Material 3, HugeIcons, JUnit 4, AndroidX Compose UI Test, AndroidX Room Migration Test.

## Global Constraints

- Work only in `C:\Users\18734\Desktop\HTML\rikkahub-port-2.4.1` on branch `codex/port-private-to-2.4.1`; do not reset, clean, or overwrite `C:\Users\18734\Desktop\HTML\rikkahub-source`.
- Preserve Tavern Helper/SillyTavern rendering, layered group context, message transport, and unrelated user changes.
- Pause is graceful: an in-flight reply finishes before automatic chaining stops; this feature never cancels that reply.
- Director state and mode override affect only the current conversation and never mutate `Assistant.turnTakingStrategy`.
- A nominated member overrides one reply only; one-round mode snapshots currently enabled members and lets each speak at most once, with early moderator `STOP` allowed.
- Reuse RikkaHub Material 3 theme, `UIAvatar`, existing 8 dp spacing rhythm, existing bottom-sheet behavior, and verified HugeIcons `UserGroup03`, `Pause`, `Play`, and `Next`.
- Do not add fixed colors, gradients, glass effects, custom shadows, emoji production icons, or a separate director theme.
- Put reusable UI copy and icon accessibility descriptions in `app/src/main/res/values/strings.xml` and Chinese translations in `app/src/main/res/values-zh/strings.xml`.
- Keep database migration additive and backwards compatible: Room version `26` becomes `27`, and legacy or malformed runtime JSON decodes to `GroupRuntimeState()`.
- Every production change follows red-green-refactor and ends with focused tests plus a small commit.

---

## File and Responsibility Map

### Persistence

- `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt`: stores runtime-state JSON.
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27.kt`: adds the non-null `group_runtime_state` column.
- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`: raises the schema version.
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`: registers the manual migration.
- `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`: maps runtime state between model and entity with safe decoding.
- `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json`: generated Room schema.

### Domain and orchestration

- `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt`: serializable director state.
- `app/src/main/java/me/rerere/rikkahub/service/group/GroupDirectorEngine.kt`: commands, typed results, sanitization, candidate override, post-reply transitions, and continuation policy.
- `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`: per-conversation director-state mutex.
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`: command entry point, atomic selection commit, generation lifecycle integration, restoration, and persistence.

### View model and Compose

- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiState.kt`: pure model-to-UI mapper.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControls.kt`: FAB, sheet, actions, mode selector, and member nomination row.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`: command forwarding and typed notice flow.
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`: group-only integration and effective-manual selector behavior.

### Tests

- `app/src/test/java/me/rerere/rikkahub/data/repository/ConversationRuntimeStateMappingTest.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27_Test.kt`
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt`
- `app/src/test/java/me/rerere/rikkahub/service/group/GroupDirectorEngineTest.kt`
- `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
- `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiStateTest.kt`
- `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControlsTest.kt`

---

### Task 1: Persist `GroupRuntimeState` Through Room

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/data/repository/ConversationRuntimeStateMappingTest.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27_Test.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt:35-43`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt:30-63`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt:28-63`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:349-399`
- Generate: `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json`

**Interfaces:**
- Consumes: existing `Conversation.groupRuntimeState: GroupRuntimeState` and `JsonInstant`.
- Produces: `ConversationEntity.groupRuntimeState: String`, `Migration_26_27`, `conversationToEntity(Conversation)`, and `conversationFromEntity(ConversationEntity, List<MessageNode>)`.

- [x] **Step 1: Write failing JVM mapping tests**

Create `ConversationRuntimeStateMappingTest.kt` with exact round-trip and malformed-input cases:

```kotlin
package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.group.GroupRuntimeState
import me.rerere.rikkahub.service.group.GroupSceneState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRuntimeStateMappingTest {
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `entity mapping round trips group runtime state`() {
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            groupRuntimeState = GroupRuntimeState(
                scene = GroupSceneState(summary = "Moonlit courtyard", tension = 4),
            ),
        )

        val restored = conversationFromEntity(
            entity = conversationToEntity(conversation),
            messageNodes = emptyList(),
        )

        assertEquals(conversation.groupRuntimeState, restored.groupRuntimeState)
    }

    @Test
    fun `malformed runtime json falls back to empty state`() {
        val entity = conversationToEntity(
            Conversation(assistantId = assistantId, messageNodes = emptyList())
        ).copy(groupRuntimeState = "{broken")

        val restored = conversationFromEntity(entity, emptyList())

        assertEquals(GroupRuntimeState(), restored.groupRuntimeState)
    }
}
```

- [x] **Step 2: Run the mapping test and confirm the missing mapper contract**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.repository.ConversationRuntimeStateMappingTest" --console=plain
```

Expected: compilation fails because `conversationToEntity`, `conversationFromEntity`, and `ConversationEntity.groupRuntimeState` do not exist.

- [x] **Step 3: Add the entity column and pure mapping functions**

Insert the new field after `statusVariables` in `ConversationEntity.kt`:

```kotlin
@ColumnInfo("group_runtime_state", defaultValue = "{}")
val groupRuntimeState: String = "{}",
```

Replace the two repository mapping methods with delegates:

```kotlin
fun conversationToConversationEntity(conversation: Conversation): ConversationEntity =
    conversationToEntity(conversation)

fun conversationEntityToConversation(
    conversationEntity: ConversationEntity,
    messageNodes: List<MessageNode>,
): Conversation = conversationFromEntity(conversationEntity, messageNodes)
```

Add these top-level functions below `ConversationRepository` in the same file so JVM tests can exercise mapping without constructing DAO dependencies:

```kotlin
internal fun conversationToEntity(conversation: Conversation): ConversationEntity {
    require(conversation.messageNodes.none { node ->
        node.messages.any { message -> message.hasBase64Part() }
    })
    return ConversationEntity(
        id = conversation.id.toString(),
        title = conversation.title,
        nodes = "[]",
        createAt = conversation.createAt.toEpochMilli(),
        updateAt = conversation.updateAt.toEpochMilli(),
        assistantId = conversation.assistantId.toString(),
        chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
        isPinned = conversation.isPinned,
        customSystemPrompt = conversation.customSystemPrompt ?: "",
        modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
        lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
        workspaceCwd = conversation.workspaceCwd ?: "",
        folderId = conversation.folderId?.toString() ?: "",
        statusVariables = JsonInstant.encodeToString(conversation.statusVariables),
        groupRuntimeState = JsonInstant.encodeToString(conversation.groupRuntimeState),
        activeGroupMemberId = conversation.activeGroupMemberId?.toString() ?: "",
        groupMemberQueue = JsonInstant.encodeToString(conversation.groupMemberQueue),
        groupMemberQueueIndex = conversation.groupMemberQueueIndex,
    )
}

internal fun conversationFromEntity(
    entity: ConversationEntity,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.parse(entity.id),
    title = entity.title,
    messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
    createAt = Instant.ofEpochMilli(entity.createAt),
    updateAt = Instant.ofEpochMilli(entity.updateAt),
    assistantId = Uuid.parse(entity.assistantId),
    chatSuggestions = JsonInstant.decodeFromString(entity.chatSuggestions),
    isPinned = entity.isPinned,
    customSystemPrompt = entity.customSystemPrompt.ifEmpty { null },
    modeInjectionIds = JsonInstant.decodeFromString(entity.modeInjectionIds),
    lorebookIds = JsonInstant.decodeFromString(entity.lorebookIds),
    workspaceCwd = entity.workspaceCwd.ifEmpty { null },
    folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
    statusVariables = runCatching {
        JsonInstant.decodeFromString<kotlinx.serialization.json.JsonObject>(entity.statusVariables)
    }.getOrDefault(kotlinx.serialization.json.JsonObject(emptyMap())),
    groupRuntimeState = runCatching {
        JsonInstant.decodeFromString<GroupRuntimeState>(entity.groupRuntimeState)
    }.getOrDefault(GroupRuntimeState()),
    activeGroupMemberId = entity.activeGroupMemberId.ifEmpty { null }?.let { Uuid.parse(it) },
    groupMemberQueue = runCatching {
        JsonInstant.decodeFromString<List<Uuid>>(entity.groupMemberQueue)
    }.getOrDefault(emptyList()),
    groupMemberQueueIndex = entity.groupMemberQueueIndex,
)
```

Add this import to `ConversationRepository.kt`:

```kotlin
import me.rerere.rikkahub.service.group.GroupRuntimeState
```

- [x] **Step 4: Run the mapping tests and all repository-adjacent JVM tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.repository.ConversationRuntimeStateMappingTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, two tests pass.

- [x] **Step 5: Write the failing 26-to-27 migration test**

Create `Migration_26_27_Test.kt`:

```kotlin
package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_26_27_Test {
    private val databaseName = "migration-26-27"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate26To27_addsRuntimeStateWithEmptyObjectDefault() {
        helper.createDatabase(databaseName, 26).close()

        val db = helper.runMigrationsAndValidate(databaseName, 27, true, Migration_26_27)
        val values = ContentValues().apply {
            put("id", Uuid.random().toString())
            put("assistant_id", Uuid.random().toString())
            put("title", "Legacy group")
            put("nodes", "[]")
            put("create_at", 1L)
            put("update_at", 1L)
            put("suggestions", "[]")
            put("is_pinned", 0)
        }
        assertTrue(db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) > 0)

        db.query("SELECT group_runtime_state FROM ConversationEntity").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("{}", cursor.getString(0))
        }
        db.close()
    }
}
```

- [x] **Step 6: Run the migration test and confirm version 27 is absent**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.Migration_26_27_Test" --console=plain
```

Expected: compilation fails because `Migration_26_27` and schema version 27 do not exist.

- [x] **Step 7: Implement and register the additive migration**

Create `Migration_26_27.kt`:

```kotlin
package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn26To27("ConversationEntity", "group_runtime_state")) {
            db.execSQL(
                "ALTER TABLE ConversationEntity " +
                    "ADD COLUMN group_runtime_state TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }
}

private fun SupportSQLiteDatabase.hasColumn26To27(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
```

Change `AppDatabase.kt` to:

```kotlin
version = 27,
```

Add the import and migration registration in `DataSourceModule.kt`:

```kotlin
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
```

```kotlin
.addMigrations(
    Migration_6_7,
    Migration_11_12,
    Migration_13_14,
    Migration_14_15,
    Migration_15_16,
    Migration_25_26,
    Migration_26_27,
)
```

- [x] **Step 8: Generate schema 27 and run persistence verification**

Run:

```powershell
.\gradlew.bat :app:kspDebugKotlin :app:testDebugUnitTest --tests "me.rerere.rikkahub.data.repository.ConversationRuntimeStateMappingTest" --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.Migration_26_27_Test" --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`; `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json` contains `group_runtime_state` with default `'{}'`.

- [x] **Step 9: Commit the persistence slice**

```powershell
git add app/src/main/java/me/rerere/rikkahub/data/db app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt app/src/test/java/me/rerere/rikkahub/data/repository app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27_Test.kt app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json
git commit -m "fix: persist group runtime state"
```

---

### Task 2: Build the Pure Director State Machine

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/service/group/GroupDirectorEngine.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/service/group/GroupDirectorEngineTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt:14-24`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/data/repository/ConversationRuntimeStateMappingTest.kt`

**Interfaces:**
- Consumes: `TurnTakingStrategy`, `normalizeGroupMemberQueue`, and enabled member IDs in stable order.
- Produces: `GroupDirectorState`, `GroupPlaybackState`, `GroupDirectorCommand`, `GroupDirectorCommandResult`, `GroupDirectorCommandStatus`, `GroupDirectorSelectionResult`, and `GroupDirectorEngine`.

- [x] **Step 1: Extend runtime serialization tests before adding the model**

Add these tests to `GroupRuntimeStateTest.kt`:

```kotlin
@Test
fun `runtime state round trips every director field`() {
    val state = GroupRuntimeState(
        director = GroupDirectorState(
            modeOverride = TurnTakingStrategy.AUTO_MODERATOR,
            playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
            oneShotNextMemberId = memberA,
            oneShotReturnToPaused = true,
            oneRoundActive = true,
            oneRoundRemainingMemberIds = listOf(memberA, memberB),
            skipNextRequested = true,
        )
    )

    val decoded = Json.decodeFromString<GroupRuntimeState>(Json.encodeToString(state))

    assertEquals(state.director, decoded.director)
}

@Test
fun `legacy runtime json without director uses defaults`() {
    val decoded = Json.decodeFromString<GroupRuntimeState>("{}")

    assertEquals(GroupDirectorState(), decoded.director)
}
```

Add this import:

```kotlin
import me.rerere.rikkahub.data.model.TurnTakingStrategy
```

Extend the Task 1 repository round-trip fixture so persistence is proven for the new director fields as well as scene state:

```kotlin
groupRuntimeState = GroupRuntimeState(
    scene = GroupSceneState(summary = "Moonlit courtyard", tension = 4),
    director = GroupDirectorState(
        modeOverride = TurnTakingStrategy.AUTO_MODERATOR,
        playbackState = GroupPlaybackState.PAUSED,
        oneShotNextMemberId = memberA,
        oneRoundActive = true,
        oneRoundRemainingMemberIds = listOf(memberA),
        skipNextRequested = true,
    ),
),
```

Add `memberA` to that test class and import the director types and `TurnTakingStrategy`:

```kotlin
private val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
```

- [x] **Step 2: Create failing engine tests for every approved transition**

Create `GroupDirectorEngineTest.kt` with these fixtures and assertions:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.TurnTakingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorEngineTest {
    private val engine = GroupDirectorEngine()
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val enabled = listOf(a, b, c)

    private fun context(active: Boolean = false) = GroupDirectorCommandContext(
        generationActive = active,
        orderedEnabledMemberIds = enabled,
    )

    @Test
    fun `idle pause is immediate and active pause waits for completion`() {
        val idle = engine.reduce(GroupDirectorState(), GroupDirectorCommand.PauseAfterCurrent, context())
        val active = engine.reduce(GroupDirectorState(), GroupDirectorCommand.PauseAfterCurrent, context(true))

        assertEquals(GroupPlaybackState.PAUSED, idle.state.playbackState)
        assertEquals(GroupPlaybackState.PAUSE_AFTER_CURRENT, active.state.playbackState)
        assertEquals(active.state, engine.reduce(active.state, GroupDirectorCommand.PauseAfterCurrent, context(true)).state)
    }

    @Test
    fun `one round snapshots order and removes each completed speaker once`() {
        val started = engine.reduce(GroupDirectorState(), GroupDirectorCommand.ContinueOneRound, context())
        val afterA = engine.afterReply(started.state, a)
        val duplicateA = engine.afterReply(afterA, a)
        val afterB = engine.afterReply(duplicateA, b)
        val finished = engine.afterReply(afterB, c)

        assertEquals(enabled, started.state.oneRoundRemainingMemberIds)
        assertEquals(listOf(b, c), duplicateA.oneRoundRemainingMemberIds)
        assertFalse(finished.oneRoundActive)
        assertEquals(GroupPlaybackState.PAUSED, finished.playbackState)
    }

    @Test
    fun `restored round stays paused and newly enabled member is excluded`() {
        val restored = engine.sanitize(
            state = GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, b),
            ),
            enabledMemberIds = listOf(a, b, c),
            generationActive = false,
        )

        assertEquals(GroupPlaybackState.PAUSED, restored.playbackState)
        assertEquals(listOf(a, b), restored.oneRoundRemainingMemberIds)
    }

    @Test
    fun `paused nomination runs once and returns to paused`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.PAUSED),
            GroupDirectorCommand.QueueMemberOnce(b),
            context(),
        )
        val selected = engine.applyCandidate(queued.state, normalCandidateId = a, orderedCandidateMemberIds = enabled)
        val completed = engine.afterReply(selected.state, b)

        assertTrue(queued.shouldStartGeneration)
        assertEquals(b, selected.memberId)
        assertNull(selected.state.oneShotNextMemberId)
        assertEquals(GroupPlaybackState.PAUSED, completed.playbackState)
        assertFalse(completed.oneShotReturnToPaused)
    }

    @Test
    fun `skip consumes one candidate and selects the following queue member`() {
        val pending = engine.reduce(GroupDirectorState(), GroupDirectorCommand.SkipNext, context()).state
        val selected = engine.applyCandidate(pending, normalCandidateId = a, orderedCandidateMemberIds = enabled)

        assertEquals(b, selected.memberId)
        assertFalse(selected.state.skipNextRequested)
    }

    @Test
    fun `single member skip clears request and reports no alternative`() {
        val result = engine.reduce(
            GroupDirectorState(skipNextRequested = true),
            GroupDirectorCommand.SkipNext,
            GroupDirectorCommandContext(false, listOf(a)),
        )

        assertEquals(GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER, result.status)
        assertFalse(result.state.skipNextRequested)
    }

    @Test
    fun `mode override is conversation local and manual blocks ordinary chaining`() {
        val state = engine.reduce(
            GroupDirectorState(),
            GroupDirectorCommand.SetMode(TurnTakingStrategy.MANUAL),
            context(true),
        ).state

        assertEquals(TurnTakingStrategy.MANUAL, engine.effectiveStrategy(state, TurnTakingStrategy.AUTO_MODERATOR))
        assertFalse(engine.shouldContinueAfterReply(state, TurnTakingStrategy.MANUAL, false, 0, 3))
    }

    @Test
    fun `pending one shot continues after current reply even when pause is pending`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT),
            GroupDirectorCommand.QueueMemberOnce(b),
            context(true),
        ).state
        val afterCurrent = engine.afterReply(queued, a)

        assertTrue(engine.shouldContinueAfterReply(afterCurrent, TurnTakingStrategy.AUTO_ROUND_ROBIN, false, 1, 1))
        assertEquals(b, afterCurrent.oneShotNextMemberId)
    }

    @Test
    fun `moderator stop ends an active round and keeps it paused`() {
        val stopped = engine.afterNoCandidate(
            GroupDirectorState(
                playbackState = GroupPlaybackState.RUNNING,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, b),
            )
        )

        assertFalse(stopped.oneRoundActive)
        assertEquals(emptyList<Uuid>(), stopped.oneRoundRemainingMemberIds)
        assertEquals(GroupPlaybackState.PAUSED, stopped.playbackState)
    }

    @Test
    fun `sanitization removes stale round and one shot members`() {
        val stale = Uuid.parse("00000000-0000-0000-0000-000000000099")
        val sanitized = engine.sanitize(
            state = GroupDirectorState(
                oneShotNextMemberId = stale,
                oneRoundActive = true,
                oneRoundRemainingMemberIds = listOf(a, stale, b, a),
            ),
            enabledMemberIds = listOf(a, b),
            generationActive = true,
        )

        assertNull(sanitized.oneShotNextMemberId)
        assertEquals(listOf(a, b), sanitized.oneRoundRemainingMemberIds)
    }

    @Test
    fun `running nomination consumes once without forcing pause`() {
        val queued = engine.reduce(
            GroupDirectorState(playbackState = GroupPlaybackState.RUNNING),
            GroupDirectorCommand.QueueMemberOnce(c),
            context(active = true),
        )
        val selected = engine.applyCandidate(queued.state, a, enabled)
        val completed = engine.afterReply(selected.state, c)

        assertFalse(queued.shouldStartGeneration)
        assertEquals(c, selected.memberId)
        assertEquals(GroupPlaybackState.RUNNING, completed.playbackState)
    }
}
```

- [x] **Step 3: Run the focused tests and confirm domain types are missing**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateTest" --tests "me.rerere.rikkahub.service.group.GroupDirectorEngineTest" --console=plain
```

Expected: compilation fails on the new director types.

- [x] **Step 4: Add serializable director state to `GroupRuntimeState`**

Add this property to `GroupRuntimeState`:

```kotlin
val director: GroupDirectorState = GroupDirectorState(),
```

Add the following types below `GroupRuntimeState` and import `TurnTakingStrategy`:

```kotlin
import me.rerere.rikkahub.data.model.TurnTakingStrategy
```

```kotlin
@Serializable
data class GroupDirectorState(
    val modeOverride: TurnTakingStrategy? = null,
    val playbackState: GroupPlaybackState = GroupPlaybackState.RUNNING,
    val oneShotNextMemberId: Uuid? = null,
    val oneShotReturnToPaused: Boolean = false,
    val oneRoundActive: Boolean = false,
    val oneRoundRemainingMemberIds: List<Uuid> = emptyList(),
    val skipNextRequested: Boolean = false,
)

@Serializable
enum class GroupPlaybackState {
    RUNNING,
    PAUSE_AFTER_CURRENT,
    PAUSED,
}
```

- [x] **Step 5: Implement the pure engine with fixed command/result contracts**

Create `GroupDirectorEngine.kt`:

```kotlin
package me.rerere.rikkahub.service.group

import me.rerere.rikkahub.data.model.TurnTakingStrategy
import kotlin.uuid.Uuid

sealed interface GroupDirectorCommand {
    data object PauseAfterCurrent : GroupDirectorCommand
    data object ContinueOneRound : GroupDirectorCommand
    data object SkipNext : GroupDirectorCommand
    data class QueueMemberOnce(val memberId: Uuid) : GroupDirectorCommand
    data class SetMode(val strategy: TurnTakingStrategy) : GroupDirectorCommand
}

enum class GroupDirectorCommandStatus {
    APPLIED,
    NOT_GROUP,
    NO_ENABLED_MEMBERS,
    INVALID_MEMBER,
    NO_ALTERNATIVE_MEMBER,
}

data class GroupDirectorCommandContext(
    val generationActive: Boolean,
    val orderedEnabledMemberIds: List<Uuid>,
)

data class GroupDirectorCommandResult(
    val state: GroupDirectorState,
    val status: GroupDirectorCommandStatus = GroupDirectorCommandStatus.APPLIED,
    val shouldStartGeneration: Boolean = false,
)

data class GroupDirectorSelectionResult(
    val memberId: Uuid?,
    val state: GroupDirectorState,
    val status: GroupDirectorCommandStatus = GroupDirectorCommandStatus.APPLIED,
)

class GroupDirectorEngine {
    fun effectiveStrategy(
        state: GroupDirectorState,
        assistantDefault: TurnTakingStrategy,
    ): TurnTakingStrategy = state.modeOverride ?: assistantDefault

    fun sanitize(
        state: GroupDirectorState,
        enabledMemberIds: List<Uuid>,
        generationActive: Boolean,
    ): GroupDirectorState {
        val enabled = enabledMemberIds.distinct()
        val enabledSet = enabled.toSet()
        val remaining = state.oneRoundRemainingMemberIds
            .filter { it in enabledSet }
            .distinct()
        val roundActive = state.oneRoundActive && remaining.isNotEmpty()
        val allowedOneShotIds = if (roundActive) remaining.toSet() else enabledSet
        val oneShot = state.oneShotNextMemberId?.takeIf { it in allowedOneShotIds }
        val keepReturnToPaused = state.oneShotReturnToPaused &&
            (generationActive || oneShot != null)
        val playback = when {
            state.oneRoundActive && !roundActive -> GroupPlaybackState.PAUSED
            !generationActive && state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT ->
                GroupPlaybackState.PAUSED
            !generationActive && roundActive -> GroupPlaybackState.PAUSED
            !generationActive && state.oneShotReturnToPaused -> GroupPlaybackState.PAUSED
            else -> state.playbackState
        }
        return state.copy(
            playbackState = playback,
            oneShotNextMemberId = oneShot,
            oneShotReturnToPaused = keepReturnToPaused,
            oneRoundActive = roundActive,
            oneRoundRemainingMemberIds = remaining,
        )
    }

    fun eligibleMemberIds(
        state: GroupDirectorState,
        enabledMemberIds: List<Uuid>,
    ): List<Uuid> {
        val enabled = enabledMemberIds.distinct()
        if (!state.oneRoundActive) return enabled
        val enabledSet = enabled.toSet()
        return state.oneRoundRemainingMemberIds
            .filter { it in enabledSet }
            .distinct()
    }

    fun reduce(
        state: GroupDirectorState,
        command: GroupDirectorCommand,
        context: GroupDirectorCommandContext,
    ): GroupDirectorCommandResult {
        val enabled = context.orderedEnabledMemberIds.distinct()
        val clean = sanitize(state, enabled, context.generationActive)
        val eligible = eligibleMemberIds(clean, enabled)
        return when (command) {
            GroupDirectorCommand.PauseAfterCurrent -> GroupDirectorCommandResult(
                state = clean.copy(
                    playbackState = if (context.generationActive) {
                        GroupPlaybackState.PAUSE_AFTER_CURRENT
                    } else {
                        GroupPlaybackState.PAUSED
                    }
                )
            )

            GroupDirectorCommand.ContinueOneRound -> {
                if (enabled.isEmpty()) {
                    GroupDirectorCommandResult(clean, GroupDirectorCommandStatus.NO_ENABLED_MEMBERS)
                } else {
                    val remaining = if (clean.oneRoundActive && clean.oneRoundRemainingMemberIds.isNotEmpty()) {
                        clean.oneRoundRemainingMemberIds
                    } else {
                        enabled
                    }
                    GroupDirectorCommandResult(
                        state = clean.copy(
                            playbackState = GroupPlaybackState.RUNNING,
                            oneRoundActive = true,
                            oneRoundRemainingMemberIds = remaining,
                        ),
                        shouldStartGeneration = !context.generationActive,
                    )
                }
            }

            GroupDirectorCommand.SkipNext -> {
                if (eligible.size < 2) {
                    GroupDirectorCommandResult(
                        state = clean.copy(skipNextRequested = false),
                        status = GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER,
                    )
                } else {
                    GroupDirectorCommandResult(clean.copy(skipNextRequested = true))
                }
            }

            is GroupDirectorCommand.QueueMemberOnce -> when {
                enabled.isEmpty() -> GroupDirectorCommandResult(
                    clean,
                    GroupDirectorCommandStatus.NO_ENABLED_MEMBERS,
                )
                command.memberId !in eligible -> GroupDirectorCommandResult(
                    clean,
                    GroupDirectorCommandStatus.INVALID_MEMBER,
                )
                else -> GroupDirectorCommandResult(
                    state = clean.copy(
                        oneShotNextMemberId = command.memberId,
                        oneShotReturnToPaused = clean.playbackState != GroupPlaybackState.RUNNING,
                    ),
                    shouldStartGeneration = !context.generationActive,
                )
            }

            is GroupDirectorCommand.SetMode -> GroupDirectorCommandResult(
                clean.copy(modeOverride = command.strategy)
            )
        }
    }

    fun applyCandidate(
        state: GroupDirectorState,
        normalCandidateId: Uuid?,
        orderedCandidateMemberIds: List<Uuid>,
    ): GroupDirectorSelectionResult {
        val ordered = orderedCandidateMemberIds.distinct()
        val clean = sanitize(state, ordered, generationActive = true)
        if (clean.playbackState == GroupPlaybackState.PAUSED && clean.oneShotNextMemberId == null) {
            return GroupDirectorSelectionResult(null, clean)
        }
        val usedOneShot = clean.oneShotNextMemberId != null
        val candidate = clean.oneShotNextMemberId ?: normalCandidateId
        if (candidate == null || candidate !in ordered) {
            return GroupDirectorSelectionResult(null, clean)
        }
        val selected = if (clean.skipNextRequested) {
            val start = ordered.indexOf(candidate)
            (1 until ordered.size)
                .asSequence()
                .map { offset -> ordered[(start + offset) % ordered.size] }
                .firstOrNull { it != candidate }
                ?: return GroupDirectorSelectionResult(
                    memberId = null,
                    state = clean.copy(skipNextRequested = false),
                    status = GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER,
                )
        } else {
            candidate
        }
        return GroupDirectorSelectionResult(
            memberId = selected,
            state = clean.copy(
                oneShotNextMemberId = if (usedOneShot) null else clean.oneShotNextMemberId,
                skipNextRequested = false,
            ),
        )
    }

    fun afterReply(state: GroupDirectorState, speakerId: Uuid): GroupDirectorState {
        val remaining = if (state.oneRoundActive) {
            state.oneRoundRemainingMemberIds.filterNot { it == speakerId }
        } else {
            state.oneRoundRemainingMemberIds
        }
        val roundFinished = state.oneRoundActive && remaining.isEmpty()
        val oneShotFinished = state.oneShotReturnToPaused && state.oneShotNextMemberId == null
        val pauseFinished = state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT &&
            state.oneShotNextMemberId == null
        return state.copy(
            playbackState = if (roundFinished || oneShotFinished || pauseFinished) {
                GroupPlaybackState.PAUSED
            } else {
                state.playbackState
            },
            oneShotReturnToPaused = if (oneShotFinished) false else state.oneShotReturnToPaused,
            oneRoundActive = state.oneRoundActive && !roundFinished,
            oneRoundRemainingMemberIds = remaining,
        )
    }

    fun afterNoCandidate(state: GroupDirectorState): GroupDirectorState = if (state.oneRoundActive) {
        state.copy(
            playbackState = GroupPlaybackState.PAUSED,
            oneRoundActive = false,
            oneRoundRemainingMemberIds = emptyList(),
            skipNextRequested = false,
        )
    } else {
        state.copy(
            playbackState = if (state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT) {
                GroupPlaybackState.PAUSED
            } else {
                state.playbackState
            },
            skipNextRequested = false,
        )
    }

    fun afterFailure(state: GroupDirectorState): GroupDirectorState {
        val mustPause = state.oneRoundActive ||
            state.oneShotReturnToPaused ||
            state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT
        return state.copy(
            playbackState = if (mustPause) GroupPlaybackState.PAUSED else state.playbackState,
            oneShotReturnToPaused = false,
        )
    }

    fun shouldContinueAfterReply(
        state: GroupDirectorState,
        effectiveStrategy: TurnTakingStrategy,
        isAddressedTurn: Boolean,
        alreadySent: Int,
        configuredLimit: Int,
    ): Boolean {
        if (state.oneShotNextMemberId != null) return true
        if (isAddressedTurn || state.playbackState != GroupPlaybackState.RUNNING) return false
        if (state.oneRoundActive) return state.oneRoundRemainingMemberIds.isNotEmpty()
        if (effectiveStrategy == TurnTakingStrategy.MANUAL) return false
        return shouldContinueGroupAutoReplies(alreadySent, configuredLimit)
    }
}
```

- [x] **Step 6: Run all director, runtime, and scheduler tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.group.GroupRuntimeStateTest" --tests "me.rerere.rikkahub.service.group.GroupDirectorEngineTest" --tests "me.rerere.rikkahub.service.group.GroupTurnSchedulerTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`; all new and existing group scheduler tests pass.

- [x] **Step 7: Commit the pure domain slice**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/group/GroupContextModels.kt app/src/main/java/me/rerere/rikkahub/service/group/GroupDirectorEngine.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupRuntimeStateTest.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupDirectorEngineTest.kt app/src/test/java/me/rerere/rikkahub/data/repository/ConversationRuntimeStateMappingTest.kt
git commit -m "feat: add group director state machine"
```

---

### Task 3: Integrate Director Decisions With `ChatService`

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:202-1086, 1737-1811`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`
- Modify: `app/src/test/java/me/rerere/rikkahub/service/group/GroupDirectorEngineTest.kt`

**Interfaces:**
- Consumes: every Task 2 director type and existing scheduler functions.
- Produces: `ChatService.applyGroupDirectorCommand(Uuid, GroupDirectorCommand): GroupDirectorCommandResult`, conversation restoration sanitization, atomic selection consumption, and director-aware continuation.

- [x] **Step 1: Add failing service-boundary regression assertions**

Extend `ChatServiceTest.kt` so generation-start copies prove that director state survives selection and streaming setup:

```kotlin
@Test
fun `generation start keeps resolved director state`() {
    val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    val initial = Conversation(assistantId = assistantId, messageNodes = emptyList())
    val resolved = initial.copy(
        groupRuntimeState = GroupRuntimeState(
            director = GroupDirectorState(
                playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                oneShotNextMemberId = memberId,
            )
        )
    )

    val result = conversationAtGenerationStart(initial, resolved)

    assertEquals(resolved.groupRuntimeState.director, result.groupRuntimeState.director)
}
```

Add imports for `GroupDirectorState`, `GroupPlaybackState`, and `GroupRuntimeState`.

Add this engine test to lock down one-round cap bypass before changing the service continuation branch:

```kotlin
@Test
fun `active one round continues past ordinary cap while members remain`() {
    val state = GroupDirectorState(
        playbackState = GroupPlaybackState.RUNNING,
        oneRoundActive = true,
        oneRoundRemainingMemberIds = listOf(b, c),
    )

    assertTrue(
        engine.shouldContinueAfterReply(
            state = state,
            effectiveStrategy = TurnTakingStrategy.AUTO_MODERATOR,
            isAddressedTurn = false,
            alreadySent = 9,
            configuredLimit = 1,
        )
    )
}
```

- [x] **Step 2: Run the focused tests before service edits**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest" --tests "me.rerere.rikkahub.service.group.GroupDirectorEngineTest" --console=plain
```

Expected: the new assertions pass against the pure code; this establishes the service-preservation baseline before orchestration changes.

- [x] **Step 3: Add a per-session state lock**

Add imports and the lock API to `ConversationSession.kt`:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

```kotlin
private val groupDirectorMutex = Mutex()

suspend fun <T> withGroupDirectorLock(block: suspend () -> T): T =
    groupDirectorMutex.withLock { block() }
```

The lock is held for state snapshots/commits, the single moderator selection call, streaming message merges, and persistence. It is never held across the selected member's streamed reply.

- [x] **Step 4: Add the command entry point and lazy generation starter**

Keep the existing `CancellationException` import and add these imports to `ChatService.kt`:

```kotlin
import kotlinx.coroutines.CoroutineStart
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupDirectorCommandContext
import me.rerere.rikkahub.service.group.GroupDirectorCommandResult
import me.rerere.rikkahub.service.group.GroupDirectorCommandStatus
import me.rerere.rikkahub.service.group.GroupDirectorEngine
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupTurnSelection
import me.rerere.rikkahub.service.group.normalizeGroupMemberQueue
```

Add this field next to other service helpers:

```kotlin
private val groupDirectorEngine = GroupDirectorEngine()
```

Add the public command API after `triggerMemberReply`:

```kotlin
suspend fun applyGroupDirectorCommand(
    conversationId: Uuid,
    command: GroupDirectorCommand,
): GroupDirectorCommandResult {
    val session = getOrCreateSession(conversationId)
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getAssistantById(session.state.value.assistantId)
        ?: settings.getCurrentAssistant()
    if (assistant.assistantType != AssistantType.GROUP) {
        return GroupDirectorCommandResult(
            state = session.state.value.groupRuntimeState.director,
            status = GroupDirectorCommandStatus.NOT_GROUP,
        )
    }
    val result = session.withGroupDirectorLock {
        val current = session.state.value
        val enabledIds = assistant.groupMembers.filter { it.enabled }.map { it.id }
        val orderedIds = normalizeGroupMemberQueue(current.groupMemberQueue, enabledIds)
        val reduced = groupDirectorEngine.reduce(
            state = current.groupRuntimeState.director,
            command = command,
            context = GroupDirectorCommandContext(
                generationActive = session.isGenerating,
                orderedEnabledMemberIds = orderedIds,
            ),
        )
        if (reduced.state != current.groupRuntimeState.director) {
            saveConversation(
                conversationId,
                current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = reduced.state)
                ),
            )
        }
        reduced
    }
    if (result.status == GroupDirectorCommandStatus.APPLIED && result.shouldStartGeneration) {
        startGroupDirectorGeneration(conversationId)
    }
    return result
}

private suspend fun startGroupDirectorGeneration(conversationId: Uuid) {
    val session = getOrCreateSession(conversationId)
    session.withGroupDirectorLock {
        if (session.isGenerating) return@withGroupDirectorLock
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                handleMessageComplete(conversationId = conversationId, allowAutoChain = true)
                _generationDoneFlow.emit(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                addError(error, conversationId, title = "Group director failed")
            }
        }
        session.setJob(job)
        job.start()
    }
}
```

- [x] **Step 5: Sanitize restored director state without auto-starting work**

Replace the group branch in `initializeConversation` with this exact normalization:

```kotlin
val cleanedConversation = if (assistant.assistantType == AssistantType.GROUP) {
    val withoutNudges = renderedConversation.removeGroupContinuationNudgeNodes()
    val enabledIds = assistant.groupMembers.filter { it.enabled }.map { it.id }
    val restoredDirector = groupDirectorEngine.sanitize(
        state = withoutNudges.groupRuntimeState.director,
        enabledMemberIds = enabledIds,
        generationActive = false,
    )
    withoutNudges.copy(
        groupRuntimeState = withoutNudges.groupRuntimeState.copy(director = restoredDirector)
    )
} else {
    renderedConversation
}
```

Keep this existing persistence block immediately after the normalization:

```kotlin
updateConversation(conversationId, cleanedConversation)
if (cleanedConversation != renderedConversation) {
    saveConversation(conversationId, cleanedConversation)
}
```

This persists `PAUSE_AFTER_CURRENT -> PAUSED` and leaves a restored round paused until an explicit `ContinueOneRound` command.

- [x] **Step 6: Make group speaker selection an atomic director commit**

Replace `resolveNextSpeaker` with the following implementation. It calls the moderator at most once and commits the chosen speaker, queue cursor, consumed one-shot, and consumed skip in one save:

```kotlin
private suspend fun resolveNextSpeaker(
    conversation: Conversation,
    groupAssistant: Assistant,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    allowModeratorStop: Boolean = false,
): Uuid? {
    val session = getOrCreateSession(conversation.id)
    return session.withGroupDirectorLock {
        val current = session.state.value
        val enabledIds = groupAssistant.groupMembers.filter { it.enabled }.map { it.id }
        val director = groupDirectorEngine.sanitize(
            state = current.groupRuntimeState.director,
            enabledMemberIds = enabledIds,
            generationActive = true,
        )
        val eligibleIds = groupDirectorEngine.eligibleMemberIds(director, enabledIds)
        val orderedEligible = normalizeGroupMemberQueue(current.groupMemberQueue, eligibleIds)
        val effectiveStrategy = groupDirectorEngine.effectiveStrategy(
            director,
            groupAssistant.turnTakingStrategy,
        )

        val normalSelection = if (director.oneShotNextMemberId != null) {
            null
        } else {
            when (effectiveStrategy) {
                TurnTakingStrategy.MANUAL -> {
                    val memberId = current.activeGroupMemberId?.takeIf { it in orderedEligible }
                        ?: orderedEligible.firstOrNull()
                    memberId?.let {
                        GroupTurnSelection(it, orderedEligible, orderedEligible.indexOf(it))
                    }
                }
                TurnTakingStrategy.AUTO_ROUND_ROBIN -> nextRoundRobinSelection(
                    persistedQueue = current.groupMemberQueue,
                    persistedIndex = current.groupMemberQueueIndex,
                    activeMemberId = current.activeGroupMemberId,
                    enabledMemberIds = orderedEligible,
                )
                TurnTakingStrategy.AUTO_MODERATOR -> {
                    val resolved = resolveNextSpeakerViaModerator(
                        conversation = current,
                        groupAssistant = groupAssistant,
                        settings = settings,
                        allowStop = allowModeratorStop || director.oneRoundActive,
                        eligibleMemberIds = orderedEligible,
                    )
                    selectModeratorTurn(
                        persistedQueue = current.groupMemberQueue,
                        enabledMemberIds = orderedEligible,
                        activeMemberId = current.activeGroupMemberId,
                        resolvedMemberId = resolved,
                        allowConsecutiveSameSpeaker =
                            groupAssistant.groupReplyOptions.allowConsecutiveSameSpeaker,
                    )
                }
            }
        }

        val selection = groupDirectorEngine.applyCandidate(
            state = director,
            normalCandidateId = normalSelection?.memberId,
            orderedCandidateMemberIds = normalSelection?.queue ?: orderedEligible,
        )
        val selectedId = selection.memberId
        if (selectedId == null) {
            val stopped = if (
                selection.state.playbackState == GroupPlaybackState.PAUSED &&
                selection.status == GroupDirectorCommandStatus.APPLIED
            ) {
                selection.state
            } else {
                groupDirectorEngine.afterNoCandidate(selection.state)
            }
            if (stopped != current.groupRuntimeState.director) {
                saveConversation(
                    current.id,
                    current.copy(
                        groupRuntimeState = current.groupRuntimeState.copy(director = stopped)
                    ),
                )
            }
            return@withGroupDirectorLock null
        }

        val committedQueue = normalSelection?.queue ?: orderedEligible
        val committed = current.copy(
            activeGroupMemberId = selectedId,
            groupMemberQueue = committedQueue,
            groupMemberQueueIndex = committedQueue.indexOf(selectedId).coerceAtLeast(0),
            groupRuntimeState = current.groupRuntimeState.copy(director = selection.state),
        )
        saveConversation(current.id, committed)
        selectedId
    }
}
```

Change `resolveNextSpeakerViaModerator` to accept eligible IDs and restrict its member list:

```kotlin
private suspend fun resolveNextSpeakerViaModerator(
    conversation: Conversation,
    groupAssistant: Assistant,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    allowStop: Boolean,
    eligibleMemberIds: List<Uuid>,
): Uuid? {
    val eligibleSet = eligibleMemberIds.toSet()
    val enabled = groupAssistant.groupMembers.filter { it.enabled && it.id in eligibleSet }
```

Keep the existing moderator prompt, provider call, `parseGroupModeratorDecision`, and fallback body after this new `enabled` declaration. Restrict the existing local scorer result before choosing its fallback:

```kotlin
val localScores = GroupSpeakerScorer().score(
    groupAssistant = groupAssistant,
    messages = conversation.currentMessages,
    runtimeState = conversation.groupRuntimeState,
    activeMemberId = conversation.activeGroupMemberId,
).filter { it.memberId in eligibleSet }
```

- [x] **Step 7: Prevent streaming chunks from overwriting a concurrent director command**

In the `GenerationChunk.Messages` branch, replace the read/merge/write trio with a locked merge:

```kotlin
val session = getOrCreateSession(conversationId)
val updatedConversation = session.withGroupDirectorLock {
    val merged = session.state.value.mergeMessages(stampedMessages)
    updateConversation(conversationId, merged)
    merged
}
```

Use `updatedConversation` for the existing notification update. Apply the same lock to the `onCompletion` fallback mutation:

```kotlin
val session = getOrCreateSession(conversationId)
val updatedConversation = session.withGroupDirectorLock {
    val updated = session.state.value.copy(
        messageNodes = session.state.value.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.finishReasoning() })
        },
        updateAt = Instant.now(),
    )
    updateConversation(conversationId, updated)
    updated
}
```

- [x] **Step 8: Apply post-reply director state and replace the old auto-chain condition**

In `onSuccess`, replace the current runtime-state update with this locked latest-state update:

```kotlin
val conversationAfterRuntimeUpdate = if (
    groupAssistant.assistantType == AssistantType.GROUP && effectiveMemberId != null
) {
    getOrCreateSession(conversationId).withGroupDirectorLock {
        val latest = getConversationFlow(conversationId).value
        val runtimeWithDebug = latest.groupRuntimeState.copy(
            lastResolverDebug = dynamicContextResult?.debugState
                ?: latest.groupRuntimeState.lastResolverDebug,
        )
        val updatedRuntime = GroupRuntimeStateUpdater().updateAfterReply(
            previous = runtimeWithDebug,
            groupAssistant = groupAssistant,
            messages = latest.currentMessages,
            speakerId = effectiveMemberId,
        )
        val updated = latest.copy(
            groupRuntimeState = updatedRuntime.copy(
                director = groupDirectorEngine.afterReply(
                    updatedRuntime.director,
                    effectiveMemberId,
                )
            )
        )
        saveConversation(conversationId, updated)
        updated
    }
} else {
    getConversationFlow(conversationId).value.also {
        saveConversation(conversationId, it)
    }
}
```

Remove the old duplicate `saveConversation(conversationId, conversationAfterRuntimeUpdate)` call. Replace the old `turnTakingStrategy != MANUAL` and ordinary-cap branch with:

```kotlin
} else if (allowAutoChain && groupAssistant.assistantType == AssistantType.GROUP) {
    val alreadySent = countGroupRepliesSinceLastUserMessage(
        conversationAfterRuntimeUpdate,
        groupAssistant,
    )
    val director = conversationAfterRuntimeUpdate.groupRuntimeState.director
    val effectiveStrategy = groupDirectorEngine.effectiveStrategy(
        director,
        groupAssistant.turnTakingStrategy,
    )
    if (
        groupDirectorEngine.shouldContinueAfterReply(
            state = director,
            effectiveStrategy = effectiveStrategy,
            isAddressedTurn = isAddressedTurn,
            alreadySent = alreadySent,
            configuredLimit = groupAssistant.groupReplyOptions.maxAutoRepliesPerUserTurn,
        )
    ) {
        handleMessageComplete(conversationId = conversationId, allowAutoChain = true)
    }
}
```

- [x] **Step 9: Pause explicit director runs after provider failure and preserve cancellation**

At the beginning of the existing `onFailure` block, add:

```kotlin
if (it is CancellationException) throw it
if (groupAssistant.assistantType == AssistantType.GROUP && effectiveMemberId != null) {
    val session = getOrCreateSession(conversationId)
    session.withGroupDirectorLock {
        val current = session.state.value
        val failedState = groupDirectorEngine.afterFailure(current.groupRuntimeState.director)
        if (failedState != current.groupRuntimeState.director) {
            saveConversation(
                conversationId,
                current.copy(
                    groupRuntimeState = current.groupRuntimeState.copy(director = failedState)
                ),
            )
        }
    }
}
```

Keep the existing error notification and logging after this block.

- [x] **Step 10: Compile and run all service/group regressions**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.service.ChatServiceTest" --tests "me.rerere.rikkahub.service.group.*" --console=plain
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`; existing group transport, context, scheduler, and runtime tests remain green.

- [x] **Step 11: Commit the orchestration slice**

```powershell
git add app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt app/src/test/java/me/rerere/rikkahub/service/group/GroupDirectorEngineTest.kt
git commit -m "feat: orchestrate group director commands"
```

---

### Task 4: Derive UI State and Expose ViewModel Commands

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiState.kt`
- Create: `app/src/test/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiStateTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt:42-190`

**Interfaces:**
- Consumes: persisted director state, group assistant members, `Settings`, and generation activity.
- Produces: `GroupDirectorUiState`, `GroupDirectorMemberUi`, `buildGroupDirectorUiState`, `ChatVM.applyGroupDirectorCommand`, and `ChatVM.groupDirectorNotices`.

- [x] **Step 1: Write failing UI-state mapper tests**

Create `GroupDirectorUiStateTest.kt`:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GroupMember
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorUiStateTest {
    private val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val source = Assistant(name = "Alice")
    private val group = Assistant(
        name = "Cast",
        assistantType = AssistantType.GROUP,
        turnTakingStrategy = TurnTakingStrategy.AUTO_ROUND_ROBIN,
        groupMembers = listOf(
            GroupMember(id = memberId, assistantId = source.id, displayName = "Aileen")
        ),
    )
    private val settings = Settings(assistants = listOf(group, source))

    @Test
    fun `non group assistant has no director ui`() {
        val solo = Assistant(name = "Solo")
        val conversation = Conversation(assistantId = solo.id, messageNodes = emptyList())

        assertNull(buildGroupDirectorUiState(conversation, solo, Settings(assistants = listOf(solo)), false))
    }

    @Test
    fun `mapper uses override and normalizes stale pending pause for display`() {
        val conversation = Conversation(
            assistantId = group.id,
            messageNodes = emptyList(),
            groupRuntimeState = GroupRuntimeState(
                director = GroupDirectorState(
                    modeOverride = TurnTakingStrategy.MANUAL,
                    playbackState = GroupPlaybackState.PAUSE_AFTER_CURRENT,
                    oneShotNextMemberId = memberId,
                )
            ),
        )

        val state = buildGroupDirectorUiState(conversation, group, settings, false)!!

        assertEquals(TurnTakingStrategy.MANUAL, state.effectiveMode)
        assertEquals(GroupPlaybackState.PAUSED, state.playbackState)
        assertEquals("Aileen", state.members.single().name)
        assertTrue(state.members.single().isQueuedNext)
        assertFalse(state.isGenerating)
    }
}
```

- [x] **Step 2: Run the mapper test and confirm the UI model is absent**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.GroupDirectorUiStateTest" --console=plain
```

Expected: compilation fails because the mapper types do not exist.

- [x] **Step 3: Implement the pure UI mapper**

Create `GroupDirectorUiState.kt`:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantType
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorEngine
import me.rerere.rikkahub.service.group.GroupPlaybackState
import kotlin.uuid.Uuid

data class GroupDirectorMemberUi(
    val id: Uuid,
    val name: String,
    val avatar: Avatar,
    val isQueuedNext: Boolean,
)

data class GroupDirectorUiState(
    val effectiveMode: TurnTakingStrategy,
    val playbackState: GroupPlaybackState,
    val isGenerating: Boolean,
    val oneRoundActive: Boolean,
    val oneRoundRemainingCount: Int,
    val members: List<GroupDirectorMemberUi>,
    val canPause: Boolean,
    val canContinueRound: Boolean,
    val canSkip: Boolean,
)

internal fun buildGroupDirectorUiState(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    isGenerating: Boolean,
): GroupDirectorUiState? {
    if (assistant.assistantType != AssistantType.GROUP) return null
    val engine = GroupDirectorEngine()
    val enabledMembers = assistant.groupMembers.filter { it.enabled }
    val enabledIds = enabledMembers.map { it.id }
    val director = engine.sanitize(
        state = conversation.groupRuntimeState.director,
        enabledMemberIds = enabledIds,
        generationActive = isGenerating,
    )
    val eligibleIds = engine.eligibleMemberIds(director, enabledIds).toSet()
    return GroupDirectorUiState(
        effectiveMode = engine.effectiveStrategy(director, assistant.turnTakingStrategy),
        playbackState = director.playbackState,
        isGenerating = isGenerating,
        oneRoundActive = director.oneRoundActive,
        oneRoundRemainingCount = director.oneRoundRemainingMemberIds.size,
        members = enabledMembers.map { member ->
            val source = settings.assistants.find { it.id == member.assistantId }
            GroupDirectorMemberUi(
                id = member.id,
                name = member.displayName.ifBlank {
                    source?.name?.ifBlank { assistant.name } ?: assistant.name
                }.ifBlank { "?" },
                avatar = member.avatar,
                isQueuedNext = member.id == director.oneShotNextMemberId,
            )
        },
        canPause = director.playbackState != GroupPlaybackState.PAUSED,
        canContinueRound = !isGenerating && eligibleIds.isNotEmpty(),
        canSkip = eligibleIds.isNotEmpty(),
    )
}
```

- [x] **Step 4: Add command forwarding and typed notices to `ChatVM`**

Add imports:

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupDirectorCommandStatus
```

Add state and command forwarding below the existing manual-member methods:

```kotlin
private val _groupDirectorNotices = MutableSharedFlow<GroupDirectorCommandStatus>(
    extraBufferCapacity = 1,
)
val groupDirectorNotices = _groupDirectorNotices.asSharedFlow()

fun applyGroupDirectorCommand(command: GroupDirectorCommand) {
    viewModelScope.launch {
        val result = chatService.applyGroupDirectorCommand(_conversationId, command)
        if (result.status != GroupDirectorCommandStatus.APPLIED) {
            _groupDirectorNotices.emit(result.status)
        }
    }
}
```

- [x] **Step 5: Run mapper tests and compile the ViewModel bridge**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.pages.chat.GroupDirectorUiStateTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit the UI-state slice**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiState.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt app/src/test/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorUiStateTest.kt
git commit -m "feat: expose group director ui state"
```

---

### Task 5: Add Original-Style Director FAB and Bottom Sheet

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControls.kt`
- Create: `app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControlsTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt:292-462`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: Task 4 UI state and `ChatVM.applyGroupDirectorCommand`.
- Produces: group-only `GroupDirectorFab`, `GroupDirectorSheet`, resource-backed action labels, and effective-manual integration with `GroupMemberSelector`.

- [x] **Step 1: Add string resources before the composables**

Append these base resources before `</resources>` in `values/strings.xml`:

```xml
<string name="group_director_title">Director</string>
<string name="group_director_open">Open group director</string>
<string name="group_director_pause_after_current">Pause after current reply</string>
<string name="group_director_continue_round">Continue one round</string>
<string name="group_director_continue_current_round">Continue this round</string>
<string name="group_director_skip_next">Skip next speaker</string>
<string name="group_director_mode_manual">Manual</string>
<string name="group_director_mode_round_robin">Round robin</string>
<string name="group_director_mode_moderator">Moderator</string>
<string name="group_director_status_running">Running</string>
<string name="group_director_status_paused">Paused</string>
<string name="group_director_status_pause_pending">Pauses after this reply</string>
<string name="group_director_status_round_remaining">Round: %1$d remaining</string>
<string name="group_director_nominate_member">Make %1$s the next speaker</string>
<string name="group_director_no_members">No enabled group members</string>
<string name="group_director_invalid_member">That member is no longer available</string>
<string name="group_director_no_alternative">No other member is available</string>
<string name="group_director_not_group">This conversation is not a group chat</string>
```

Append these translations before `</resources>` in `values-zh/strings.xml`:

```xml
<string name="group_director_title">导演台</string>
<string name="group_director_open">打开群聊导演台</string>
<string name="group_director_pause_after_current">当前角色说完后暂停</string>
<string name="group_director_continue_round">继续一轮</string>
<string name="group_director_continue_current_round">继续本轮</string>
<string name="group_director_skip_next">跳过下一位</string>
<string name="group_director_mode_manual">手动</string>
<string name="group_director_mode_round_robin">轮询</string>
<string name="group_director_mode_moderator">主持人</string>
<string name="group_director_status_running">运行中</string>
<string name="group_director_status_paused">已暂停</string>
<string name="group_director_status_pause_pending">本条回复结束后暂停</string>
<string name="group_director_status_round_remaining">本轮剩余 %1$d 位</string>
<string name="group_director_nominate_member">指定%1$s下一位发言</string>
<string name="group_director_no_members">没有已启用的群组成员</string>
<string name="group_director_invalid_member">该角色已不可用</string>
<string name="group_director_no_alternative">暂无其他角色</string>
<string name="group_director_not_group">当前会话不是群聊</string>
```

- [x] **Step 2: Write failing Compose tests for the visible contract**

Create `GroupDirectorControlsTest.kt`:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class GroupDirectorControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pausedState = GroupDirectorUiState(
        effectiveMode = TurnTakingStrategy.AUTO_ROUND_ROBIN,
        playbackState = GroupPlaybackState.PAUSED,
        isGenerating = false,
        oneRoundActive = true,
        oneRoundRemainingCount = 2,
        members = emptyList(),
        canPause = false,
        canContinueRound = true,
        canSkip = false,
    )

    @Test
    fun fabUsesDirectorAccessibilityLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                GroupDirectorFab(state = pausedState, onClick = {})
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.group_director_open))
            .assertIsDisplayed()
    }

    @Test
    fun pausedRoundShowsContinueCurrentRoundAndDispatchesCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var command: GroupDirectorCommand? = null
        composeRule.setContent {
            MaterialTheme {
                GroupDirectorSheetContent(
                    state = pausedState,
                    onCommand = { command = it },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.group_director_continue_current_round))
            .performClick()
        assertEquals(GroupDirectorCommand.ContinueOneRound, command)
        composeRule
            .onNodeWithText(context.getString(R.string.group_director_status_round_remaining, 2))
            .assertIsDisplayed()
    }

    @Test
    fun memberNominationDispatchesExactlyOneOneShotCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memberId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        var commands = emptyList<GroupDirectorCommand>()
        val state = pausedState.copy(
            oneRoundActive = false,
            oneRoundRemainingCount = 0,
            members = listOf(
                GroupDirectorMemberUi(memberId, "Aileen", Avatar.Dummy, isQueuedNext = false)
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                GroupDirectorSheetContent(
                    state = state,
                    onCommand = { commands = commands + it },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.group_director_nominate_member, "Aileen")
            )
            .performClick()
        assertEquals(listOf(GroupDirectorCommand.QueueMemberOnce(memberId)), commands)
    }
}
```

- [x] **Step 3: Run the Compose test and confirm the controls are absent**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.GroupDirectorControlsTest" --console=plain
```

Expected: compilation fails because `GroupDirectorFab` and `GroupDirectorSheetContent` do not exist.

- [x] **Step 4: Implement the themed FAB and bottom-sheet content**

Create `GroupDirectorControls.kt` with the following public composables and no fixed colors:

```kotlin
package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.UserGroup03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.TurnTakingStrategy
import me.rerere.rikkahub.service.group.GroupDirectorCommand
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.ui.components.ui.UIAvatar

@Composable
fun GroupDirectorFab(
    state: GroupDirectorUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = when (state.playbackState) {
                    GroupPlaybackState.PAUSED -> HugeIcons.Play
                    GroupPlaybackState.PAUSE_AFTER_CURRENT -> HugeIcons.Pause
                    GroupPlaybackState.RUNNING -> HugeIcons.UserGroup03
                },
                contentDescription = stringResource(R.string.group_director_open),
            )
            if (state.oneRoundActive) {
                Text(
                    text = state.oneRoundRemainingCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun GroupDirectorSheet(
    state: GroupDirectorUiState,
    onDismiss: () -> Unit,
    onCommand: (GroupDirectorCommand) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        GroupDirectorSheetContent(
            state = state,
            onCommand = onCommand,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
        )
    }
}

@Composable
fun GroupDirectorSheetContent(
    state: GroupDirectorUiState,
    onCommand: (GroupDirectorCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        TurnTakingStrategy.MANUAL to R.string.group_director_mode_manual,
        TurnTakingStrategy.AUTO_ROUND_ROBIN to R.string.group_director_mode_round_robin,
        TurnTakingStrategy.AUTO_MODERATOR to R.string.group_director_mode_moderator,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.group_director_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = when {
                state.oneRoundActive -> stringResource(
                    R.string.group_director_status_round_remaining,
                    state.oneRoundRemainingCount,
                )
                state.playbackState == GroupPlaybackState.PAUSED ->
                    stringResource(R.string.group_director_status_paused)
                state.playbackState == GroupPlaybackState.PAUSE_AFTER_CURRENT ->
                    stringResource(R.string.group_director_status_pause_pending)
                else -> stringResource(R.string.group_director_status_running)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { onCommand(GroupDirectorCommand.PauseAfterCurrent) },
                enabled = state.canPause,
                modifier = Modifier.weight(1f),
            ) {
                Icon(HugeIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.group_director_pause_after_current))
            }
            FilledTonalButton(
                onClick = { onCommand(GroupDirectorCommand.ContinueOneRound) },
                enabled = state.canContinueRound,
                modifier = Modifier.weight(1f),
            ) {
                Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(
                        if (state.oneRoundActive) {
                            R.string.group_director_continue_current_round
                        } else {
                            R.string.group_director_continue_round
                        }
                    )
                )
            }
        }
        FilledTonalButton(
            onClick = { onCommand(GroupDirectorCommand.SkipNext) },
            enabled = state.canSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Next, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.group_director_skip_next))
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.effectiveMode == mode,
                    onClick = { onCommand(GroupDirectorCommand.SetMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(stringResource(label))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.members, key = { it.id.toString() }) { member ->
                val nominateDescription = stringResource(
                    R.string.group_director_nominate_member,
                    member.name,
                )
                Surface(
                    onClick = { onCommand(GroupDirectorCommand.QueueMemberOnce(member.id)) },
                    modifier = Modifier.semantics {
                        contentDescription = nominateDescription
                    },
                    shape = MaterialTheme.shapes.large,
                    color = if (member.isQueuedNext) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = if (member.isQueuedNext) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        UIAvatar(
                            name = member.name,
                            value = member.avatar,
                            modifier = Modifier.size(32.dp),
                            onClick = { onCommand(GroupDirectorCommand.QueueMemberOnce(member.id)) },
                        )
                        Text(member.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
```

- [x] **Step 5: Integrate the controls into `ChatPage` and use the effective mode**

At the start of `ChatPageContent`, derive the group assistant and state once:

```kotlin
val groupAssistant = assistant.takeIf { it.assistantType == AssistantType.GROUP }
val directorUiState = remember(conversation, groupAssistant, setting, loadingJob) {
    groupAssistant?.let {
        buildGroupDirectorUiState(
            conversation = conversation,
            assistant = it,
            settings = setting,
            isGenerating = loadingJob?.isActive == true,
        )
    }
}
var showDirectorSheet by rememberSaveable { mutableStateOf(false) }
```

Collect typed notices with existing `LocalToaster`:

```kotlin
val context = LocalContext.current
LaunchedEffect(vm) {
    vm.groupDirectorNotices.collect { status ->
        val message = when (status) {
            GroupDirectorCommandStatus.NO_ENABLED_MEMBERS -> R.string.group_director_no_members
            GroupDirectorCommandStatus.INVALID_MEMBER -> R.string.group_director_invalid_member
            GroupDirectorCommandStatus.NO_ALTERNATIVE_MEMBER -> R.string.group_director_no_alternative
            GroupDirectorCommandStatus.NOT_GROUP -> R.string.group_director_not_group
            GroupDirectorCommandStatus.APPLIED -> return@collect
        }
        toaster.show(context.getString(message))
    }
}
```

Add the group-only FAB to the existing `Scaffold`:

```kotlin
floatingActionButton = {
    directorUiState?.let { state ->
        GroupDirectorFab(
            state = state,
            onClick = { showDirectorSheet = true },
        )
    }
},
```

Move `ga`, enabled members, and available IDs above the `Scaffold` so both the bottom bar and director mapper use one assistant instance. Replace the old default-only manual check with:

```kotlin
val isGrp = groupAssistant != null &&
    directorUiState?.effectiveMode == TurnTakingStrategy.MANUAL &&
    enabledManualMembers.isNotEmpty()
```

After the `Scaffold`, render the sheet:

```kotlin
if (showDirectorSheet && directorUiState != null) {
    GroupDirectorSheet(
        state = directorUiState,
        onDismiss = { showDirectorSheet = false },
        onCommand = vm::applyGroupDirectorCommand,
    )
}
```

Reuse the existing `LocalContext` import and add imports for `GroupDirectorCommandStatus` and the new controls. Keep the existing `ChatInput`, send/cancel affordance, and manual `GroupMemberSelector` layout intact.

- [x] **Step 6: Run Compose tests, resource validation, and Kotlin compilation**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.pages.chat.GroupDirectorControlsTest" --console=plain
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`; FAB accessibility, paused-round action, and command dispatch tests pass.

- [x] **Step 7: Commit the original-style UI slice**

```powershell
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControls.kt app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/androidTest/java/me/rerere/rikkahub/ui/pages/chat/GroupDirectorControlsTest.kt
git commit -m "feat: add group director controls"
```

---

### Task 6: Full Regression, Emulator Smoke, and Result Recording

**Files:**
- Modify: `docs/superpowers/plans/2026-07-16-group-chat-director-controls-plan.md`

**Interfaces:**
- Consumes: completed Tasks 1-5.
- Produces: verified APK, migration/Compose evidence, emulator evidence, and an updated result block in this plan.

- [x] **Step 1: Run the complete JVM suite and Debug build**

Run:

```powershell
.\gradlew.bat test :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-universal-debug.apk` exists and is newer than the last source commit.

- [x] **Step 2: Run all connected instrumentation tests**

Run:

```powershell
adb -s emulator-5554 wait-for-device
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Expected: `BUILD SUCCESSFUL`; migration and director Compose tests pass on `emulator-5554`.

- [x] **Step 3: Install and launch the universal Debug APK**

Run:

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s emulator-5554 shell monkey -p me.rerere.rikkahub.debug -c android.intent.category.LAUNCHER 1
adb -s emulator-5554 shell dumpsys window | Select-String 'mCurrentFocus|mFocusedApp'
```

Expected: install reports `Success`; focused package is `me.rerere.rikkahub.debug`.

- [x] **Step 4: Perform the exact manual smoke matrix**

Use an existing group assistant with at least three enabled members and record each row as `PASS` or `FAIL` in the result block:

1. Open group chat: director FAB is visible, themed, and does not cover send/cancel.
2. Open sheet: Material 3 drag handle, title, actions, segmented modes, and avatars match current light/dark theme.
3. During a reply, tap `说完暂停`: current reply completes, pending status appears, and no next automatic reply starts.
4. While paused, tap `继续一轮`: every snapshot member replies at most once and the round returns to paused.
5. In a moderator round, accept early `STOP`: remaining auto replies stop and status becomes paused.
6. Tap `跳过下一位`: the following valid queue member speaks; a one-member group shows `暂无其他角色`.
7. While paused, nominate one avatar: that member replies once and the conversation returns to paused.
8. Switch among manual, round-robin, and moderator: only this conversation changes; manual reveals the existing member selector.
9. Leave and reopen the chat, then force-stop/relaunch the app: mode, paused state, queued next member, and round remainder restore without implicit generation.
10. Open a non-group conversation: director FAB is absent.

- [x] **Step 5: Verify a clean crash buffer after the smoke**

Run:

```powershell
adb -s emulator-5554 logcat -d -b crash
```

Expected: no new crash entry for `me.rerere.rikkahub.debug`.

- [x] **Step 6: Record final evidence in this plan**

Append this block and replace each result value with the observed command result or manual status:

```markdown
## Implementation Results

- JVM tests and Debug APK: PASS
- Connected instrumentation: PASS
- Migration 26-to-27: PASS
- Director FAB and original visual style: PASS
- Graceful pause: PASS
- One-round and moderator STOP: PASS
- Skip-next and single-member notice: PASS
- One-shot nomination: PASS
- Conversation-only mode override: PASS
- Page/process restoration: PASS
- Non-group visibility guard: PASS
- Crash buffer: PASS
```

If one row fails, record the exact observed behavior and keep Task 6 open until the corresponding focused test and implementation are corrected.

- [x] **Step 7: Check the final diff and commit verification evidence**

Run:

```powershell
git diff --check
git status --short
git add docs/superpowers/plans/2026-07-16-group-chat-director-controls-plan.md
git commit -m "docs: record group director verification"
```

Expected: `git diff --check` prints nothing; the commit succeeds; no build output or local configuration file is staged.

---

## Implementation Results

Verified on `emulator-5554` (Android 15 / API 35) on 2026-07-16. Full evidence and exact commands are recorded in `.superpowers/sdd/director-task-6-report.md`. After the configured external provider returned HTTP 402, the five successful-output-dependent rows were rerun against a deterministic local OpenAI-compatible fixture. The fixture configuration was then restored byte-for-byte and its server stopped.

- JVM tests and Debug APK: **PASS** — `BUILD SUCCESSFUL`; rebuilt universal APK timestamp `2026-07-16T04:57:06.9124708+08:00`, SHA-256 `94B0EA6FF1DE70E4CD6FD5C6C0F695104975A64EA75620A9DD797A01CF64C802`.
- Connected instrumentation: **PASS** — `BUILD SUCCESSFUL`; app instrumentation 13 tests, 0 failures/errors/skips.
- Migration 26-to-27: **PASS** — focused migration instrumentation 1/1.
- Director FAB and original visual style: **PASS** — themed non-overlapping FAB plus Material 3 sheet/handle/actions/modes/three avatars verified by UI tree and screenshot.
- Graceful pause: **PASS** — the review rerun used a delayed four-chunk stream; `21-pending-status.xml/png` captured the complete in-flight status `本条回复结束后暂停`, `22-complete-paused.xml/png` captured the completed `已暂停` state, `22-request-count.txt` recorded one request/one completion/zero extra request, and `23-room-final.json` recorded `playbackState=PAUSED`.
- One-round and moderator STOP: **PASS** — one round produced `QA B`, `QA Member`, and `Q AA` exactly once then paused; moderator UUID -> member -> `STOP` produced no remaining auto reply.
- Skip-next and single-member notice: **PASS** — skip was consumed and a following valid member completed a successful reply; one-member `暂无其他角色` notice remains screenshot-confirmed.
- One-shot nomination: **PASS** — paused nomination produced exactly one successful `QA B` stream and returned to paused with no pending one-shot.
- Conversation-only mode override: **PASS** — saved conversation persisted moderator override while a new same-group conversation stayed manual and exposed the existing member selector.
- Page/process restoration: **PASS** — page reopen and force-stop/relaunch restored moderator, paused round, queued member/cursor, and three-member remainder; corrected app-PID logcat showed zero implicit generation starts.
- Non-group visibility guard: **PASS** — solo conversation UI dump contained zero director label/title matches.
- Crash buffer: **PASS** — final crash buffer empty and app remained focused in `RouteActivity`.

Task 6 is complete: every required command is green, all ten emulator smoke rows are PASS, the crash buffer is clean, and temporary fixture configuration was restored.

---

## Final Review Hardening

The final branch review identified two orchestration barriers and one migration-test gap. They were closed on 2026-07-16 without changing the original Material 3 UI:

- **Manual barrier: PASS** — switching to manual during an active reply now records `PAUSE_AFTER_CURRENT`; the current reply finishes, then automatic chaining stops. If a one-round run is active, its remaining snapshot is retained in `PAUSED` state for explicit `继续一轮`. Manual mode no longer supplies an automatic speaker candidate, including the completion-window case where the previous handoff had already approved continuation.
- **Cancellation normalization: PASS** — cancellation during member streaming, pending pause, one-round generation, or moderator selection before a member is committed now persists `PAUSED`, releases the generation/reply phase, retains an unfinished round remainder, clears transient one-shot/skip commands, and rethrows `CancellationException`.
- **Legacy-row migration coverage: PASS** — the 26-to-27 instrumentation test now inserts a valid version-26 conversation before migration and verifies that the existing row receives `group_runtime_state='{}'`.

Validation:

- Focused red/green cycles: missing `afterCancellation`, missing cancellation handoff normalization, and missing manual no-candidate normalization each failed before implementation.
- `:app:testDebugUnitTest` filtered to `ChatServiceTest` and `service.group.*`: **PASS**, 90 tests, 0 failures/errors.
- Focused `Migration_26_27_Test` on `emulator-5554`: **PASS**, 1 test.
- `:app:compileDebugKotlin -x :web:buildWebUi`: **PASS**.
- `git diff --check`: **PASS**.

---

## Completion Gate

The feature is complete only when all six tasks are checked, every command in Task 6 is green, the ten-row emulator matrix is recorded, the crash buffer is clean, and the branch contains no uncommitted production or test change.

**Completion gate: PASS (2026-07-16).** Tasks 1-6 and all 47 implementation steps are checked; the final-review manual/cancellation barriers are covered and green; the required JVM/build and connected-instrumentation commands are green; all ten emulator rows are recorded as PASS; the final crash buffer is empty; and the completed hardening pass leaves no uncommitted production or test change.
