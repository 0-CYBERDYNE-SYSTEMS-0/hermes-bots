# Fleet + Pulse behavioral specification

Status: approved for implementation on 2026-09-15.

## Product model

Hermes Bots has four top-level destinations:

1. **Fleet** answers “where are my bots?”
2. **Pulse** answers “what is happening, and what needs me?”
3. **Chats** provides conversation entry points and recent bot conversations.
4. **Settings** contains configuration, including gateways.

Detail screens such as a bot chat, room, editor, routine, notification history, or gateway editor
do not show the top-level navigation bar.

## Fleet

- Fleet groups all bot rows by gateway connection ID. The visible label is the configured
  connection label; groups never merge because labels happen to match.
- Every configured gateway has a machine header, including a gateway whose roster is empty or
  currently unavailable.
- The primary gateway sorts first. Remaining gateways sort by label, case-insensitively.
- Inside a machine, the primary default assistant sorts first, followed by the machine's existing
  custom sections and bot rows.
- A collapsed machine header still shows connection state, bot count, working count, unread count,
  and primary status.
- A machine without a saved user choice uses the smart default: expanded when it is primary, has
  a working bot, has unread activity, or is not ready; otherwise collapsed.
- A manual expand/collapse choice is persisted by connection ID and wins over the smart default.
- Search matches bot name, display name, description, preview, and machine label. Matching machines
  temporarily open while the query is non-empty; clearing the query returns to persisted/default
  disclosure state.
- Hidden bots remain excluded unless the existing Hidden filter is active.
- Bot taps, unread marking, long-press actions, routines, edit, hide, and custom-section behavior
  remain available.

## Pulse

- Pulse is a top-level destination, replacing the old Activity entry point.
- Its badge counts only unresolved, non-expired items that need the user. Working activity alone
  never badges the tab.
- Pulse presents, in order:
  1. **Needs you** — approvals and clarification requests, newest first, preserving server choices.
  2. **Working now** — visible active bots, most recently active first.
  3. **Recent** — the newest notification-history entries, capped by the existing limit.
- Each actionable row displays its machine label when known and opens the existing bot chat.
- The History action continues to open full notification history.
- Existing approval quick actions and failure feedback remain unchanged.

## Chats

- Chats is a top-level hub, not a new wire protocol.
- It links to the existing Any chats and Group chats experiences.
- It lists visible bot conversations ordered by most recent activity; tapping opens the existing
  canonical bot chat. Rows include a machine label so same-named bots remain distinguishable.

## Settings and navigation

- Fleet is the cold-start destination.
- Selecting a top-level destination does not stack duplicate copies of it.
- Re-selecting Fleet, Pulse, Chats, or Settings returns to that root destination.
- The top-level bar is hidden on detail screens and restored when returning to a root.
- Notification taps continue to open the targeted bot chat directly.

## Public test seams

- `FleetPresentation.groups(...)`: raw roster + connections + socket states to ordered machine
  groups and summaries.
- `FleetPresentation.isExpanded(...)`: saved override versus smart default.
- `ActivitySections`: Needs you / Working now / Recent ordering and filtering.
- `RootDestination`: stable route-to-tab mapping and detail-route exclusion.

## Out of scope

- No new RPC names or payloads.
- No remote avatar dependency; the existing local deterministic faces remain canonical.
- No fabricated progress percentage or inferred task-completion state.
- No automatic navigation away from the destination the user selected.
