# Tavern Web Compatibility Runtime Design

**Date:** 2026-08-22  
**Status:** Approved direction  
**Scope:** Android immersive Tavern conversation renderer

## Objective

Keep RikkaHub's current native chat shell, top bar, message actions, conversation tree, and input composer while making
character-card content behave as closely as practical to SillyTavern 1.18.0. The acceptance references are the local
SillyTavern instance at `http://127.0.0.1:8123/` and the three supplied screenshots: a rich character/status panel, an
interactive visual opening selector, and theme-colored quoted dialogue.

This is a compatibility-runtime project, not a one-card visual imitation. New cards using the same public contracts
must benefit without card-specific Kotlin branches.

## Chosen Architecture

Use the existing single app-owned `TavernConversationWebView` as the conversation document and keep untrusted card HTML
inside per-message sandboxed iframes. Extend the existing parent/iframe runtime broker into a TavernHelper-compatible
facade. The app-owned parent remains responsible for message layout, branch controls, resource interception, lifecycle,
and native actions. Card-authored HTML remains responsible for its internal layout, animation, theme switchers, panels,
and visual opening choices.

Embedding the complete SillyTavern frontend is rejected because it would introduce a second chat state, composer,
navigation model, persistence layer, and extension lifecycle inside RikkaHub. Adding isolated one-off render cases is
also rejected because it would continue to fail on new cards.

## 1. Display Macro Resolution

The renderer must resolve `{user}`, `{{user}}`, `{char}`, and `{{char}}` for all displayed Tavern text, including stored
historical greetings and raw HTML that did not pass through the send/greeting creation pipeline.

- `user` resolves to `DisplaySetting.userNickname`; blank resolves to `你`.
- `char` resolves to the active card/assistant display name.
- Resolution is display-only. Persisted message content and alternate greeting source text remain unchanged.
- The same resolved names are exposed in the runtime context so HTML and scripts observe consistent values.
- Macro processing must preserve script/style syntax and must not recursively interpret arbitrary card-generated text.
- Existing richer visual macros continue to use the shared `PlaceholderTransformer` implementation where safe.

## 2. SillyTavern Markdown and Theme Semantics

Before Markdown rendering, apply the SillyTavern 1.18.0 dialogue-quote transform to text regions while excluding fenced
code, inline code, HTML tags/attributes, and `<style>` content. Supported pairs are:

- `"..."`
- `“...”`
- `«...»`
- `「...」`
- `『...』`
- `＂...＂`

The transformed dialogue uses `<q>`. The document defines `--SmartThemeQuoteColor` and applies it to `.mes_text q`,
including nested emphasis, while prose continues to use the normal body color. Existing `<font color>`, card CSS, and
explicit `<q>` markup keep precedence consistent with SillyTavern.

The parent document also exposes a broader set of SillyTavern theme variables derived from the active Material theme,
with stable defaults matching the reference renderer. Card CSS remains scoped to the relevant message/member unless it
is inside its own iframe.

## 3. Rich HTML and Media

Raw HTML messages retain their full document structure inside a sandboxed iframe. When scripts are permitted for the
card, the iframe receives `allow-scripts`; it never receives direct Android bridge access. All host calls travel through
the validated parent broker.

The iframe compatibility layer must support:

- responsive cards, CSS animations/transitions, 2D/3D transforms, accordions, tabs, and theme switches;
- HTTPS images and stylesheets when the card's network permission is enabled;
- `data:` and `blob:` resources;
- imported/local RikkaHub resources mapped through the conversation resource registry;
- intrinsic-height updates using `ResizeObserver`, with loop and maximum-height guards;
- fullscreen expansion for content that intentionally exceeds the inline viewport;
- graceful per-resource failure placeholders without replacing the entire message.

Remote resources remain subject to the existing network permission. Navigation requires a trusted user gesture and is
opened by the native host. `file:` and arbitrary `content:` URLs are never exposed to the iframe.

## 4. TavernHelper Message Compatibility

Add the subset of TavernHelper's public message API required by real visual greeting cards, designed so it can be
extended without changing the transport:

- `getChatMessages(range, options)`
- `setChatMessage(fieldValues, messageId, options)`
- `setChatMessages(messages, options)`

The facade uses SillyTavern-compatible zero-based visible message indices and supports negative indices/ranges. Returned
records include `message_id`, `name`, `role`, `is_hidden`, `message`, `data`, `extra`, and, when requested,
`swipe_id`, `swipes`, `swipes_data`, and `swipes_info`.

For the opening message, `swipes` maps to the imported `first_mes + alternate_greetings` collection and `swipe_id` maps
to the active `TavernGreetingSession` selection. A call such as:

```js
const messages = await getChatMessages('0', { include_swipes: true });
await setChatMessage(messages[0].swipes[target], 0, {
  swipe_id: target,
  refresh: 'display_and_render_current',
});
```

must select the matching RikkaHub opening branch, persist it through the existing session/action path, refresh the
single document, update the native `1 / N` state, and emit message-swiped/context events.

General message edits are allowed only through validated runtime operations and existing conversation mutation paths.
The compatibility layer never mutates a detached JS-only copy of chat state. Invalid ranges, stale revisions, oversized
payloads, unknown messages, and disallowed role changes return structured errors.

For compatibility with cards that call these names as globals, the iframe exposes both global functions and aliases on
`TavernHelper`. Existing RikkaHub runtime APIs remain available.

## 5. Data Flow

1. Compose builds a `TavernConversationSnapshot` from the authoritative conversation tree, greeting session, card,
   theme, and settings.
2. Display macros are resolved while serializing text parts; persisted source remains untouched.
3. The app-owned document renders Markdown directly and raw HTML in isolated iframes.
4. A card script calls the injected TavernHelper facade.
5. The iframe posts a runtime request to the parent; the parent adds its unforgeable action token and invokes the Kotlin
   bridge.
6. Kotlin validates permissions, target conversation, range/index, revision, and payload size, then delegates to the
   existing conversation/greeting mutation callback.
7. The authoritative state changes; Compose emits a snapshot patch or replacement; the document and every iframe get
   the updated context and relevant events.

## 6. Security and Failure Isolation

- Only the app-owned document sees Android JavaScript interfaces and the action token.
- Card frames cannot forge privileged calls by directly naming an Android bridge.
- Script execution follows the card/runtime permission; network loading follows the network permission.
- Runtime responses are bounded and JSON-serialized; callback names and request sizes are validated.
- Iframe exceptions, failed resources, or unsupported APIs produce local diagnostics and do not destroy the surrounding
  conversation document.
- No broad filesystem origin, universal file access, or mixed-content bypass is introduced.

## 7. Compatibility and Migration

Existing Markdown messages, status placeholders/HUD, native greeting arrows, branch controls, tool cards, reasoning,
message long-press actions, and the native composer keep their current UI and behavior. The visual selector inside a
card and the native `1 / N` controls are two views of the same greeting-session state, not duplicate state machines.

Cards without scripts still render rich static HTML. Cards with unsupported TavernHelper calls receive a structured
`UNSUPPORTED_METHOD` rejection so missing coverage is diagnosable and can be extended centrally.

## 8. Verification Strategy

Implementation is test-driven and must include:

- JVM tests for display-only name expansion, blank-name fallback, preservation of stored source, and HTML/script edge
  cases;
- document tests for all six quote pairs, exclusions, explicit colors, and generated theme variables;
- controller/bridge tests for message ranges, swipe payloads, stale revisions, invalid indices, permission gates, and
  opening selection persistence;
- Android WebView tests proving iframe broker calls, card resize, remote/data/local image handling, and quote colors;
- regression tests for native greeting arrows, message branches, status HUD, Markdown, and script-disabled fallback;
- full `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, and `:app:assembleDebug`;
- physical-device acceptance with the three supplied real cards, including tapping the visual opening cards, observing
  native counter synchronization, checking `{user}` replacement, verifying portrait/status media, quote colors,
  animation, scrolling, back/re-entry, and absence of fatal WebView/app errors.

The goal is complete only when the real cards visibly work on device. DOM-only or unit-test-only evidence is
insufficient for linked images and interactive HTML.
