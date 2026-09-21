# Roadmap

Ideas for future work, roughly by priority. Nothing here is promised; the [README](README.md) describes what exists today.

## App

* **Tokens in the Android Keystore.** They are currently kept in the app's private preferences (see *Limitations*).
* **Health Connect availability check** (`getSdkStatus`) with a clear message and an install button instead of a failure on devices without it.
* **`ForegroundServicePermission` lint error.** The `health` foreground service starts on current devices; either declare the extra sensor permission the lint rule asks for or document why it is not needed.
* **Sync settings:** interval, Wi-Fi only, charging only.
* **Filter by data origin** (`dataOriginFilter`) so duplicates can be dropped on the phone instead of on the server.
* **Hint to update** when the server announces a minimum app version.
* **Instrumented tests and an install check on an emulator in CI** (API 35/36).
* **Explicit serialisation for the remaining record types** (13 are explicit, the rest use reflection), then enable R8.
* Lower priority: Compose/Material 3 and dark theme; `network_security_config` and `dataExtractionRules` (cleartext is already off and backup is disabled, so these are belt and braces).

## Server

* **Rate limiting** and a cap on concurrent connections (today: 64 MB body limit, 500 records per request).
* **Machine-readable contract (OpenAPI) and a conformance test suite** that any third-party backend can run against its own URL.
* **Observability:** structured (JSON) logs and a Prometheus `/metrics` endpoint.
* **Export** (CSV / Parquet / FHIR) and ready-made daily views.
* **Packaging:** `pyproject.toml`, `ruff`/`mypy` in CI.
* Optional encryption of the database at rest.

## Releases and CI

* `needs:` on tests and lint before a release is published.
* Build provenance (`actions/attest-build-provenance`) and an SBOM (CycloneDX) attached to releases.
* CodeQL (Kotlin, Python) and a dependency vulnerability check.
* **APK signature scheme v3** so the signing key can be rotated one day without asking everyone to reinstall.
* Release notes generated from a `CHANGELOG.md`.

## Not planned

* A built-in username/password sign-in in the receiver. It is deliberately left to an auth backend; a minimal single-user login would need its own security review.
