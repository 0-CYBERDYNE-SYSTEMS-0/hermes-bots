#!/usr/bin/env python3
"""Two-way bot<->bot routing verification (host-side harness).

Opens a canonical Bot Chat session on a gateway, submits an instruction that
makes the bot call message_agent toward a peer on another gateway, and records
every protocol event until the turn settles. Run one round:

  uv run --with websockets python scripts/verify-two-way.py \
      --url http://127.0.0.1:9119 --token dev-token-9119 \
      --profile scout --say '...' --mark A1

Event frames follow PROTOCOL.md §3: {"method":"event","params":{type,session_id,seq,payload}}.
"""
import argparse, json, sys, time
from websockets.sync.client import connect

WS_PATH = "/api/ws"

def rpc(sock, mid, method, params):
    sock.send(json.dumps({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}))
    while True:
        msg = json.loads(sock.recv(timeout=30))
        if msg.get("id") == mid:
            if "error" in msg:
                raise RuntimeError(f"{method} error: {msg['error']}")
            return msg.get("result")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token")
    ap.add_argument("--profile", required=True)
    ap.add_argument("--say", required=True)
    ap.add_argument("--mark", required=True)
    ap.add_argument("--budget", type=int, default=420, help="seconds to wait for settle")
    ap.add_argument("--wait-followup", type=int, default=0,
                    help="keep listening N s after the first complete for the async relay reply")
    ap.add_argument("--auto-approve", action="store_true",
                    help="respond 'once' to approval.request events (what the app's card does)")
    a = ap.parse_args()

    wsurl = a.url.replace("http://", "ws://").replace("https://", "wss://") + WS_PATH
    if a.token:
        wsurl += "?token=" + a.token
    sock = connect(wsurl, open_timeout=10, max_size=None)

    res = rpc(sock, 1, "session.create", {
        "title": "Bot Chat", "hidden": True, "profile": a.profile,
        "close_on_disconnect": False,
    })
    sid = res.get("session_id") or res.get("id")
    print(f"[{a.mark}] session {sid} on {a.url} profile={a.profile}", flush=True)

    rpc(sock, 2, "prompt.submit", {"session_id": sid, "text": a.say})
    print(f"[{a.mark}] submitted; waiting for settle (budget {a.budget}s)", flush=True)

    tools, final_text, err = [], "", None
    deadline_followup = 0.0
    t0 = time.time()
    last_event = t0
    while time.time() - t0 < a.budget and time.time() - last_event < 180:
        try:
            msg = json.loads(sock.recv(timeout=15))
        except TimeoutError:
            continue
        if msg.get("id") is not None and "error" in msg:
            err = msg["error"]; break
        if msg.get("method") != "event":
            continue
        p = msg.get("params") or {}
        if p.get("session_id") != sid:
            continue
        last_event = time.time()
        etype, pay = p.get("type"), p.get("payload") or {}
        if etype == "tool.start":
            tools.append(pay.get("name") or "?")
            print(f"[{a.mark}] tool.start {pay.get('name')} t+{time.time()-t0:.0f}s", flush=True)
        elif etype == "tool.complete":
            print(f"[{a.mark}] tool.complete t+{time.time()-t0:.0f}s", flush=True)
        elif etype == "message.complete":
            final_text = pay.get("text") or ""
            status = pay.get("status")
            print(f"[{a.mark}] message.complete status={status} t+{time.time()-t0:.0f}s", flush=True)
            if status == "error" or pay.get("error"):
                err = pay.get("error") or status
            if a.mark in final_text or err or a.wait_followup <= 0:
                break
            # First turn done (e.g. "message sent"); keep listening for the relay reply.
            deadline_followup = time.time() + a.wait_followup
        elif etype == "approval.request":
            print(f"[{a.mark}] approval.request t+{time.time()-t0:.0f}s", flush=True)
            if a.auto_approve:
                req = pay.get("request_id") or ""
                sock.send(json.dumps({"jsonrpc": "2.0", "id": 1000 + len(tools), "method": "approval.respond",
                                      "params": {"session_id": sid, "request_id": req, "choice": "once"}}))
                print(f"[{a.mark}] approved 'once' req={req[:12]}", flush=True)
        elif etype == "error":
            err = pay; print(f"[{a.mark}] protocol error event: {pay}", flush=True)
            break

    if a.wait_followup > 0 and not err and a.mark not in final_text:
        print(f"[{a.mark}] waiting up to {a.wait_followup}s for the async relay reply", flush=True)
        while time.time() < deadline_followup:
            try:
                msg = json.loads(sock.recv(timeout=15))
            except TimeoutError:
                continue
            if msg.get("method") != "event":
                continue
            p = msg.get("params") or {}
            if p.get("session_id") != sid:
                continue
            etype, pay = p.get("type"), p.get("payload") or {}
            if etype == "message.complete":
                text = pay.get("text") or ""
                print(f"[{a.mark}] follow-up t+{time.time()-t0:.0f}s: {text[:200]}", flush=True)
                if a.mark in text:
                    final_text = text
                    break

    ok = bool(final_text) and a.mark in final_text and not err
    print(f"[{a.mark}] RESULT {'PASS' if ok else 'CHECK'} tools={tools or '-'}")
    print(f"[{a.mark}] FINAL: {(final_text or str(err))[:500]}")
    return 0 if ok else 2

if __name__ == "__main__":
    sys.exit(main())
