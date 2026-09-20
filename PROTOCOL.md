# Hermes Gateway Wire Protocol — Client Bible

> The exact contract for the Android client (`ai.hermes.bots`). Every method name and param
> key below was extracted from the hermes-agent source at `~/.hermes/hermes-agent`
> (upstream: `https://github.com/NousResearch/hermes-agent`, MIT). Citations are
> `path:line` relative to that repo. **Hardcode these strings. Do not invent variants.**
> If behavior differs at runtime, re-verify against source at the cited location.

---

## 1. Architecture map

Three "server" concepts exist. Only the first matters to this app:

| Concept | What it is | Where |
|---|---|---|
| **`hermes serve` / `hermes dashboard`** | One HTTP+WS server (FastAPI/uvicorn). Default port **9119**. This is what we connect to. | `hermes_cli/web_server.py`, `hermes_cli/subcommands/dashboard.py:1-60` |
| `hermes gateway` | Separate long-running process bridging 13+ chat platforms (Telegram, Discord, Slack, WhatsApp, …) + OpenAI-compatible API on **8642**. Not our concern except that its state shows up in profiles. | `gateway/run.py` |
| `tui_gateway/` | The JSON-RPC dispatcher implementation shared by TUI/desktop/dashboard — rides inside `serve`. | `tui_gateway/server.py`, `tui_gateway/ws.py` |

The desktop app (Electron) spawns a loopback `hermes serve` and connects to it over the same
protocol we will use remotely. The desktop is **not special** — it is a reference client.

---

## 2. Transport

### Endpoint
`ws(s)://host[:port]/api/ws` — mounted in `hermes_cli/web_routers/chat_ws.py:541`, handled by
`tui_gateway/ws.py:handle_ws`. Desktop reference URL builder: `apps/desktop/electron/connection-config.ts:102-108`.

### Auth on the upgrade
Two modes, detected via `GET /api/status` (public, no auth) → response field **`auth_required`**
(`apps/desktop/electron/connection-config.ts:919-921` reads it; `hermes_cli/dashboard_auth/public_paths.py` lists `/api/status` as public):

- **Token mode** (`auth_required:false` or loopback): append **`?token=<SESSION_TOKEN>`**.
  Verified constant-time against `_SESSION_TOKEN` (`hermes_cli/web_server_chat.py:285-290`).
  The token comes from env `HERMES_DASHBOARD_SESSION_TOKEN` or a fresh `secrets.token_urlsafe(32)`
  per server start (`hermes_cli/web_server.py:292-299`).
- **Gated mode** (OAuth/basic auth provider, `auth_required:true`): `?token=` is **rejected**. Use one of:
  - `?ticket=<TICKET>` — single-use ticket from authenticated `POST /api/auth/ws-ticket`, **30 s TTL**
    (`hermes_cli/dashboard_auth/ws_tickets.py:21`);
  - or WS subprotocol `hermes-gateway-ticket.<ticket>` alongside advertised `hermes-gateway-v1`
    (`web_server_chat.py:202-217`).
  Basic-auth credentials go as standard `Authorization: Basic base64(user:pass)` on the REST call
  that mints the ticket (desktop sends custom headers on the upgrade too — `connection-config.ts:523-545`).

Close codes on failed upgrade: **4401** bad credential · **4403** host/origin disallowed ·
**4404** chat disabled · **4408** peer not loopback in loopback mode (`chat_ws.py:102-149`).

### Header rules for a native client
- **Do not send an `Origin` header** — no Origin is explicitly allowed (`web_server_chat.py:182`).
- **Host header must be the real bound host:port** — never rewrite it.
- A remote (non-loopback) server must have been started with `--host 0.0.0.0` and an auth
  provider configured (`hermes_cli/main_dashboard.py:435-515`).

### Framing & limits
- One JSON object per WS **text frame**; `json.dumps(..., ensure_ascii=False)` outgoing
  (`ws.py:302-322`, `ws.py:101-104`). No newline framing over WS.
- Server max incoming frame: raised to **384 MiB** for base64 attachments (`web_server.py:367-369`).
  Our client should cap uploads far below this.
- TCP_NODELAY + keepalive set by server (`ws.py:213-232`).

---

## 3. Handshake, heartbeat, catch-up

### First frame after accept (always)
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready",
 "payload":{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":"<hex>"}}}
```
(`ws.py:272-277`). **Store `replay_epoch`.** If it changes after a reconnect, reset all
per-session `seq` watermarks (`tui_gateway/event_replay.py:16-20`).

### Heartbeat (client-driven, mandatory)
Every **15 s** send `{"jsonrpc":"2.0","id":"h1","method":"gateway.ping"}` → inline reply
`{"jsonrpc":"2.0","id":"h1","result":{"ok":true}}` (`ws.py:325-328`). If no ack within **45 s**,
force reconnect (reference client: `ui-tui/src/gatewayClient.ts:30-31`). There is no
server-initiated ping. A dispatcher-level `ping` method also exists (`{"pong":true}`,
`tui_gateway/methods_voice.py:442-446`) that works mid-turn — useful for health probes.

### Reconnect / catch-up
Reconnect with exponential backoff (1 s → 30 s cap). Then per session you were viewing:

```
→ {"jsonrpc":"2.0","id":N,"method":"session.events.since",
   "params":{"session_id":"<sid>","last_seen":<last seq>}}
← {"events":[{"type":...,"session_id":...,"payload":{...},"seq":N}, ...],
   "latest_seq":N,"truncated":bool,"count":N,"epoch":"<hex>"}
```
(`tui_gateway/methods_session.py:2122-2134`). Replayed frames are **bare params**, not full
JSON-RPC envelopes (`event_replay.py:63-71`). Server-side ring: **512 events × 64 sessions**.
If `truncated:true` or `epoch` ≠ stored `replay_epoch` → **refetch via `session.resume`**
instead of trusting the replay.

On disconnect the server reaps sessions you created with `close_on_disconnect:true` and
detaches others with a grace window (`ws.py:345-371`). **Use `close_on_disconnect:false`**
for the canonical bot chats so the bot relationship survives mobile disconnects.

---

## 4. JSON-RPC conventions

- Requests: `{"jsonrpc":"2.0","id":<int|str>,"method":"...","params":{...}}` — **params must be
  an object**, never omitted/null (`tui_gateway/server.py:713-723`). **No batching** — one
  request per frame.
- Responses: `result:{...}` or `error:{code,message,data?}` (`server.py:701-703`).
  Codes: -32700 parse, -32600 invalid request, -32602 invalid params, -32601 unknown method,
  -32603 internal, -32000 handler error; method-specific codes are 4xxx/5xxx.
- Long-running handlers (`prompt.submit`, `session.resume`, `profiles.list`, `bot_relay.*`, …)
  run on a thread pool — **their responses may arrive after later calls' responses**
  (`server.py:756-781`). Match by `id`, never by order.
- Server push: `{"jsonrpc":"2.0","method":"event","params":{"type":"<kind>",
  "session_id":"<sid>","payload":{...},"seq":N}}` — no `id`. `session_id`/`seq` omitted for
  global events (`server.py:571-577`, `ws.py:272-277`). `seq` is per-session monotonic
  (`event_replay.py:39-60`).

---

## 5. RPC method catalog

### 5.1 Turn loop (chat)
| Method | Params | Result / behavior |
|---|---|---|
| `prompt.submit` | `session_id`, `text`; opt `display_kind:"hidden"`, `interrupted`, `queued`, `surface`, truncation params (`truncate_before_user_ordinal`, `truncate_before_row_id`, `confirm_truncate`, `confirm_empty_truncate`), `rebind_survivor_row_ids` | Returns **immediately** `{"status":"streaming","survivor_user_row_ids"?:[...]}` (`methods_prompt.py:534-633`). Turn then streams events (below). Busy session → error **4091**; over session cap → **4090** with `data.reason`. |
| `session.interrupt` | `session_id` | Stop the running turn (`methods_session.py:1922`). |
| `session.steer` / `session.redirect` | `session_id`, `text` | Inject text mid-turn (`methods_session.py:1975-1989`). |
| `prompt.background`, `prompt.btw` | | Background turns (`methods_prompt.py:953,968`). v2. |

**Turn event sequence** (payload builder `prompt_turn.py:622-676`):
`message.start` → N× `message.delta {text, rendered?}` (coalesced ~33 ms batches, `ws.py:63-69`)
→ interspersed `tool.start` / `tool.complete` / `status.update` / `todo.updated`
→ `message.complete {text, usage, status, reasoning?, warning?, error?, recoverable?,
error_surface?, rendered?, billing?}` — `status` ∈ `"complete"|"error"|"cancelled"|…`.
Interim: `message.interim {text, already_streamed}`.

### 5.2 Sessions
| Method | Params | Result |
|---|---|---|
| `session.create` | `messages`?(seed) `parent_session_id`? `cwd`? `title`? `hidden`? `close_on_disconnect`? `room_plumbing`? `follow_profile_config`? `profile`? `model`/`provider`? `fast`? `cols`? | `{"session_id","stored_session_id","message_count","messages","info":{model,provider,tools,skills,cwd,branch,project,lazy,desktop_contract,profile_name}}` (`methods_session.py:296-360`). **`session_id` = runtime id for prompt.submit; `stored_session_id` = durable key across restarts.** No DB row until first prompt. |
| `session.list` | `limit`?(200) `include_hidden`? OR exact `title` | `{"sessions":[{id,resolved_id,root_title,title,preview,started_at,last_active,message_count,…}]}` (`methods_session.py:390-402`). Exact-title lookup resolves compression lineage to live tip (`363-387`). |
| `session.resume` | `session_id` (stored id or key); **`profile` REQUIRED for profile-scoped sessions on server ≥0.21.1** (without it the root-home registry is searched → 4007 `session not found`; verified live 2026-09-11 — `session.resume {session_id:"<scout canonical>", profile:"scout"}` → 83 msgs, same call without `profile` → 4007); opt `cols`,`omit_messages`,`defer_history`,`eager_build` | `{"session_id","resumed","message_count","messages","info","inflight","running","session_key","started_at","status",…,"pending_approval"?,"pending_clarify"?,"queued"?,"todo_state"?}` (`787-818`, `651-667`). Use `inflight`/`pending_approval` to re-render a turn that was mid-flight when we disconnected (`server.py:2690-2726`). |
| `session.close` | `session_id` | `{"closed":bool}` |
| `session.compress` | `session_id`, `focus_topic`? | `{"compressed":bool,…}` — this is what `/new` maps to inside a canonical bot chat. |
| `session.title` | `session_id` (read) or + `title` (write) | `{title,session_key,pending?}` + emits `session.info` |
| `session.delete` / `session.set_hidden` / `session.active_list` / `session.most_recent` | | roster/session hygiene (`methods_session.py:876-971`) |
| `session.events.since` | see §3 | catch-up |

### 5.3 Profiles (= **bots**)
| Method | Params | Result |
|---|---|---|
| `profiles.list` | `include_sessions`?(true) | Rows: `{name,path,is_default,model,provider,description,display_name,skill_count, last_session:{id,title,preview,started_at,last_active,message_count}, worker_session:{id,source,title,last_active}, canonical_session:{id,resolved_id,root_title,title,preview,started_at,last_active,message_count}, ui_meta:{…}, ui_meta_revisions:{key:int}, has_avatar:bool}`; result carries `bot_mode_protocol:true` (`methods_profiles.py:237-254`, presence fields built `175-221`, canonical resolution `147-172`). |
| `profiles.create` | `name` (req), `description`?, `clone_from`?, `clone_all`?, `no_skills`?, `soul`?, `model`+`provider`?, `share_auth`?, `mirror_credentials`? | `{ok,name,path,soul_written,model_set,mirrored}` (`337-374`). Errors: 4061 name required, 4062 failed. |
| `profiles.describe` | `name` | Editor snapshot: `soul`, model, skills, toolsets, mcp_servers (`400-433`). |
| `profiles.configure` | `name` + any of: `ui_meta` (dict; **merge**; `null` value deletes key; 64 KB cap), `ui_meta_revisions`/`ui_meta_expected_revisions` (per-key CAS — mismatch rejects whole write, returns `ui_meta_conflicts`), `soul`, `description`, `model`+`provider` (may need `confirm_expensive_model`), `disabled_skills`, `enabled_toolsets`, `enabled_mcp_servers` | `{ok,applied:{…},confirm_required?,confirm_message?}` (`563-586`). Error 5064. |
| `profiles.set_asset` | `name`, `asset:"avatar"`, `data` (data-URL or base64 PNG/JPEG/WebP **≤2 MB**) or `clear:true` | `{ok,asset,size,removed?}` (`595-630`). Errors 4066/4069/4070. |
| `profiles.get_asset` | `name`, `asset` | `{found,mime,size,data}` / `{found:false}` (`633-647`). |

**Bot identity convention:** a bot's UI state lives in `ui_meta["hermes-bots"]` (sections,
colors, groups, hidden flag, image kind — the desktop strips data-URLs before writing,
`apps/desktop/src/plugins/hermes-bots/data.ts:299-440`). Avatars ride `set_asset`, never `ui_meta`.

### 5.4 Config & models
| Method | Params | Result |
|---|---|---|
| `config.get` | `key` ∈ {`provider`,`profile`,`project`,`full`,`prompt`,`skin`,`indicator`,`personality`,`reasoning`,`fast`,`busy`,`approval_mode`,`approvals.mode`,`details_mode`,`thinking_mode`,`density`,`theme`,`statusbar`,`focus`,`mouse`,`mtime`}; opt `profile` | `{"value":…}` / `{"config":…}` / `{"home":…}` (`methods_config.py:199-240`) |
| `config.set` | `key`,`value` | may return `confirm_required`/`confirm_message` (`methods_config_set.py:458`) |
| `model.options` | `explicit_only`?,`include_unconfigured`?,`refresh`?,`session_id`?/`profile`? | full provider/model picker inventory (`methods_complete.py:278-287`) |
| `model.save_key` | `slug`,`api_key` | provider record + `authenticated:true` (`290-320`); `model.disconnect {slug}` |
| `gateway.capabilities` | — | `{"per_session_exclusive_submit":bool}` — gate submit UX on it (`methods_voice.py:434-439`) |

### 5.5 Approvals & blocking prompts (must handle in chat)
Server pushes `approval.request {request_id, command?, choices:["once","session","always","deny"],…}`
(`server.py:662-668`, payload builder `614-627`). Pending approvals also come back on
`session.resume.pending_approval` and are re-emitted on reconnect.

| Method | Params | Result |
|---|---|---|
| `approval.respond` | `session_id`?,`request_id`,`choice` ∈ payload `choices` (`"once"` default),`all`? | `{"resolved":…}` (`methods_prompt.py:1104-1168`; stale-session fallback by request_id across live sessions `1124-1151`) |
| `approval.pending` | `session_id` | `{"approvals":[…]}` |
| `clarify.respond` | mirror of clarify.request payload (`request_id`, chosen answer) | same pattern |
| `sudo.*`, `secret.request`→`secret.respond`, `mcp.setup.*`, etc. | same `_block` factory (`server.py:1228-1273`) | each blocking prompt has a matching `*.respond` + `*.expire {request_id}` event on timeout |

**0.21.3+ dialect (verified against `tui_gateway/server_requests.py` in 0.21.3):** blocking
prompts also arrive as JSON-RPC **server-initiated requests** — a frame with an `id`
(`"srq-<12hex>"`) and `method` ∈ `clarify`/`approval`/`sudo`/`secret`, `params` carrying
`session_id` + the prompt fields (`clarify`: `question`/`questions:[{qid,question,choices,…}]`,
`choices`) — with **no** `*.respond` method and **no** `*.expire`; the answer is the JSON-RPC
**result frame** for that id (`clarify`: `{"answer"}` or batch `{"answers":{qid:…}}`, `""` =
skip; approval mirrors `{"choice"}`), and timeouts/interrupts arrive as
`request.cancel {id, method, reason}`. Unanswered prompts come back from `session.resume` as
`open_requests:[{id, method, params}]`. The app re-emits these internally as the legacy
`<method>.request` event shape (payload + `request_id` = srq id + `server_request:true`), so
chat/notifications speak one card model; `ApprovalCard.serverRequestId` selects the dialect
for the answer.

### 5.6 Groups (bot group chats)
`tui_gateway/methods_groups.py`:
- `groups.capabilities` → `{protocol_version, driver, authority_gateway_id, room_link, features, methods, max_log_limit}` (`218-247`)
- `groups.list {limit,offset,include_disbanded}` → `{rooms,next_offset}` (`346`)
- `groups.create {room_id?, name, members}` → `{room}` (idempotent) (`359`). **Runtime finding
  (v0.21.0): `room_id` is effectively REQUIRED** — the handler passes it through
  (`methods_groups.py:365`) and `create_room` validates it as a string even when absent
  (`gateway/hosted_rooms.py:835` → `gateway/hosted_rooms_common.py:27-28`), so omitting it
  fails with **4110 "room_id must be a string"**. Clients must generate one (e.g. UUID);
  the desktop always does.
- `groups.state {room_id}` → `{room, driver_status?}` (`369`)
- `groups.send {room_id, event_id?, payload}` → `{event, client_event_id, accepted, driver_started}` (`383`).
  **Runtime finding (v0.21.0): the user-event payload must be EXACTLY `{text, thread_id}`**
  (`gateway/hosted_room_discussion.py:49` `_USER_PAYLOAD_FIELDS`, enforced by `_exact_fields`) —
  an extra `"type":"message.user"` key fails with **5112 "user payload has unknown fields: type"**
  (the type is implied for user events; see `gateway/hosted_rooms.py:51`).
- `groups.log {room_id, since_seq=0, limit≤max_log_limit}` → monotonic room-log delta
  `{events:[{room_id,seq,event_id,kind,actor,payload,created_at,…}]}` — the room TRANSCRIPT RPC
  (missing from earlier drafts of this doc; the client's room view is fed by this, not
  `groups.state`). Passthrough to `gateway.hosted_rooms.read_events`
  (`tui_gateway/methods_groups.py:489-495`, `gateway/hosted_rooms.py:1111-1150`).
- `groups.disband {room_id, cancel_id?}` (`398`), `groups.stop {room_id, cancel_id?}` (`431`)
- `groups.approve {room_id, member_id, task_id, execution_generation, choice, request_id}` (`439`), `groups.retry {room_id, task_id}` (`450`)
- Pending user actions ride `groups.state` → `driver_status.pending_actions` (NOT in
  `groups.log`; the log's event-kind taxonomy excludes them): approval actions are
  `{kind:"approval", task_id, execution_generation, run_id, session_id, request_id,
  approval:{request_id, choices:["once","deny"], command?…}, member_id}`; retry actions are
  `{kind:"retry", task_id}` for indeterminate tasks; `choice` is `"once"` or `"deny"`
  (`tui_gateway/methods_groups.py:369-377`, `tui_gateway/hosted_room_service.py:74,296-304,532-548`,
  `tui_gateway/hosted_room_driver.py:392-405`).
- Group member sessions are created with `room_plumbing:true`, hidden.

### 5.7 Bot relay (cross-connection A2A — the app becomes the relay)
`tui_gateway/methods_bot_relay.py`. Desktop reference loops: `apps/desktop/src/plugins/hermes-bots/relay.ts:29-68,247-311,316-483`.
- `bot_relay.roster.sync {agents:[{profile,handle,connection_id,connection_label,title,description}]}` → `{count}` — push the roster of agents on *other* connections to each gateway (`39-47`). **Loop: every 60 s.** **Runtime finding (2026-09-10): write_remote_roster on our install MERGE-UPSERTS by (connection_id, profile)** — a push owns its connections' rows and preserves other connections' rows (empty push = no-op) — because this install runs several relay clients (desktop app + this app) sharing one `~/.hermes/bot_relay/roster.json`; the upstream replace semantics let each client's sync erase the others', breaking `message_agent` target resolution (see `docs/patches/hermes-agent-relay-multi-client.patch`; upstream may differ).
- `bot_relay.outbox.drain {connections?: [connection_id,…]}` → `{envelopes:[{id,message,target_connection,target_profile}]}` — atomic claim (`50-58`). **Loop: every 30 s + immediately on event `bot_relay.outbox.pending`** (debounced). **Runtime finding (2026-09-10): the app always sends `connections` = its own connection ids** — the claim then skips envelopes addressed to other clients' connections (they resolve via the 900 s envelope TTL `'queued_expired'` instead of being stolen); omitting the param keeps upstream claim-everything behavior.
- `bot_relay.deliver {profile, message}` → `{reply}` — blocking on the **target** connection's socket; budget = 120 s lock-wait + 600 s turn × 2 attempts ⇒ **client timeout must exceed ~1320 s** (`28-29`). Errors 4090/4091/4092/5092 with `data.reason`, 5093 timeout.
- `bot_relay.reply {id, reply?, error?, reason?}` → `{ok:true}` — post back on the **sender's** socket (`146-161`).
- Server-side spool: `~/.hermes/bot_relay/{claimed,outbox,replies,roster.json}`.

### 5.8 Assets / files / exec (supporting cast)
- `image.attach {session_id, …}` / `image.attach_bytes` / `file.attach {session_id, data:<dataURL>}` (`methods_prompt.py:669-856`) — v1.5.
- `image.generate {prompt, aspect_ratio?, …}` → `{available, success, image, image_data?}` (`methods_images.py:43-82`) — v2.
- `cli.exec {argv, timeout?≤600}` → `{blocked,code,output}` (`methods_tools.py:434-447`); `shell.exec {command}` (30 s cap; dangerous blocked 4005) — power features, v1.5.
- `voice.record {action:"start"|"stop"}` → events `voice.transcript {text}` / `voice.status` / `voice.interrupted` (`methods_voice.py:703-756`) — v2.

### 5.9 Change-notification events (demote polling)
`change_watcher.py:176-190` broadcasts: `sessions.changed`, `cron.changed`, `platforms.changed`,
`pairing.changed`, `pet.changed`, **`bot_relay.outbox.pending`** (payload `{}`, 0.5 s floor / 2 s
cadence). On receipt, re-poll the corresponding resource ahead of schedule.

---

## 6. Full event taxonomy (params.type)

| type | payload (abbrev) | source |
|---|---|---|
| `gateway.ready` | `{skin, change_events, heartbeat, replay_epoch}` | ws.py:272 |
| `message.start` | — | prompt_turn.py:776 |
| `message.delta` | `{text, rendered?}` | prompt_turn.py:521 |
| `message.interim` | `{text, already_streamed}` | prompt_turn.py:526 |
| `message.complete` | `{text, usage, status, reasoning?, warning?, error?, recoverable?, error_surface?, rendered?, billing?, failure_reason?}` | prompt_turn.py:799 |
| `reasoning.available` | `{text, verbose?}` | tool_progress.py:261 |
| `thinking.delta` | `{text}` | agent_callbacks.py:92 |
| `tool.start` | `{tool_id, name, context, args?, args_text?}` | tool_progress.py:208 |
| `tool.complete` | `{tool_id, name, args, result, duration_s?, summary?, inline_diff?}` | tool_progress.py:240 |
| `tool.generating` / `tool.output_risk` | `{name}` / `{tool_id,name,risk,findings,redacted}` | agent_callbacks.py:91, tool_progress.py:254 |
| `todo.updated` | normalized todo state | tool_progress.py:244 |
| `status.update` | `{kind:"loop"|"process"|"goal", text}` | session_notifications.py:147,332 |
| `approval.request` (+`clarify.request`,`sudo.request`,`secret.request`,`mcp.setup.request`,…) | blocking prompt payloads | server.py:662-668,1228-1273 |
| `*.expire` | `{request_id}` | server.py:1228-1273 |
| `session.title` | `{session_id:<stored key>, title}` | prompt_turn.py:547 |
| `session.info` | model/provider/cwd/branch snapshot | agent_callbacks.py:150,225,384 |
| `session.usage` | usage ticker | prompt_turn.py:549 |
| `session.resume_progress` | hydration progress | methods_session.py |
| `session.reclaimed` | session taken by another viewer (global) | session_lifecycle.py:280 |
| `error` | `{message}` | prompt_turn.py:96,492,708 |
| `notice` / `notification.show` / `notification.clear` | `{…}` / `{key}` | agent_callbacks.py:99-102 |
| `reaction` | `{kind}` | agent_callbacks.py:94 |
| `moa.progress` / `moa.phase` / `moa.aggregating` | MoA subagent progress | tool_progress.py:271-352 |
| `voice.*`, `wake.*` | v2 | methods_voice.py |
| `sessions.changed`, `cron.changed`, `platforms.changed`, `bot_relay.outbox.pending`, … | `{}` | change_watcher.py:176-190 |

---

## 7. REST endpoints the client needs

Auth headers: **`X-Hermes-Session-Token: <token>`** (canonical, `web_server.py:299,383-395`) or
legacy `Authorization: Bearer <token>`. In gated mode: standard `Authorization: Basic …` for the
browser-flow endpoints + ticket minting.

| Route | Use |
|---|---|
| `GET /api/status` (public) | health + `auth_required` detection — the **first call** when adding a gateway |
| `POST /api/auth/ws-ticket` (gated mode) | mint single-use WS ticket (30 s TTL) |
| `GET /api/cron/jobs`, `POST /api/cron/jobs`, `PUT/DELETE /api/cron/jobs/{id}` | routines UI (`web_routers/cron.py:211-271`) — **desktop bots plugin uses REST for cron, not RPC** (`cron.tsx`); pause/resume are `POST /api/cron/jobs/{id}/pause` and `…/resume` (`web_routers/cron.py:251-257`) |
| `GET /api/profiles` | legacy/simple; prefer RPC `profiles.list` (richer: sessions, ui_meta, avatar) (`web_routers/profiles.py:73-95`) |
| `GET /api/model/options` | REST mirror of `model.options` (`programmatic-integration.md:129-140`) |
| `/api/files/download?token=` | only route that takes `?token=` (`web_server.py:395-404`) |
| `/api/audio/transcribe`, `/api/audio/speak` | v2 voice (`web_routers/audio.py:77-339`) |

`/api/pub` and `/api/events` are **WebSockets**, not HTTP — a transport relay for a separate
publisher process. **Do not use them**: no seq, no replay. `/api/ws` is strictly better for us.

---

## 8. Remote gateway recipe (what the connection screen must implement)

1. User enters base URL (accept scheme-less `host:port`, prefix `http://`; default port 9119 —
   `connection-config.ts:66-100`).
2. `GET /api/status` → parse `auth_required` (and reachability).
3. If token mode: store token; WS URL = `ws(s)://host[:port]/api/ws?token=<urlenc>`.
4. If gated mode: store user+pass; every connect → `POST /api/auth/ws-ticket` with Basic auth →
   `ws(s)://…/api/ws?ticket=<ticket>` (30 s TTL, single use). Re-mint on every reconnect.
5. "Test" button: HTTP leg (status call) + WS leg (connect, success = any frame received, e.g.
   `gateway.ready`, or socket open after 750 ms grace; 10 s connect timeout —
   `apps/desktop/electron/gateway-ws-probe.ts:41-180`).
6. Fixed token provisioning on the server side (document in README): set
   `HERMES_DASHBOARD_SESSION_TOKEN=<token>` in the gateway's env, run `hermes serve --host 0.0.0.0 --port 9119`.

### Headless serve on a remote box (README material)
`hermes serve --host 0.0.0.0 --port 9119` (`subcommands/dashboard.py:17-59`). Non-loopback
binds **require an auth provider** — interactive setup offers basic (username & password) or
OAuth (`main_dashboard.py:435-515`). `--insecure` is a deprecated no-op post June-2026 hardening.
`--profile <name>` runs a server scoped to one profile. For token mode set
`HERMES_DASHBOARD_SESSION_TOKEN` first.

---

## 9. Minimum-viable client recipe (checklist)

1. `GET /api/status` → `auth_required`.
2. Dial WS (token or ticket as above; no Origin header; Host intact).
3. First frame = `gateway.ready` → store `replay_epoch`. Any other first frame = protocol error.
4. Heartbeat `gateway.ping` every 15 s; reconnect if silent 45 s; backoff 1→30 s.
5. Roster: `profiles.list {}` every 5 s; demote to backstop on `sessions.changed`.
6. Open bot chat: `session.resume {session_id: canonical_session.resolved_id}` (create hidden
   `"Bot Chat"` session with `close_on_disconnect:false` if absent) → render `messages` → live events.
7. Send: `prompt.submit {session_id, text}` → follow message.* events.
8. Approvals/clarifies: render cards → `approval.respond` / `clarify.respond`.
9. Reconnect: `session.events.since {session_id, last_seen}` per open session; epoch-change or
   `truncated` → full `session.resume`.
10. Multi-gateway relay: §5.7 loops, verbatim timings.

---

## 10. Verification map (source files)

| Area | File(s) |
|---|---|
| WS endpoint + close codes | `hermes_cli/web_routers/chat_ws.py`, `hermes_cli/web_server_chat.py`, `tui_gateway/ws.py` |
| Auth headers/public paths | `hermes_cli/web_server.py`, `hermes_cli/dashboard_auth/public_paths.py`, `ws_tickets.py` |
| Dispatcher + envelope | `tui_gateway/server.py` |
| Event replay/catch-up | `tui_gateway/event_replay.py` |
| Method handlers | `tui_gateway/methods_{prompt,session,profiles,config,config_set,complete,voice,images,tools,groups,bot_relay}.py` |
| Turn events | `tui_gateway/prompt_turn.py`, `agent_callbacks.py`, `tool_progress.py`, `session_notifications.py`, `change_watcher.py` |
| Desktop reference client | `apps/desktop/electron/connection-config.ts`, `gateway-ws-probe.ts`, `apps/desktop/src/plugins/hermes-bots/*` |
| Programmatic integration doc | `website/docs/developer-guide/programmatic-integration.md` |
| Multi-connection doc | `website/docs/user-guide/multi-connection-desktop.md` |
| Bot mode doc | `website/docs/user-guide/bot-mode.md` |
