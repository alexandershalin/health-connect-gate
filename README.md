# Health Connect Gate

An Android app that reads your data from [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect) and
copies it to **your own server** over HTTPS. Nothing is sent anywhere else.

The app was originally written to feed [Hermes Agent](https://hermes-agent.nousresearch.com/), but it works with **any backend that
implements the [API contract](#api-contract)** below. This repository also contains a ready-to-run reference server
([`server/`](server/), the *receiver*) and instructions for [deploying it yourself](#self-hosted-deployment), either next to a
Hermes Agent dashboard or behind any sign-in service of your own.

> Status: **v0.1, pre-release.** It works for one user against one self-hosted server; read [Limitations](#limitations).

## Contents

* [Why](#why) · [How it works](#how-it-works) · [API contract](#api-contract)
* [Self-hosted deployment](#self-hosted-deployment)
* [Permissions and privacy](#permissions-and-privacy) · [Requirements](#requirements)
* [Build](#build) · [Releases](#releases) · [Development](#development)
* [License](#license) · [Limitations](#limitations)

## Why

Health Connect is the single place on an Android phone where a watch, a phone step counter, a blood-pressure cuff, a
scale and fitness apps all write their data. It has no export to a server you control. This app is the missing bridge:
it pulls every record type Health Connect exposes and uploads it to a gateway you run, so the data can be stored,
de-duplicated and analysed without a third-party cloud.

## How it works

```
Health Connect ──read──▶ Health Connect Gate ──HTTPS──▶ your gateway (sign-in + receiver)
   (on the phone)         (this app)
```

1. **Configure** – on first start you type the gateway's domain name. There is no built-in server address.
2. **Sign in** – OAuth 2.0 authorization-code flow with PKCE (S256). The app opens the gateway's login page in the
   browser and receives the code on a one-shot loopback listener (`http://127.0.0.1:<random port>/callback`), then exchanges
   it for an access and a refresh token. The `state` value is checked. Expired access tokens are refreshed once
   automatically.
3. **Choose and grant** – you choose which categories of data are synchronized (seven of them; the sensitive ones, *cycle tracking* and
   *sexual activity*, are off until you turn them on). The app asks Health Connect only for the chosen types, plus
   `READ_HEALTH_DATA_HISTORY` (without it Health Connect only returns the 30 days before the first grant) and
   `READ_HEALTH_DATA_IN_BACKGROUND` (for the hourly background sync).
4. **Sync** – manually ("Sync all data", runs as a foreground service of type *health*) or hourly through WorkManager.
   The gateway decides the time window; the app asks it, reads the records month by month, uploads them in batches
   and only marks a month done when **the server confirms it**. The server's list of completed chunks is the source
   of truth, so an interrupted or repeated sync is safe.
5. **Diagnostics** – phases, errors and stack traces are kept in a local outbox and uploaded to the gateway.
   Health records and credentials never enter diagnostics (tokens are redacted).

The main screen shows the server and sign-in state, whether a sync is running, the last success, the last problem in plain language, the next
background run and how many of the chosen types are allowed. **Sign out** removes the tokens and the resumable state from the phone and stops the
background sync (a session that was already issued stays valid on the server until it expires).

Only one synchronisation runs at a time (file lock shared by the service and the worker).

## API contract

Any server that implements the following can be used with the app. All bodies are JSON and every URL is
`https://<the domain you typed in the app>/…` (plain HTTP is never used). Authenticated endpoints expect
`Authorization: Bearer <access token>`. The app waits at most 15 s to connect and 30 s for an answer.

| Method and path | Auth | Purpose |
|---|---|---|
| `GET /auth/native/authorize` | – | Opened in the browser. Query: `code_challenge`, `code_challenge_method=S256`, `redirect_uri`, `state`. After the user signs in it must redirect to `redirect_uri` with `code` and `state` appended. |
| `POST /auth/native/token` | – | `{"code", "code_verifier"}` → `{"access_token", "refresh_token"}` |
| `POST /auth/native/refresh` | – | `{"refresh_token"}` → `{"access_token", "refresh_token"?}` |
| `GET /api/health/config` | – | Which time window to sync (below). |
| `GET /api/health/sync/status` | yes | Which chunks the server already holds completely. |
| `POST /api/health/sync` | yes | Batch upload (below). |
| `POST /api/health/diagnostics` | yes | App diagnostics (below). |

### Sign-in (`/auth/native/*`)

* `redirect_uri` is always a loopback address `http://127.0.0.1:<random port>/callback`; a server should accept exactly
  that shape and nothing else. `state` must be returned unchanged, the code must be single-use and bound to the
  `code_challenge`, and the token endpoint must verify `code_verifier` (S256).
* Access tokens are opaque to the app. When `sync/status` or `sync` answers `401` the app calls `/auth/native/refresh`
  once and retries; if that fails it reports "sign in again". (A `401` on the diagnostics upload just leaves the events in the app's outbox.) Give access tokens a lifetime of hours and refresh tokens days.
* The login page itself is up to the server (password form, single sign-on, …).

### Configuration – `GET /api/health/config`

```json
{ "history_start": "2026-01-01T00:00:00Z", "history_end": "2026-09-20T12:00:00Z", "chunk_months": 1, "batch_size": 250 }
```

```json
{ "history_start": "2026-01-01T00:00:00Z", "chunk_months": 1, "batch_size": 250, "accepts_gzip": true, "record_format": 2 }
```

`accepts_gzip` and `record_format` are **optional capabilities** (see [Upload](#upload--post-apihealthsync)): a server that does not send them is
treated as "no gzip, record format 1". `history_start` (ISO-8601, required) is the beginning of the window to read. `history_end` is optional (the app uses "now" when it is
absent) but, if present, must be later than `history_start` – otherwise the app refuses to sync. `chunk_months` (1–12) is how many months make up one chunk and `batch_size`
(25–500) how many records go into one upload; the app clamps out-of-range values.

### Progress – `GET /api/health/sync/status`

```json
{ "chunks": { "2026-09|2026-09-01T00:00:00Z|2026-09-20T12:00:00Z": { "complete": true } } }
```

A chunk listed with `"complete": true` is skipped by the app. The chunk id is chosen by the app and is opaque to the server: `<month>|<start>|<end>` for a fixed window and `<month>|<start>|open` for a
window that ends "now" (the same id is reused while the start does not change, and such a window is never skipped). The reference receiver keeps the
newest 200 chunk ids.

### Upload – `POST /api/health/sync`

```json
{
  "schema_version": 1,
  "run_id": "uuid",
  "chunk_id": "2026-09|2026-09-01T00:00:00Z|2026-09-20T12:00:00Z",
  "chunk_start": "2026-09-01T00:00:00Z",
  "chunk_end": "2026-09-20T12:00:00Z",
  "records": [ { "id": "<Health Connect record id>", "kind": "Steps", "data": { } } ]
}
```

* `kind` is the Health Connect record class without the `Record` suffix; `data` holds the record's fields.
* **Compression.** If the config says `"accepts_gzip": true`, the app compresses upload bodies larger than 1 KiB and sends `Content-Encoding: gzip`.
  A server that advertises it must decompress (with a limit on the expanded size - the reference receiver refuses more than 64 MB) and answer `415` to encodings it
  does not know. Without the flag the app always sends plain JSON.
* **Record format.** With `"record_format": 2` in the config the envelope carries `"record_format": 2` and the app writes the record types the reference
  receiver models (steps, distance, active and total calories, heart rate, speed, sleep, exercise, basal metabolic rate, weight, height, blood pressure, resting
  heart rate) in *canonical units only*: `meters`, `kilocalories`, `kilocaloriesPerDay`, `kilograms`, `metersPerSecond`, `millimetersOfMercury`. The unit conversions
  Health Connect adds (feet, inches, miles, joules, pounds, ...), the null `*$annotations` fields, `metadata.id` (it is the top-level `id`) and an `endZoneOffset` equal
  to `startZoneOffset` are left out; everything else is unchanged. All other record types, and every record when the server does not announce format 2, are sent in
  format 1 (all fields, including the conversions).
* **Be idempotent.** Records are re-sent after interrupted syncs: de-duplicate on `id` and answer normally.
  A successful answer is HTTP 2xx with a JSON object; a body such as
  `{"schema_version": 1, "ok": true, "accepted": 7, "duplicates": 1, "received": 8, "total": 4321, "chunk_id": "…"}` is what the
  reference receiver returns.
* When a chunk has been sent completely, the app posts the same envelope once more with `"complete": true` and an empty
  `records` array. The server must answer `{"ok": true, "chunk_id": "<the same id>"}`; only then does the app consider the chunk done
  (and only then should the server list it as complete in `sync/status`).
* Any other non-2xx answer (except `401`) is an error and stops the sync; the app retries later from the server's list of completed chunks.

### Diagnostics – `POST /api/health/diagnostics`

```json
{ "schema_version": 1, "events": [ { "event_id": "uuid", "timestamp": "…", "phase": "sync_phase", "message": "…", "exception_type": "…", "stack_trace": "…" } ] }
```

At most 50 events per request; `exception_type` and `stack_trace` are optional. Servers should treat the text as untrusted and may redact it further,
and should ignore an `event_id` they already have (the reference receiver does), because a retried upload repeats events.

### Checklist for a compatible backend

1. HTTPS with a valid certificate on port 443.
2. The seven endpoints above, with the sign-in rules (PKCE S256, loopback `redirect_uri`, single-use code).
3. `401` (not `403`/`5xx`) for a missing or expired token on the authenticated endpoints.
4. Idempotent uploads, and the `complete` handshake answered with the same `chunk_id`.
5. An answer within 30 seconds.

## Self-hosted deployment

The reference server in [`server/`](server/) implements the three `/api/health/*` endpoints of the contract plus
`/api/health/diagnostics/status` and two local probes. It does **not** implement sign-in (`/auth/native/*`): every request is
checked against an *auth backend* you point it at. Two ways to run it:

* **With a Hermes Agent dashboard** (below) – the dashboard provides sign-in and the receiver delegates authentication to it.
* **With another sign-in service of your own** – implement `/auth/native/*` yourself and set `HEALTH_RECEIVER_AUTH_URL` to any
  URL that answers `200` for an authenticated request and `401` otherwise ([details](#using-another-auth-backend)),
  or implement the whole [contract](#api-contract) in your own server and ignore the receiver.

There is no built-in login in the receiver: it is not meant to face the internet without an authenticating service in front of it.

### Requirements

* A Linux server (or any host) with a public DNS name and TLS – the app only speaks HTTPS. A reverse proxy such as nginx or
  Caddy terminates TLS.
* Python 3.11 or newer with the standard library only (CI runs 3.11 and 3.14; SQLite comes with Python, 3.24+ is needed).
  Or Docker instead of Python: see [Docker](#docker).
* For the Hermes mode, a running [Hermes Agent](https://hermes-agent.nousresearch.com/) dashboard with its username/password sign-in enabled.
* Disk: about 60 bytes per record in the SQLite store (tens of thousands of records per month of typical data are a few MB).

### Install

```
git clone <this repository> /opt/health-connect-gate
cd /opt/health-connect-gate/server
python3 -m unittest discover -s tests        # optional: about 15 seconds
```

No packages need to be installed. Run it in the foreground to try it out:

```
HEALTH_RECEIVER_ROOT=/var/lib/health-connect-gate \
HEALTH_RECEIVER_AUTH_URL=http://127.0.0.1:9119/api/auth/me \
python3 receiver.py
```

For a permanent service use the example unit [`server/deploy/health-connect-gate-receiver.service`](server/deploy/health-connect-gate-receiver.service)
(create a system user, copy the unit to `/etc/systemd/system/`, `systemctl enable --now health-connect-gate-receiver`).

### Docker

[`server/Dockerfile`](server/Dockerfile) builds a small image (Python standard library only, no pip, runs as an unprivileged user) and
[`server/compose.yaml`](server/compose.yaml) runs it hardened: read-only root filesystem, all capabilities dropped, no new privileges,
data in a named volume, the port published on `127.0.0.1` only. TLS still comes from a reverse proxy on the host (see [Reverse proxy](#reverse-proxy)).

```
cd server
echo 'HEALTH_RECEIVER_AUTH_URL=http://host.docker.internal:9119/api/auth/me' > .env     # your auth backend (git-ignored file)
docker compose up -d --build
curl -s http://127.0.0.1:9120/readyz
```

* `HEALTH_RECEIVER_AUTH_URL` is required by compose. Inside a container `127.0.0.1` is the container itself, so a service on the Docker
  host is reached as `host.docker.internal` (compose maps it). That service must listen on an address the Docker network can reach, not only on
  `127.0.0.1` of the host; otherwise use the address of another container or host.
* Other variables from [Configuration](#configuration) can be added to `environment:`; `HEALTH_RECEIVER_PUBLISH_PORT` changes the host port.
  Without an auth backend every request is answered with `401` (fail closed).
* Backups: `docker compose exec receiver python3 /app/receiver.py backup /data/backups/`, then `docker compose cp receiver:/data/backups ./backups`.
  Check a copy with `... receiver.py verify /data/backups/<file>`. Restore: stop the service, copy the file over `/data/health_sync.sqlite3`
  (for example with a throw-away `docker run -v` container), start the service.
* Update: `git pull && docker compose up -d --build`. The data volume survives; the schema is upgraded on start.
* Without compose: `docker build -t health-connect-gate-receiver server`, then `docker run -d --read-only --cap-drop ALL --tmpfs /run/receiver:uid=10001,gid=10001,mode=0700 --tmpfs /tmp
  -e HEALTH_RECEIVER_AUTH_URL=... -p 127.0.0.1:9120:9120 -v receiver-data:/data health-connect-gate-receiver`. The image also answers
  `docker run ... IMAGE backup` and `... verify`.
* The health check uses `/healthz` (the process answers); `/readyz` also checks the auth backend.

### Configuration

Environment variables (all optional):

| Variable | Default | Meaning |
|---|---|---|
| `HEALTH_RECEIVER_HOST` / `_PORT` | `127.0.0.1` / `9120` | Where to listen. Keep it on loopback and put a proxy in front. |
| `HEALTH_RECEIVER_ROOT` | `./data` | Data directory (database, configuration file). Must be writable. |
| `HEALTH_RECEIVER_AUTH_URL` | `http://127.0.0.1:9119/api/auth/me` | Auth backend probe (see below). |
| `HEALTH_RECEIVER_STORE` | `sqlite` | `sqlite` (compact database) or `jsonl` (plain append-only files). |
| `HEALTH_RECEIVER_DB` | `<ROOT>/health_sync.sqlite3` | SQLite file (created with mode 0600). |
| `HEALTH_RECEIVER_MIRROR_JSONL` | `0` | In `sqlite` mode, `1` additionally appends every raw record to JSONL files. |
| `HEALTH_RECEIVER_LOCK` | `<server dir>/run/receiver.lock` | Lock file that serialises writers; keep it outside the data directory. |
| `HEALTH_RECEIVER_READONLY` | `0` | `1` answers writes with `503` and stores nothing (handy for trials). |

Which time window the app syncs is decided by an optional JSON file `<ROOT>/health_connect_config.json`:

```json
{ "history_start": "2026-01-01T00:00:00Z", "chunk_months": 1, "batch_size": 250, "dynamic_range": true }
```

Without the file the receiver answers `history_start = 1970-01-01`, `chunk_months = 1`, `batch_size = 250`. With
`"dynamic_range": true` it answers the time of the newest stored record as `history_start` and "now" as `history_end`, so after the first
full import the app only reads what is new. Values are clamped to the ranges in the [contract](#api-contract).

**What the SQLite store keeps.** Each record is stored compactly (one canonical unit per value, timestamps in milliseconds,
heart-rate and speed samples packed), not as the original JSON; the unit conversions Health Connect adds are dropped. Records that
Google Fit re-exports as an exact copy (same type, time span and value) of a phone-counter or Xiaomi watch record are folded into a
small placeholder pointing at the original, so they are not counted twice, and their ids are remembered so a re-upload is a duplicate.
If you need the raw JSON, use `HEALTH_RECEIVER_STORE=jsonl` or `HEALTH_RECEIVER_MIRROR_JSONL=1`.

### Reverse proxy

Publish one domain and route by path: the five health endpoints go to the receiver, everything else (including `/auth/native/*`)
to the sign-in service. Examples that were syntax-checked with `nginx -t` and `caddy validate`:
[`server/deploy/nginx.conf.example`](server/deploy/nginx.conf.example) and [`server/deploy/Caddyfile.example`](server/deploy/Caddyfile.example).

| Path (exact) | Goes to |
|---|---|
| `/api/health/config`, `/api/health/sync`, `/api/health/sync/status`, `/api/health/diagnostics`, `/api/health/diagnostics/status` | receiver (`127.0.0.1:9120`) |
| everything else | Hermes dashboard (`127.0.0.1:9119`) or your sign-in service |

Do **not** route `/healthz` and `/readyz` to the public. Allow request bodies of at least 64 MB (nginx: `client_max_body_size 64m`) and
read timeouts of 60 s or more.

### Hermes mode

1. **Enable the dashboard's username/password sign-in.** The Hermes Agent dashboard ships a `basic` sign-in provider
   (see the environment-variable reference in the Hermes Agent documentation). Set, in the environment of the dashboard:
   `HERMES_DASHBOARD_BASIC_AUTH_USERNAME`, `HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH` (a scrypt hash, preferred over the plaintext
   `…_PASSWORD`; the docs show how to compute it) and `HERMES_DASHBOARD_BASIC_AUTH_SECRET` (32+ random bytes). Without a fixed secret the
   dashboard signs sessions with a random key per process, so every dashboard restart invalidates the app's tokens and you must sign in again.
   Start the dashboard on loopback, e.g. `hermes dashboard --host 127.0.0.1 --port 9119 --no-open`.
2. **Start the receiver** ([install](#install)); the default `HEALTH_RECEIVER_AUTH_URL` already points at the dashboard.
3. **Configure the reverse proxy** as above.
4. **Sign in from the app**: enter the domain, tap *Sign in*, use the dashboard's login page, grant Health Connect access and tap *Sync all data*.

How the receiver authenticates: it forwards the caller's `Authorization` and `Cookie` headers to the dashboard's `GET /api/auth/me` and
accepts the request only if that answers `200` (`401` → `401`; anything else or an unreachable dashboard → `503`, i.e. it fails closed).
Positive answers are cached for at most 60 s. Any authenticated dashboard session is accepted – there is no per-user authorisation, so use a
single-user setup. If the dashboard is down, health sync is unavailable too. (Details of the Hermes side were checked against
Hermes Agent as of September 2026; consult its documentation if they have changed.)

### Using another auth backend

Set `HEALTH_RECEIVER_AUTH_URL` to any URL that (a) receives the caller's `Authorization` and `Cookie` headers, (b) answers `200`
for a valid session and `401` for an invalid one. Combined with your own implementation of `/auth/native/*`, that is all the receiver needs.

### Check that it works

```
curl -s http://127.0.0.1:9120/healthz                              # {"status":"ok","readonly":false}
curl -s http://127.0.0.1:9120/readyz                               # "auth_backend": true when the sign-in service is reachable
curl -s https://gateway.example.com/api/health/config              # the JSON window from the contract
curl -si https://gateway.example.com/api/health/sync/status        # HTTP 401 without a token
```

### Backups and safety

* With the SQLite store the database file is the only copy of what the app has uploaded. Do not `cp` a running database; use the built-in
  command, which is safe while the receiver is running (read-only copy of one consistent snapshot, verified, written under a temporary name and
  then renamed, mode 0600, never overwrites, plus a `.sha256` file):

  ```bash
  python3 receiver.py backup                  # -> $HEALTH_RECEIVER_ROOT/backups/health_sync-<UTC time>.sqlite3
  python3 receiver.py backup /mnt/usb/        # into a directory (or give a file name)
  python3 receiver.py verify [FILE]           # integrity check of a copy (default: the live database); exit status 1 on failure
  ```

  Run it with the same environment as the service (`HEALTH_RECEIVER_ROOT`, or `HEALTH_RECEIVER_DB`). The command prints counters only, never record
  contents. A copy on the same disk is not a backup: move it to another machine. A writer waits for a moment while the snapshot is taken.
  Nothing runs it automatically; schedule it yourself if you want regular copies.
* Restore: stop the service, put the copy in place of the database file (`sha256sum -c` first, `receiver.py verify FILE`), start the service.
* The data is health data: keep the directory private, serve only over HTTPS, use a strong password, and consider restricting access by
  VPN or IP. The receiver never logs record contents or tokens.

## Permissions and privacy

* Health Connect **read** permissions only for the categories you choose (activity, heart and vitals, body measurements, sleep and mindfulness, nutrition and hydration,
  cycle tracking, sexual activity), plus history and background read. The two sensitive categories are off by default. All permissions are *declared* in the
  manifest, since Android only lets an app request what it declares, but only the chosen ones are requested. To withdraw a permission completely use the
  Health Connect settings (the app has a button for it).
* `INTERNET`, and foreground-service permissions for the health sync service.
* `POST_NOTIFICATIONS` is declared but never requested; enable it in system settings if you want progress notifications.
* Data goes only to the domain you enter, over HTTPS (plain HTTP is never used). No analytics, advertising or
  third-party services.
* Tokens are kept in the app's private storage. Android backup is disabled.
* See the [privacy policy](PRIVACY.md) and the [security policy](SECURITY.md) (how to report a vulnerability).

## Requirements

Android 8.0 (API 26) or newer with Health Connect (built into Android 14+, a separate app before that).

## Build

Requires JDK 17 and the Android SDK (platform 36, build-tools 35 or newer).

```
./gradlew :app:assembleDebug            # debug APK: app/build/outputs/apk/debug/
```

The repository ships **without** a default server address. To pre-fill the domain field in your own builds, put
`gate.defaultDomain=gateway.example.com` into `~/.gradle/gradle.properties` (outside the repository) or pass
`-Pgate.defaultDomain=...`.

## Releases

Releases are built by GitHub Actions and are signed with the project's release key. Pushing a tag `v<versionName>`
(the tag must equal `versionName` in `app/build.gradle.kts`, for example `v0.3`; `versionCode` is derived from it as
`major*10000 + minor*100 + patch`, so it can never fall below an earlier build and cause Android's "downgrade" refusal) builds the APK, verifies its
signature, package name and version, and publishes it with a SHA-256 file as a GitHub Release (versions `0.x` are
marked as pre-releases). Nothing is built on ordinary pushes; **CI** (app build, server tests, secret scan) runs on pull requests and on demand.

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

## Development

* Server tests (standard library only): `cd server && python3 -m unittest discover -s tests` and
  `python3 tests/smoke_write.py` (end-to-end with a stub auth backend; run it again with `HEALTH_RECEIVER_STORE=sqlite`).
* App: `./gradlew :app:testDebugUnitTest` (JVM tests: data categories, permissions vs manifest, error texts, explicit serialisation against the reflective
  encoder on real Health Connect objects) and `./gradlew :app:assembleDebug`.

## License

[MIT](LICENSE) © 2026 Alexander Shalin. The app bundles third-party libraries under Apache-2.0, BSD-3-Clause and MIT; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The server uses only the Python standard library.

## Troubleshooting

**"App not installed" when installing over another build.** Android never installs a lower `versionCode` over a higher one and never
installs an APK signed with a different key over an existing app of the same package. Debug builds (built locally) and release builds
(from GitHub) are signed with different keys, so remove the other build first. Remember that the old copy may be hidden: check other users,
a work profile or Private Space and the *Uninstall for all users* option in system settings. With a computer and `adb` you can check and clean up:

```
adb shell pm list users
adb shell pm list packages -u | grep healthconnectgate     # -u also lists apps uninstalled with "keep data"
adb uninstall --user <id> com.bishop.healthconnectgate     # once per user id that still has it
```

Compare the file you downloaded with the published checksum (`sha256sum -c HealthConnectGate-<version>.apk.sha256`) if an installation fails
for no visible reason.

## Limitations

* Tokens are stored unencrypted in the app's private storage.
* Only the 13 record types the reference receiver models are written by explicit code; the remaining types are still serialised by reflection over the Health Connect
  classes (a library upgrade can change that JSON, and R8 stays off because of it). A unit test guards the explicit encoder against the reflective output.
* The interface is a minimal, English-only screen built in code.
* One gateway and one user are assumed; the receiver has no sign-in of its own.
