# Health Connect Gate

An Android app that copies your data from [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect)
to **your own server** over HTTPS. Nothing is sent anywhere else.

It was written to feed [Hermes Agent](https://hermes-agent.nousresearch.com/) but works with **any backend that implements the
[API contract](#api-contract)**. [`server/`](server/) contains a ready-to-run reference server (the *receiver*, Python standard library only).

> Status: pre-1.0 (v0.3.x). Works for one user against one self-hosted server; see [Limitations](#limitations).

Health Connect is where a watch, the phone's step counter, a blood-pressure cuff, a scale and fitness apps all write their data, and it
has no export to a server you control. This app is that bridge.

## How it works

```
Health Connect ──read──▶ Health Connect Gate ──HTTPS──▶ your gateway (sign-in + receiver)
```

1. **Domain** – on first start you type the gateway's domain. The app has no built-in server address.
2. **Sign in** – OAuth 2.0 authorization code with PKCE (S256) in the browser; the code returns to a one-shot loopback listener
   (`http://127.0.0.1:<random port>/callback`). Access tokens are refreshed automatically.
3. **Choose and grant** – you choose which of seven data categories are synchronised (*cycle tracking* and *sexual activity* start off).
   Only the chosen Health Connect types are requested, plus history and background read.
4. **Sync** – by hand ("Sync all data", a foreground service of type *health*) or hourly through WorkManager, one run at a time.
   The server names the time window and lists the chunks (months) it already holds; the app reads the rest, uploads it in batches and marks a
   chunk done only when **the server confirms it**, so an interrupted sync is safe to repeat.
   After the first full read the app asks Health Connect for **changes** (a changes token) when the server supports it, so corrected
   records are updated and deleted records disappear on the server; a full read of the current window is repeated weekly as a safety net.
5. **Diagnostics** – phases and errors go to a local outbox and are uploaded to the server. Health data and credentials never enter them.

The main screen shows the server and sign-in state, the last success, the last problem in plain language and the next background run.
**Sign out** removes tokens and resumable state and stops the background sync.

## API contract

All bodies are JSON; every URL is `https://<domain>/…`. Authenticated endpoints expect `Authorization: Bearer <access token>`.
The app waits up to 15 s to connect and 30 s for an answer.

| Method and path | Auth | Purpose |
|---|---|---|
| `GET /auth/native/authorize` | – | Opened in the browser: `code_challenge`, `code_challenge_method=S256`, `redirect_uri`, `state`; redirect back with `code` and `state`. |
| `POST /auth/native/token` | – | `{"code","code_verifier"}` → `{"access_token","refresh_token"}` |
| `POST /auth/native/refresh` | – | `{"refresh_token"}` → `{"access_token","refresh_token"?}` |
| `GET /api/health/config` | – | Time window and capabilities. |
| `GET /api/health/sync/status` | yes | Chunks the server holds completely. |
| `POST /api/health/sync` | yes | Batch upload. |
| `POST /api/health/changes` | yes | Updated records and deletions (optional, announced in the config). |
| `POST /api/health/diagnostics` | yes | App diagnostics. |

**Sign-in rules.** `redirect_uri` is always the loopback shape above; return `state` unchanged; codes are single-use and bound to the
`code_challenge`; verify `code_verifier`. On `401` from an authenticated endpoint the app calls `/auth/native/refresh` once and retries, then reports
"sign in again". The login page itself is up to the server.

### `GET /api/health/config`

```json
{ "history_start": "2026-01-01T00:00:00Z", "history_end": "2026-09-20T12:00:00Z", "chunk_months": 1, "batch_size": 250,
  "accepts_gzip": true, "record_format": 2, "accepts_changes": true }
```

`history_start` (ISO-8601) is required; `history_end` is optional (default: now) and must be later than `history_start`; `chunk_months` is 1–12;
`batch_size` 25–500 (the app clamps). The last three are **optional capabilities**; when missing the app assumes *no gzip, record format 1, no changes*.

### `GET /api/health/sync/status`

```json
{ "chunks": { "2026-09|2026-09-01T00:00:00Z|2026-09-20T12:00:00Z": { "complete": true } } }
```

Chunks with `"complete": true` are skipped by the app. Chunk ids are chosen by the app: `<month>|<start>|<end>` for a fixed window and
`<month>|<start>|open` for a window that ends "now" (never skipped). The receiver keeps the newest 200.

### `POST /api/health/sync`

```json
{ "schema_version": 1, "run_id": "uuid", "chunk_id": "…", "chunk_start": "…", "chunk_end": "…",
  "records": [ { "id": "<Health Connect record id>", "kind": "Steps", "data": { } } ] }
```

* `kind` is the Health Connect record class without `Record`; `data` holds its fields.
* **Gzip.** With `"accepts_gzip": true` the app sends bodies over 1 KiB with `Content-Encoding: gzip`. The server must inflate with a size limit
  (the receiver refuses more than 64 MB: `413`), answer `415` to unknown encodings and `400` to corrupt data.
* **Record format 2** (`"record_format": 2` in the config; the envelope then carries it too): the record types the receiver models (steps, distance,
  active and total calories, heart rate, speed, sleep, exercise, basal metabolic rate, weight, height, blood pressure, resting heart rate) use
  *canonical units only* (`meters`, `kilocalories`, `kilocaloriesPerDay`, `kilograms`, `metersPerSecond`, `millimetersOfMercury`) and omit the derived
  conversions, null `*$annotations`, `metadata.id` and an `endZoneOffset` equal to the start offset. Everything else, and every record for a server
  without format 2, uses format 1 (all fields).
* **Idempotence and versions.** Records are re-sent after interrupted syncs. Key on `id`. A known `id` whose record has a later
  `metadata.lastModifiedTime` (or the same time and a higher `clientRecordVersion`) **replaces** the stored one; otherwise it is a duplicate.
  A success is HTTP 2xx with JSON, e.g. `{"schema_version":1,"ok":true,"accepted":7,"duplicates":1,"updated":0,"received":8,"total":4321,"chunk_id":"…"}`.
* **Completion.** After a chunk's last batch the app posts the same envelope with `"complete": true` and no records. Answer
  `{"ok": true, "chunk_id": "<same id>"}`; only then may `sync/status` list the chunk as complete.
* Any other non-2xx answer (except `401`) stops the sync; the app retries later.

### `POST /api/health/changes`

Sent only when the config says `"accepts_changes": true`. The app takes a Health Connect changes token before a full read and afterwards sends
only what changed since, so corrections and deletions reach the server for any period, not just the open window.

```json
{ "schema_version": 1, "run_id": "uuid", "record_format": 2,
  "upserts": [ { "id": "…", "kind": "Steps", "data": { } } ], "deleted_ids": [ "<Health Connect record id>" ] }
```

At most 500 of each per request (gzip allowed, same rules as `sync`). Apply `upserts` exactly like `sync` records (new → insert, newer → replace, else
duplicate), then delete `deleted_ids`. Answer `{"ok": true, "accepted", "updated", "duplicates", "received", "deleted", "missing", "total"}`.
The receiver also removes the Google Fit placeholders (see [Configuration](#configuration)) that pointed at a deleted record. If the endpoint answers
`404`, `405` or `501` the app falls back to plain full reads. A token that expired (Health Connect keeps them 30 days) or changed
server/data selection triggers a full read too; deletions that happened while no token was valid cannot be detected.

### `POST /api/health/diagnostics`

```json
{ "schema_version": 1, "events": [ { "event_id": "uuid", "timestamp": "…", "phase": "…", "message": "…", "exception_type": "…", "stack_trace": "…" } ] }
```

At most 50 events; `exception_type` and `stack_trace` are optional. Treat the text as untrusted, and ignore an `event_id` you already have (retries repeat events).

### Checklist for a compatible backend

HTTPS on 443 · the endpoints above with PKCE S256 and single-use codes · `401` (not `403`/`5xx`) for a missing or expired token · idempotent uploads and the
`complete` handshake · an answer within 30 s.

## Run the receiver

The receiver implements the `/api/health/*` endpoints plus `/api/health/diagnostics/status` and two local probes. It has **no sign-in of its own**: it forwards
the caller's `Authorization` and `Cookie` headers to an *auth backend* (`HEALTH_RECEIVER_AUTH_URL`) and accepts the request only on `200`
(`401` → `401`, anything else or unreachable → `503`, i.e. fail closed; positive answers are cached up to 60 s). Any authenticated session is accepted,
so use a single-user setup. Put it behind a TLS reverse proxy; do not expose it directly.

**Requirements:** any host with a public DNS name and TLS; Python 3.11+ (standard library only, SQLite 3.24+) or Docker. About 60 bytes per record.

```
git clone <this repository> /opt/health-connect-gate && cd /opt/health-connect-gate/server
python3 -m unittest discover -s tests          # optional, about 15 s
HEALTH_RECEIVER_ROOT=/var/lib/health-connect-gate HEALTH_RECEIVER_AUTH_URL=http://127.0.0.1:9119/api/auth/me python3 receiver.py
```

For a service use [`deploy/health-connect-gate-receiver.service`](server/deploy/health-connect-gate-receiver.service) (system user, `systemctl enable --now`).

### Docker

[`server/Dockerfile`](server/Dockerfile) and [`server/compose.yaml`](server/compose.yaml): unprivileged user, read-only root filesystem, no capabilities, data in a
named volume, port published on `127.0.0.1` only.

```
cd server
echo 'HEALTH_RECEIVER_AUTH_URL=http://host.docker.internal:9119/api/auth/me' > .env    # git-ignored; your auth backend
docker compose up -d --build && curl -s http://127.0.0.1:9120/readyz
```

Inside a container `127.0.0.1` is the container itself, so a service on the Docker host is `host.docker.internal` (mapped by compose) and must listen on
an address the Docker network can reach. `HEALTH_RECEIVER_PUBLISH_PORT` changes the host port. Update with `git pull && docker compose up -d --build`
(the volume and schema survive). The image also answers `docker run … IMAGE backup` and `… verify`.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `HEALTH_RECEIVER_HOST` / `_PORT` | `127.0.0.1` / `9120` | Where to listen. Keep loopback behind a proxy. |
| `HEALTH_RECEIVER_ROOT` | `./data` | Data directory; must be writable. |
| `HEALTH_RECEIVER_AUTH_URL` | `http://127.0.0.1:9119/api/auth/me` | Auth backend probe. |
| `HEALTH_RECEIVER_STORE` | `sqlite` | `sqlite` (compact; supports updates and deletions) or `jsonl` (plain append-only files). |
| `HEALTH_RECEIVER_DB` | `<ROOT>/health_sync.sqlite3` | SQLite file (mode 0600). |
| `HEALTH_RECEIVER_MIRROR_JSONL` | `0` | With `sqlite`, `1` also appends new raw records to JSONL files. |
| `HEALTH_RECEIVER_LOCK` | `<server dir>/run/receiver.lock` | Writer lock; keep it outside the data directory. |
| `HEALTH_RECEIVER_READONLY` | `0` | `1` answers writes with `503` and stores nothing. |

The time window comes from an optional `<ROOT>/health_connect_config.json`, e.g.
`{"history_start": "2026-01-01T00:00:00Z", "chunk_months": 1, "batch_size": 250, "dynamic_range": true}`. Defaults: `1970-01-01`, 1, 250. With
`"dynamic_range": true` the newest stored record is the `history_start` and "now" the `history_end`, so after the first import only new data is read.

**What the SQLite store keeps.** One compact row per record (one canonical unit per value, millisecond timestamps, packed heart-rate and speed samples), not the
original JSON. A Google Fit record that exactly duplicates a phone-counter or Xiaomi-watch record (same type, span and value) is folded into a placeholder that
points at the original and remembers its id, so it is not counted twice. Need the raw JSON? Use `jsonl` or the mirror. The JSONL store cannot apply updates or
deletions (`accepts_changes` is then not announced).

### Reverse proxy

Publish one domain and route these exact paths to the receiver, everything else (including `/auth/native/*`) to the sign-in service:
`/api/health/config`, `/sync`, `/sync/status`, `/changes`, `/diagnostics`, `/diagnostics/status` (all under `/api/health/`). Examples checked with `nginx -t` and
`caddy validate`: [`nginx.conf.example`](server/deploy/nginx.conf.example), [`Caddyfile.example`](server/deploy/Caddyfile.example). Never route `/healthz` or
`/readyz` publicly. Allow bodies of at least 64 MB and read timeouts of 60 s or more.

### Hermes mode

Enable the Hermes Agent dashboard's `basic` sign-in in its environment: `HERMES_DASHBOARD_BASIC_AUTH_USERNAME`, `HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH` (scrypt,
preferred) and `HERMES_DASHBOARD_BASIC_AUTH_SECRET` (32+ random bytes; without it every dashboard restart invalidates the app's tokens). Start the dashboard on
loopback and the receiver with its default `HEALTH_RECEIVER_AUTH_URL`, which already points at it. Details were checked against Hermes Agent in September 2026.
Any other sign-in service works if it implements `/auth/native/*` and the auth URL answers `200`/`401`.

### Check that it works

```
curl -s http://127.0.0.1:9120/healthz                          # {"status":"ok","readonly":false}
curl -s http://127.0.0.1:9120/readyz                           # "auth_backend": true when the sign-in service answers
curl -s https://gateway.example.com/api/health/config          # window and capabilities
curl -si https://gateway.example.com/api/health/sync/status    # 401 without a token
```

### Backups

With SQLite the database file is the only copy of the uploaded data. Do not `cp` a running database:

```
python3 receiver.py backup                # -> $HEALTH_RECEIVER_ROOT/backups/health_sync-<UTC time>.sqlite3
python3 receiver.py backup /mnt/usb/      # into a directory or a file name
python3 receiver.py verify [FILE]         # integrity check (default: the live database); exit status 1 on failure
```

It is safe while the receiver runs (read-only snapshot, verified, written under a temporary name then renamed, mode 0600, never overwrites, plus a `.sha256`
file) and prints counters only. Use the same environment as the service. A copy on the same disk is not a backup: move it elsewhere. Nothing runs it automatically.
Restore: stop the service, check the copy with `sha256sum -c` and `receiver.py verify`, replace the database file, start the service. In Docker:
`docker compose exec receiver python3 /app/receiver.py backup /data/backups/`, then `docker compose cp receiver:/data/backups ./backups`.

This is health data: keep the directory private, use HTTPS and a strong password, and consider VPN or IP restrictions. The receiver never logs record contents or tokens.

## Permissions and privacy

* Health Connect **read** permissions only for the categories you choose, plus history and background read. Android only lets an app request what the manifest
  declares, so all are declared, but only the chosen ones are requested. Withdraw them in Health Connect settings (the app has a button).
* `INTERNET` and foreground-service permissions for the sync service. `POST_NOTIFICATIONS` is declared but never requested.
* Data goes only to the domain you enter, over HTTPS. No analytics, advertising or third-party services. Tokens stay in the app's private storage; Android
  backup is disabled.
* See the [privacy policy](PRIVACY.md) and the [security policy](SECURITY.md).

## Build, releases, development

Android 8.0+ with Health Connect (built in from Android 14). JDK 17 and the Android SDK (platform 36, build-tools 35+) to build:

```
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # JVM tests
cd server && python3 -m unittest discover -s tests && python3 tests/smoke_write.py   # again with HEALTH_RECEIVER_STORE=sqlite
```

The repository has no default server address; to pre-fill the domain in your own builds set `gate.defaultDomain=gateway.example.com` in
`~/.gradle/gradle.properties` or pass `-Pgate.defaultDomain=…`.

**Releases** are built by GitHub Actions and signed with the project key: pushing a tag `v<versionName>` (equal to `versionName` in `app/build.gradle.kts`)
builds the APK, verifies signature, package and version, and publishes it with a SHA-256 file. `versionCode` is derived from `versionName`
(`major*10000 + minor*100 + patch`) so it can never fall below an earlier build. Nothing is built on ordinary pushes; CI (app build, server tests, Docker image,
secret scan) runs on pull requests and on demand. Official APKs are signed with a certificate whose SHA-256 is
`CA:55:6D:77:20:FB:84:F0:22:19:EB:0D:BD:34:11:3A:89:F0:20:BD:C0:0A:78:23:60:38:FE:89:74:4A:F1:EC`
(`apksigner verify --print-certs HealthConnectGate-<version>.apk`). Debug builds use a different key.

Maintainers: repository secrets `GATE_KEYSTORE_BASE64`, `GATE_KEYSTORE_PASSWORD`, `GATE_KEY_ALIAS`, `GATE_KEY_PASSWORD`. The keystore is never committed;
`scripts/check-secrets.sh` (CI, or a pre-commit hook) catches credentials, keys and server addresses and can read personal strings from a file outside the
repository (`GATE_PRIVATE_PATTERNS`).

## Troubleshooting

**"App not installed".** Android never installs a lower `versionCode` over a higher one, nor an APK signed with another key over an installed app of the same
package. Debug and release builds use different keys, so remove the other one first. It may be hidden: check other users, a work profile or Private Space,
and *Uninstall for all users*. With `adb`:

```
adb shell pm list users
adb shell pm list packages -u | grep healthconnectgate    # -u also lists apps uninstalled with "keep data"
adb uninstall --user <id> com.bishop.healthconnectgate    # once per user id that still has it
```

Compare the file with its checksum (`sha256sum -c HealthConnectGate-<version>.apk.sha256`) if an install fails for no visible reason.

## Limitations

* Tokens are stored unencrypted in the app's private storage.
* The 13 record types the receiver models are written by explicit code; the rest are serialised by reflection (a library upgrade can change that JSON, and R8
  stays off because of it). A unit test guards the explicit encoder.
* Deletions that happen while the app has no valid changes token (first run, more than 30 days offline, another server or data selection) are not detected;
  the weekly full read repairs corrections but cannot see missing records.
* Google Fit placeholders keep no values, so a corrected Google Fit copy of an already folded record is not applied.
* Minimal English-only interface. One gateway and one user are assumed.

## License

[MIT](LICENSE) © 2026 Alexander Shalin. The app bundles third-party libraries under Apache-2.0, BSD-3-Clause and MIT ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md));
the server uses only the Python standard library.
