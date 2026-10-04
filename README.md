# Hermes Bots for Android

Hermes Bots is a native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent) gateways. It lets you chat with and manage bots from a phone; the gateway runs the agent, tools, and scheduled jobs.

## Features

- Bot roster across gateways, with search, sections, avatars, hidden bots, and activity indicators.
- Persistent per-bot chats with streaming replies, approval and clarify cards, interrupt and steer, slash commands, and reconnect recovery.
- Bot editor for models, skills, profile details, and avatars.
- Per-bot routines, multi-bot group chats, cross-gateway bot relay, and local activity notifications.

Voice dictation, image generation, terminal views, and OAuth-portal authentication are not currently included.

## Gateway compatibility

Use Hermes Agent **0.21.5 or newer** for the feature set documented here. Android can connect to older gateways for core chat, but newer RPCs may be missing: server-request prompts use a capability introduced in 0.21.4, and gateway slash-command routing is documented against 0.21.5. The app falls back to legacy prompt handling where supported; older gateways may not support slash commands. No maximum gateway version is specified.

## First-time setup

1. Install Hermes Agent on a machine that can stay available, following its [installation instructions](https://github.com/NousResearch/hermes-agent). Use the gateway version above.
2. Install Hermes Bots on Android from the APK provided by your distributor, or build and install it using the instructions below.
3. Choose a connection route: configure an [HTTPS network connection](#connect-over-a-network), including the bundled Tailscale setup, or use the [USB setup](#connect-a-usb-phone-for-local-development) for local development.
4. If adding a gateway manually, open **Gateways → +**, enter its URL, choose **Token** or **User + password**, enter the matching credential, tap **Test**, then **Save**. Star a gateway to make it primary.
5. Open the bot roster and select a bot to start chatting.

### Connect a USB phone for local development

When Hermes Agent is bound to `127.0.0.1` on the computer, forward its port to the connected phone:

```bash
export HERMES_DASHBOARD_SESSION_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf 'Gateway token (keep it private): %s\n' "$HERMES_DASHBOARD_SESSION_TOKEN"
hermes serve --host 127.0.0.1 --port 9119
adb reverse tcp:9119 tcp:9119
```

Store a stable token in the service environment if the gateway should keep using it after restart. In Hermes Bots, use `http://127.0.0.1:9119` and the gateway token. This loopback route is for local development.

### Connect over a network

Use an **HTTPS URL with a valid certificate** for any remote gateway. A TLS-enabled reverse proxy or an HTTPS endpoint on your private network can forward both HTTP requests and WebSocket traffic to Hermes Agent. Configure gateway authentication as well. The app rejects cleartext HTTP for remote hosts; do not use basic authentication or a gateway token over ordinary LAN or public Wi-Fi HTTP.

For Tailscale, configure an HTTPS endpoint that forwards to the gateway, then connect with its DNS name, for example `https://<machine>.<tailnet>.ts.net`. Do not use an HTTP tailnet IP address.

Configure gateway authentication for every connection. The bundled Tailscale helper provisions a gateway token; if you expose Hermes directly beyond loopback, configure gateway authentication on that server. Android supports gateway tokens and basic username/password authentication; OAuth-portal sign-in is not currently supported.

To provision a gateway with the bundled Tailscale HTTPS setup, run this on the macOS gateway machine:

```bash
bash scripts/provision-tailnet-gateway.sh --qr
```

The macOS helper binds Hermes Agent to loopback, exposes it through Tailscale Serve HTTPS, and provisions a persistent gateway token. Keep the machine and phone on the same tailnet. With Python's optional `qrcode` package installed, `--qr` prints a scannable QR code. Without it, the helper prints and saves the deep link as text; you can install `qrcode` and rerun the helper, or add the printed HTTPS URL and token manually in **Gateways → +**. The QR code and deep link carry access information; treat them like a password and do not share or post them.

When entering a remote address, include `https://` explicitly. Scheme-less addresses default to HTTP and are suitable only for loopback development.

## Data and privacy

Hermes Bots connects directly to the gateways you add; it does not host your conversations or run the model. Chat content and actions performed in the app are sent to the selected gateway. The gateway executes tools and routines and may forward prompts to the model provider configured on that machine. Review the gateway operator's and model provider's data practices.

If a model switch triggers a gateway warning about cost or data policy, the app asks before it retries the switch.

Gateway URLs, authentication credentials, and local app preferences are stored in the app's private Android DataStore and excluded from Android backup and device transfer. Re-add gateways after reinstalling or moving to a new device. There is no Hermes Bots cloud account or hosted conversation sync.

## Building and verification

Requirements: JDK 17 and Android SDK 35. The app supports Android 10 (API 29) and newer.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests and Android lint before submitting changes:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Run the Android launch/navigation smoke test with an attached device or running emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

See the [build and documentation index](docs/README.md) and the [CI workflow](.github/workflows/ci.yml). CI may expose a debug APK as a GitHub Actions artifact for 7 days; this is a temporary build artifact. The repository does not document a signed, stable public APK distribution channel.

## Troubleshooting

- **Connection test fails:** Confirm the gateway is running, the URL uses the right scheme, and the certificate is valid for HTTPS connections.
- **WebSocket close `4401`:** Check the token or username/password and confirm the gateway's authentication mode.
- **WebSocket close `4403 host_mismatch` on USB:** Use `adb reverse` and `http://127.0.0.1:9119` for the local development route.
- **Slash commands are unavailable:** Check that the gateway is version 0.21.5 or newer.
- **No notifications:** Android 13 and newer require notification permission. Grant it when prompted or in Android Settings → Apps → Hermes Bots → Notifications.

## Developer references

- [PROTOCOL.md](PROTOCOL.md) documents the gateway wire contract for contributors.
- [docs/README.md](docs/README.md) links the setup, build, and verification references.

## License

MIT — see [LICENSE](LICENSE).
