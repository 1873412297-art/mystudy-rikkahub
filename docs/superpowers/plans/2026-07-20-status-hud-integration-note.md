# Status HUD Integration Note (2026-07-20)

How to replay the status-block tag family + status HUD work onto a clean branch,
given that parts of the wiring are entangled with unrelated WIP in the dirty
`private-main` workspace.

## 1. Summary of the two commits

- **`5bf9d53e` — `feat: expand status block tag family and add bare details fallback`**
  Introduces `StatusBlockExtractor.kt` (+ its test, both previously untracked) and
  expands the status tag family recognized by all three parsers
  (`StatusBlockExtractor`, `RichTextRenderPolicy`, `Markdown`) to
  `status_block|statusblock|statusbar|status!?|状态栏` (case-insensitive,
  longest-first alternation). This adds `<statusbar>`, `<statusblock>` (covers
  camelCase `<StatusBlock>`), `<状态栏>`, and closes the gap where legacy
  `<status!>` was routed by the render layer but not extracted for the HUD /
  bubble cleaning. `StatusBlockExtractor` also gains a conservative bare
  `<details>` fallback: with no known status tags present, a maximal run of
  consecutive `<details>` blocks separated only by whitespace is treated as a
  status region when it has 2+ blocks, or is a single trailing block at end of
  text. Extractor-only fallback; render-layer segmentation untouched.
  Includes extended tests in `StatusBlockExtractorTest`, `RichTextRenderPolicyTest`,
  `MarkdownStatusBlockTest`.

- **`fc970e7f` — `feat: add status HUD bar for status block display`**
  Adds `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/StatusHudBar.kt`
  (new file, +318 lines) only: the chat-page HUD composable that shows the
  extracted status region of the most recent assistant message containing a
  status block. Collapsed: header line (or "状态栏") + section count. Expanded:
  full header, individually collapsible sections (auto-collapse over a height
  threshold; `isHtml` sections rendered via `MarkdownWebView` with tavern
  runtime context), and numbered option chips feeding the chat send flow.
  **Deliberately contains no call-site wiring** — see below.

Both commits are self-contained except for the two integration gaps documented
in sections 2–4.

## 2. HUD-only changes needed in `ChatPage.kt`

The working-tree diff of `ChatPage.kt` has 2 hunks. Hunk 1 is pure HUD.
Hunk 2 mixes an **author-note** callback (NOT HUD — exclude) with the closing
brace the HUD Column wrap needs. Verbatim working-tree diff, annotated:

```diff
@@ import block @@
 import androidx.compose.foundation.layout.Column
+import androidx.compose.foundation.layout.PaddingValues          # HUD
 import androidx.compose.foundation.layout.fillMaxSize
```

```diff
@@ ChatPageContent, Scaffold content lambda (HEAD ~line 511) @@
             containerColor = Color.Transparent,
         ) { innerPadding ->
+            Column(                                                          # HUD start
+                modifier = Modifier
+                    .fillMaxSize()
+                    .padding(innerPadding)
+            ) {
+            // 动态状态栏（HUD）：最近一条含状态块的 assistant 消息的状态
+            StatusHudBar(
+                conversation = conversation,
+                onOptionClick = { optionText ->
+                    if (loadingJob == null && currentChatModel != null && !isManualGroup) {
+                        // 点击选项 = 直接作为用户消息发送（复用聊天页发送链路）
+                        vm.handleMessageSend(listOf(UIMessagePart.Text(optionText)))
+                        scope.launch {
+                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
+                        }
+                    } else {
+                        // 生成中 / 未选模型 / 手动群聊需选人：退化为填入输入框
+                        inputState.setMessageText(optionText)
+                    }
+                },
+                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
+            )
             ChatList(
-                innerPadding = innerPadding,
+                innerPadding = PaddingValues(0.dp),                          # HUD: Column now owns the padding
                 conversation = conversation,
```

```diff
@@ end of the same content lambda (HEAD ~line 584) @@
                 onConversationSystemPromptChange = { newPrompt ->
                     vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                     vm.saveConversationAsync()
                 },
+                onConversationAuthorNoteChange = { note ->                   # AUTHOR-NOTE — NOT HUD, do not replay
+                    vm.updateConversation(conversation.copy(authorNote = note))
+                    vm.saveConversationAsync()
+                },
                 onMentionRole = onMentionRole,
             )
+            }                                                                # HUD: closes the Column added above
         }
```

**Replay notes:**

- `isManualGroup` does **not** exist at clean HEAD (it comes from the group-chat
  WIP). The companion patch drops `&& !isManualGroup` from the condition and
  shortens the else-comment. If your target branch already has group chat,
  restore the full condition `loadingJob == null && currentChatModel != null && !isManualGroup`.
- All other referenced symbols (`loadingJob`, `currentChatModel`, `vm`, `scope`,
  `chatListState`, `inputState`, `conversation`, `UIMessagePart`) exist at HEAD.

## 3. HUD-only changes needed in `ChatMessage.kt`

The working-tree diff has 5 hunks; only parts of two are HUD. The rest belongs
to tavern runtime (`tavernConversationId`/`tavernCurrentMessage` params),
auto-collapse (`AutoCollapseContent` wrap, `rememberSaveable`), and
`replaceResidualUserName` — all NOT HUD, do not replay.

Hunk A (imports) — verbatim, annotated:

```diff
 import me.rerere.rikkahub.R
 import me.rerere.rikkahub.Screen
+import me.rerere.rikkahub.data.ai.status.StatusBlockExtractor              # HUD
+import me.rerere.rikkahub.data.ai.transformers.replaceResidualUserName     # NOT HUD — exclude
 import me.rerere.rikkahub.data.model.Assistant
```

Hunk B (`displayParts`, HEAD ~line 144) — verbatim working-tree version:

```diff
     val displayParts = remember(message.parts, assistant, message.memberId, settings.userNickname) {
+        val userDisplayName = settings.userNickname.ifBlank { "你" }        # NOT HUD (feeds replaceResidualUserName)
         message.parts.stripVisibleSpeakerPrefixes(
             assistant = assistant,
             memberId = message.memberId,
             userName = settings.userNickname,
             messageName = message.name,
-        )
+        ).map { part ->                                                     # NOT HUD — replaceResidualUserName pass
+            if (part is UIMessagePart.Text) {
+                part.copy(text = replaceResidualUserName(part.text, userDisplayName))
+            } else {
+                part
+            }
+        }.mapNotNull { part ->                                              # HUD start
+            // 状态块清理：气泡只留叙事正文，状态内容由 StatusHudBar 展示（仅动显示层）
+            if (part is UIMessagePart.Text &&
+                (part.text.contains("<status", ignoreCase = true) ||
+                    part.text.contains("<maintext", ignoreCase = true))
+            ) {
+                val cleaned = StatusBlockExtractor.extract(part.text).cleanedText
+                if (cleaned.isBlank()) null else part.copy(text = cleaned)
+            } else {
+                part
+            }
+        }                                                                   # HUD end
     }
```

For a HUD-only replay, chain `mapNotNull` directly after
`stripVisibleSpeakerPrefixes(...)` (skip the `userDisplayName` line and the
`.map { replaceResidualUserName ... }` pass) — this is exactly what the
companion patch does. If your target branch later lands the
`replaceResidualUserName` feature, re-insert its `.map` pass before the
`.mapNotNull` gate.

Behavior note: the gate intentionally fires only when the text contains
`<status` or `<maintext` (case-insensitive), so `<状态栏>` regions are handled
by the render layer instead of `.cleanedText` — pre-existing design, not a bug.

## 4. `MarkdownWebView` tavern-params issue

`StatusHudBar.HudSection` renders `isHtml` sections via:

```kotlin
MarkdownWebView(
    content = section.content,
    isRawHtml = true,
    maxHeightDp = 300,
    tavernConversationId = tavernConversationId,
    tavernCurrentMessage = tavernCurrentMessage,
    ...
)
```

`tavernConversationId` / `tavernCurrentMessage` do **not** exist on
`MarkdownWebView` at clean HEAD — they come from the tavern-runtime WIP
(modified `MarkdownWebView.kt` + untracked `TavernRuntime*` files).

Resolution options:

- **(a) Land the tavern runtime first** (recommended if tavern is slated for
  the same branch). No `StatusHudBar.kt` edit needed; HUD HTML sections keep
  chat-scope tavern variables and host events.
- **(b) Drop the two arguments** — exact 2-line edit inside
  `StatusHudBar.kt` (`HudSection`, the `MarkdownWebView(` call):

  ```diff
                  MarkdownWebView(
                      content = section.content,
                      isRawHtml = true,
                      maxHeightDp = 300,
-                     tavernConversationId = tavernConversationId,
-                     tavernCurrentMessage = tavernCurrentMessage,
                      modifier = Modifier
  ```

  Also delete the now-unused `tavernConversationId` / `tavernCurrentMessage`
  parameters of `HudSection` and the `tavernCurrentMessage` `remember` block at
  the top of `StatusHudBar` (or leave them; they compile unused with warnings).
  Behavior cost: HTML sections in the HUD lose chat-scope tavern variable
  access. Plain-text sections (the common case) are unaffected.

**Recommendation:** (a) if tavern runtime will land on the target branch anyway;
otherwise (b) — it is a small, explicit, easily reversible narrowing.

## 5. Verification checklist after replay

1. Unit tests (all must pass; 60 tests total across these classes):

   ```bash
   ./gradlew :app:testDebugUnitTest \
     --tests "me.rerere.rikkahub.data.ai.status.StatusBlockExtractorTest" \
     --tests "me.rerere.rikkahub.ui.components.richtext.RichTextRenderPolicyTest" \
     --tests "me.rerere.rikkahub.ui.components.richtext.MarkdownStatusBlockTest" \
     --tests "me.rerere.rikkahub.data.ai.transformers.StatusPlaceholderTransformerTest" \
     --tests "me.rerere.rikkahub.data.ai.transformers.StatusTrailingBlockTransformerTest"
   ```

2. Full package build: `./gradlew :app:assembleDebug`
   (on Windows, if only `:web:buildWebUi` fails environmentally, retry with
   `-x :web:buildWebUi` and note it).

3. Emulator smoke with screenshots:
   - Install the debug APK on a running emulator.
   - Inject ready-made conversations directly into the Room DB instead of
     typing into the chat UI (`adb shell input text` corrupts non-ASCII and
     Compose send-button taps are unreliable). Reference script:
     `verification-screenshots/inject_conversations.py` in the dirty workspace —
     pull `databases/rikka_hub` via `adb exec-out run-as <pkg>.debug cat ...`,
     insert `ConversationEntity` + `message_node` rows with host Python
     (checkpoint the WAL first), push back via `/data/local/tmp` + `run-as cp`,
     delete stale `-wal`/`-shm`, relaunch.
   - Cover the tag matrix: `<状态栏>`, `<statusbar>`, `<STATUSBAR>`,
     `<StatusBlock>`, `<status!>`, and a bare trailing `<details>` pair.
   - Per conversation verify: collapsed HUD shows header/section count,
     expanded HUD lists the extracted sections, and the bubble shows cleaned
     narrative (for `<status*>` tags) with no raw tags visible.
   - Reference screenshots from the original verification live in
     `verification-screenshots/tagfam-*.png` (dirty workspace, untracked).

## 6. What is NOT covered

The two commits + this note + the two patches do **not** include, and nobody
should expect them to include:

- **ChatPage/ChatMessage/ChatList wiring as committed code** — the wiring is
  delivered as patches/docs only (sections 2–3), because the working-tree diffs
  of those files mix HUD with unrelated WIP.
- **Author-note feature** (`ConversationAuthorNoteCard.kt`,
  `onConversationAuthorNoteChange` plumbing, `Conversation.authorNote`).
- **Tavern runtime** (`MarkdownWebView.kt` tavern params,
  `TavernRuntime*` files, `TavernHostEventBus.kt`, `StatusPlaceholderTransformer`
  changes beyond HEAD).
- **Auto-collapse content** (`AutoCollapseContent.kt` and its ChatMessage wrap).
- **`replaceResidualUserName`** transformer and its ChatMessage pass.
- **Group-chat WIP** (`isManualGroup`, director controls, etc.).
- `verification-screenshots/` (verification artifacts, stays untracked).

Each of those should be committed/replayed separately by their owners.

## Companion patches

- `status-hud-wiring-chatpage.patch` — HUD-only `ChatPage.kt` wiring
  (Column wrap + `StatusHudBar(...)` + `PaddingValues` change + closing brace;
  `isManualGroup` guard dropped, see section 2).
- `status-hud-wiring-chatmessage.patch` — HUD-only `ChatMessage.kt` changes
  (extractor import + `.mapNotNull` `.cleanedText` gate chained directly after
  `stripVisibleSpeakerPrefixes`, see section 3).

Both were validated with `git apply --check` **and** a real `git apply` against
pristine HEAD copies of the two files (2026-07-20): all hunks apply, with git
auto-correcting a 1-line offset on the last hunk of each patch. If your target
branch has drifted near the hunk context, reapply by hand using the verbatim
hunks quoted above.
