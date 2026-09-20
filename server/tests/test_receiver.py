"""Receiver tests. Every payload is synthetic (ids ``test-*``, zero values) — no medical data.

Run:  python3 -m unittest discover -s tests -v
"""
import http.client
import json
import logging
import os
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import receiver  # noqa: E402

GOOD = "good-test-token-value"
COOKIE = "hermes_session=cookie-test-value"


class StubDashboard:
    """Stands in for the dashboard's /api/auth/me. Counts calls so caching can be asserted."""

    def __init__(self):
        outer = self
        self.calls = 0

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a):
                pass

            def do_GET(self):
                outer.calls += 1
                ok = self.headers.get("Authorization") == f"Bearer {GOOD}" or self.headers.get("Cookie") == COOKIE
                if self.path != "/api/auth/me":
                    body, code = b'{"detail":"nope"}', 404
                elif ok:
                    body, code = json.dumps({"user_id": "test", "expires_at": int(datetime.now().timestamp()) + 3600}).encode(), 200
                else:
                    body, code = json.dumps({"error": "session_expired", "detail": "Unauthorized",
                                             "reason": "invalid_or_expired_session", "login_url": "/login"}).encode(), 401
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}/api/auth/me"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def rec(i, kind="Steps", start="2026-01-01T00:00:00Z", end="2026-01-01T00:05:00Z"):
    return {"id": f"test-{i}", "kind": kind, "data": {"startTime": start, "endTime": end, "count": 0,
                                                        "metadata": {"id": f"test-{i}"}}}


def envelope(records, chunk="c1", complete=False, **kw):
    body = {"schema_version": 1, "run_id": "run-test", "chunk_id": chunk, "chunk_start": "2026-01-01T00:00:00Z",
            "chunk_end": "2026-02-01T00:00:00Z", "records": records}
    if complete:
        body["complete"] = True
    body.update(kw)
    return body


class ReceiverTestCase(unittest.TestCase):
    readonly = False

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "data"
        self.root.mkdir()
        self.stub = StubDashboard()
        self.api = receiver.HealthApi(receiver.Store(self.root, Path(self.tmp.name) / "run" / "lock"), readonly=self.readonly)
        self.auth = receiver.Authenticator(self.stub.url, timeout=2)
        self.server = receiver.make_server("127.0.0.1", 0, self.api, self.auth)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.stub.stop()
        self.tmp.cleanup()

    def call(self, method, path, body=None, token=GOOD, headers=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = dict(headers or {})
        if token:
            h["Authorization"] = f"Bearer {token}"
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        if data is not None:
            h.setdefault("Content-Type", "application/json")
        conn.request(method, path, body=data, headers=h)
        resp = conn.getresponse()
        text = resp.read().decode()
        conn.close()
        try:
            return resp.status, json.loads(text)
        except ValueError:
            return resp.status, text

    def lines(self, name):
        p = self.root / name
        return [json.loads(x) for x in p.read_text().split("\n") if x.strip()] if p.exists() else []


class TestProbesAndRouting(ReceiverTestCase):
    def test_healthz(self):
        self.assertEqual(self.call("GET", "/healthz", token=None), (200, {"status": "ok", "readonly": False}))

    def test_readyz_ok_and_degraded_when_dashboard_down(self):
        status, body = self.call("GET", "/readyz", token=None)
        self.assertEqual((status, body["status"], body["auth_backend"], body["data_dir"]), (200, "ok", True, True))
        self.stub.stop()
        status, body = self.call("GET", "/readyz", token=None)
        self.assertEqual((status, body["status"], body["auth_backend"]), (503, "degraded", False))
        self.stub = StubDashboard()  # so tearDown can stop something

    def test_unknown_path_and_wrong_method(self):
        self.assertEqual(self.call("GET", "/api/health/nope", token=None), (404, {"detail": "Not Found"}))
        self.assertEqual(self.call("GET", "/api/health/sync", token=None), (405, {"detail": "Method Not Allowed"}))
        self.assertEqual(self.call("POST", "/api/health/config", {}, token=None), (405, {"detail": "Method Not Allowed"}))

    def test_get_endpoints_write_nothing(self):
        self.call("GET", "/api/health/config", token=None)
        self.call("GET", "/api/health/sync/status")
        self.call("GET", "/api/health/diagnostics/status")
        self.assertEqual(list(self.root.iterdir()), [])


class TestConfig(ReceiverTestCase):
    def test_defaults_without_file(self):
        status, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual(status, 200)
        self.assertEqual(body, {"history_start": "1970-01-01T00:00:00Z", "history_end": None,
                                "chunk_months": 1, "batch_size": 250})

    def test_static_and_clamps(self):
        (self.root / "health_connect_config.json").write_text(json.dumps(
            {"history_start": "2026-01-01T00:00:00Z", "history_end": "2026-02-01T00:00:00Z", "chunk_months": 99,
             "batch_size": 1, "plan_id": "x", "sync_required": True}))
        _, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual(body, {"history_start": "2026-01-01T00:00:00Z", "history_end": "2026-02-01T00:00:00Z",
                                "chunk_months": 12, "batch_size": 25})

    def test_garbage_numbers_fall_back(self):
        (self.root / "health_connect_config.json").write_text('{"chunk_months":"x","batch_size":null}')
        _, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual((body["chunk_months"], body["batch_size"]), (1, 250))

    def test_dynamic_range_uses_start_then_end_ignores_future_and_is_incremental(self):
        now = datetime.now(timezone.utc)
        past = (now - timedelta(hours=3)).replace(microsecond=0)
        future = now + timedelta(days=1)
        iso = lambda d: d.isoformat().replace("+00:00", "Z")
        (self.root / "health_connect_config.json").write_text('{"dynamic_range": true, "batch_size": 250}')
        self.call("POST", "/api/health/sync", envelope([
            rec(1, start=iso(past - timedelta(hours=1)), end=iso(future)),      # start wins over future end
            rec(2, start=iso(past), end=iso(past + timedelta(minutes=1))),
            {"id": "test-3", "kind": "Total", "data": {"startTime": iso(future), "endTime": iso(future)}},  # future start
            {"id": "test-4", "endTime": iso(past + timedelta(hours=1))},        # endTime fallback (no startTime)
        ]))
        _, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual(body["history_start"], iso(past + timedelta(hours=1)))
        self.assertGreater(body["history_end"], body["history_start"])
        # append a newer record: index must pick it up without a rebuild
        newer = past + timedelta(hours=2)
        self.call("POST", "/api/health/sync", envelope([rec(5, start=iso(newer), end=iso(newer))]))
        _, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual(body["history_start"], iso(newer))

    def test_future_start_becomes_visible_once_it_is_in_the_past(self):
        s = self.api.store
        soon = datetime.now(timezone.utc) + timedelta(seconds=1)
        with s.locked():
            s._future.append(soon)
            self.assertIsNone(s.latest_observation(datetime.now(timezone.utc)))
            self.assertEqual(s.latest_observation(soon + timedelta(seconds=1)), soon)


class TestAuth(ReceiverTestCase):
    def test_public_config_needs_no_auth(self):
        self.assertEqual(self.call("GET", "/api/health/config", token=None)[0], 200)
        self.assertEqual(self.stub.calls, 0)

    def test_no_credentials_401_without_asking_dashboard(self):
        for method, path in (("GET", "/api/health/sync/status"), ("GET", "/api/health/diagnostics/status")):
            status, body = self.call(method, path, token=None)
            self.assertEqual((status, body["error"]), (401, "unauthenticated"))
        self.assertEqual(self.call("POST", "/api/health/sync", envelope([]), token=None)[0], 401)
        self.assertEqual(self.stub.calls, 0)

    def test_invalid_bearer_gets_dashboard_401_body_and_writes_nothing(self):
        status, body = self.call("POST", "/api/health/sync", envelope([rec(1)]), token="bad")
        self.assertEqual((status, body["error"], body["reason"]), (401, "session_expired", "invalid_or_expired_session"))
        self.assertEqual(list(self.root.iterdir()), [])

    def test_valid_bearer_and_cookie(self):
        self.assertEqual(self.call("GET", "/api/health/sync/status")[0], 200)
        self.assertEqual(self.call("GET", "/api/health/sync/status", token=None, headers={"Cookie": COOKIE})[0], 200)
        self.assertEqual(self.call("GET", "/api/health/sync/status", token=None, headers={"Cookie": "x=y"})[0], 401)

    def test_positive_result_is_cached(self):
        for _ in range(5):
            self.call("GET", "/api/health/sync/status")
        self.assertEqual(self.stub.calls, 1)

    def test_dashboard_down_fails_closed_with_503(self):
        self.stub.stop()
        status, body = self.call("GET", "/api/health/sync/status")
        self.assertEqual((status, body), (503, {"detail": "auth backend unavailable"}))
        status, _ = self.call("POST", "/api/health/sync", envelope([rec(1)]))
        self.assertEqual(status, 503)
        self.assertEqual(list(self.root.iterdir()), [])
        self.stub = StubDashboard()

    def test_large_unauthenticated_body_gets_clean_401(self):
        big = envelope([rec(i) for i in range(500)])
        status, _ = self.call("POST", "/api/health/sync", big, token="bad")
        self.assertEqual(status, 401)

    def test_token_never_logged(self):
        records = []
        handler = logging.Handler()
        handler.emit = lambda r: records.append(r.getMessage())
        logging.getLogger("health-receiver").addHandler(handler)
        logging.getLogger("health-receiver").setLevel(logging.DEBUG)
        try:
            self.call("POST", "/api/health/sync", envelope([rec(1)]))
            self.call("POST", "/api/health/sync", envelope([rec(1)]), token="secret-bad-token")
        finally:
            logging.getLogger("health-receiver").removeHandler(handler)
        joined = "\n".join(records)
        self.assertTrue(records)
        for needle in (GOOD, "secret-bad-token", "test-1"):
            self.assertNotIn(needle, joined)


class TestSync(ReceiverTestCase):
    def test_envelope_sync_dedup_manifest_audit(self):
        status, body = self.call("POST", "/api/health/sync", envelope([rec(1), rec(2), rec(1)]))
        self.assertEqual(status, 200)
        self.assertEqual(body, {"schema_version": 1, "ok": True, "accepted": 2, "duplicates": 1, "received": 3,
                                "total": 2, "chunk_id": "c1"})
        status, body = self.call("POST", "/api/health/sync", envelope([rec(2), rec(3)], complete=True))
        self.assertEqual((body["accepted"], body["duplicates"], body["total"]), (1, 1, 3))
        self.assertEqual([r["id"] for r in self.lines("health_connect_sync.jsonl")], ["test-1", "test-2", "test-3"])
        manifest = json.loads((self.root / "health_connect_manifest.json").read_text())
        self.assertEqual(manifest["chunks"]["c1"], {"run_id": "run-test", "chunk_start": "2026-01-01T00:00:00Z",
                                                    "chunk_end": "2026-02-01T00:00:00Z", "complete": True})
        audit = self.lines("health_connect_sync_audit.jsonl")
        self.assertEqual([(a["received"], a["accepted"], a["duplicates"], a["complete"]) for a in audit],
                         [(3, 2, 1, False), (2, 1, 1, True)])
        self.assertEqual(set(audit[0]), {"timestamp", "chunk_id", "chunk_start", "chunk_end", "received", "accepted",
                                         "duplicates", "complete"})
        _, st = self.call("GET", "/api/health/sync/status")
        self.assertEqual((st["records"], st["has_more"], list(st["chunks"])), (3, False, ["c1"]))

    def test_complete_zero_record_chunk_confirms_chunk_id(self):
        _, body = self.call("POST", "/api/health/sync", envelope([], chunk="empty", complete=True))
        self.assertEqual((body["ok"], body["chunk_id"], body["accepted"]), (True, "empty", 0))

    def test_legacy_list_payload(self):
        status, body = self.call("POST", "/api/health/sync", [rec(1), {"kind": "NoId", "v": 1}])
        self.assertEqual((status, body["accepted"], body["chunk_id"]), (200, 2, None))
        status, body = self.call("POST", "/api/health/sync", [{"kind": "NoId", "v": 1}])  # sha256 fallback key
        self.assertEqual((body["accepted"], body["duplicates"]), (0, 1))
        audit = self.lines("health_connect_sync_audit.jsonl")
        self.assertIsNone(audit[0]["chunk_id"])
        self.assertFalse(audit[0]["complete"])

    def test_validation_errors(self):
        cases = [
            ({"schema_version": 1}, 400, "records must be an array"),
            ("nope", 400, "records must be an array"),
            (envelope([], schema_version=2), 400, "invalid chunk envelope"),
            (envelope([], run_id=""), 400, "invalid chunk envelope"),
            (envelope([rec(i) for i in range(501)]), 413, "batch exceeds 500 records"),
        ]
        for payload, code, detail in cases:
            self.assertEqual(self.call("POST", "/api/health/sync", payload), (code, {"detail": detail}), payload)
        self.assertEqual(self.call("POST", "/api/health/sync", raw=b"{not json")[0], 400)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_max_batch_accepted(self):
        status, body = self.call("POST", "/api/health/sync", envelope([rec(i) for i in range(500)]))
        self.assertEqual((status, body["accepted"]), (200, 500))

    def test_invalid_element_writes_nothing(self):
        """Legacy code appended the valid records before failing on the bad one."""
        self.call("POST", "/api/health/sync", envelope([rec(1)]))
        before = (self.root / "health_connect_sync.jsonl").read_bytes()
        status, body = self.call("POST", "/api/health/sync", envelope([rec(2), "oops"]))
        self.assertEqual((status, body), (400, {"detail": "records must contain objects"}))
        self.assertEqual((self.root / "health_connect_sync.jsonl").read_bytes(), before)
        self.assertEqual(len(self.lines("health_connect_sync_audit.jsonl")), 1)

    def test_unicode_roundtrip(self):
        r = rec(1)
        r["data"]["note"] = "тест ✓  "
        self.call("POST", "/api/health/sync", envelope([r]))
        self.assertEqual(self.lines("health_connect_sync.jsonl")[0]["data"]["note"], "тест ✓  ")
        self.assertEqual(self.call("POST", "/api/health/sync", envelope([r]))[1]["duplicates"], 1)

    def test_existing_data_is_deduplicated_and_untouched(self):
        existing = json.dumps(rec(1), separators=(",", ":")) + "\n" + "not json\n" + json.dumps(rec(2)) + "\n"
        (self.root / "health_connect_sync.jsonl").write_text(existing)
        _, body = self.call("POST", "/api/health/sync", envelope([rec(2), rec(3)]))
        self.assertEqual((body["accepted"], body["duplicates"], body["total"]), (1, 1, 3))
        data = (self.root / "health_connect_sync.jsonl").read_text()
        self.assertTrue(data.startswith(existing))

    def test_torn_last_line_is_healed_not_glued(self):
        (self.root / "health_connect_sync.jsonl").write_text(json.dumps(rec(1)) + "\n" + '{"id":"torn')
        _, body = self.call("POST", "/api/health/sync", envelope([rec(2)]))
        self.assertEqual(body["accepted"], 1)
        parsed = []
        for x in (self.root / "health_connect_sync.jsonl").read_text().split("\n"):
            try:
                parsed.append(json.loads(x))
            except ValueError:
                pass  # the torn fragment stays isolated on its own line
        self.assertEqual({p["id"] for p in parsed}, {"test-1", "test-2"})

    def test_index_rebuilds_when_file_is_replaced(self):
        self.call("POST", "/api/health/sync", envelope([rec(1), rec(2)]))
        p = self.root / "health_connect_sync.jsonl"
        p.unlink()
        p.write_text(json.dumps(rec(9)) + "\n")
        _, st = self.call("GET", "/api/health/sync/status")
        self.assertEqual(st["records"], 1)
        _, body = self.call("POST", "/api/health/sync", envelope([rec(1)]))
        self.assertEqual(body["accepted"], 1)

    def test_external_append_is_seen(self):
        self.call("POST", "/api/health/sync", envelope([rec(1)]))
        with (self.root / "health_connect_sync.jsonl").open("a") as f:
            f.write(json.dumps(rec(2)) + "\n")
        _, body = self.call("POST", "/api/health/sync", envelope([rec(2), rec(3)]))
        self.assertEqual((body["accepted"], body["duplicates"], body["total"]), (1, 1, 3))

    def test_chunked_transfer_encoding(self):
        payload = json.dumps(envelope([rec(1)])).encode()
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.putrequest("POST", "/api/health/sync")
        conn.putheader("Authorization", f"Bearer {GOOD}")
        conn.putheader("Transfer-Encoding", "chunked")
        conn.endheaders()
        half = len(payload) // 2
        for part in (payload[:half], payload[half:]):
            conn.send(f"{len(part):x}\r\n".encode() + part + b"\r\n")
        conn.send(b"0\r\n\r\n")
        resp = conn.getresponse()
        body = json.loads(resp.read())
        self.assertEqual((resp.status, body["accepted"]), (200, 1))
        conn.close()

    def test_keep_alive_sequence(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        for i in range(3):
            conn.request("POST", "/api/health/sync", body=json.dumps(envelope([rec(i)])),
                         headers={"Authorization": f"Bearer {GOOD}", "Content-Type": "application/json"})
            r = conn.getresponse()
            self.assertEqual(r.status, 200)
            r.read()
        conn.close()

    def test_concurrent_overlapping_writers_never_duplicate(self):
        errors = []

        def worker(offset):
            try:
                for n in range(5):
                    s, _ = self.call("POST", "/api/health/sync", envelope([rec(offset + n + k) for k in range(20)]))
                    if s != 200:
                        errors.append(s)
            except Exception as exc:  # pragma: no cover
                errors.append(repr(exc))

        threads = [threading.Thread(target=worker, args=(i * 3,)) for i in range(8)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        self.assertEqual(errors, [])
        ids = [r["id"] for r in self.lines("health_connect_sync.jsonl")]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(self.call("GET", "/api/health/sync/status")[1]["records"], len(ids))

    def test_second_store_instance_shares_the_flock(self):
        """Two receiver processes (e.g. during a cutover overlap) must not double-append."""
        other = receiver.HealthApi(receiver.Store(self.root, self.api.store.lock_path))
        self.call("POST", "/api/health/sync", envelope([rec(1)]))
        self.assertEqual(other.sync(envelope([rec(1), rec(2)]))["duplicates"], 1)
        self.assertEqual(self.call("POST", "/api/health/sync", envelope([rec(2)]))[1]["duplicates"], 1)
        self.assertEqual(len(self.lines("health_connect_sync.jsonl")), 2)


class TestDiagnostics(ReceiverTestCase):
    def test_ingest_redacts_and_filters(self):
        events = [{"event_id": "e1", "timestamp": "2026-01-01T00:00:00Z", "phase": "sync",
                   "message": "failed Authorization: Bearer abc.def-123 access_token=xyz", "exception_type": "IOException",
                   "stack_trace": "at x token: secret123, more", "ignored": "dropped"},
                  {"unknown_only": 1}]
        status, body = self.call("POST", "/api/health/diagnostics", {"schema_version": 1, "events": events})
        self.assertEqual((status, body), (200, {"ok": True, "accepted": 1}))
        stored = self.lines("health_connect_diagnostics.jsonl")
        self.assertEqual(len(stored), 1)
        self.assertNotIn("ignored", stored[0])
        text = json.dumps(stored[0])
        for secret in ("abc.def-123", "xyz", "secret123"):
            self.assertNotIn(secret, text)
        self.assertIn("[REDACTED]", text)
        status, st = self.call("GET", "/api/health/diagnostics/status")
        self.assertEqual((status, st), (200, {"ok": True, "count": 1, "latest_timestamp": "2026-01-01T00:00:00Z"}))

    def test_limits_and_validation(self):
        detail = "events must be an array of at most 50 objects"
        self.assertEqual(self.call("POST", "/api/health/diagnostics", {"events": [{"phase": "x"}] * 51}), (400, {"detail": detail}))
        self.assertEqual(self.call("POST", "/api/health/diagnostics", [1]), (400, {"detail": detail}))
        self.assertEqual(self.call("POST", "/api/health/diagnostics", {"events": [{"phase": "a"}, 5]}),
                         (400, {"detail": "events must contain objects"}))
        self.assertEqual(self.call("POST", "/api/health/diagnostics", {"events": [{"phase": "x"}] * 50})[1]["accepted"], 50)

    def test_status_on_empty(self):
        self.assertEqual(self.call("GET", "/api/health/diagnostics/status")[1], {"ok": True, "count": 0, "latest_timestamp": None})


class TestReadOnlyMode(ReceiverTestCase):
    readonly = True

    def test_posts_are_refused_and_nothing_is_written(self):
        self.assertEqual(self.call("GET", "/healthz", token=None)[1]["readonly"], True)
        self.assertEqual(self.call("POST", "/api/health/sync", envelope([rec(1)]))[0], 503)
        self.assertEqual(self.call("POST", "/api/health/diagnostics", {"events": []})[0], 503)
        self.assertEqual(self.call("GET", "/api/health/config", token=None)[0], 200)
        self.assertEqual(self.call("GET", "/api/health/sync/status")[0], 200)
        self.assertEqual(list(self.root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
