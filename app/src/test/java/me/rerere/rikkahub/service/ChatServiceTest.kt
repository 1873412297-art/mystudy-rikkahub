package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.group.GroupDirectorState
import me.rerere.rikkahub.service.group.GroupPlaybackState
import me.rerere.rikkahub.service.group.GroupRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `session director lock serializes state commits`() = runBlocking {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        try {
            List(8) {
                async(Dispatchers.Default) {
                    session.withGroupDirectorLock {
                        val current = active.incrementAndGet()
                        maximumActive.accumulateAndGet(current, ::maxOf)
                        delay(10)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        } finally {
            scope.cancel()
        }

        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `completed superseded job does not clear current generation`() = runBlocking {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ConversationSession(
            id = conversationId,
            initial = Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        val oldJob = scope.launch {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    allowCompletion.await()
                }
            }
        }
        started.await()
        session.setJob(oldJob)
        val currentJob = Job()

        try {
            session.setJob(currentJob)
            allowCompletion.complete(Unit)
            oldJob.join()

            assertSame(currentJob, session.getJob())
        } finally {
            currentJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `preset message macros are expanded without losing html render mode`() {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val assistant = Assistant(name = "Alice")
        val settings = Settings(
            displaySetting = DisplaySetting(userNickname = "Bob"),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val messages = listOf(
            UIMessage.assistantHtml("<main>{{user}} meets {{char}} on {{model_name}}</main>")
        )

        val rendered = renderPresetMessageMacros(
            messages = messages,
            settings = settings,
            assistant = assistant,
            model = model,
        )

        val text = rendered.single().parts.single() as UIMessagePart.Text
        assertEquals("<main>Bob meets Alice on Test Model</main>", text.text)
        assertEquals(UIMessagePart.RenderMode.HTML, text.renderMode)
    }

    @Test
    fun `generation start keeps group speaker state from resolved conversation`() {
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000010")
        val memberA = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val memberB = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val initial = Conversation(
            assistantId = assistantId,
            messageNodes = emptyList(),
            chatSuggestions = listOf("stale suggestion"),
        )
        val resolved = initial.copy(
            activeGroupMemberId = memberA,
            groupMemberQueue = listOf(memberA, memberB),
            groupMemberQueueIndex = 1,
        )

        val result = conversationAtGenerationStart(
            initialConversation = initial,
            resolvedConversation = resolved,
        )

        assertEquals(emptyList<String>(), result.chatSuggestions)
        assertEquals(memberA, result.activeGroupMemberId)
        assertEquals(listOf(memberA, memberB), result.groupMemberQueue)
        assertEquals(1, result.groupMemberQueueIndex)
    }

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
}
