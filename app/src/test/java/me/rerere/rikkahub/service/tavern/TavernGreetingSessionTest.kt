package me.rerere.rikkahub.service.tavern

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.TavernCharacterCard
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import me.rerere.rikkahub.data.model.tavernOpeningRef
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeController
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimePermissionStore
import me.rerere.rikkahub.ui.components.richtext.runtime.TavernRuntimeRequest
import me.rerere.rikkahub.ui.pages.tavern.empty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernGreetingSessionTest {

    private val card = TavernCharacterCard.empty().copy(
        name = "Aster",
        firstMes = "<h1>First</h1>",
        alternateGreetings = listOf("<h1>Second</h1>", "<h1>Third</h1>"),
    )

    @Test
    fun `every greeting receives an independent writable overlay`() {
        val session = greetingSession(card = card)
        val first = session.candidates[0]
        val second = session.candidates[1]

        first.runtime.setVariable(TavernGreetingVariableScope.CHAT, "hp", JsonPrimitive(7))
        first.runtime.setVariable(TavernGreetingVariableScope.GLOBAL, "route", JsonPrimitive("left"))
        first.runtime.upsertWorldEntry(buildJsonObject { put("id", "door"); put("content", "open") })
        first.runtime.updateOpening(UIMessage.assistantHtml("changed"))
        first.runtime.registerMacro("first_only", "() => 'one'")

        assertEquals(JsonPrimitive(7), first.overlay().chatVariables["hp"])
        assertEquals(JsonPrimitive("left"), first.overlay().globalVariables["route"])
        assertEquals("open", first.overlay().worldEntries.single()["content"]?.let { (it as JsonPrimitive).content })
        assertEquals("changed", first.overlay().messages.single().toText())
        assertTrue("first_only" in first.overlay().registrations.macros)

        assertNull(second.overlay().chatVariables["hp"])
        assertNull(second.overlay().globalVariables["route"])
        assertTrue(second.overlay().worldEntries.isEmpty())
        assertEquals("<h1>Second</h1>", second.overlay().messages.single().toText())
        assertFalse("first_only" in second.overlay().registrations.macros)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `candidate bindings journal real runtime mutations without touching another candidate`() {
        val session = greetingSession(card = card)
        val first = session.candidates[0]
        val second = session.candidates[1]
        val bindings = first.runtime.runtimeBindings()
        val controller = TavernRuntimeController(
            conversationId = session.conversationId,
            worldRepository = bindings.worldRepository,
            variableGateway = bindings.variableGateway,
            scriptRegistry = bindings.scriptRegistry,
            registrationObserver = bindings.registrationObserver,
            currentMessageWriter = bindings.currentMessageWriter,
            permissionStore = TavernRuntimePermissionStore(TavernRuntimePermissions()),
        )

        assertTrue(controller.dispatch(request("variables.set", "scope" to "chat", "key" to "hp", "value" to 9)).ok)
        assertTrue(controller.dispatch(request("variables.set", "scope" to "global", "key" to "path", "value" to "A")).ok)
        assertTrue(controller.dispatch(request("world.upsertEntry", "entry" to buildJsonObject {
            put("id", "gate")
            put("content", "unlocked")
        })).ok)
        assertTrue(controller.dispatch(request("messages.updateCurrent", "patch" to JsonPrimitive("rewritten"))).ok)
        assertTrue(controller.dispatch(request("macros.register", "name" to "route", "source" to "() => 'A'")).ok)
        assertTrue(controller.dispatch(request("slash.register", "name" to "choose", "source" to "() => 'A'")).ok)
        assertTrue(controller.dispatch(request("sendHook.register", "source" to "() => 'hooked'")).ok)

        val overlay = first.overlay()
        assertEquals(JsonPrimitive(9), overlay.chatVariables["hp"])
        assertEquals(JsonPrimitive("A"), overlay.globalVariables["path"])
        assertEquals("unlocked", (overlay.worldEntries.single()["content"] as JsonPrimitive).content)
        assertEquals("rewritten", overlay.messages.single().toText())
        assertTrue("route" in overlay.registrations.macros)
        assertTrue("choose" in overlay.registrations.slashCommands)
        assertEquals("() => 'hooked'", overlay.registrations.sendHookSource)
        assertNull(second.overlay().chatVariables["hp"])
        assertTrue(second.overlay().worldEntries.isEmpty())
    }

    @Test
    fun `candidate overlay flow publishes runtime mutations for context repush`() {
        val candidate = greetingSession(card = card).candidates.first()
        val before = candidate.runtime.overlayFlow.value

        candidate.runtime.setVariable(TavernGreetingVariableScope.CHAT, "hp", JsonPrimitive(8))

        assertNotEquals(before, candidate.runtime.overlayFlow.value)
        assertEquals(JsonPrimitive(8), candidate.runtime.overlayFlow.value.chatVariables["hp"])
    }

    @Test
    fun `global and world commit state is an operation journal not a stale snapshot`() {
        val candidate = greetingSession(card = card).candidates.first()
        candidate.runtime.setVariable(TavernGreetingVariableScope.GLOBAL, "route", JsonPrimitive("left"))
        candidate.runtime.setVariable(TavernGreetingVariableScope.GLOBAL, "obsolete", JsonPrimitive("old"))
        candidate.runtime.deleteVariable(TavernGreetingVariableScope.GLOBAL, "obsolete")
        candidate.runtime.upsertWorldEntry(buildJsonObject { put("id", "door"); put("content", "open") })
        candidate.runtime.upsertWorldEntry(buildJsonObject { put("id", "removed"); put("content", "old") })
        candidate.runtime.deleteWorldEntry("removed")

        val snapshot = candidate.snapshot()

        assertEquals(JsonPrimitive("left"), snapshot.journal.globalVariables["route"])
        assertTrue(snapshot.journal.globalVariables.containsKey("obsolete"))
        assertNull(snapshot.journal.globalVariables["obsolete"])
        assertEquals("open", (snapshot.journal.worldUpserts["door"]?.get("content") as JsonPrimitive).content)
        assertTrue("removed" in snapshot.journal.worldDeletes)

        val concurrentlyChanged = buildJsonObject {
            put("route", "old")
            put("unrelated", "preserved")
            put("obsolete", "delete me")
        }
        val rebased = rebaseGreetingGlobalVariables(concurrentlyChanged, snapshot.journal)
        assertEquals(JsonPrimitive("left"), rebased["route"])
        assertEquals(JsonPrimitive("preserved"), rebased["unrelated"])
        assertNull(rebased["obsolete"])
    }

    @Test
    fun `failed deletes do not become future destructive mutations`() {
        val candidate = greetingSession(card = card).candidates.first()

        assertFalse(candidate.runtime.deleteVariable(TavernGreetingVariableScope.GLOBAL, "future"))
        assertFalse(candidate.runtime.deleteWorldEntry("future-world"))
        val journal = candidate.snapshot().journal

        assertFalse(journal.globalVariables.containsKey("future"))
        assertFalse("future-world" in journal.worldDeletes)
    }

    @Test
    fun `selection freezes candidate writes and failed commit reopens the journal`() = runBlocking {
        lateinit var selected: TavernGreetingCandidate
        val session = greetingSession(card = card) { candidate ->
            selected.runtime.setVariable(TavernGreetingVariableScope.CHAT, "late", JsonPrimitive(1))
            error("fail")
        }
        selected = session.candidates.first()

        assertThrows(IllegalStateException::class.java) { runBlocking { session.commit(selected.id) } }
        assertNull(selected.overlay().chatVariables["late"])
        selected.runtime.setVariable(TavernGreetingVariableScope.CHAT, "retry", JsonPrimitive(2))
        assertEquals(JsonPrimitive(2), selected.overlay().chatVariables["retry"])
    }

    @Test
    fun `selected candidate is committed once and all unselected candidates are discarded`() = runBlocking {
        val commits = mutableListOf<TavernGreetingCandidateSnapshot>()
        val session = greetingSession(card = card) { candidate -> commits += candidate }
        val chosen = session.candidates[2]
        chosen.runtime.setVariable(TavernGreetingVariableScope.CHAT, "route", JsonPrimitive("third"))

        val committed = session.commit(chosen.id)

        assertEquals(chosen.id, committed.id)
        assertEquals(listOf(chosen.id), commits.map { it.id })
        assertEquals(JsonPrimitive("third"), commits.single().overlay.chatVariables["route"])
        assertEquals(chosen.id, session.committedCandidateId)
        assertTrue(session.candidates.isEmpty())
        assertTrue(session.isLocked)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.commit(chosen.id) }
        }
        Unit
    }

    @Test
    fun `native send cannot commit a candidate before its runtime is ready`() = runBlocking {
        val session = greetingSession(card = card, ready = false)
        val candidate = session.candidates.first()

        assertThrows(IllegalStateException::class.java) { runBlocking { session.commitSelected() } }
        assertFalse(session.isLocked)
        session.markCandidateReady(candidate.id)
        assertEquals(candidate.id, session.commitSelected().id)
    }

    @Test
    fun `commit request closes native send window before asynchronous persistence starts`() {
        val session = greetingSession(card = card)
        val candidate = session.candidates.first()
        assertTrue(session.isSelectedCandidateReady())

        assertTrue(session.requestCommit(candidate.id))
        assertFalse(session.requestCommit(candidate.id))

        assertFalse(session.isSelectedCandidateReady())
    }

    @Test
    fun `failed commit keeps every candidate and leaves session unlocked`() = runBlocking {
        val session = greetingSession(card = card) { error("disk failed") }
        val ids = session.candidates.map { it.id }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.commit(ids[1]) }
        }

        assertEquals(ids, session.candidates.map { it.id })
        assertNull(session.committedCandidateId)
        assertFalse(session.isLocked)
        Unit
    }

    @Test
    fun `first user send commits the candidate currently selected in the stage`() = runBlocking {
        val commits = mutableListOf<TavernGreetingCandidateSnapshot>()
        val session = greetingSession(card = card) { commits += it }
        val selected = session.candidates[1]

        session.selectCandidate(selected.id)
        val committed = session.commitSelected()

        assertEquals(selected.id, committed.id)
        assertEquals(listOf(selected.id), commits.map { it.id })
        assertTrue(session.isLocked)
        Unit
    }

    @Test
    fun `conversation with a user message is locked and greeting change requires a new conversation`() {
        val conversation = conversation(messages = listOf(UIMessage.assistantHtml("hello"), UIMessage.user("hi")))

        val session = greetingSession(card = card, conversation = conversation)

        assertTrue(session.isLocked)
        assertTrue(requiresNewConversationForGreetingChange(conversation))
        assertThrows(TavernGreetingLockedException::class.java) {
            runBlocking { session.commit(session.candidates.first().id) }
        }
    }

    @Test
    fun `opening messages carry typed index content and card fingerprints`() {
        val session = greetingSession(card = card)

        session.candidates.forEachIndexed { index, candidate ->
            val text = candidate.overlay().messages.single().parts.single() as UIMessagePart.Text
            val ref = text.tavernOpeningRef()
            assertEquals(index, ref?.greetingIndex)
            assertEquals(64, ref?.contentFingerprint?.length)
            assertEquals(session.candidates.first().openingRef.cardFingerprint, ref?.cardFingerprint)
        }
    }

    @Test
    fun `navigation prefers valid index and retains legacy base64 compatibility`() {
        val greetings = card.allGreetings()
        val encodedThird = Base64.getEncoder().encodeToString(greetings[2].toByteArray())

        assertEquals(1, resolveGreetingNavigation(1, encodedThird, greetings)?.greetingIndex)
        assertEquals(2, resolveGreetingNavigation(null, encodedThird, greetings)?.greetingIndex)
        assertEquals(greetings[2], resolveGreetingNavigation(null, encodedThird, greetings)?.legacyGreeting)
        assertNull(resolveGreetingNavigation(99, null, greetings))
        assertNull(resolveGreetingNavigation(null, "not base64", greetings))
    }

    @Test
    fun `new conversation request preserves assistant and selected greeting index`() {
        val assistantId = Uuid.random()

        val request = TavernGreetingConversationRequest(assistantId, greetingIndex = 2)

        assertEquals(assistantId, request.assistantId)
        assertEquals(2, request.greetingIndex)
        assertNotEquals(Uuid.NIL, request.conversationId)
    }

    @Test
    fun `committed overlay replaces only an unlocked opening and preserves other preset messages`() {
        val session = greetingSession(card = card)
        val oldOpening = session.candidates[0].overlay().messages.single()
        val system = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("rules")))
        val base = conversation(messages = listOf(system, oldOpening))
        val chosen = session.candidates[1].snapshot()

        val merged = mergeCommittedGreeting(base, chosen)

        assertEquals(listOf(system.id, chosen.overlay.messages.single().id), merged.currentMessages.map { it.id })
        assertEquals(chosen.overlay.chatVariables, merged.statusVariables)
        assertEquals(1, (merged.currentMessages.last().parts.single() as UIMessagePart.Text).tavernOpeningRef()?.greetingIndex)
        assertTrue(
            (merged.currentMessages.last().parts.single() as UIMessagePart.Text).metadata
                ?.containsKey("runtimeState") == true,
        )
    }

    private fun greetingSession(
        card: TavernCharacterCard,
        conversation: Conversation = conversation(),
        ready: Boolean = true,
        commit: suspend (TavernGreetingCandidateSnapshot) -> Unit = {},
    ): TavernGreetingSession = TavernGreetingSession.create(
        conversation = conversation,
        card = card,
        initialChatVariables = buildJsonObject { put("base", true) },
        initialGlobalVariables = JsonObject(emptyMap()),
        initialWorldEntries = emptyList(),
        commitTarget = TavernGreetingCommitTarget(commit),
    ).also { session -> if (ready) session.candidates.forEach { it.runtime.markReady() } }

    private fun conversation(messages: List<UIMessage> = emptyList()): Conversation = Conversation.ofId(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messages = messages.map { me.rerere.rikkahub.data.model.MessageNode.of(it) },
    )

    private fun request(method: String, vararg values: Pair<String, Any>): TavernRuntimeRequest =
        TavernRuntimeRequest(
            id = Uuid.random().toString(),
            method = method,
            params = buildJsonObject {
                values.forEach { (key, value) ->
                    when (value) {
                        is JsonObject -> put(key, value)
                        is JsonPrimitive -> put(key, value)
                        is Int -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            },
        )
}
