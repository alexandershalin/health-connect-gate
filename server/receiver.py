#!/usr/bin/env python3
"""Health Connect Gate receiver: the server side of the Android app (stdlib only).

Serves the endpoints the app talks to (the API contract is in the repository README):

    GET  /api/health/config              public
    GET  /api/health/sync/status         authenticated
    POST /api/health/sync                authenticated
    POST /api/health/diagnostics         authenticated
    GET  /api/health/diagnostics/status  authenticated
    GET  /healthz, /readyz               local probes (do not publish them)

Sign-in (/auth/native/*) is NOT part of this program. Authentication is delegated to an auth backend: the caller's
Authorization/Cookie headers are forwarded to HEALTH_RECEIVER_AUTH_URL (by default the Hermes Agent dashboard's
``/api/auth/me``) and the request is accepted only on HTTP 200. Anything else fails closed. No health payload or
credential is ever logged.

Configuration (environment):
    HEALTH_RECEIVER_HOST      bind address                (default 127.0.0.1)
    HEALTH_RECEIVER_PORT      bind port                   (default 9120)
    HEALTH_RECEIVER_ROOT      data directory              (default ./data)
    HEALTH_RECEIVER_LOCK      cross-process flock file    (default <this dir>/run/receiver.lock)
    HEALTH_RECEIVER_AUTH_URL  auth probe URL              (default http://127.0.0.1:9119/api/auth/me)
    HEALTH_RECEIVER_READONLY  "1" -> POST endpoints answer 503 and nothing is written
    HEALTH_RECEIVER_STORE     "sqlite" (default, compact database) | "jsonl" (plain append-only files)
    HEALTH_RECEIVER_DB        SQLite file                 (default <ROOT>/health_sync.sqlite3)
    HEALTH_RECEIVER_MIRROR_JSONL  sqlite mode: "1" also appends the raw records to JSONL files (default "0")
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import logging
import os
import re
import signal
import sys
import threading
import time
import zlib
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional

import health_sqlite

log = logging.getLogger("health-receiver")

MAX_BODY_BYTES = 64 * 1024 * 1024
MAX_RECORDS = 500
MAX_EVENTS = 50
RECORD_FORMAT = 2  # 2 = canonical units only (see the README); the full format 1 is still accepted
AUTH_CACHE_MAX_ENTRIES = 256
AUTH_CACHE_MAX_TTL = 60.0
AUTH_NEGATIVE_TTL = 5.0
AUTH_TIMEOUT = 5.0
SOCKET_TIMEOUT = 60.0

DEFAULT_ROOT = Path("data")
DEFAULT_AUTH_URL = "http://127.0.0.1:9119/api/auth/me"
DEFAULT_LOCK = Path(__file__).resolve().parent / "run" / "receiver.lock"  # never inside the data dir
DRAIN_LIMIT = 8 * 1024 * 1024

UNAUTHENTICATED_BODY = {"error": "unauthenticated", "detail": "Unauthorized",
                        "reason": "no_cookie", "login_url": "/login"}


class HttpError(Exception):
    def __init__(self, status: int, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


# --------------------------------------------------------------------------- helpers

def dumps(value: Any) -> str:
    """Same encoding FastAPI's JSONResponse uses."""
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


def record_key(record: dict) -> str:
    rid = record.get("id")
    if rid:
        return str(rid)
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def redact_diagnostic(value: Any, limit: int = 12000) -> str:
    text = str(value)
    text = re.sub(r"(?i)bearer\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]", text)
    text = re.sub(r"(?i)(access_token|refresh_token|token|authorization)\s*[:=]\s*[^,;\s}]+", r"\1=[REDACTED]", text)
    return text[:limit]


def _parse_ts(raw: str) -> datetime:
    value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value


def _fsync_dir(path: Path) -> None:
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


# --------------------------------------------------------------------------- storage

class Store:
    """JSONL/manifest/audit/diagnostics storage with an incremental record index.

    The JSONL file is append-only, so the set of record keys and the newest observation time
    are maintained incrementally (only bytes appended since the last scan are parsed). If the
    file is replaced or truncated the index is rebuilt from scratch. Writers serialise on a
    thread lock plus an flock so a second receiver process cannot interleave appends.
    """

    def __init__(self, root: Path, lock_path: Optional[Path] = None):
        self.root = Path(root)
        self.records = self.root / "health_connect_sync.jsonl"
        self.manifest = self.root / "health_connect_manifest.json"
        self.audit = self.root / "health_connect_sync_audit.jsonl"
        self.diagnostics = self.root / "health_connect_diagnostics.jsonl"
        self.config = self.root / "health_connect_config.json"
        self.lock_path = Path(lock_path) if lock_path else DEFAULT_LOCK
        self._thread_lock = threading.RLock()
        self._depth = 0
        self._lock_fd: Optional[int] = None
        self._reset_index()

    # -- locking
    def locked(self):
        return _StoreLock(self)

    def _reset_index(self) -> None:
        self._ino: Optional[int] = None
        self._offset = 0
        self._keys: set[str] = set()
        self._latest_past: Optional[datetime] = None
        self._scan_now: Optional[datetime] = None
        self._future: list[datetime] = []

    # -- index
    def refresh(self) -> None:
        """Bring the index up to date with the file. Caller holds ``locked()``."""
        try:
            st = self.records.stat()
        except FileNotFoundError:
            self._reset_index()
            return
        if self._ino != st.st_ino or st.st_size < self._offset:
            self._reset_index()
            self._ino = st.st_ino
        if st.st_size == self._offset:
            return
        with self.records.open("rb") as stream:
            stream.seek(self._offset)
            chunk = stream.read(st.st_size - self._offset)
        end = chunk.rfind(b"\n")
        if end < 0:  # only a partial trailing line so far
            return
        scan_now = datetime.now(timezone.utc)
        self._scan_now = scan_now
        for raw in chunk[: end + 1].split(b"\n"):
            if not raw.strip():
                continue
            try:
                record = json.loads(raw.decode("utf-8", errors="replace"))
            except (ValueError, TypeError):
                continue
            if not isinstance(record, dict):
                continue
            self._keys.add(record_key(record))
            self._observe_times(record, scan_now)
        self._offset += end + 1

    def _observe_times(self, record: dict, scan_now: datetime) -> None:
        """Mirror of the legacy dynamic_range scan: startTime first, endTime only as fallback."""
        data = record.get("data")
        starts = [record.get("startTime"), data.get("startTime") if isinstance(data, dict) else None]
        ends = [record.get("endTime"), data.get("endTime") if isinstance(data, dict) else None]
        raw_values = [v for v in starts if isinstance(v, str)] or [v for v in ends if isinstance(v, str)]
        try:
            for raw in raw_values:
                value = _parse_ts(raw)
                if value > scan_now:
                    self._future.append(value)
                elif self._latest_past is None or value > self._latest_past:
                    self._latest_past = value
        except (ValueError, TypeError):
            return

    def latest_observation(self, now: datetime) -> Optional[datetime]:
        latest = self._latest_past
        still_future = []
        for value in self._future:
            if value <= now:
                if latest is None or value > latest:
                    latest = value
            else:
                still_future.append(value)
        self._future = still_future
        if latest is not None and (self._latest_past is None or latest > self._latest_past):
            self._latest_past = latest
        return latest

    def key_count(self) -> int:
        return len(self._keys)

    def has_key(self, key: str) -> bool:
        return key in self._keys

    # -- reads
    def read_manifest(self) -> dict:
        try:
            value = json.loads(self.manifest.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {"chunks": {}}
        except (OSError, ValueError):
            return {"chunks": {}}

    def read_config_file(self) -> dict:
        try:
            value = json.loads(self.config.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError):
            return {}

    # -- writes (caller holds locked())
    def append_lines(self, path: Path, lines: list[str]) -> None:
        if not lines:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = "".join(lines).encode("utf-8")
        with path.open("ab+") as stream:
            size = stream.seek(0, os.SEEK_END)
            if size:
                stream.seek(size - 1)
                if stream.read(1) != b"\n":  # heal a torn previous write instead of gluing onto it
                    payload = b"\n" + payload
            stream.seek(0, os.SEEK_END)
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())

    def write_manifest(self, manifest: dict) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        tmp = self.manifest.with_suffix(".tmp")
        with tmp.open("w", encoding="utf-8") as stream:
            stream.write(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
            stream.flush()
            os.fsync(stream.fileno())
        tmp.replace(self.manifest)
        _fsync_dir(self.root)


class _StoreLock:
    def __init__(self, store: Store):
        self.store = store

    def __enter__(self):
        s = self.store
        s._thread_lock.acquire()
        s._depth += 1
        if s._depth == 1:
            try:
                s.lock_path.parent.mkdir(parents=True, exist_ok=True)
                s._lock_fd = os.open(s.lock_path, os.O_RDWR | os.O_CREAT, 0o600)
                fcntl.flock(s._lock_fd, fcntl.LOCK_EX)
            except BaseException:
                s._depth -= 1
                if s._lock_fd is not None:
                    os.close(s._lock_fd)
                    s._lock_fd = None
                s._thread_lock.release()
                raise
        return s

    def __exit__(self, *exc):
        s = self.store
        s._depth -= 1
        if s._depth == 0 and s._lock_fd is not None:
            try:
                fcntl.flock(s._lock_fd, fcntl.LOCK_UN)
            finally:
                os.close(s._lock_fd)
                s._lock_fd = None
        s._thread_lock.release()
        return False


# --------------------------------------------------------------------------- endpoints

class HealthApi:
    def __init__(self, store: Store, readonly: bool = False):
        self.store = store
        self.readonly = readonly

    def _latest_observation(self, now: datetime) -> Optional[datetime]:
        with self.store.locked():
            self.store.refresh()
            return self.store.latest_observation(now)

    def config(self) -> dict:
        s = self.store
        config: dict[str, Any] = {"history_start": "1970-01-01T00:00:00Z", "history_end": None,
                                  "chunk_months": 1, "batch_size": 250}
        loaded = s.read_config_file()
        for key in ("history_start", "history_end", "chunk_months", "batch_size"):
            if key in loaded:
                config[key] = loaded[key]
        try:
            config["chunk_months"] = max(1, min(12, int(config["chunk_months"])))
        except (TypeError, ValueError):
            config["chunk_months"] = 1
        try:
            config["batch_size"] = max(25, min(500, int(config["batch_size"])))
        except (TypeError, ValueError):
            config["batch_size"] = 250
        if loaded.get("dynamic_range") is True:
            now = datetime.now(timezone.utc)
            latest = self._latest_observation(now)
            if latest is not None:
                config["history_start"] = latest.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            config["history_end"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        config["accepts_gzip"] = True  # the app compresses uploads only when the server says it can read them
        config["record_format"] = RECORD_FORMAT
        return config

    def sync_status(self) -> dict:
        s = self.store
        with s.locked():
            manifest = s.read_manifest()
            s.refresh()
            return {"schema_version": 1, "records": s.key_count(), "chunks": manifest.get("chunks", {}),
                    "has_more": False}

    @staticmethod
    def _validate_sync(payload: Any) -> tuple[bool, list]:
        """Validate before anything is written; returns (legacy_list_form, records)."""
        legacy = isinstance(payload, list)
        if legacy:
            records = payload
        elif isinstance(payload, dict):
            records = payload.get("records")
        else:
            records = None
        if not isinstance(records, list):
            raise HttpError(400, "records must be an array")
        if not legacy:
            if (not isinstance(payload.get("schema_version"), int) or payload["schema_version"] != 1
                    or any(not isinstance(payload.get(k), str) or not payload[k]
                           for k in ("run_id", "chunk_id", "chunk_start", "chunk_end"))):
                raise HttpError(400, "invalid chunk envelope")
        if len(records) > MAX_RECORDS:
            raise HttpError(413, "batch exceeds 500 records")
        if any(not isinstance(r, dict) for r in records):
            raise HttpError(400, "records must contain objects")
        return legacy, records

    def sync(self, payload: Any) -> dict:
        s = self.store
        legacy, records = self._validate_sync(payload)
        with s.locked():
            s.root.mkdir(parents=True, exist_ok=True)
            s.refresh()
            seen: set[str] = set()
            lines: list[str] = []
            accepted = duplicates = 0
            for record in records:
                key = record_key(record)
                if s.has_key(key) or key in seen:
                    duplicates += 1
                    continue
                lines.append(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                seen.add(key)
                accepted += 1
            s.append_lines(s.records, lines)
            manifest = s.read_manifest()
            chunks = manifest.setdefault("chunks", {})
            if not legacy and payload.get("complete") is True:
                chunks[payload["chunk_id"]] = {"run_id": payload["run_id"], "chunk_start": payload["chunk_start"],
                                               "chunk_end": payload["chunk_end"], "complete": True}
            s.write_manifest(manifest)
            audit = {"timestamp": datetime.now(timezone.utc).isoformat(),
                     "chunk_id": None if legacy else payload["chunk_id"],
                     "chunk_start": None if legacy else payload["chunk_start"],
                     "chunk_end": None if legacy else payload["chunk_end"],
                     "received": len(records), "accepted": accepted, "duplicates": duplicates,
                     "complete": False if legacy else payload.get("complete") is True}
            s.append_lines(s.audit, [json.dumps(audit, separators=(",", ":")) + "\n"])
            s.refresh()
            return {"schema_version": 1, "ok": True, "accepted": accepted, "duplicates": duplicates,
                    "received": len(records), "total": s.key_count(),
                    "chunk_id": None if legacy else payload["chunk_id"]}

    @staticmethod
    def _redacted_events(payload: Any) -> list[dict]:
        events = payload.get("events") if isinstance(payload, dict) else None
        if not isinstance(events, list) or len(events) > MAX_EVENTS:
            raise HttpError(400, "events must be an array of at most 50 objects")
        if any(not isinstance(e, dict) for e in events):
            raise HttpError(400, "events must contain objects")
        out = []
        for event in events:
            redacted = {key: redact_diagnostic(event[key])
                        for key in ("event_id", "timestamp", "phase", "message", "exception_type", "stack_trace")
                        if key in event}
            if redacted:
                out.append(redacted)
        return out

    def diagnostics(self, payload: Any) -> dict:
        s = self.store
        lines = [json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n"
                 for e in self._redacted_events(payload)]
        with s.locked():
            s.root.mkdir(parents=True, exist_ok=True)
            s.append_lines(s.diagnostics, lines)
        return {"ok": True, "accepted": len(lines)}

    def diagnostics_status(self) -> dict:
        s = self.store
        count = 0
        latest = None
        with s.locked():
            if s.diagnostics.exists():
                for line in s.diagnostics.read_bytes().split(b"\n"):
                    try:
                        event = json.loads(line.decode("utf-8", errors="replace"))
                    except (ValueError, TypeError):
                        continue
                    if isinstance(event, dict):
                        count += 1
                        latest = event.get("timestamp")
        return {"ok": True, "count": count, "latest_timestamp": latest}


class SqliteHealthApi(HealthApi):
    """Same wire contract as ``HealthApi``; records, manifest chunks, audit and diagnostics live in SQLite.

    Write order for a batch: ``BEGIN IMMEDIATE`` -> insert -> (optional) append the accepted raw records to the
    legacy JSONL mirror and fsync -> ``COMMIT``. A SQLite failure therefore never leaves the client with a 200
    for data that is not stored. Manifest/audit mirror files are written after the commit and are best effort.
    """

    def __init__(self, store: Store, db: "health_sqlite.SqliteStore", readonly: bool = False, mirror: bool = True):
        super().__init__(store, readonly)
        self.db = db
        self.mirror = mirror

    def _latest_observation(self, now: datetime) -> Optional[datetime]:
        with self.store.locked():
            return self.db.latest_observation(now, persist=not self.readonly)

    def sync_status(self) -> dict:
        with self.store.locked():
            return {"schema_version": 1, "records": self.db.count(), "chunks": self.db.chunks(), "has_more": False}

    def sync(self, payload: Any) -> dict:
        s = self.store
        legacy, records = self._validate_sync(payload)
        with s.locked():
            self.db.begin()
            try:
                accepted_records, duplicates, _ = self.db.ingest(records, datetime.now(timezone.utc))
                accepted = len(accepted_records)
                if self.mirror and accepted_records:
                    s.root.mkdir(parents=True, exist_ok=True)
                    s.append_lines(s.records, [json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n"
                                               for r in accepted_records])
                chunk = None
                if not legacy and payload.get("complete") is True:
                    chunk = {"run_id": payload["run_id"], "chunk_start": payload["chunk_start"],
                             "chunk_end": payload["chunk_end"], "complete": True}
                    self.db.set_chunk(payload["chunk_id"], chunk)
                audit = {"timestamp": datetime.now(timezone.utc).isoformat(),
                         "chunk_id": None if legacy else payload["chunk_id"],
                         "chunk_start": None if legacy else payload["chunk_start"],
                         "chunk_end": None if legacy else payload["chunk_end"],
                         "received": len(records), "accepted": accepted, "duplicates": duplicates,
                         "complete": False if legacy else payload.get("complete") is True}
                self.db.add_audit(audit)
                total = self.db.count()
                self.db.commit()
            except BaseException:
                self.db.rollback()
                raise
            if self.mirror:
                try:
                    manifest = s.read_manifest()
                    if chunk is not None:
                        manifest.setdefault("chunks", {})[payload["chunk_id"]] = chunk
                    s.write_manifest(manifest)
                    s.append_lines(s.audit, [json.dumps(audit, separators=(",", ":")) + "\n"])
                except OSError:
                    log.exception("JSONL mirror (manifest/audit) failed after commit; SQLite is authoritative")
            return {"schema_version": 1, "ok": True, "accepted": accepted, "duplicates": duplicates,
                    "received": len(records), "total": total,
                    "chunk_id": None if legacy else payload["chunk_id"]}

    def diagnostics(self, payload: Any) -> dict:
        events = self._redacted_events(payload)
        with self.store.locked():
            self.db.begin()
            try:
                self.db.add_diagnostics(events)
                if self.mirror and events:
                    self.store.root.mkdir(parents=True, exist_ok=True)
                    self.store.append_lines(self.store.diagnostics,
                                            [json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n"
                                             for e in events])
                self.db.commit()
            except BaseException:
                self.db.rollback()
                raise
        return {"ok": True, "accepted": len(events)}

    def diagnostics_status(self) -> dict:
        with self.store.locked():
            count, latest = self.db.diagnostics_status()
        return {"ok": True, "count": count, "latest_timestamp": latest}


# --------------------------------------------------------------------------- auth

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Authenticator:
    """Delegates session verification to an auth backend (see module docstring)."""

    def __init__(self, url: str = DEFAULT_AUTH_URL, timeout: float = AUTH_TIMEOUT):
        self.url = url
        self.timeout = timeout
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)
        self._cache: dict[str, tuple[float, int, Any]] = {}
        self._lock = threading.Lock()

    def check(self, authorization: Optional[str], cookie: Optional[str]) -> tuple[int, Any]:
        """Return (200, None) when the caller is authenticated, else (status, json_body)."""
        authorization = (authorization or "").strip()
        cookie = (cookie or "").strip()
        if not authorization and not cookie:
            return 401, UNAUTHENTICATED_BODY
        cache_key = hashlib.sha256(f"{authorization}\0{cookie}".encode()).hexdigest()
        now = time.monotonic()
        with self._lock:
            hit = self._cache.get(cache_key)
            if hit and hit[0] > now:
                return hit[1], hit[2]
        status, body, ttl = self._ask_dashboard(authorization, cookie)
        if ttl > 0:
            with self._lock:
                if len(self._cache) >= AUTH_CACHE_MAX_ENTRIES:
                    self._cache = {k: v for k, v in self._cache.items() if v[0] > now}
                    if len(self._cache) >= AUTH_CACHE_MAX_ENTRIES:
                        self._cache.clear()
                self._cache[cache_key] = (now + ttl, status, body)
        return status, body

    def _ask_dashboard(self, authorization: str, cookie: str) -> tuple[int, Any, float]:
        headers = {"Accept": "application/json"}
        if authorization:
            headers["Authorization"] = authorization
        if cookie:
            headers["Cookie"] = cookie
        request = urllib.request.Request(self.url, headers=headers, method="GET")
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                status, raw = response.status, response.read(65536)
        except urllib.error.HTTPError as exc:
            status, raw = exc.code, exc.read(65536)
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            log.warning("auth backend unreachable: %s", type(exc).__name__)
            return 503, {"detail": "auth backend unavailable"}, 0.0
        try:
            body = json.loads(raw.decode("utf-8", errors="replace")) if raw else None
        except ValueError:
            body = None
        if status == 200:
            ttl = AUTH_CACHE_MAX_TTL
            expires_at = body.get("expires_at") if isinstance(body, dict) else None
            if isinstance(expires_at, (int, float)) and not isinstance(expires_at, bool):
                ttl = max(0.0, min(ttl, float(expires_at) - time.time()))
            return 200, None, ttl
        if status == 401:
            return 401, body if isinstance(body, dict) else UNAUTHENTICATED_BODY, AUTH_NEGATIVE_TTL
        log.warning("auth backend answered unexpected status %s", status)
        return 503, {"detail": "auth backend unavailable"}, 0.0

    def reachable(self) -> bool:
        try:
            with self._opener.open(urllib.request.Request(self.url, method="GET"), timeout=self.timeout):
                return True
        except urllib.error.HTTPError:
            return True
        except (urllib.error.URLError, OSError, TimeoutError):
            return False


# --------------------------------------------------------------------------- HTTP

ROUTES: dict[str, dict[str, tuple[str, bool]]] = {
    "/api/health/config": {"GET": ("config", False)},
    "/api/health/sync/status": {"GET": ("sync_status", True)},
    "/api/health/sync": {"POST": ("sync", True)},
    "/api/health/diagnostics": {"POST": ("diagnostics", True)},
    "/api/health/diagnostics/status": {"GET": ("diagnostics_status", True)},
}
WRITE_ACTIONS = {"sync", "diagnostics"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "health-receiver"
    sys_version = ""
    timeout = SOCKET_TIMEOUT

    api: HealthApi
    auth: Authenticator

    def log_message(self, fmt, *args):  # never log headers/bodies; access line is written in _send
        pass

    def _send(self, status: int, body: Any, extra: Optional[dict] = None, close: bool = False) -> None:
        raw = dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        if close or self.close_connection:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)
        log.info("%s %s -> %s (%.0f ms)", self.command, self.path.split("?", 1)[0], status,
                 (time.monotonic() - self._t0) * 1000)

    def _read_body(self) -> bytes:
        """The request body with any Content-Encoding removed (gzip is supported; identity is the default)."""
        raw = self._read_raw_body()
        encoding = (self.headers.get("Content-Encoding") or "identity").strip().lower()
        if encoding in ("", "identity"):
            return raw
        if encoding != "gzip":
            raise HttpError(415, "unsupported Content-Encoding")
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            # Bounded output: a few kilobytes of gzip must not be able to expand into gigabytes.
            out = decoder.decompress(raw, MAX_BODY_BYTES + 1)
            if len(out) > MAX_BODY_BYTES or decoder.unconsumed_tail:
                raise HttpError(413, "decompressed body too large")
        except zlib.error:
            raise HttpError(400, "invalid gzip body")
        if not decoder.eof:
            raise HttpError(400, "truncated gzip body")
        return out

    def _read_raw_body(self) -> bytes:
        if (self.headers.get("Transfer-Encoding") or "").lower() == "chunked":
            data = bytearray()
            while True:
                size_line = self.rfile.readline(65537).split(b";", 1)[0].strip()
                try:
                    size = int(size_line, 16)
                except ValueError:
                    raise HttpError(400, "invalid chunked encoding")
                if size == 0:
                    while self.rfile.readline(65537) not in (b"\r\n", b"\n", b""):
                        pass
                    return bytes(data)
                if len(data) + size > MAX_BODY_BYTES:
                    raise HttpError(413, "request body too large")
                data += self.rfile.read(size)
                self.rfile.readline(3)
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise HttpError(400, "invalid Content-Length")
        if length < 0:
            raise HttpError(400, "invalid Content-Length")
        if length > MAX_BODY_BYTES:
            raise HttpError(413, "request body too large")
        return self.rfile.read(length) if length else b""

    def _reject_early(self, status: int, body: Any) -> None:
        """Answer before reading the body; drain a bounded amount so the client sees a clean response."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if 0 < length <= DRAIN_LIMIT and (self.headers.get("Transfer-Encoding") or "").lower() != "chunked":
            try:
                remaining = length
                while remaining > 0:
                    block = self.rfile.read(min(65536, remaining))
                    if not block:
                        break
                    remaining -= len(block)
            except OSError:
                pass
            else:
                return self._send(status, body)
        self.close_connection = True
        self._send(status, body)

    def _dispatch(self) -> None:
        self._t0 = time.monotonic()
        path = self.path.split("?", 1)[0]
        method = self.command
        try:
            if path in ("/healthz", "/readyz") and method == "GET":
                return self._probe(path)
            route = ROUTES.get(path)
            if route is None:
                self.close_connection = True
                return self._send(404, {"detail": "Not Found"})
            if method not in route:
                self.close_connection = True
                return self._send(405, {"detail": "Method Not Allowed"}, {"Allow": ", ".join(route)})
            action, needs_auth = route[method]
            if needs_auth:
                status, body = self.auth.check(self.headers.get("Authorization"), self.headers.get("Cookie"))
                if status != 200:
                    return self._reject_early(status, body)
            if action in WRITE_ACTIONS:
                if self.api.readonly:
                    return self._reject_early(503, {"detail": "receiver is in read-only mode"})
                raw = self._read_body()
                try:
                    payload = json.loads(raw.decode("utf-8")) if raw else None
                except (ValueError, UnicodeDecodeError):
                    raise HttpError(400, "invalid JSON body")
                return self._send(200, getattr(self.api, action)(payload))
            return self._send(200, getattr(self.api, action)())
        except HttpError as exc:
            self.close_connection = True
            self._send(exc.status, {"detail": exc.detail})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception:  # never leak internals; details go to the journal without payloads
            log.exception("unhandled error on %s %s", method, path)
            self.close_connection = True
            try:
                self._send(500, {"detail": "Internal Server Error"})
            except OSError:
                pass

    def _probe(self, path: str) -> None:
        if path == "/healthz":
            return self._send(200, {"status": "ok", "readonly": self.api.readonly})
        store = self.api.store
        data_ok = store.root.is_dir() and os.access(store.root, os.R_OK | os.W_OK | os.X_OK)
        db = getattr(self.api, "db", None)
        if db is not None:
            try:
                with store.locked():
                    db.db.execute("SELECT 1").fetchone()
            except Exception:
                data_ok = False
        auth_ok = self.auth.reachable()
        ok = data_ok and auth_ok
        body = {"status": "ok" if ok else "degraded", "data_dir": data_ok,
                "auth_backend": auth_ok, "readonly": self.api.readonly}
        if db is not None:
            body["store"] = "sqlite"
        self._send(200 if ok else 503, body)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = _dispatch


class ReceiverServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64
    allow_reuse_address = True


def make_server(host: str, port: int, api: HealthApi, auth: Authenticator) -> ReceiverServer:
    handler = type("BoundHandler", (Handler,), {"api": api, "auth": auth})
    return ReceiverServer((host, port), handler)


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stderr, format="%(asctime)s %(levelname)s %(message)s")
    env = os.environ
    root = Path(env.get("HEALTH_RECEIVER_ROOT") or DEFAULT_ROOT)
    lock = Path(env["HEALTH_RECEIVER_LOCK"]) if env.get("HEALTH_RECEIVER_LOCK") else None
    readonly = env.get("HEALTH_RECEIVER_READONLY", "") == "1"
    host = env.get("HEALTH_RECEIVER_HOST", "127.0.0.1")
    port = int(env.get("HEALTH_RECEIVER_PORT", "9120"))
    store_kind = env.get("HEALTH_RECEIVER_STORE", "sqlite")
    files = Store(root, lock)
    if store_kind == "sqlite":
        db_path = Path(env.get("HEALTH_RECEIVER_DB") or root / "health_sync.sqlite3")
        db = health_sqlite.SqliteStore(db_path)
        if db.count() == 0 and files.records.exists() and files.records.stat().st_size > 0:
            # An empty database next to existing JSONL data would accept every record again as "new".
            log.error("refusing to start: %s is empty but %s already holds data (legacy JSONL layout); use another "
                      "HEALTH_RECEIVER_ROOT/HEALTH_RECEIVER_DB or HEALTH_RECEIVER_STORE=jsonl", db_path, files.records)
            return 2
        mirror = env.get("HEALTH_RECEIVER_MIRROR_JSONL", "0") == "1"
        api: HealthApi = SqliteHealthApi(files, db, readonly=readonly, mirror=mirror)
        log.info("store=sqlite db=%s rows=%s mirror_jsonl=%s", db_path, db.count(), mirror)
    elif store_kind == "jsonl":
        api = HealthApi(files, readonly=readonly)
    else:
        log.error("unknown HEALTH_RECEIVER_STORE=%r (expected jsonl|sqlite)", store_kind)
        return 2
    auth = Authenticator(env.get("HEALTH_RECEIVER_AUTH_URL", DEFAULT_AUTH_URL))
    server = make_server(host, port, api, auth)
    log.info("listening on %s:%s root=%s readonly=%s", host, port, root, readonly)

    def stop(signum, _frame):
        log.info("signal %s: shutting down", signum)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        server.serve_forever()
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
