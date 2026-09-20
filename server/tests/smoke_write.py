#!/usr/bin/env python3
"""End-to-end smoke test WITH writes, fully isolated (stdlib only, synthetic data only).

Starts a stub "dashboard" (auth backend) and a real ``receiver.py`` subprocess on free loopback
ports, pointing HEALTH_RECEIVER_ROOT / _LOCK at a fresh temp directory. Nothing outside the
temp directory and the two free ports is touched.

    python3 tests/smoke_write.py            # exit 0 = all checks passed
"""
import http.client
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
RECEIVER = HERE / "receiver.py"
GOOD = "Bearer smoke-good-token-XYZ"
RESULTS: list[tuple[bool, str]] = []


def check(cond: bool, name: str, detail: str = "") -> None:
    RESULTS.append((bool(cond), name))
    print(("PASS " if cond else "FAIL ") + name + (f"  [{detail}]" if detail and not cond else ""), flush=True)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class StubDashboard(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port: int):
        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                ok = self.path == "/api/auth/me" and self.headers.get("Authorization") == GOOD
                body = json.dumps({"user": "smoke"} if ok else {"error": "unauthenticated"}).encode()
                self.send_response(200 if ok else 401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        super().__init__(("127.0.0.1", port), H)


def call(port, method, path, body=None, token=GOOD, raw=None, headers=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    h = dict(headers or {})
    if token:
        h["Authorization"] = token
    data = raw
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    conn.request(method, path, body=data, headers=h)
    r = conn.getresponse()
    payload = r.read()
    conn.close()
    try:
        return r.status, json.loads(payload)
    except ValueError:
        return r.status, payload


class Receiver:
    def __init__(self, root, lock, port, auth_url, readonly=False):
        self.port = port
        # defaults < the caller's environment (e.g. HEALTH_RECEIVER_STORE=sqlite) < what this test must control
        self.env = {"HEALTH_RECEIVER_STORE": "jsonl", "HEALTH_RECEIVER_MIRROR_JSONL": "1", **os.environ}
        self.env.update(HEALTH_RECEIVER_HOST="127.0.0.1", HEALTH_RECEIVER_PORT=str(port),
                        HEALTH_RECEIVER_ROOT=str(root), HEALTH_RECEIVER_LOCK=str(lock),
                        HEALTH_RECEIVER_AUTH_URL=auth_url, HEALTH_RECEIVER_READONLY="1" if readonly else "0",
                        PYTHONDONTWRITEBYTECODE="1")
        self.log = tempfile.TemporaryFile()
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen([sys.executable, str(RECEIVER)], env=self.env, stderr=self.log,
                                     stdout=subprocess.DEVNULL)
        for _ in range(100):
            try:
                if call(self.port, "GET", "/healthz", token=None)[0] == 200:
                    return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("receiver did not start")

    def stop(self) -> int:
        self.proc.send_signal(signal.SIGTERM)
        return self.proc.wait(timeout=15)

    def logtext(self) -> str:
        self.log.seek(0)
        return self.log.read().decode("utf-8", "replace")


def rec(i, start="2026-01-01T00:00:00Z"):
    return {"id": f"smoke-{i}", "kind": "Steps", "data": {"startTime": start, "endTime": "2026-01-01T00:05:00Z",
                                                            "count": i, "note": "синтетика ✓"}}


def env_body(records, chunk="c1", complete=False):
    body = {"schema_version": 1, "run_id": "run-smoke", "chunk_id": chunk, "chunk_start": "2026-01-01T00:00:00Z",
            "chunk_end": "2026-02-01T00:00:00Z", "records": records}
    if complete:
        body["complete"] = True
    return body


def lines(path: Path):
    return [l for l in path.read_text(encoding="utf-8").split("\n") if l] if path.exists() else []


def main() -> int:
    tmp = tempfile.TemporaryDirectory(prefix="hc-smoke-")
    root = Path(tmp.name) / "data"
    lock = Path(tmp.name) / "run" / "receiver.lock"
    root.mkdir()
    stub_port, rport = free_port(), free_port()
    check(rport not in (9119, 9120) and stub_port not in (9119, 9120), "isolated ports (not 9119/9120)")
    stub = StubDashboard(stub_port)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    auth_url = f"http://127.0.0.1:{stub_port}/api/auth/me"
    (root / "health_connect_config.json").write_text(json.dumps(
        {"history_start": "2026-01-01T00:00:00Z", "history_end": "2026-06-01T00:00:00Z",
         "chunk_months": 99, "batch_size": 10}))

    rx = Receiver(root, lock, rport, auth_url)
    rx.start()
    try:
        # --- probes / config (public)
        st, b = call(rport, "GET", "/readyz", token=None)
        check(st == 200 and b["auth_backend"] and b["data_dir"], "readyz ok with stub auth up", str(b))
        st, b = call(rport, "GET", "/api/health/config", token=None)
        check(st == 200 and b["chunk_months"] == 12 and b["batch_size"] == 25 and b["history_start"].startswith("2026-01-01"),
              "config public + clamps (99->12, 10->25)", str(b))

        # --- auth gate
        for method, path, body in [("GET", "/api/health/sync/status", None), ("POST", "/api/health/sync", env_body([rec(0)])),
                                   ("POST", "/api/health/diagnostics", {"events": [{"message": "x"}]}),
                                   ("GET", "/api/health/diagnostics/status", None)]:
            s1, _ = call(rport, method, path, body, token=None)
            s2, _ = call(rport, method, path, body, token="Bearer wrong")
            check(s1 == 401 and s2 == 401, f"{method} {path} -> 401 without/with bad token", f"{s1}/{s2}")
        check(not lines(root / "health_connect_sync.jsonl") and not (root / "health_connect_manifest.json").exists(),
              "nothing written by rejected requests")

        # --- sync
        st, b = call(rport, "POST", "/api/health/sync", env_body([rec(i) for i in range(3)]))
        check(st == 200 and b["ok"] and (b["accepted"], b["duplicates"], b["received"], b["total"], b["chunk_id"]) == (3, 0, 3, 3, "c1"),
              "sync envelope: 3 accepted", str(b))
        st, b = call(rport, "POST", "/api/health/sync", env_body([rec(i) for i in range(5)], complete=True))
        check(st == 200 and (b["accepted"], b["duplicates"], b["total"]) == (2, 3, 5), "resend: dedup by id (2 new, 3 dup)", str(b))
        st, b = call(rport, "POST", "/api/health/sync", env_body([], chunk="c2", complete=True))
        check(st == 200 and b["ok"] and b["accepted"] == 0 and b["chunk_id"] == "c2", "zero-record complete chunk confirmed", str(b))
        st, b = call(rport, "GET", "/api/health/sync/status")
        check(st == 200 and b["records"] == 5 and set(b["chunks"]) == {"c1", "c2"} and b["has_more"] is False,
              "status reflects manifest + records", str(b))
        st, b = call(rport, "POST", "/api/health/sync", [rec(100), rec(101)])
        check(st == 200 and b["accepted"] == 2 and b["chunk_id"] is None, "legacy list payload", str(b))

        before_lines = len(lines(root / "health_connect_sync.jsonl"))
        st, b = call(rport, "POST", "/api/health/sync", env_body([rec(200), "oops"]))
        check(st == 400 and len(lines(root / "health_connect_sync.jsonl")) == before_lines, "invalid element -> 400, nothing partially written", str(b))
        check(call(rport, "POST", "/api/health/sync", {"schema_version": 1, "records": []})[0] == 400, "bad envelope -> 400")
        check(call(rport, "POST", "/api/health/sync", raw=b"{not json", headers={"Content-Type": "application/json"})[0] == 400, "invalid JSON -> 400")
        st, b = call(rport, "POST", "/api/health/sync", env_body([rec(1000 + i) for i in range(500)], chunk="big"))
        check(st == 200 and b["accepted"] == 500, "500-record batch accepted", str(b))
        check(call(rport, "POST", "/api/health/sync", env_body([rec(2000 + i) for i in range(501)], chunk="huge"))[0] == 413, "501 records -> 413")

        # --- chunked transfer encoding (what some HTTP stacks send)
        conn = http.client.HTTPConnection("127.0.0.1", rport, timeout=30)
        conn.putrequest("POST", "/api/health/sync")
        conn.putheader("Authorization", GOOD)
        conn.putheader("Transfer-Encoding", "chunked")
        conn.endheaders()
        payload = json.dumps(env_body([rec(3000)], chunk="ck")).encode()
        conn.send(f"{len(payload):x}\r\n".encode() + payload + b"\r\n0\r\n\r\n")
        r = conn.getresponse()
        cb = json.loads(r.read())
        conn.close()
        check(r.status == 200 and cb["accepted"] == 1, "chunked request body", str(cb))

        # --- concurrency: overlapping writers must not create duplicate lines
        shared = [rec(5000 + i) for i in range(60)]
        results = []

        def worker(k):
            results.append(call(rport, "POST", "/api/health/sync", env_body(shared, chunk=f"cc{k}"))[1])

        threads = [threading.Thread(target=worker, args=(k,)) for k in range(8)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        check(sum(r["accepted"] for r in results) == 60 and sum(r["duplicates"] for r in results) == 60 * 7,
              "8 concurrent overlapping writers: exactly 60 accepted in total", str([(r["accepted"], r["duplicates"]) for r in results]))

        # --- diagnostics
        ev = [{"event_id": "e1", "timestamp": "2026-09-19T12:00:00Z", "phase": "sync", "message": "failed Authorization: Bearer abc.def.ghi",
               "exception_type": "IOException", "stack_trace": "token=SECRETVALUE at x", "ignored_field": "drop me"}]
        st, b = call(rport, "POST", "/api/health/diagnostics", {"events": ev})
        check(st == 200 and b["ok"] and b["accepted"] == 1, "diagnostics accepted", str(b))
        diag = (root / "health_connect_diagnostics.jsonl").read_text(encoding="utf-8")
        check("abc.def.ghi" not in diag and "SECRETVALUE" not in diag and "drop me" not in diag and "[REDACTED]" in diag,
              "diagnostics redacted + whitelisted fields")
        st, b = call(rport, "GET", "/api/health/diagnostics/status")
        check(st == 200 and b == {"ok": True, "count": 1, "latest_timestamp": "2026-09-19T12:00:00Z"}, "diagnostics status", str(b))

        # --- on-disk integrity
        rl = lines(root / "health_connect_sync.jsonl")
        parsed = [json.loads(l) for l in rl]
        ids = [p["id"] for p in parsed]
        check(len(ids) == len(set(ids)) == 5 + 2 + 500 + 1 + 60, "JSONL: every line valid JSON, no duplicate ids", f"{len(ids)} lines / {len(set(ids))} unique")
        check(any(p["data"]["note"] == "синтетика ✓" for p in parsed), "UTF-8 preserved on disk")
        man = json.loads((root / "health_connect_manifest.json").read_text())
        check(man["chunks"]["c1"]["complete"] is True and "c2" in man["chunks"], "manifest valid, complete chunks recorded")
        check(not list(root.glob("*.tmp")), "no leftover manifest .tmp")
        audit = [json.loads(l) for l in lines(root / "health_connect_sync_audit.jsonl")]
        check(len(audit) >= 8 and all({"timestamp", "received", "accepted", "duplicates"} <= set(a) for a in audit), "audit rows well-formed", str(len(audit)))
        total_before = len(ids)

        # --- graceful stop + restart: state survives, dedup works across restarts
        check(rx.stop() == 0, "SIGTERM -> clean exit 0")
        rx = Receiver(root, lock, rport, auth_url)
        rx.start()
        st, b = call(rport, "GET", "/api/health/sync/status")
        check(st == 200 and b["records"] == total_before, "after restart: index rebuilt from disk", str(b))
        st, b = call(rport, "POST", "/api/health/sync", env_body([rec(0), rec(9999)], chunk="post-restart"))
        check(st == 200 and (b["accepted"], b["duplicates"]) == (1, 1), "after restart: dedup against pre-restart data", str(b))

        # --- dynamic_range on synthetic data
        cfg = json.loads((root / "health_connect_config.json").read_text())
        cfg["dynamic_range"] = True
        (root / "health_connect_config.json").write_text(json.dumps(cfg))
        st, b = call(rport, "GET", "/api/health/config", token=None)
        check(st == 200 and b["history_start"].startswith("2026-01-01T00:00:00") and b["history_end"] > b["history_start"],
              "dynamic_range: history_start = newest startTime, history_end = now", str(b))

        # --- auth backend down -> fail closed, public config still up
        stub.shutdown()
        stub.server_close()
        st, _ = call(rport, "GET", "/api/health/sync/status", token="Bearer another-token")
        check(st == 503, "dashboard unreachable -> 503 (fail closed)", str(st))
        st, _ = call(rport, "POST", "/api/health/sync", env_body([rec(7777)], chunk="down"), token="Bearer another-token")
        check(st == 503 and not any('"smoke-7777"' in l for l in lines(root / "health_connect_sync.jsonl")), "write refused while auth backend down")
        check(call(rport, "GET", "/api/health/config", token=None)[0] == 200, "public config still served while auth backend down")
        check(call(rport, "GET", "/readyz", token=None)[0] == 503, "readyz degraded while auth backend down")

        # --- log hygiene
        text = rx.logtext()
        check("smoke-good-token" not in text and "abc.def.ghi" not in text and "SECRETVALUE" not in text and "синтетика" not in text
              and "smoke-1000" not in text, "logs contain no tokens or payload data")
        check("Traceback" not in text, "no tracebacks in logs")
        check(rx.stop() == 0, "second SIGTERM -> clean exit 0")

        # --- read-only mode on the same (now populated) directory
        snap = {p.name: p.read_bytes() for p in root.iterdir()}
        stub2 = StubDashboard(stub_port)
        threading.Thread(target=stub2.serve_forever, daemon=True).start()
        ro = Receiver(root, lock, rport, auth_url, readonly=True)
        ro.start()
        st, _ = call(rport, "POST", "/api/health/sync", env_body([rec(8888)], chunk="ro"))
        st2, _ = call(rport, "POST", "/api/health/diagnostics", {"events": [{"message": "x"}]})
        st3, b3 = call(rport, "GET", "/api/health/sync/status")
        check(st == 503 and st2 == 503 and st3 == 200, "read-only mode: POST 503, GET still served", f"{st}/{st2}/{st3}")
        check({p.name: p.read_bytes() for p in root.iterdir()} == snap, "read-only mode wrote nothing (byte-identical)")
        ro.stop()
        stub2.shutdown()
    finally:
        if rx.proc and rx.proc.poll() is None:
            rx.proc.kill()

    tmp.cleanup()
    failed = [n for ok, n in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
