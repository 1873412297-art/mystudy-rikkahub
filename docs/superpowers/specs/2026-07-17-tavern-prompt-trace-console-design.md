# Tavern Prompt Trace Console Design — Phase A1

**Date:** 2026-07-17
**Branch baseline:** `private-main` at `f771041b`
**Status:** Approved during interactive design review

## Goal

Add a read-only Tavern prompt diagnostics console to RikkaHub. For every model request made in a Tavern-backed solo conversation or an eligible Tavern group conversation, record the semantic messages that actually reached the provider boundary, the mode/lorebook injections that contributed to them, their source breakdown, and the returned prompt-token usage.

The feature must fit RikkaHub's existing `Assistant -> Conversation -> MessageNode -> UIMessage` architecture. It must not create a parallel character library, duplicate the chat system, or alter generation behavior.

## Roadmap Position

The user requested the Tavern work in three sequential stages:

1. **A — Prompt diagnostics**
   - **A1, this specification:** persisted snapshots of requests that were actually sent.
   - **A2, after A1 is implemented and verified:** simulated preview for the current draft before sending.
2. **B — Conversation-level story controls**
   - Scene switching, greeting selection, conversation variables, and reversible lorebook controls.
3. **C — Integrated Tavern console**
   - Combine A and B into one complete operational surface and add the remaining runtime diagnostics.

Only A1 is implemented under this specification. The A2 tab may display an explicit coming-soon state so the page structure remains stable, but it does not simulate or persist a draft prompt yet.

## Confirmed Product Decisions

1. The entry point is a persistent Tavern icon in the existing chat top bar, next to the current assistant or group title.
2. The console is a dedicated full-screen Compose page, not a bottom sheet.
3. The console records both Tavern solo chats and group chats whose enabled source members include at least one Tavern character card.
4. Snapshots bind to the exact generated reply branch rather than only to a conversation or message node.
5. Each conversation retains its 20 newest provider-call snapshots. The oldest entries are removed after a new snapshot is stored.
6. Final text and semantic message order are preserved exactly. Binary payloads and credentials are excluded.
7. The provider-returned prompt-token count is the authoritative total. Per-section token counts are clearly labeled model-agnostic estimates.
8. Trace data is stored in an independent Room table instead of being embedded in `Conversation`, `MessageNode`, or `UIMessage`.
9. Trace capture is observational. Any tracing or persistence failure must leave normal generation unaffected.

## Scope

### Included

- A top-bar Tavern diagnostics entry for eligible conversations.
- A full-screen `TavernPromptConsole` route.
- One persisted trace per provider invocation, including multi-step tool runs.
- Trace lifecycle states for prepared, streaming, completed, cancelled, and failed calls.
- Exact semantic messages immediately before `Provider.streamText()` or `Provider.generateText()`.
- Structured source sections for assistant/card prompt, conversation prompt override, memory, tool prompt text, group layered context, injections, history, and current user input.
- Structured mode-injection and lorebook-hit provenance.
- Actual prompt-token usage when returned by the provider.
- Model-agnostic estimated tokens for sections and messages.
- Attachment references without copying base64 or binary data.
- Branch-aware history selection, retention, cleanup, database migration, tests, and emulator verification.

### Excluded

- Sending a draft through a simulated transform pipeline.
- Editing lorebooks, prompts, scenes, greetings, variables, or runtime permissions.
- Displaying provider HTTP bodies, custom headers, API keys, cookies, or authentication state.
- Capturing provider-private protocol objects or reasoning signatures.
- Copying traces when a conversation is forked.
- Exporting traces inside Tavern JSON or PNG character cards.
- A second message-action entry point; the approved entry is the chat top bar.
- Refactoring unrelated Tavern rendering, status blocks, group scheduling, or provider implementations.

## Eligibility

Phase A1 uses a narrow, deterministic definition of a Tavern conversation:

- **Solo:** the active assistant has non-null `tavernCardJson`.
- **Group:** at least one enabled `GroupMember` resolves to a source assistant with non-null `tavernCardJson`.

`statusRenderJs`, generic HTML rendering, lorebook selection alone, or globally enabled Tavern runtime permissions do not make a conversation eligible in A1 because the current model has no explicit per-assistant Tavern-runtime flag. Stage C may broaden this rule after introducing an explicit capability model.

The helper that evaluates eligibility is shared by the chat top bar and trace creation so the UI and data pipeline cannot disagree.

## Architecture

### High-level data flow

```text
ChatService resolves conversation, assistant, and group speaker
  -> creates PromptTraceSeed for eligible Tavern generation
  -> GenerationHandler begins provider step N
  -> builds assistant/conversation system prompt, memory, and tool prompt text
  -> records source sections in a side-channel PromptTraceSession
  -> runs InputMessageTransformers with the same trace session
       -> PromptInjectionTransformer records structured injection matches
  -> captures final semantic UIMessage list at the provider boundary
  -> persists PREPARED trace
  -> provider starts returning a response
  -> binds trace to the newly generated UIMessage.id and marks STREAMING
  -> merges returned prompt-token usage
  -> marks COMPLETED, CANCELLED, or FAILED
  -> retention cleanup keeps the newest 20 traces for the conversation
```

Tracing uses a side channel. No diagnostic marker is added to text sent to a model, persisted conversation messages, tool schemas, or character-card JSON.

### Core units

Create focused files instead of expanding `ChatService.kt` or `GenerationHandler.kt` with UI and serialization details:

- `data/ai/trace/PromptTraceModels.kt`
  - Serializable trace payload, sections, hits, semantic parts, attachment references, statuses, and seed.
- `data/ai/trace/PromptTraceSession.kt`
  - Per-provider-call in-memory collector.
- `data/ai/trace/PromptTraceSanitizer.kt`
  - Converts `UIMessage` parts into persistent diagnostic parts and strips binary/private metadata.
- `data/ai/trace/PromptTokenEstimator.kt`
  - Deterministic local estimates labeled as approximate.
- `data/db/entity/PromptTraceEntity.kt`
  - Room persistence row.
- `data/db/dao/PromptTraceDAO.kt`
  - Queries, lifecycle updates, cleanup, and retention.
- `data/repository/PromptTraceRepository.kt`
  - JSON mapping, failure isolation, branch lookup, and user-facing history operations.
- `ui/pages/tavern/console/TavernPromptConsolePage.kt`
  - Full-screen console.
- `ui/pages/tavern/console/TavernPromptConsoleVM.kt`
  - Selected trace, tabs, history, copy actions, and clear action.
- Focused composables for overview, injection hits, sent messages, history selector, and empty states.

### Trace seed

`ChatService` supplies facts that exist before `GenerationHandler` transforms messages:

```kotlin
data class PromptTraceSeed(
    val conversationId: Uuid,
    val requestAnchorMessageId: Uuid?,
    val assistantId: Uuid,
    val modelId: Uuid,
    val isGroup: Boolean,
    val speakerMemberId: Uuid? = null,
    val speakerName: String? = null,
    val sourceHints: List<PromptTraceSourceHint> = emptyList(),
)
```

`requestAnchorMessageId` is normally the newest persisted, real user message included in the call. It is selected before group transport rewrite and excludes synthetic empty transport messages and group continuation nudges. It provides a stable fallback for calls that fail or are cancelled before a response message exists.

For group generation, `GroupContextBuildResult` exposes the synthetic layered-context message ID as a trace source hint. Transport rewrite copies preserve message IDs, so the hint remains valid after `applyGroupApiRewrite`.

### Transformer context integration

Add an optional trace session to `TransformerContext` and the `transforms` helper:

```kotlin
val promptTraceSession: PromptTraceSession? = null
```

Existing transformer call sites continue to work with the default `null`. General transformers remain unchanged. The trace session records source facts only where the pipeline already knows their meaning:

- `GenerationHandler`: effective system prompt, memory, and tool prompt text.
- `ChatService` / `GroupContextBuilder`: group layered context and current speaker.
- `PromptInjectionTransformer`: selected mode injections, lorebook matches, and final application placement.
- Provider boundary: the final semantic messages after every input transformer.

This avoids attempting to infer provenance later from changed text.

## Persistence

### Room entity

Add one table:

```kotlin
@Entity(
    tableName = "prompt_trace",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("response_message_id"),
        Index(value = ["conversation_id", "created_at"]),
    ],
)
data class PromptTraceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("request_anchor_message_id") val requestAnchorMessageId: String?,
    @ColumnInfo("response_message_id") val responseMessageId: String?,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("model_id") val modelId: String,
    @ColumnInfo("speaker_member_id") val speakerMemberId: String?,
    @ColumnInfo("provider_step_index") val providerStepIndex: Int,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("actual_prompt_tokens") val actualPromptTokens: Int?,
    @ColumnInfo("error_summary") val errorSummary: String?,
    @ColumnInfo("payload_json") val payloadJson: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
```

`response_message_id` is nullable because a request can fail before the first response chunk. It is indexed but not a foreign key because messages are serialized inside `message_node.messages`, not represented as individual Room rows.

### Database migration

- Increment `AppDatabase` from version 27 to 28.
- Register `PromptTraceEntity` and `PromptTraceDAO`.
- Add `Migration_27_28` that creates the table, foreign key, and indices.
- Add the migration to `DataSourceModule`.
- Export and validate the version-28 Room schema.
- Test migration from a populated version-27 database and verify existing conversations and message branches remain intact.

### Retention

After creating or finalizing a trace, run a single DAO transaction that retains the 20 newest traces for that conversation ordered by `created_at DESC, provider_step_index DESC`.

Retention counts every provider invocation, not only user turns. This is intentional: a tool-assisted generation can send materially different prompts at each provider step, and each generated assistant message can have its own branch ID and usage.

### Cleanup

- Conversation deletion uses the Room foreign-key cascade.
- `ChatService.deleteMessage` deletes traces whose `responseMessageId` equals the deleted branch.
- Regeneration from an earlier user message and other tail-truncation operations compute removed message IDs and delete matching traces.
- Traces with a removed `requestAnchorMessageId` and no surviving response binding are also deleted.
- Selecting a different branch does not delete or rewrite traces.
- Forking a conversation does not copy traces; the request happened in the source conversation, not the fork.
- A console action can clear all traces for the current conversation without modifying chat messages.

## Trace Payload

The payload is versioned Kotlin serialization JSON:

```kotlin
@Serializable
data class PromptTracePayload(
    val schemaVersion: Int = 1,
    val metadata: PromptTraceMetadata,
    val sections: List<PromptTraceSection>,
    val injectionHits: List<PromptInjectionTrace>,
    val finalMessages: List<PromptTraceMessage>,
)
```

Unknown future fields are ignored when reading. A malformed payload produces a typed unavailable state in the console rather than crashing conversation loading.

### Metadata

Record:

- Conversation, assistant, and model IDs.
- Solo/group mode.
- Actual group speaker member ID and display name.
- Provider step index.
- Request anchor and response message IDs.
- Start and finish timestamps.
- Lifecycle status.
- Actual prompt-token usage when available.
- Final semantic message count.

Provider type may be shown if it is already available from the selected model/provider setting, but provider credentials and configuration values are excluded.

### Source sections

Each section contains:

- Stable section kind.
- Human-readable label.
- Original text.
- Character count.
- Approximate token count.
- Optional source message ID.
- Optional final target message ID/index.

The initial section kinds are:

```text
ASSISTANT_OR_CARD_SYSTEM
CONVERSATION_SYSTEM_OVERRIDE
MEMORY
TOOL_PROMPT
GROUP_LAYERED_CONTEXT
MODE_INJECTION
LOREBOOK_INJECTION
HISTORY_MESSAGE
CURRENT_USER_MESSAGE
OTHER_TRANSFORMED_CONTENT
```

When a conversation-level system prompt replaces the assistant prompt, record only the active override as sent content and mark the assistant/card prompt as inactive metadata rather than counting it toward the request.

Sections describe provenance; `finalMessages` remains the accurate provider-bound ordering. If a later transformer merges or rewrites content, the console shows both the original source and its last known target instead of presenting the section estimate as a provider-native measurement.

### Injection hits

Refactor the current pure injection selection path so it returns structured collection results while preserving the existing output:

```kotlin
data class CollectedPromptInjection(
    val injection: PromptInjection,
    val sourceType: PromptInjectionSourceType,
    val lorebookId: Uuid? = null,
    val lorebookName: String? = null,
    val match: PromptInjectionMatch? = null,
)
```

For a lorebook entry record:

- Lorebook ID and name.
- Entry ID and name.
- Match type: `CONSTANT`, `KEYWORD`, or `REGEX`.
- Every keyword or regular expression that matched.
- Scan depth and scanned message IDs/count.
- Case-sensitivity and regex flags.
- Injection position, role, priority, and depth.
- Exact injected content and approximate tokens.
- Resulting message ID/index when resolvable.

For a mode injection record its selected binding, position, role, priority, depth, and content.

Trigger evaluation happens once. The same structured result both drives `applyInjections` and populates the trace, preventing diagnostic logic from drifting away from actual injection behavior.

Disabled, unbound, or unmatched lorebook entries are not included in A1.

### Final semantic messages

Capture the list immediately before:

```kotlin
providerImpl.streamText(...)
```

or:

```kotlin
providerImpl.generateText(...)
```

For each message record:

- Stable message ID.
- Semantic role.
- Optional `memberId` and name.
- Final list index.
- Full text and reasoning text parts.
- Character count and approximate text tokens.
- Sanitized attachment references.
- Tool call name, approval state, argument summary, output summary, total lengths, and hashes where useful.

The semantic capture occurs before provider-specific DTO conversion. It therefore remains consistent across OpenAI-compatible, Responses API, Claude, Gemini, and future providers. The UI explicitly labels it `进入提供商适配层前`, not `原始 HTTP 请求`.

### Sanitization rules

- Keep ordinary `UIMessagePart.Text` content exactly.
- Keep reasoning text but discard provider signatures and opaque reasoning metadata.
- For `data:` images or other embedded binary, store MIME type, decoded byte length when cheaply available, and SHA-256; do not store the base64 body.
- For file/content URIs, store type, display name, MIME type, URI, and optional hash.
- For network URLs, strip query parameters and fragments before persistence.
- Do not persist custom headers, API keys, cookies, authorization values, provider custom bodies, or raw provider request objects.
- Tool arguments and textual output are diagnostic summaries with original length and hash; binary output is represented only by metadata.
- Error summaries are short messages without stack traces or secrets.

## Multi-step Tools and Branch Binding

`GenerationHandler.generateText` can call the provider repeatedly while resolving tools. A1 creates a new trace for every `generateInternal` provider call:

1. Record the input message-ID set before the provider call.
2. Persist the provider-bound messages as `PREPARED`.
3. On the first returned chunk, identify the newly created assistant message ID relative to the input set.
4. Bind that ID to the trace and mark it `STREAMING`.
5. Merge usage updates into the trace.
6. Mark the trace `COMPLETED` after that provider step finishes.

The next tool step receives a new `traceId` and incremented `providerStepIndex`. This prevents a later tool result from overwriting the prompt that created the earlier tool-call branch.

If cancellation or failure occurs before any response ID exists, the trace remains linked by `requestAnchorMessageId` and is still selectable as a cancelled or failed attempt.

## Lifecycle and Failure Isolation

### Statuses

```kotlin
@Serializable
enum class PromptTraceStatus {
    PREPARED,
    STREAMING,
    COMPLETED,
    CANCELLED,
    FAILED,
}
```

- `PREPARED`: final semantic messages have been built and the request is about to enter the provider.
- `STREAMING`: at least one response chunk has been received and a response branch is bound.
- `COMPLETED`: the provider step and output processing finished.
- `CANCELLED`: cancellation propagated through the normal generation path.
- `FAILED`: a non-cancellation exception escaped the provider step.

### Failure isolation

All repository calls made for tracing use a best-effort wrapper:

- Trace persistence exceptions are logged with a stable tag.
- They never replace, delay, cancel, or convert the generation result.
- Cancellation from the generation job is always rethrown after the trace is marked best-effort.
- A trace failure does not enter the user-visible chat error list as a model-generation failure.
- The console can show `采集异常` only when a partial trace row exists.

## Token Accounting

### Actual total

Use provider-returned `TokenUsage.promptTokens` for the trace bound to that response message. This is the authoritative total displayed at the top of the overview.

If the provider omits prompt usage, display `未提供` rather than replacing it with an estimate.

Actual totals may include provider protocol overhead, tool schemas, cached-input accounting, or multimodal processing that is not represented by visible text sections. The UI does not claim that section estimates sum exactly to the actual total.

### Estimated sections

Add a deterministic, model-agnostic `PromptTokenEstimator`:

- Count CJK, Kana, and Hangul code points approximately one token each.
- Estimate other non-whitespace text in groups of roughly four code points.
- Count punctuation/symbol groups conservatively.
- Exclude binary bytes.
- Label every result with `约`.

The estimator is intentionally lightweight and dependency-free. A model-specific tokenizer can replace it later behind the same interface.

## UI Design

### Native RikkaHub visual language

The console remains a native Material 3 surface:

- Use `MaterialTheme` colors and typography.
- Use existing top-app-bar, tab, card, dropdown/bottom-sheet selector, copy, and empty-state patterns.
- Follow the repository's spacing and accessibility conventions.
- Do not embed the console in a WebView or give it a separate SillyTavern visual theme.
- Use a verified HugeIcons stroke icon for the top-bar entry. `Cards02` is the preferred Tavern/character-card glyph; final implementation must keep the verified import `me.rerere.hugeicons.stroke.Cards02`.

### Navigation

Add:

```kotlin
Screen.TavernPromptConsole(conversationId: String)
```

The chat top bar displays the icon only when the current conversation passes the shared Tavern eligibility rule. Opening the page does not stop generation. Returning restores the existing chat route and scroll position through normal navigation behavior.

### Header and trace selector

The console header contains:

- Title: `酒馆控制台`.
- Current assistant or group name.
- Selected trace time/sequence.
- Status chip.
- Group speaker name when applicable.
- A selector for up to the latest 20 traces, newest first.

The default selection is:

1. The newest trace bound to the currently selected assistant reply branch, when the current branch has one.
2. Otherwise the newest trace in the conversation.

Changing a `MessageNode.selectIndex` and reopening the console therefore shows the snapshot for that exact branch when available.

### Tabs

#### Overview

- Actual prompt tokens or `未提供`.
- Approximate tokens by section.
- Mode/lorebook hit counts.
- Model, provider step, group speaker, semantic message count, and status.
- Ordered source/injection timeline.
- Notice explaining why approximate sections may not equal actual provider usage.

#### Hits

- Group by lorebook, followed by mode injections.
- Show matched terms, match type, scan range, injection position, priority, role, and depth.
- Expand an item to inspect its exact injected content.
- Do not list every unmatched entry in A1.

#### Sent messages

- List messages in final provider-bound order.
- Show role, group member name, text/reasoning, attachment references, and summarized tool parts.
- Expand/collapse long items.
- Copy one message.
- Copy a readable full snapshot with explicit role and source headings.

#### Preview

- Show an explicit A2 coming-soon state.
- It does not run transformers, mutate the draft, create a trace, or imply that the displayed A1 snapshot is a preview.

### Empty and degraded states

Handle:

- No request has been sent in this conversation.
- The selected historical reply predates trace support.
- The selected reply branch has no trace, but other traces exist.
- The trace was cancelled before a response branch was created.
- The trace payload cannot be decoded.
- Trace capture failed while chat generation still succeeded.

The console provides `清空本会话记录` behind a confirmation dialog.

## Copy Format

The full readable copy action produces plain text:

```text
Tavern Prompt Trace
Conversation: ...
Assistant: ...
Model: ...
Speaker: ...
Status: ...
Actual prompt tokens: ...

[Injection hits]
...

[Final provider-bound messages]
1. SYSTEM
...
2. USER
...
```

Binary content, credentials, stripped URL query strings, and provider-private metadata never appear in copied output.

## Testing Strategy

### Pure JVM tests

- Tavern eligibility for solo, mixed group, disabled group member, and non-Tavern conversations.
- Token estimator determinism and multilingual behavior.
- Sanitizer excludes base64 bodies, query strings, signatures, headers, and binary tool output.
- Full text and semantic order are preserved.
- Injection match details are identical to the injections actually applied.
- Constant, keyword, regex, case-sensitive, invalid-regex, scan-depth, priority, and every injection position.
- Source-section mapping for assistant prompt, conversation override, memory, tools, group layered context, history, and current input.
- Trace lifecycle transitions and multi-step provider indexing.
- Retention keeps exactly the newest 20 records.

### Repository and migration tests

- Version-27 database migrates to version 28 with the new table and indices.
- Existing conversation and `message_node` rows survive unchanged.
- Payload round-trip and unknown-field compatibility.
- Conversation deletion cascades to traces.
- Branch deletion and tail truncation remove the correct response/anchor traces.
- Selecting another branch leaves all traces intact.
- A malformed payload is returned as a degraded result rather than throwing.

### Generation tests

- A solo Tavern provider call stores the exact transformed semantic messages.
- A group Tavern call records the actual member and layered context.
- Streaming binds the first new assistant message ID.
- Non-streaming binds the generated assistant message ID.
- Tool-assisted generation creates one trace per provider step.
- Prompt usage updates the matching trace.
- Cancellation before and after response binding produces the correct status.
- Provider failure preserves the prepared payload and short error summary.
- Repository failures do not change generated chunks or chat errors.
- Non-Tavern conversations do not create traces.

### Compose and instrumentation tests

- Top-bar entry is visible only for eligible conversations.
- Navigation opens the correct conversation console.
- Default selection follows the selected reply branch.
- History selector switches traces without changing the conversation branch.
- Overview, hits, sent messages, empty states, malformed state, copy actions, and clear confirmation render correctly.
- A group trace displays the actual speaker.

### Final verification

Run focused tests, the full relevant JVM suite, lint where practical, and:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Install the Debug APK on `emulator-5554` and manually verify:

1. Tavern solo request.
2. Tavern group request with the correct speaker.
3. Lorebook constant/keyword hit.
4. Regenerated alternative branch with a distinct trace.
5. Tool-assisted multi-step request.
6. User cancellation.
7. Provider failure.
8. App restart and trace restoration.
9. Branch deletion and conversation deletion.
10. The 21st trace removes the oldest record.

## Compatibility

- Existing conversations require no model-field defaults because traces live in a new table.
- Generation output, prompt construction, transformer ordering, provider calls, and stored `UIMessage` objects remain unchanged.
- `PromptInjectionTransformer` is refactored to expose the same decisions it already makes; existing behavior tests must remain green.
- Tavern Helper runtime permissions, HTML/status rendering, character-card import/export, group director controls, layered group context, and web APIs remain intact.
- Whole-database backup includes trace rows. Tavern card JSON/PNG export and conversation forks do not.

## Success Criteria

Phase A1 is complete when:

1. A Tavern solo reply exposes the exact provider-bound semantic messages.
2. A Tavern group reply exposes the actual speaker and group layered context.
3. Every displayed lorebook hit is the same hit used by the generation transformer.
4. Alternative reply branches retain distinct trace records.
5. Actual prompt tokens equal the bound response's `usage.promptTokens` when the provider supplies usage.
6. Binary/base64 content and credentials are absent from the database and copied output.
7. Traces survive process restart and are limited to 20 per conversation.
8. Deleting a branch or conversation removes its corresponding trace data.
9. Trace failures never prevent, cancel, or misreport normal chat generation.
10. Database migration, JVM tests, instrumentation tests, Debug assembly, and emulator smoke all pass.
