# Dynamic Group Context Design

Date: 2026-06-17
Status: Draft approved in chat, written for review

## Background

The current group chat runtime already supports:

- Group member turn-taking
- Layered context injection
- Runtime scene summary, relationship notes, and private notes
- Manual, round-robin, and auto-moderator reply modes

However, the current context pipeline is still too static:

- Context scope is filtered mostly by member-based rules and a simple max message cap
- The same filtering logic is reused across very different scene conditions
- Characters often receive too much irrelevant history or too little event-specific context
- Explicit user addressing is not elevated into a first-class runtime signal
- Event relevance depends too heavily on raw recent text rather than structured runtime state

The result is that group replies can still feel generic, over-shared, or insufficiently role-aware even after earlier runtime fixes.

## Goals

- Make group context selection dynamic rather than fixed
- Prioritize event relevance over simple recency in non-addressed scenes
- Treat explicit user addressing as a hard routing signal
- Preserve strong character knowledge boundaries
- Improve role fidelity while reducing unnecessary context load
- Keep the fast path rule-based and only use lightweight model help in high-value cases

## Non-Goals

- No rich-text mention chip protocol in v1
- No fully model-driven memory selection for every turn
- No open-ended multi-character free-for-all when the user explicitly addresses one role
- No replacement of the existing group runtime model with a large new subsystem in one pass

## User-Confirmed Product Rules

The following rules were explicitly confirmed in design review:

- Dynamic context uses combined scoring rather than a single fixed heuristic
- Explicit addressing has absolute priority
- Explicit addressing is recognized by:
  - Direct role-name addressing
  - Continuation using second-person phrasing after the previous turn already locked the addressed role
- Non-addressed scenes prioritize event relevance over recent interaction or relationship weight
- Event relevance uses a hybrid of:
  - Keyword matching
  - Runtime structured tags
- Low-relevance characters are fully isolated from the current event context
- Relevance is split into four layers:
  - Core
  - Strongly related
  - Weakly related
  - Isolated
- Core role qualification is:
  - Explicitly addressed, or
  - Highest event relevance score, or
  - Holder of key secrets or key items
- History strategy is aggressive rather than conservative
- Isolated roles only receive the user's last message
- Core roles use a dynamic history window:
  - Default: recent 6 full rounds
  - Expanded: recent 10 full rounds during strong plot progression, secret revelation, or high conflict
- Strongly related roles receive:
  - Recent 2 rounds
  - One scene summary
  - Their own most recent prior reply
- Weakly related roles receive:
  - One scene summary
  - Their own most recent prior reply
- Runtime structured tags must include:
  - Characters
  - Locations
  - Items
  - Events
  - Secrets
  - Emotions
  - Conflicts
- Tag extraction uses a hybrid strategy:
  - Rules first
  - Model-assisted correction only when needed
- Model-assisted tag correction is preferred when secret or major plot advancement is involved
- Model-assisted tag correction only sees a small local window:
  - Last user message
  - The next 1 to 2 character replies
- In explicit addressing mode, only the addressed character generates
- Addressing UX should support:
  - Direct role-name input
  - `@role` text mention
  - Long-press avatar to insert `@role`
  - Typing `@` in the input box to choose a role
- Chat history should preserve `@role` as plain visible text rather than converting it into chips

## Proposed Architecture

### 1. DynamicContextResolver

Introduce a new resolver that replaces the current fixed filtering path as the main entry for group member generation.

Responsibilities:

- Detect addressed mode vs non-addressed mode
- Build the current focus event profile
- Score candidate members
- Assign each candidate into one of the four relevance layers
- Build a role-specific context package
- Return both generation-ready messages and debug metadata

This resolver becomes the main source of truth for per-speaker context assembly before provider rewrite and model generation.

### 2. Runtime Event State

Add a dedicated runtime event layer instead of continuing to overload plain scene summary text.

Suggested structure:

```kotlin
@Serializable
data class GroupEventState(
    val recentEvents: List<GroupEventRecord> = emptyList(),
    val activeFocus: GroupEventFocus? = null,
)

@Serializable
data class GroupEventRecord(
    val sourceMessageId: Uuid,
    val speakerId: Uuid? = null,
    val characters: List<Uuid> = emptyList(),
    val locations: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val importance: Int = 0,
)

@Serializable
data class GroupEventFocus(
    val characterIds: List<Uuid> = emptyList(),
    val locations: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)
```

`GroupRuntimeState` should be extended with:

```kotlin
val eventState: GroupEventState = GroupEventState()
```

### 3. Addressed Runtime State

Persist a short-lived addressed target so "you continue" can reliably resolve to the same role.

Suggested fields:

```kotlin
val activeAddressedMemberId: Uuid? = null
val activeAddressedTurnId: Uuid? = null
```

This state should be updated when:

- A user explicitly addresses a role by name
- A user explicitly addresses a role using `@role`
- A user uses second-person continuation after an addressed turn is still active

This state should be cleared when:

- The user addresses a different role
- The conversation shifts away and no longer matches continuation heuristics

## Resolver Flow

For each candidate speaker generation:

### Step 1. Determine Mode

The resolver first detects whether the current turn is in addressed mode.

Addressed mode is entered when:

- The latest user message contains a direct role-name address
- The latest user message contains `@role`
- The latest user message uses second-person continuation and the previous addressed target is still active

If addressed mode is active:

- Only the addressed member is eligible to generate
- Other members are not considered for turn-taking in this user turn
- The addressed member receives full addressed-mode context assembly

### Step 2. Build Focus Event Profile

Construct a current event focus using:

- The latest user message
- The latest 1 to 2 relevant character replies in the local window
- Recent event records from runtime state
- Existing scene summary when still relevant

Priority order:

1. Structured event records
2. Rule-extracted keyword matches
3. Model-assisted tag correction when:
   - Secret revelation is detected
   - Strong conflict is detected
   - Major plot advancement is detected

### Step 3. Score Members

Each candidate member receives a combined score with event relevance as the dominant factor.

Score components:

- Event relevance
  - Presence in focus characters
  - Linked key locations, items, or secrets
  - Ownership of key secrets or key items
- Recent interaction
  - Spoke recently
  - Was directly reacted to recently
- Relationship weight
  - Strong affinity
  - Strong tension
  - Active conflict relationship with focus participants

The scoring model should favor determinism and debuggability rather than opaque complexity.

### Step 4. Assign Relevance Layer

Members are assigned into:

- Core
- Strongly related
- Weakly related
- Isolated

Rules:

- Addressed target is always Core
- Highest event relevance role can be Core
- Key-secret or key-item holder can be Core
- Low-scoring members become Isolated

### Step 5. Build Layer-Specific Context

#### Core

- Default: recent 6 full rounds
- Expand to recent 10 full rounds when:
  - Secret revelation
  - Strong conflict
  - Major plot progression
- Include:
  - Current focus summary
  - Relevant scene summary
  - Relevant relationship notes
  - Private notes

#### Strongly Related

- Include:
  - Recent 2 rounds
  - One concise scene summary
  - The member's most recent prior reply

#### Weakly Related

- Include:
  - One concise scene summary
  - The member's most recent prior reply

#### Isolated

- Include:
  - Only the user's last message

### Step 6. Knowledge Boundary Filter

After layer selection, apply a second-pass knowledge visibility filter:

- A message may be recent but still not visible if it depends on secrets this role should not know
- Public scene summaries may be rewritten into lower-information summaries for non-core roles
- Secret-bearing event records should not be exposed outside eligible roles

### Step 7. Produce Debuggable Output

The resolver should return a structured result:

```kotlin
data class DynamicGroupContextResult(
    val messages: List<UIMessage>,
    val layer: GroupContextLayer,
    val focus: GroupEventFocus?,
    val scoreBreakdown: GroupContextScoreBreakdown,
    val debugSections: List<String>,
)
```

This allows the debug sheet to explain:

- Why a role was selected
- Why a role only received a small context slice
- Which focus event or secret triggered expansion

## Mention UX

### Input Mentions

Support `@role` insertion when typing in the input box.

Behavior:

- Typing `@` opens a member selector
- Only enabled group members are listed
- Selecting a member inserts `@DisplayName`
- The message remains plain text in chat history

### Avatar Long-Press

Long-pressing a role avatar inserts `@DisplayName` into the input field at the current cursor position.

### History Rendering

Do not introduce a structured mention part in v1.

- Keep mentions as visible plain text
- Parse mentions semantically before generation
- Preserve original text in stored chat history

## Data Model Changes

### Conversation / Runtime

Extend conversation runtime persistence with:

- `groupRuntimeState.eventState`
- addressed target state

### Group Member Context Filter

The current static `contextFilter` should remain for backward compatibility, but it should become a fallback or coarse override rather than the primary mechanism.

Recommended v1 behavior:

- Existing member filter options still apply as hard visibility boundaries
- Dynamic resolver then selects within the allowed space

## Extraction Strategy

### Fast Path: Rule Extraction

Use deterministic extraction for:

- Known role names
- Known aliases where explicitly configured
- Known locations
- Known item keywords
- Secret/conflict/emotion keyword lists

### Slow Path: Model-Assisted Correction

Invoke only when:

- Secret reveal is likely
- Strong conflict is likely
- Major plot advancement is likely

Model sees only:

- The latest user message
- The latest 1 to 2 role replies

The model output should be constrained to a tiny schema-like structure to minimize ambiguity.

## Compatibility

This design should preserve:

- Existing group message storage format
- Existing assistant role-card generation behavior
- Existing manual and round-robin modes

The new resolver should be introduced incrementally behind current group context options where practical.

## Debug / Inspection

Extend the existing group context debug surface to show:

- Current addressed target
- Current focus event tags
- Role layer classification
- Score breakdown by:
  - Event relevance
  - Recent interaction
  - Relationship weight
- Whether model-assisted tag correction was used

## Testing Strategy

### Unit Tests

- Addressed mode detection by direct name
- Addressed mode detection by `@role`
- Addressed continuation using second-person follow-up
- Event focus construction from rule-only signals
- Event focus correction when secret progression triggers model-assisted mode
- Layer assignment for:
  - Core
  - Strongly related
  - Weakly related
  - Isolated
- Knowledge-boundary filtering for secret-bearing messages
- Context window expansion from 6 to 10 rounds
- Mention text preservation in stored message history

### Integration Tests

- Group auto reply with addressed mode only producing one role
- Non-addressed event-driven multi-role selection behaving deterministically
- Dynamic context debug output matching actual context slices

### Manual Smoke

- `@role` insertion from input box
- `@role` insertion from avatar long-press
- Explicit addressed turn only generating the addressed role
- Follow-up "you continue" keeping the addressed target
- Event-heavy scene causing Core window expansion
- Low-relevance role staying isolated from secret context

## Rollout Order

Recommended implementation order:

1. Add addressed target parsing and runtime persistence
2. Add `@role` input and avatar long-press insertion
3. Introduce runtime `eventState`
4. Implement rule-based event extraction
5. Introduce `DynamicContextResolver`
6. Replace fixed filtering path with resolver output for group generation
7. Add slow-path model-assisted tag correction
8. Expand debug sheet
9. Run group smoke tests and tune thresholds

## Open Constraints to Respect

- Keep reply speed acceptable in the default fast path
- Avoid storing large model-generated analysis blobs in conversation state
- Keep secret visibility deterministic and inspectable
- Avoid introducing mention rendering complexity before the behavior is proven

## Recommended First Implementation Scope

The first implementation pass should include:

- Addressed parsing
- `@role` UI insertion
- Addressed runtime persistence
- Rule-based event extraction
- Four-layer dynamic context slicing
- Debug visibility for layer and focus state

The first pass should not yet include:

- Complex alias dictionaries beyond configured names
- Always-on model-assisted tag extraction
- Rich mention chips
- Large-scale memory refactors
