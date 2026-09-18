# Agent UX P0 — "Read the transcript properly" — implementation spec

Source: `design/agent-ux-gap-research.html` (§4.1, §4.6, roadmap P0). Scope decided 2026-09-17.

## Problem

The research audit's core finding: hermes-bots has agent-grade plumbing (streaming, approvals,
steer, replay) but a 2022-grade display plane. The highest-leverage fixes are all items where
the wire already delivers the data and only the UI is missing — no protocol changes, no new
permissions, no new dependencies. That is the P0 tier this spec implements.

## Gap evaluation — what's in, what's out, and why

### In scope (wire-ready, low risk, not bloat)

| # | Item | Why it's worth it | Wire status |
|---|------|-------------------|-------------|
| 1 | **Clickable links** | Documented deferral (`MarkdownText.kt`); every bot reply with a source URL is dead text. Pure renderer work. | Already in message text |
| 2 | **Code language chip + copy button** | The fence language token is discarded by `splitFences()`; copy is whole-bubble long-press only. Universal affordance in every leader client. | Already in message text |
| 3 | **Lightweight syntax highlighting** | Largest single "perceived competence" win for a coding agent's own client. Kept deliberately small: one pure-Kotlin scanner, four token classes (keyword/string/comment/number), theme-color mapping, hard char cap. No WebView, no third-party highlighter dependency. | Already in message text |
| 4 | **`inline_diff` rendering** | `tool.complete` carries `inline_diff?` (PROTOCOL.md §6) and the app drops it — it isn't even captured into `ChatItem`. Diff review is the core trust UI of Cursor/Claude Code. | `tool.complete {…, inline_diff?}` |
| 5 | **Plan / todo checklist** | `todo.updated` events arrive and fall into the event handler's `else` no-op. A live checklist that flips to done is the single strongest "the agent knows what it's doing" surface. Also seeded from the documented `todo_state?` field of `session.resume` so reconnect mid-plan restores it. | `todo.updated` (§6), `session.resume → todo_state?` (§5.2) |

### Out of scope (risk or bloat for this PR) — deliberately deferred

- **Tables / math / inline images** (research: effort M): surgery on the hand-rolled markdown
  parser for output that is rare relative to links/code. Revisit after P0 proves out.
- **Per-message actions (regenerate/edit/share)**: there is no regenerate RPC in PROTOCOL.md;
  adding the button would mean inventing one (AGENTS.md forbids inventing RPC). Copy already
  exists via long-press.
- **P1 media & voice** (file.attach UI, camera, dictation, TTS, image.generate): each needs a
  new permission (e.g. `RECORD_AUDIO`) or a new server-call surface plus composer rework. That
  is a separate, separately-reviewed change — not a rider on a display-plane PR.
- **Browser snapshot strip, artifacts/canvas, generative UI, Termux bridge**: P2+ per the
  research roadmap; artifacts require protocol additions (spec PR first).

## Design

### 1. Links (`MarkdownText.kt`)

- New token types in the line annotator: `[label](url)` markdown links and bare
  `https?://…` autolinks. Boundary rules live in the pure `data/Linkify.kt` helper:
  trailing punctuation `.,;:!?"'` and unbalanced `)` are trimmed, balanced parens are kept.
- Links never detected inside inline code spans.
- Rendered with Compose 1.7 `LinkAnnotation.Url` + `withLink`: theme primary color,
  underline, tap routed through a `linkInteractionListener` that calls `LocalUriHandler`
  inside `runCatching` (no browser → no crash).
- **Deliberate non-goal: Chrome Custom Tabs.** It would add the `androidx.browser`
  dependency for a nicer transition. `UriHandler`/`ACTION_VIEW` is zero-dependency and
  universal; Custom Tabs is the first follow-up if in-app feel is wanted. The fleet UI spec's
  no-WebView rule stays intact either way.

### 2–3. Code blocks (`MarkdownText.kt` + new `data/CodeDisplay.kt`)

- `splitFences` captures the fence info string; `Segment.Code` carries it.
- `CodeLanguages.label(info)` maps aliases (kotlin/kt, py, ts, sh/zsh, yml, rs, …) to a
  display name; unknown info strings render nothing (no chip), never raw.
- `CodeTokenizer.tokenize(code, language)` — pure function returning `List<CodeSpan>`
  (start/end/kind: KEYWORD, STRING, COMMENT, NUMBER). Single left-to-right scanner with
  string (incl. escape) / line-comment (`//`, `#` by family) / block-comment (`/* */`)
  states; per-language keyword sets for the languages the bots actually emit: kotlin, java,
  python, js/ts, bash/shell, go, rust, c/cpp, csharp, csharp aliases, sql, yaml, json (json =
  strings/numbers only), xml/html (strings/comments only). Unknown languages get
  strings/comments/numbers only. Hard cap: bodies over 20 000 chars are not tokenized
  (perf guard; plain render, content unaffected).
- Code block UI: surface gains a header row — language chip (uppercase, monospace, muted;
  only when known) and a text "Copy" action (matches the existing "Show all"/"Details"
  text-action idiom; core icon set has no copy glyph and no new dependency is warranted).
  Copy = clipboard + haptic + transient "Copied" label. Code body keeps monospace +
  horizontal scroll; spans are painted via `AnnotatedString` theme roles:
  keyword = primary, string = tertiary, comment = onSurfaceVariant italic, number = secondary.

### 4. Inline diffs (new `data/DiffText.kt` + `ToolChip`)

- `DiffText.extract(payload)` tolerates the unpinned wire shape: JSON string → text;
  object → first non-blank of `diff`, then `patch`, then `text` (priority order, not JSON
  order); array → join of extracted elements.
- `DiffText.parse(raw, maxLines)` classifies unified-diff lines into
  FILE (`---`/`+++`), META (`diff `/`index `), HUNK (`@@`), ADD, DEL, CONTEXT and reports
  truncation past the cap. `DiffText.addedRemoved(raw)` yields the collapsed badge counts.
- UI: red/green monospace rows inside the existing expandable ToolChip (research §4.6).
  The collapsed chip face carries a Cursor-style `+N −M` badge as its preview; expanding
  renders the rows, with a short preview set before the existing "Show all" toggle reveals
  the capped full diff. ADD = tertiary-on-tertiaryContainer, DEL = error-on-errorContainer
  (low alpha), HUNK = primary, FILE/META = muted. The chip is expandable when a diff exists
  even if the tool has no other text.

### 5. Todo checklist (new `data/TodoState.kt` + `ChatScreen`)

- PROTOCOL.md documents the event but not the payload ("normalized todo state"), so the
  parser is **tolerant by contract**: top-level array or object wrapper (`todos`/`items`/
  `list`); items read `content`/`text`/`title`/`label`; status read from `status`/`state`
  and mapped (completed/done → done, in_progress/active/running → active, else pending).
  Anything that doesn't parse renders nothing — a server variation can never break the chat.
  Hard cap 20 items.
- `ChatUiState.todo: List<TodoItem>`; `EVENT_TODO_UPDATED` replaces it; `open()` seeds it
  from `session.resume`'s `todo_state?`. State persists across turns until replaced (the
  desktop's plan anchor behavior); the card is user-collapsible.
- UI: a pinned collapsible "Plan" card above the working-status strip — per-item glyph
  (done: outlined check, primary; active: pulsing dot, secondary; pending: hollow circle,
  muted), done items dimmed, header shows `done/total`.

## Constraints honored

- **No PROTOCOL.md changes, no invented RPC** — every field consumed here is already in the
  authoritative contract (`tool.complete.inline_diff?`, `todo.updated`, `session.resume
  todo_state?`).
- **No new permissions** — manifest stays `INTERNET` + `POST_NOTIFICATIONS`.
- **No new dependencies** — links use the platform `UriHandler`; icons stay within the core
  set (text-action idiom instead of a copy glyph); highlighting is hand-rolled.
- **No WebView** — nothing here renders remote content in-process.
- Pure logic (tokenize, diff parse, todo parse, language aliases) lives in `data/` and is
  unit-tested JVM-side; Compose files only map results to styles.

## Test plan

- `CodeDisplayTest` — alias mapping; tokenizer spans for kotlin/python/bash/json (strings,
  escapes, `//` and `#` comments, block comments, keywords, numbers); unknown language
  fallback; >20 000-char guard.
- `LinkifyTest` — markdown-link matching at a position; bare-URL end boundaries (trailing
  punctuation, unbalanced vs balanced parens, angle-bracket stop, too-short URL rejection).
- `DiffTextTest` — classification of a real-shaped unified diff; cap + truncated flag;
  tolerant `extract` for string/object/array/null payloads.
- `TodoStateTest` — array payload; `{todos: […]}` wrapper; status mapping incl. unknown
  values; junk/null → empty; 20-item cap.
- Regression: existing suite stays green; `:app:assembleDebug :app:testDebugUnitTest` is the
  verification command.
