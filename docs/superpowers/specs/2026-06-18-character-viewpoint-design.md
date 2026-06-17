# Character Viewpoint Design

Date: 2026-06-18
Status: Draft approved in chat, written for review

## Background

The current group member context configuration is technically functional but product-wise too flat.

Today the member editor exposes a `ContextFilter` with a bottom-sheet style configuration built around:

- Base visibility scope
- Optional member include/exclude lists
- Mention-triggered visibility
- A raw max-message cap

This has three concrete problems:

1. The UI reads like a developer-facing filter panel rather than a player-facing role system.
2. Scope rules, trigger rules, exclusion rules, and history window limits are mixed together without a visible priority model.
3. The configuration does not scale cleanly toward the gameplay direction already emerging in the project:
   - `@` naming / direct address
   - moderator call-outs
   - secret or faction-limited knowledge
   - "overhear" or selective awareness patterns
   - role-specific private viewpoint injection

The result is that users can configure the system, but they cannot quickly understand how a member "sees" the group conversation or why a message is visible to that role.

## Product Decision Confirmed In Chat

The following product decisions were explicitly settled during design review:

- The user-facing feature name should be `角色视角`
- This is not a normal "settings list"; it should evolve toward a rule-editor mental model
- The feature is mobile-first and should not use a desktop-style persistent side rail
- The full editor should live in the group member configuration flow, not as a permanent chat-page panel
- The recommended mobile placement is a dedicated subpage inside member detail rather than a single overloaded bottom sheet
- The editor should remain compatible with future group gameplay features

## Goals

- Replace the current "context filter" mental model with a clear "character viewpoint" model
- Make message visibility understandable and inspectable on mobile
- Separate base visibility from triggers, exclusions, and windowing rules
- Introduce a deterministic rule priority that can be explained in UI
- Keep the first implementation compatible with current group runtime and storage
- Create obvious extension points for future group gameplay mechanics

## Non-Goals

- No redesign of the entire group chat page in this pass
- No fully general rules engine with arbitrary user-authored expressions
- No mention-chip rendering protocol in this pass
- No full storage migration to a brand-new persisted schema in phase 1
- No provider/model-side behavior changes unrelated to member visibility assembly

## User Problem Statement

When editing a group member, the user is not really trying to configure a generic filter. They are answering a gameplay question:

> What can this role know, when can this role notice something, and how much history does this role receive?

The current UI does not present that question clearly. The redesigned experience should instead make each member feel like a viewpoint-bearing actor in the scene.

## Primary UX Direction

### Feature Name

Rename the feature from `上下文接收范围` to `角色视角`.

Recommended subtitle:

> 控制该角色默认能看到哪些消息，以及在什么条件下会强制接收

This framing is materially better because:

- It matches the roleplay / group gameplay domain
- It reduces technical wording
- It naturally supports future features such as private knowledge, selective awareness, and directed scenes

### Placement

The full editor should live in the group member configuration flow:

- Group members list
- Open a specific member
- Enter `角色视角` subpage

This is the explicit design decision for mobile.

Why this is preferred over an all-in-one bottom sheet or an always-visible chat panel:

- Mobile space is limited
- The editor will keep growing
- Users need a stable place to tune gameplay logic while testing
- The chat screen should only expose lightweight shortcuts or previews later, not the full editor

## Mobile Page Structure

The `角色视角` subpage should be vertically structured and optimized for one-handed scanning.

### Section 1. Top Summary Card

A compact summary card appears first and always reflects the current effective configuration.

Example summary:

- `白名单视角`
- `可见 2 人`
- `@触发`
- `排除 1 人`
- `最近 20 条`

Purpose:

- Give immediate feedback before the user scrolls into details
- Make the page readable at a glance
- Reduce confusion after template application or rapid edits

### Section 2. Base Mode

This is the primary rule group and must be visually separated from all other controls.

Recommended options:

- `全部可见`
- `仅自己相关`
- `仅定向 / 被点名`
- `指定成员白名单`

Presentation:

- Single-select cards or segmented card row
- Not a plain dense list
- The currently selected mode should remain obvious even after scrolling

Behavior:

- Only the controls relevant to the selected base mode should expand below
- `指定成员白名单` should reveal member selection
- `仅定向 / 被点名` should highlight its dependency on directed addressing or `@`

### Section 3. Triggers

Triggers are conditions that can force delivery even if the base mode is restrictive.

Phase 1 trigger types:

- `被 @ 时强制接收`
- `关键词触发`
- `主持人点名` reserved trigger slot for later implementation
- `明确的定向消息`

Phase 1 UI:

- Simple toggles with conditional details
- For keywords, reveal token/chip input when enabled

### Section 4. Exclusions

Exclusions are explicit "do not show" rules.

Phase 1:

- Excluded member list

Future-compatible extension point:

- Excluded message categories
- Excluded relation tiers
- Excluded private-channel sources

Presentation:

- Keep this grouped separately from triggers
- Selecting exclusions should show chips with quick remove affordances

### Section 5. Message Window

The current `maxMessages` field should stop looking like a random low-level property and instead be presented as a history window control.

Phase 1:

- `最近 N 条`
- `0 = unlimited` remains supported in storage but should not be displayed as an implementation detail

Recommended UI:

- Stepper or discrete slider
- Helper text explaining this is the history window for this role

Future-compatible extension:

- Summary substitution when the raw message window is truncated

### Section 6. Advanced Gameplay Inputs

This section should exist even if some items are only passive or partially wired in phase 1. It creates the correct long-term information architecture.

Candidate toggles:

- `允许私有备注注入`
- `允许场景状态注入`
- `允许关系提示注入`

These map well to the existing group runtime direction and make the page feel like a viewpoint editor rather than a narrow visibility form.

### Section 7. Real-Time Preview

The page should end with an inspectable preview block.

It should answer:

- Can this member see the sample message?
- Why?
- Which rule allowed it?
- Which rule blocked it?

Example:

- Message: `@慈脂佛母 今夜佛堂中可有异动？`
- Result: `可见`
- Reason: `命中 @ 触发器，且未命中排除规则`

This is critical. Without preview, the editor remains guesswork.

## Rule Priority Model

The system must stop feeling ambiguous. The first pass should hard-code and clearly explain the precedence model.

Recommended phase 1 order:

1. Determine base visibility by `base mode`
2. Apply force-delivery triggers
3. Apply exclusions
4. Apply message window trimming

Interpretation:

- Base mode defines the default visibility space
- Triggers can broaden access
- Exclusions can block specific sources
- Windowing applies after visibility is determined

This order should be used both in runtime logic and preview explanation text.

## Templates

Add lightweight preset templates to accelerate use and reinforce the gameplay framing.

Phase 1 templates:

- `观察者`
- `核心成员`
- `隐秘角色`
- `主持人视角`

Templates are not a new rules subsystem. They are simply opinionated "fill this form" helpers.

Expected behavior:

- Applying a template immediately updates the summary card
- The user can still customize every underlying field afterward

## Data Model Strategy

### Current Model

The current persisted data model is:

```kotlin
@Serializable
data class ContextFilter(
    val scope: ContextScope = ContextScope.ALL,
    val visibleMemberIds: List<Uuid> = emptyList(),
    val excludedMemberIds: List<Uuid> = emptyList(),
    val mentionEnabled: Boolean = false,
    val mentionKeywords: List<String> = emptyList(),
    val maxMessages: Int = 0,
)
```

This model is workable but semantically flat.

### Phase 1 Strategy

Do not force a full storage migration immediately.

Instead:

- Keep persisted `ContextFilter` compatibility
- Introduce a UI/domain mapping layer that conceptually groups the data into:
  - `baseScope`
  - `triggers`
  - `exclusions`
  - `window`

This should happen in UI/view-model and domain helper code first.

Suggested conceptual view-model shape:

```kotlin
data class CharacterViewpointState(
    val baseMode: CharacterViewpointBaseMode,
    val whitelistMemberIds: List<Uuid> = emptyList(),
    val triggerRules: CharacterViewpointTriggers = CharacterViewpointTriggers(),
    val exclusionRules: CharacterViewpointExclusions = CharacterViewpointExclusions(),
    val messageWindow: CharacterViewpointWindow = CharacterViewpointWindow(),
)
```

Where:

```kotlin
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
```

This abstraction can map back to the existing `ContextFilter` for phase 1 persistence.

### Future Migration Compatibility

If the system later grows more complex, a dedicated persisted `CharacterViewpointConfig` can replace `ContextFilter`.

That migration should only happen once:

- preview behavior is stable
- template behavior is stable
- future gameplay fields are clearer

## Runtime Logic Implications

The runtime currently relies on group filtering helpers and layered context resolution. The redesign should not replace the full group runtime in this pass.

Instead, phase 1 should:

- Preserve the current filtering path
- Refactor the visibility evaluation into a reusable explanation-friendly helper
- Reuse that helper in both:
  - actual context filtering
  - the new UI preview block

This helper should return both:

- result (`visible` / `hidden`)
- structured reasons

Suggested shape:

```kotlin
data class ViewpointEvaluationResult(
    val visible: Boolean,
    val matchedRules: List<String> = emptyList(),
    val blockedRules: List<String> = emptyList(),
)
```

This will let the product explain visibility rather than only execute it.

## UI Architecture

### New Subpage

Introduce a dedicated mobile subpage or route for `角色视角`.

Recommended navigation flow:

- Member detail page shows a summary row or summary card for `角色视角`
- Tapping it opens the full `角色视角` editor page

### Member Detail Surface

The member detail page should no longer expose the full configuration inline via a dense bottom sheet.

Instead it should show:

- the current summary
- a clear affordance to edit
- no extra quick actions in phase 1; template application belongs inside the full `角色视角` editor page

## Copy Recommendations

Use product-facing labels, not implementation labels.

Recommended replacements:

- `上下文接收范围` -> `角色视角`
- `基础范围` -> `基础模式`
- `仅自己` -> `仅自己相关`
- `仅定向消息` -> `仅定向 / 被点名`
- `maxMessages` UI label -> `最近消息窗口`

The language should emphasize:

- role awareness
- message visibility
- conditional reception

not:

- context internals
- filters
- technical jargon

## Accessibility / Mobile Constraints

The page is primarily mobile, so:

- Use vertical sections rather than side-by-side persistent layouts
- Keep the summary card compact and readable without expansion
- Avoid forcing too many nested modals
- Avoid chip overflow becoming unreadable on narrow screens
- Preserve touch-friendly controls for member selection and quick removal

## Rollout Plan

### Phase 1

- Rename the feature to `角色视角`
- Replace the current bottom-sheet-heavy UX with a dedicated subpage
- Add summary card
- Reorganize fields into:
  - base mode
  - triggers
  - exclusions
  - message window
- Add preview block
- Add templates

### Phase 2

- Add richer trigger types
- Add chat-page quick entry points
- Add stronger preview examples from live conversation context
- Consider persisted dedicated viewpoint schema

### Phase 3

- Add advanced gameplay rules such as faction channels, overhearing, relation-based default visibility, and stronger moderator orchestration

## Testing Strategy

### Unit Tests

- Mapping from `ContextFilter` to `CharacterViewpointState`
- Mapping back from `CharacterViewpointState` to `ContextFilter`
- Base mode visibility evaluation
- Mention-trigger visibility evaluation
- Exclusion override behavior
- Message window trimming behavior
- Summary-text generation

### UI Tests

- Enter member detail, open `角色视角`
- Change base mode and observe summary update
- Toggle mention trigger and verify keyword input visibility
- Add/remove whitelist members
- Add/remove excluded members
- Adjust message window and verify summary update
- Preview block shows deterministic explanation text

### Manual Smoke

- Configure a member as `仅定向 / 被点名`
- Send a normal group message and verify the role does not receive full context
- Send an `@role` message and verify the role now receives context
- Exclude a member and verify their messages disappear from the role's visible history
- Change recent-window size and verify the preview/runtime match

## File / Code Impact

Likely touch points:

- [Assistant.kt](C:/Users/18734/Desktop/HTML/rikkahub-source/app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)
- [AssistantGroupMembersPage.kt](C:/Users/18734/Desktop/HTML/rikkahub-source/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersPage.kt)
- [AssistantGroupMembersVM.kt](C:/Users/18734/Desktop/HTML/rikkahub-source/app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantGroupMembersVM.kt)
- [GroupMessageContextFilter.kt](C:/Users/18734/Desktop/HTML/rikkahub-source/app/src/main/java/me/rerere/rikkahub/service/group/GroupMessageContextFilter.kt)
- [ChatService.kt](C:/Users/18734/Desktop/HTML/rikkahub-source/app/src/main/java/me/rerere/rikkahub/service/ChatService.kt)

Important maintenance note:

- The current member-detail UI file appears to contain text encoding corruption in parts of the working tree. That should be cleaned before substantial UI work begins, otherwise copy changes and future maintenance will remain error-prone.

## Final Recommendation

Implement `角色视角` as a dedicated mobile-first member subpage with:

- a top summary card
- a clearly separated base mode section
- grouped trigger / exclusion / window controls
- preset templates
- a real-time explainable preview

Do not continue extending the current flat bottom-sheet approach. It is already structurally behind the product direction.
