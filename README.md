# Health Connect Gate

An Android app that reads your data from [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) and
copies it to **your own server** over HTTPS. Nothing is sent anywhere else.

> Status: **v0.1, pre-release.** It works for one user against one self-hosted server; read [Limitations](#limitations).

## Why

Health Connect is the single place on an Android phone where a watch, a phone step counter, a blood-pressure cuff, a
scale and fitness apps all write their data. It has no export to a server you control. This app is the missing bridge:
it pulls every record type Health Connect exposes and uploads it to a gateway you run, so the data can be stored,
de-duplicated and analysed without a third-party cloud.

## How it works

```
Health Connect ──read──▶ Health Connect Gate ──HTTPS──▶ your gateway server
   (on the phone)         (this app)                      (stores, de-duplicates)
```

1. **Configure** – on first start you type the gateway's domain name. There is no built-in server address.
2. **Sign in** – OAuth 2.0 authorization-code flow with PKCE (S256). The app opens the gateway's login page in the
   browser and receives the code on a one-shot loopback listener (`http://127.0.0.1:<random port>/callback`), then exchanges
   it for an access and a refresh token. The `state` value is checked. Expired access tokens are refreshed once
   automatically.
3. **Grant access** – the app asks Health Connect for read permission for every supported record type, plus
   `READ_HEALTH_DATA_HISTORY` (without it Health Connect only returns the 30 days before the first grant) and
   `READ_HEALTH_DATA_IN_BACKGROUND` (for the hourly background sync).
4. **Sync** – manually ("Sync all data", runs as a foreground service of type *health*) or hourly through WorkManager.
   The gateway decides the time window; the app asks it, reads the records month by month, uploads them in batches
   and only marks a month done when **the server confirms it**. The server's list of completed chunks is the source
   of truth, so an interrupted or repeated sync is safe.
5. **Diagnostics** – phases, errors and stack traces are kept in a local outbox and uploaded to the gateway.
   Health records and credentials never enter diagnostics (tokens are redacted).

Only one synchronisation runs at a time (file lock shared by the service and the worker).

## Server contract

The app talks to a gateway that implements the following. All bodies are JSON. Authenticated endpoints expect
`Authorization: Bearer <access token>`; a `401` makes the app refresh the token once and retry.

| Method and path | Auth | Purpose |
|---|---|---|
| `GET /auth/native/authorize` | – | Opened in the browser. Query: `code_challenge`, `code_challenge_method=S256`, `redirect_uri` (loopback), `state`. Must redirect to `redirect_uri` with `code` and `state`. |
| `POST /auth/native/token` | – | `{"code", "code_verifier"}` → `{"access_token", "refresh_token"}` |
| `POST /auth/native/refresh` | – | `{"refresh_token"}` → `{"access_token", "refresh_token"?}` |
| `GET /api/health/config` | – | `{"history_start": ISO-8601, "history_end"?: ISO-8601 (after start), "chunk_months": 1–12, "batch_size": 25–500}` |
| `GET /api/health/sync/status` | yes | `{"chunks": {"<chunk_id>": {"complete": true}}}` – chunks the server already holds completely |
| `POST /api/health/sync` | yes | Batch upload (below). |
| `POST /api/health/diagnostics` | yes | `{"schema_version": 1, "events": [{"event_id", "timestamp", "phase", "message", "exception_type"?, "stack_trace"?}]}` (up to 50 events) |

**Batch upload** – `POST /api/health/sync`:

```json
{
  "schema_version": 1,
  "run_id": "uuid",
  "chunk_id": "2026-09|2026-09-01T00:00:00Z|2026-09-30T12:00:00Z",
  "chunk_start": "2026-09-01T00:00:00Z",
  "chunk_end": "2026-09-30T12:00:00Z",
  "records": [ { "id": "<Health Connect record id>", "kind": "Steps", "data": { } } ]
}
```

* `kind` is the Health Connect record class without the `Record` suffix; `data` is the record's fields.
  Servers should de-duplicate on `id`.
* When a chunk has been sent completely the app posts the same envelope once more with `"complete": true` and an
  empty `records` array. The server must answer `{"ok": true, "chunk_id": "<same id>"}`; only then is the chunk
  considered done. Any other non-2xx answer (except `401`) is treated as an error and the sync stops.

## Permissions and privacy

* Health Connect **read** permissions for all record types the SDK supports, plus history and background read.
  This includes sensitive categories; the app requests everything by design. Revoke what you do not want to sync.
* `INTERNET`, and foreground-service permissions for the health sync service.
* `POST_NOTIFICATIONS` is declared but never requested; enable it in system settings if you want progress notifications.
* Data goes only to the domain you enter, over HTTPS (plain HTTP is never used). No analytics, advertising or
  third-party services.
* Tokens are kept in the app's private storage. Android backup is disabled.

## Requirements

Android 8.0 (API 26) or newer with Health Connect (built into Android 14+, a separate app before that).

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

```
./gradlew :app:assembleDebug            # debug APK: app/build/outputs/apk/debug/
```

The repository ships **without** a default server address. To pre-fill the domain field in your own builds, put
`gate.defaultDomain=gateway.example.com` into `~/.gradle/gradle.properties` (outside the repository) or pass
`-Pgate.defaultDomain=...`.

## Releases

Releases are built by GitHub Actions and are signed with the project's release key. Pushing a tag `v<versionName>`
(the tag must equal `versionName` in `app/build.gradle.kts`, for example `v0.1`) builds the APK, verifies its
signature, package name and version, and publishes it with a SHA-256 file as a GitHub Release (versions `0.x` are
marked as pre-releases). Nothing is built on ordinary pushes; **CI** runs on pull requests and on demand.

Official releases are signed with a certificate whose SHA-256 fingerprint is:

```
CA:55:6D:77:20:FB:84:F0:22:19:EB:0D:BD:34:11:3A:89:F0:20:BD:C0:0A:78:23:60:38:FE:89:74:4A:F1:EC
```

Check it with `apksigner verify --print-certs HealthConnectGate-<version>.apk`. Releases are signed with a different
key than debug builds, so a debug build must be uninstalled before a release can be installed.

For maintainers – repository secrets used by the release workflow: `GATE_KEYSTORE_BASE64` (PKCS #12 keystore, base64),
`GATE_KEYSTORE_PASSWORD`, `GATE_KEY_ALIAS`, `GATE_KEY_PASSWORD`. The keystore itself must never be committed
(it is ignored by `.gitignore`); `scripts/check-secrets.sh` runs in CI and can be installed as a pre-commit hook to
catch credentials, keys and server addresses. It can also read a list of personal strings from a file outside the
repository (`GATE_PRIVATE_PATTERNS`).

## Limitations

* Tokens are stored unencrypted in the app's private storage.
* Records are serialised by reflection over the Health Connect classes, so a library upgrade can change the JSON;
  there are no automated tests yet.
* All read permissions are requested; there is no per-type selection.
* The interface is a minimal, English-only screen built in code.
* One gateway and one user are assumed.
