# Hermes Bots Fleet + Pulse UI specification

Status: implementation contract. The Smart Fleet direction, Pulse as a top-level destination,
and a native Blobatar port were approved on 2026-09-16.

This document is the visual and interaction source of truth for the Android UI. The behavioral
rules in [`fleet-pulse.bspec.md`](./fleet-pulse.bspec.md) remain authoritative for data, ordering,
navigation, and persistence. When the gallery and this document disagree, this document wins.

## 1. Product intent

Hermes Bots should feel like a compact, calm fleet console populated by recognizable characters.
The machine provides place and health; the Blobatar provides identity; typography and restrained
color provide hierarchy. The interface should look authored rather than like an unmodified
Material 3 application.

The memorable element is a field of distinct geometric bot faces inside quiet machine frames.
Status is legible without turning the whole screen into a dashboard.

### Approved composition

- **Fleet** uses gallery Option A, Smart Fleet: machine cards with useful collapsed summaries and
  nested bot sections.
- **Pulse** is a separate top-level tab informed by gallery Option B: action-first Needs you,
  Working now, and Recent sections.
- **Chats** and **Settings** use the same visual tokens and component language.
- Fleet uses the smart expansion policy already defined in the BSpec. It does not start with every
  machine collapsed.
- Pulse activity is not duplicated as a permanent horizontal rail on Fleet.
- Uploaded avatars continue to override generated avatars.
- Generated avatars use the native Blobatar generation-2 port defined in section 9.

### What belongs to the app

The phone UI shown inside [`roster-ux-gallery.html`](./roster-ux-gallery.html) is the reference. The
gallery webpage's hero, device frame, stage grid, radial gradients, grain, option switcher, policy
laboratory, reaction buttons, and decision table are presentation scaffolding and must not appear
inside the Android app.

## 2. Constraints and assumptions

- Target is the existing Kotlin and Jetpack Compose Android application, minSdk 29 and targetSdk
  35.
- The app remains native. Do not embed a WebView, JavaScript runtime, React Native, or Flutter.
- Existing repositories, protocol messages, RPC names, authentication, and stored data remain
  unchanged.
- Existing routes and screen behavior remain intact unless this specification or the BSpec names a
  change.
- Dark mode is the canonical gallery match. Light mode is a deliberate companion, not an inverted
  afterthought.
- Logical measurements below are Compose `dp`; text measurements are `sp`.
- The baseline viewport is 360 dp wide. Layout must remain usable from 320 through 600 dp and at
  Android font scales through 2.0.
- Visual density never reduces interactive hit targets below 48 × 48 dp. A visible icon may be
  smaller inside that target.

## 3. Visual foundations

### 3.1 Color tokens

Dark mode must use these values exactly:

| Token | Value | Use |
| --- | --- | --- |
| `Canvas` | `#0D1116` | Screen background |
| `CanvasDeep` | `#0B0E12` | System-bar and deepest background |
| `Surface` | `#12171E` | Machine cards and quiet containers |
| `SurfaceRaised` | `#1A212B` | Pressed rows, menus, elevated controls |
| `SurfaceInput` | `#202731` | Search and input fields |
| `SurfaceNav` | `#0C1015` at 96% opacity | Bottom navigation |
| `Line` | `#29323E` | Card and control outlines |
| `LineQuiet` | `#202833` | Dividers and navigation edge |
| `Text` | `#F3F5F7` | Primary text |
| `TextMuted` | `#9AA6B5` | Descriptions and secondary text |
| `TextDim` | `#667282` | Tertiary metadata and inactive icons |
| `Primary` | `#9DD7F2` | Selected navigation, focus, links |
| `PrimaryDeep` | `#163D52` | Selected/primary tonal surfaces |
| `Attention` | `#EF7137` | Requests that need the user |
| `Success` | `#65D28C` | Ready and actively working |
| `Danger` | `#FF6F68` | Offline/error only |
| `PrimaryMachine` | `#EFC75E` | Primary-machine star |
| `IdentityLime` | `#CDE73E` | Available to Blobatar identity only |

Light mode must use these values:

| Token | Value |
| --- | --- |
| `Canvas` | `#F6F5F1` |
| `CanvasDeep` | `#EEECE7` |
| `Surface` | `#FFFFFF` |
| `SurfaceRaised` | `#ECEFF2` |
| `SurfaceInput` | `#E8EDF1` |
| `SurfaceNav` | `#FBFAF7` at 98% opacity |
| `Line` | `#D1D8DE` |
| `LineQuiet` | `#E2E6EA` |
| `Text` | `#17202A` |
| `TextMuted` | `#596675` |
| `TextDim` | `#778391` |
| `Primary` | `#286F94` |
| `PrimaryDeep` | `#D8EBF4` |
| `Attention` | `#B94716` |
| `Success` | `#287345` |
| `Danger` | `#B93430` |
| `PrimaryMachine` | `#806000` |
| `IdentityLime` | `#647800` |

Rules:

- Identity color never communicates status by itself. Status uses text, iconography, or a labeled
  dot in addition to color.
- `Attention` is reserved for unresolved user action. It is not the selected-tab color.
- `Danger` is reserved for failed/offline states. Waiting for approval is orange, not red.
- Surfaces use borders rather than broad drop shadows. Menus, dialogs, and transient snackbars may
  use a soft black shadow up to 24 dp blur at 30% opacity.
- Scrims follow Material accessibility behavior but use `CanvasDeep` as the source color.

### 3.2 Typography

Bundle open-licensed font files; do not rely on device-specific availability.

- Display/body: **Manrope Variable**, weights 400–700.
- Metadata: **IBM Plex Mono**, weights 500 and 700.
- These replace the gallery's Avenir Next and SF Mono stacks because those proprietary fonts
  cannot be assumed or redistributed. Manrope preserves the gallery's compact, rounded body voice;
  IBM Plex Mono preserves its technical metadata contrast.
- Include both fonts' license files with the app distribution.
- Enable tabular figures for times and counts.

| Style | Font | Size / line | Weight | Use |
| --- | --- | --- | --- | --- |
| `ScreenTitle` | Manrope | 28 / 34 | 650 | Fleet, Pulse, Chats, Settings |
| `SectionTitle` | Manrope | 17 / 23 | 650 | Needs you, Working now, Recent |
| `MachineName` | Manrope | 15 / 20 | 700 | Machine heading |
| `BotName` | Manrope | 15 / 20 | 650 | Bot row primary line |
| `Body` | Manrope | 14 / 20 | 450 | Messages and descriptive copy |
| `BodySmall` | Manrope | 12 / 17 | 450 | Bot preview and empty-state copy |
| `Label` | Manrope | 12 / 16 | 650 | Buttons and bottom-nav labels |
| `Metadata` | IBM Plex Mono | 10 / 14 | 500 | State, age, machine secondary line |
| `MetadataStrong` | IBM Plex Mono | 10 / 14 | 700 | Working/attention summary |
| `Eyebrow` | IBM Plex Mono | 10 / 12 | 700 | Uppercase section labels, 0.10 em tracking |

Text rules:

- Screen titles use `-0.035 em` tracking. Other text uses default tracking except `Eyebrow`.
- Bot and machine names are one line with an ellipsis.
- Bot preview is one line in Fleet and at most two lines in Pulse and Chats.
- Metadata is sentence case except section eyebrows, which are uppercase.
- At font scales above 1.3, fixed-height rows become minimum heights and may grow. Metadata moves
  below primary content before it is truncated.

### 3.3 Spacing and shape

Use a 4 dp base grid.

| Token | Value | Use |
| --- | --- | --- |
| `ScreenGutter` | 14 dp | Root horizontal content inset |
| `TopBarHorizontal` | 17 dp | App-bar content inset |
| `GapXs` | 4 dp | Tight inline spacing |
| `GapSm` | 8 dp | Related elements |
| `GapMd` | 10 dp | Row columns |
| `GapLg` | 14 dp | Card padding and major row inset |
| `GapXl` | 20 dp | Section separation |
| `RadiusControl` | 12 dp | Icon targets and compact buttons |
| `RadiusRow` | 13 dp | Pressed bot-row surface |
| `RadiusAttention` | 15 dp | Needs-you cards |
| `RadiusInput` | 17 dp | Search field |
| `RadiusMachine` | 19 dp | Machine card |

Root content ends at least 16 dp above the bottom-navigation content area. System status and
navigation insets are always honored.

### 3.4 Icons

- Use one coherent outlined icon family at 20–22 dp with a 1.75–2 dp visual stroke.
- Visible top-bar icon size is 22 dp inside a 48 dp hit target.
- Avoid emoji and text glyphs as production icons.
- Use the same semantic icon everywhere: list/fleet, pulse/bell, chats, settings, search, add,
  overflow, history, chevron, and error.
- Decorative icons are hidden from accessibility. Buttons have explicit content descriptions.

## 4. App shell and navigation

### 4.1 Screen background and top bar

- Root background is `Canvas`, with no gradient or texture.
- Root top bar is 64 dp plus status-bar inset. It is visually transparent over `Canvas`.
- Title aligns to `TopBarHorizontal` and the visual baseline shown in the gallery.
- Fleet actions are Add bot and More. Pulse has History. Chats and Settings have only actions that
  already exist and are useful at the root.
- The former Activity icon does not appear in Fleet because Pulse is a top-level destination.
- Detail screens keep their existing back behavior and hide the bottom navigation.

### 4.2 Bottom navigation

- Height is 72 dp plus the navigation-bar inset.
- Background is `SurfaceNav`; top edge is a 1 dp `LineQuiet` divider.
- Four equal destinations appear in this order: Fleet, Pulse, Chats, Settings.
- There is no Material pill, capsule, filled indicator, or orange selection background.
- Selected icon and label use `Primary`; unselected content uses `TextDim`.
- Icons are 21 dp. Labels use `Label` at 11 sp and sit 4 dp below the icon.
- A Pulse badge is a 7 dp `Attention` dot for one unresolved item and a compact numeric badge for
  two or more. The badge disappears at zero. Working activity alone never creates a badge.
- Root switching uses a 120 ms crossfade. Detail navigation uses the platform back transition.

## 5. Shared components

### 5.1 Search field

- Full available width, 48 dp minimum height, `SurfaceInput`, `RadiusInput`, and no elevation.
- Leading search icon is 20 dp `TextDim`; input uses `Body` and `Text`.
- Placeholder is “Search bots or machines” in `TextMuted`.
- Focus adds a 1 dp `Primary` outline; it must not resize the field.
- A non-empty query adds a trailing 48 dp clear target.
- Search behavior follows the BSpec, including temporary machine expansion.

### 5.2 Health indicator

- Visible dot is 8 dp.
- Ready: `Success` with a 4 dp halo at 8% opacity.
- Needs user/reconnecting: `Attention` with a 4 dp halo at 9% opacity.
- Offline: `TextDim`, without a halo.
- An adjacent textual state is always present in the machine header.

### 5.3 Section eyebrow

- Uses `Eyebrow` and `TextDim`.
- Insets: 8 dp horizontal, 8 dp top, 6 dp bottom.
- Names come from existing custom sections. Dynamic “ACTIVE” may precede them when working bots
  exist; “ASSISTANT” may identify the primary default assistant.

### 5.4 Bot row

- Minimum height 58 dp; content inset 7 dp vertical and horizontal.
- Columns: 40 dp avatar slot, 10 dp gap, flexible copy, 8 dp gap, trailing state.
- Avatar is 36 dp in Fleet. The slot allows a 2 dp focus/working treatment without reflow.
- Pressed/focused background is `SurfaceRaised` with `RadiusRow`; idle background is transparent.
- Bot name uses `BotName`. Preview uses `BodySmall` and `TextMuted`.
- Trailing state uses `MetadataStrong`: `Success` for working, `Attention` for waiting, otherwise
  `TextMuted`.
- Unread is a 5 dp `Attention` dot after the bot name plus accessible “Unread” semantics.
- The whole row is a 48 dp-or-larger target. Long press preserves the existing action sheet.
- Hidden/offline cached rows use 56% content alpha, while their text remains readable at minimum
  contrast.

### 5.5 Empty and loading states

- Use one 64 dp Blobatar, a `SectionTitle`, one short `BodySmall` explanation, and an optional
  primary action.
- Empty states do not use generic illustrations.
- Loading preserves the target layout with quiet tonal blocks; do not animate every skeleton.
- Connection failure shows the last known content when available and labels it as cached.

## 6. Fleet screen

### 6.1 Layout

Order:

1. Top bar.
2. Search field, with 5 dp top and 13 dp bottom margins.
3. Machine cards separated by 10 dp.
4. Bottom content clearance.

The policy selector shown in the gallery is a prototype control and is not part of the app.

### 6.2 Machine card

- Full width inside `ScreenGutter`.
- `Surface` background, 1 dp `Line` border, `RadiusMachine`, clipped content, no elevation.
- Attention changes only the border to `Attention` at 55% opacity.
- Header minimum height is 64 dp with 14 dp content inset.
- Header columns: 12 dp health slot, 10 dp gap, flexible name/state, summary, 22 dp chevron.
- Machine name uses `MachineName`; the primary star follows the name at 6 dp gap in
  `PrimaryMachine`.
- Secondary line uses `Metadata` and `TextMuted`.
- Right summary is right aligned. Its first line uses `MetadataStrong`; second uses `Metadata`.
- Chevron uses `TextDim` and rotates 180 degrees over 220 ms when opening.
- Expanded content has 8 dp side inset and 9 dp bottom inset.

Machine-copy precedence:

| Condition | Secondary line | Summary line 1 | Summary line 2 |
| --- | --- | --- | --- |
| Offline | `Offline · last seen {age}` | `offline` in Danger | `{n} cached bots` |
| Needs user | `Waiting for your response` | `needs you` in Attention | `{n} bots · {r} requests` |
| Working | `Ready · {primary/last activity}` | `{n} working` in Success | `{b} bots · {u} unread` |
| Ready/quiet | `Ready · last activity {age}` | `all quiet` | `{n} bots` |
| Connecting | `Connecting…` | `connecting` in Attention | `{n} cached bots` |

Zero values are omitted from natural-language summaries. The underlying counts and opening rules
remain those defined by `FleetPresentation` and the BSpec.

### 6.3 Expansion and motion

- Opening/closing animates content height and alpha over 240 ms using a fast-out-slow-in curve.
- The header remains stationary while its panel reveals below it.
- Manual choices persist by connection ID.
- Search-forced expansion does not overwrite the saved choice.
- Reduced motion changes expansion to an immediate state change and leaves the chevron static at
  its final angle.

### 6.4 Ordering inside a machine

- Primary default assistant first.
- Dynamic Active section next when working bots exist, without duplicating a bot in its custom
  section.
- Remaining existing custom sections follow their repository order.
- Unsectioned bots appear under Bots.
- Existing hidden-filter behavior remains unchanged.

## 7. Pulse screen

Pulse uses the gallery's action-first visual vocabulary without copying its prototype-only
determinate progress percentages.

### 7.1 Layout and order

1. Top bar: “Pulse” and History.
2. Needs you.
3. Working now.
4. Recent.
5. Bottom content clearance.

Sections use `SectionTitle`, not uppercase eyebrows, because they are primary reading landmarks.
There is 20 dp before a section title and 8 dp between the title and its content.

### 7.2 Needs-you card

- `Surface` background with a 1 dp `Attention` border at 35% opacity and `RadiusAttention`.
- A 5 × 36 dp `Attention` bar sits at the leading edge.
- Main line: `{bot} needs {approval/clarification/secret response}`.
- Secondary line: `{machine} · {relative age}`.
- The primary server-provided quick action uses a compact filled `Attention` button with dark text.
  Additional server-provided choices use outlined buttons. Never fabricate choices.
- Minimum card height is 64 dp; buttons retain 48 dp targets.
- Expired requests leave Needs you immediately and never badge Pulse.
- If there are no items, show “All clear — nothing is waiting on you.” as quiet body text, not a
  large celebratory card.

### 7.3 Working-now rail

- Working bots appear as horizontally scrolling 148 dp-wide cards with 8 dp gaps.
- Card uses `Surface`, a 1 dp `Line` border, 16 dp radius, and 11 dp inset.
- Top row contains a 28 dp Blobatar, bot name, and machine name.
- Task text uses `BodySmall`, two lines maximum.
- The bottom activity rail is 3 dp tall. It is indeterminate: a `Success` segment travels through a
  `Line` track over 1.4 seconds. It never represents or implies completion percentage.
- Reduced motion shows a static 35% `Success` segment.
- When empty, show “No bots are working right now.” and omit the horizontal container.

### 7.4 Recent rows

- Use the shared bot-row rhythm with a 40 dp Blobatar and up to two preview lines.
- Bot name is primary; machine label and relative age are metadata.
- Rows open the existing canonical chat when a target is available.
- Error copy may use `Danger`, but the entire row does not become red.

## 8. Chats and Settings

### 8.1 Chats

- Top-level Any chats and Group chats are two equal cards in a two-column grid above the recent
  bot list. At widths below 340 dp or font scales above 1.3, they stack vertically.
- Cards use `SurfaceRaised`, a 1 dp `Line` border, 18 dp radius, and 14 dp inset.
- Icon is 24 dp `Primary`; title uses `MachineName`; description uses `BodySmall`.
- Recent bots use the shared bot row. Machine label leads the preview (`{machine} · {preview}`) so
  same-named bots remain distinguishable.

### 8.2 Settings

- Settings remains a quiet utility screen using `Canvas`, `Surface` groups, `LineQuiet` dividers,
  and the same typography.
- Root Settings has no back arrow. Detail Settings screens do.
- Forms retain platform-standard editing behavior, keyboard handling, validation, and secure-field
  semantics. Styling must not conceal credential state or errors.

## 9. Native Blobatar generation-2 port

### 9.1 Provenance and pin

- Port Blobatar generation 2 from upstream tag
  [`v2.7.0`](https://github.com/Alain00/blobatar/tree/v2.7.0), commit
  `ebb7ea4808b1263629fc8fa65e2398b9cbdb6f6b`.
- Generation 2 owns the frozen seed-to-look mapping. Updating the source pin requires an explicit
  migration decision and refreshed parity evidence; it is not a routine dependency update.
- Use the official Flutter/Dart port's checked-in reference fixture as the cross-language model.
  Its 1,570 layout cases are pinned to v2.4.0 and remain valid for the frozen generation-2 mapping.
- Copying or translating substantial upstream code requires retaining the MIT license and adding a
  notice such as “Blobatar © 2026 Alain, MIT License” to the app's open-source notices.
- The Android implementation is local and offline. It never calls `blobatar.dev`, sends a bot name
  over the network, or evaluates JavaScript.

### 9.2 Required parity surface

The Kotlin core must implement:

- NFC normalization, trim, and lowercase.
- UTF-8 seed hashing and independent keyed trait streams.
- All ten generation-2 silhouettes.
- The authored OKLCh palette, tone bands, contrast enforcement, and palette overrides.
- `none`, `squircle`, `circle`, and `square` backdrops.
- Structured body, face, eye, petal, and extra geometry needed by the renderer.
- All fourteen expressions: idle, happy, sad, mad, surprised, wink, sleepy, smug, unsure,
  scared, love, shy, sick, and thinking.
- Seeded breathe, bob, blink, and saccade calculations required by the approved app-state motion.
- Trait and option overrides required for tests and future editor previews.

Pointer gaze, SVG-string output, HTTP rendering, and framework adapters are outside the Android
port.

### 9.3 Kotlin module boundary

Keep Blobatar isolated from app state:

```text
ui/avatar/blobatar/
  BlobatarHash.kt        normalization, hash, keyed streams
  BlobatarTraits.kt      generation-2 trait thresholds and resolution
  BlobatarColor.kt       OKLCh palette and contrast
  BlobatarGeometry.kt    shape primitives and resolved layout
  BlobatarExpression.kt  fourteen immutable poses
  BlobatarMotion.kt      deterministic elapsed-time frames
  BlobatarRenderer.kt    Compose DrawScope renderer
  BlobatarAvatar.kt      small public composable
```

The core files do not depend on Compose or Android. Only the renderer and composable may import
Compose types. UI screens depend on `BlobatarAvatar`, not on hashing or geometry internals.

Conceptual public API:

```kotlin
@Composable
fun BlobatarAvatar(
    seed: String,
    size: Dp,
    uploaded: AvatarImage? = null,
    expression: BlobatarExpression = BlobatarExpression.Idle,
    motion: BlobatarMotionMode = BlobatarMotionMode.Static,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
)
```

If `uploaded` decodes successfully, render it as the existing circular crop and skip generated
geometry. A failed decode falls back to Blobatar without changing layout.

### 9.4 Seed contract

- Bot seed is the canonical profile name (`bot.name`), not display name, machine label, transient
  session ID, or connection ID.
- Display-name edits therefore do not change a bot's face.
- The same canonical bot name renders the same Blobatar across Fleet, Pulse, Chats, notifications,
  group chat, and editor preview.
- Same-named bots on different machines intentionally share a face; machine labels disambiguate
  them.
- Non-bot/system rows use an explicit stable product seed, never random state.

### 9.5 App-state mapping

| App state | Expression | Motion | Additional treatment |
| --- | --- | --- | --- |
| Idle/ready | Idle | Static | None |
| Working | Thinking | Ambient + held thinking loop | `Success` activity signal |
| Needs user | Unsure | Subtle held rock loop | `Attention` label/border |
| Error | Sick | Static | Error text in `Danger` |
| Offline/cached | Idle | Static | Whole avatar at 56% alpha |
| Completion event | Happy | One 900 ms pose, then Idle | Only when an existing explicit event is observed |
| Unknown | Idle | Static | Neutral metadata |

Expressions reinforce state but never replace text or status indicators. Do not infer progress,
completion, emotion, or failure from message content.

### 9.6 Size and rendering rules

| Context | Size |
| --- | --- |
| Inline current-action indicator | 20 dp |
| Group stack / compact rail | 28 dp |
| Fleet bot row | 36 dp |
| Pulse/notification row | 40 dp |
| Chat header / prominent card | 44 dp |
| Empty state / editor preview | 64 dp |

- Render against Blobatar's 100 × 100 coordinate system and scale once at draw time.
- Preserve geometry at small sizes; do not substitute simplified shapes unless a parity test proves
  the substitution visually equivalent.
- Snap only the final raster placement needed for crisp edges. Do not round core geometry before
  drawing.
- Eye color and contrast come from the Blobatar palette contract, not theme text colors.
- A generated avatar has no circular crop. Its silhouette occupies the square naturally. Uploaded
  raster avatars remain circular.

### 9.7 Motion and performance

- Use one shared monotonic frame source for all visible animated Blobatars.
- Read frame state inside the draw phase so animation invalidates drawing, not screen composition.
- Only Working and Needs-user avatars animate. Idle roster faces remain static.
- Stop animation for off-screen lazy-list items, backgrounded activities, and disabled
  `TickerMode` equivalents.
- Respect Android's animator-duration scale and Compose reduced-motion handling. Reduced motion
  renders the final static expression with no breathing, bobbing, blinking, rocking, or saccades.
- A 60 Hz source may drive visible animation, but no individual avatar owns a timer.
- On the baseline physical device, a 30-row Fleet scroll must hold the display refresh rate without
  sustained jank attributable to avatar drawing.

### 9.8 Accessibility

- When adjacent text already names the bot, the avatar is decorative and removed from the
  accessibility tree.
- When the avatar is the only representation, use “{display name}, {state}”. Do not expose shape,
  hue, or expression names.
- Motion is never required to identify state.
- Generated and uploaded avatars occupy the same semantic role and bounds.

## 10. Interaction feedback

- Tap/press feedback is a tonal surface change, not a ripple that floods the entire machine card.
- Focus uses a 2 dp `Primary` outline with 2 dp clearance.
- Machine expansion: 240 ms.
- Root crossfade: 120 ms.
- Blobatar completion pose: 900 ms maximum.
- Snackbar entrance/exit: 220 ms; snackbar uses `SurfaceRaised`, `Line`, and `Text`.
- Haptics are limited to long press, destructive confirmation, and successful explicit approval;
  ordinary navigation does not vibrate.

## 11. Responsive and accessibility behavior

- At 320–359 dp, retain 14 dp gutters and let summary metadata wrap beneath machine names.
- At 360–599 dp, use the baseline single-column layout.
- At 600 dp and above, cap the readable content column at 520 dp and center it; do not stretch bot
  rows edge to edge.
- At font scale 1.3+, machine summaries may move to a third line and Chat hub cards stack when
  needed.
- At font scale 2.0, all primary actions and complete labels remain reachable without horizontal
  scrolling.
- Screen-reader traversal follows visible reading order. Collapsed machine headers announce name,
  connection state, summary counts, primary status, and “collapsed/expanded”.
- All text/background pairs must meet WCAG AA. Primary body text targets 7:1 where practical.
- Status is never conveyed by color, face expression, or animation alone.

## 12. Implementation map

| Existing area | Required change |
| --- | --- |
| `ui/theme/Theme.kt` | Install exact color, typography, and shape tokens |
| `ui/theme/Dimens.kt` | Add the named spacing and component dimensions |
| `ui/components/FaceAvatar.kt` | Replace generated fallback with `BlobatarAvatar`; retain uploaded-image path |
| `ui/components/BotFaceClock.kt` | Adapt to one shared Blobatar frame source or replace with `BlobatarMotion` |
| `ui/roster/RosterScreen.kt` | Apply gallery-faithful app bar, search, machine card, section, and bot-row components |
| `ui/activity/ActivityScreen.kt` | Restyle as Pulse with needs-you cards, working rail, and recent rows |
| `ui/chats/ChatsScreen.kt` | Apply hub cards and shared recent-bot rows |
| `ui/settings/*` | Apply shared surfaces, typography, fields, and detail navigation |
| `ui/AppRoot.kt` | Replace default Material navigation indicator with specified bottom bar |

Prefer shared visual primitives only when two or more screens use the same complete behavior. Do
not build a new general-purpose design system around one-off elements.

## 13. Verification contract

### 13.1 Blobatar parity tests

Automated tests must prove:

- Hash states and trait streams match upstream reference vectors exactly.
- NFC, case, whitespace, non-ASCII, emoji, Greek, Arabic, and CJK inputs match.
- Shape names, structured geometry, palette hex values, tone edges, backdrops, and overrides match.
- Rounded paths match exactly where the fixture provides them; trig-derived values may use the
  upstream cross-language relative tolerance of `1e-9`.
- All ten silhouettes and fourteen expressions have explicit coverage.
- Motion seed periods/phases and selected elapsed frames match.
- Uploaded-avatar success and decode-failure fallback are covered.

Reference fixtures are read-only test inputs. Kotlin output must never regenerate its own expected
values.

### 13.2 Compose behavior tests

Cover:

- Smart, remembered, attention, search-forced, and offline machine expansion.
- Machine header copy/count precedence.
- Pulse badge excludes expired requests and working-only activity.
- Needs-you choices come only from server data.
- Determinate progress is absent from working cards.
- Bottom navigation visibility on root versus detail routes.
- Reduced motion disables all avatar and disclosure animation.
- Font scale 2.0 keeps controls reachable.

### 13.3 Screenshot matrix

Capture deterministic screenshots with animations disabled and stable fixture data:

| Viewport/theme | Fleet | Pulse | Chats | Settings |
| --- | --- | --- | --- | --- |
| 360 × 800 dp, dark | Required | Required | Required | Required |
| 411 × 891 dp, dark | Required | Required | Required | Optional |
| 360 × 800 dp, light | Required | Required | Required | Required |
| 360 × 800 dp, dark, font 1.3 | Required | Required | Required | Optional |

Fleet fixtures must include primary/working, attention, quiet collapsed, offline cached, empty
gateway, unread, uploaded avatar, and long names. Pulse fixtures must include actionable choices,
working bots, empty sections, errors, and recent rows.

### 13.4 Visual acceptance

A screen is visually complete only when all of these are true:

- Dark-mode tokens match section 3 exactly.
- Geometry differs from the specified dimensions by no more than 2 dp.
- The current Material selected-tab pill is absent.
- Machine cards read as quiet outlined containers, not large filled tiles.
- Bot rows fit the gallery density while maintaining touch and font-scale requirements.
- Monospace metadata, compact summaries, orange attention, blue selection, and yellow primary mark
  reproduce the gallery hierarchy.
- Generated faces match upstream Blobatar reference output for the fixture seeds.
- Fleet, Pulse, and Chats are reviewed side by side on the connected SM-G781V physical device.
- Build, unit tests, screenshot tests, and Android lint pass.

## 14. Implementation sequence

1. Add font assets, licenses, color/type/spacing tokens, and screenshot fixtures. Completion:
   token previews and theme tests pass in dark and light modes.
2. Port and verify the framework-free Blobatar core. Completion: all upstream parity fixtures pass.
3. Add the Compose renderer and avatar wrapper. Completion: size, expression, uploaded override,
   reduced-motion, and performance tests pass.
4. Build shared search, machine header/card, bot row, section label, and bottom navigation visuals.
   Completion: isolated component screenshots match the specified geometry and tokens.
5. Restyle Fleet without changing its BSpec behavior. Completion: the full Fleet state matrix and
   physical-device comparison pass.
6. Restyle Pulse, Chats, and Settings. Completion: root screenshot matrix and navigation tests pass.
7. Run full verification and inspect on the physical device. Completion: the visual acceptance
   list in section 13.4 is entirely satisfied.

## 15. Out of scope

- New gateway RPCs, payload variants, inferred task progress, or fabricated completion state.
- Remote Blobatar rendering or a runtime dependency on `blobatar.dev`.
- A WebView, JavaScript engine, React Native, Flutter, or server-generated avatar requirement.
- Machine Deck navigation from gallery Option C.
- Redesigning chat bubbles, the bot editor's information architecture, or gateway provisioning
  beyond applying shared tokens.
- Changing stored credentials, connection identity, bot identity, or uploaded-avatar protocol.
