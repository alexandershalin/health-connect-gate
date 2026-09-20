"""Updates and deletions (Health Connect Changes API): store rules and the /api/health/changes endpoint.
Every payload is synthetic.

Run:  python3 -m unittest tests.test_changes -v
"""
import gzip
import json
import sqlite3
import sys
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import health_sqlite as hs  # noqa: E402
import receiver  # noqa: E402
from test_receiver import ReceiverTestCase  # noqa: E402
from test_sqlite_store import Base, GFIT, HC, T0, iso, kcal, steps  # noqa: E402

LATER = iso(T0 + timedelta(hours=5), 250)
EARLIER = iso(T0 + timedelta(hours=1), 250)


def unknown_kind(rid, lm):
    """A record the store does not model: kept verbatim (kind 0)."""
    return {"id": rid, "kind": "NoSuchKind", "data": {"metadata": {"lastModifiedTime": lm}, "x": 1}}


class UpdateRuleTests(Base):
    def row(self, rid):
        return self.db.db.execute("SELECT kind, s, d, v, lm, cv, canon FROM rec WHERE k=?", (self.k(rid),)).fetchone()

    def counted(self, *records):
        self.db.begin()
        out = self.db.ingest_counted(list(records), datetime.now(timezone.utc))
        self.db.commit()
        return len(out[0]), out[1], out[2]  # accepted, duplicates, updated

    def test_a_newer_version_replaces_the_stored_value(self):
        self.assertEqual(self.counted(steps("test-1", HC, count=10)), (1, 0, 0))
        self.assertEqual(self.counted(steps("test-1", HC, count=42, lm=LATER)), (0, 0, 1))
        self.assertEqual(self.row("test-1")[3], 42)
        self.assertEqual(self.db.count(), 1)

    def test_an_older_or_identical_version_never_replaces(self):
        self.counted(steps("test-1", HC, count=10, lm=LATER))
        self.assertEqual(self.counted(steps("test-1", HC, count=99, lm=EARLIER)), (0, 1, 0))
        self.assertEqual(self.counted(steps("test-1", HC, count=99, lm=LATER)), (0, 1, 0))
        self.assertEqual(self.row("test-1")[3], 10)

    def test_same_time_but_a_higher_client_version_replaces(self):
        self.counted(steps("test-1", HC, count=10, lm=LATER, cv=1))
        self.assertEqual(self.counted(steps("test-1", HC, count=11, lm=LATER, cv=1)), (0, 1, 0))
        self.assertEqual(self.counted(steps("test-1", HC, count=12, lm=LATER, cv=2)), (0, 0, 1))
        self.assertEqual(self.row("test-1")[3], 12)

    def test_the_time_range_can_change_with_the_version(self):
        self.counted(steps("test-1", HC, count=10, minutes=30))
        self.counted(steps("test-1", HC, count=10, minutes=45, lm=LATER))
        self.assertEqual(self.row("test-1")[2], 45 * 60 * 1000 + 500)

    def test_other_records_are_untouched_and_the_batch_mixes_all_three_outcomes(self):
        self.counted(steps("test-1", HC, count=1), steps("test-2", HC, count=2))
        out = self.counted(steps("test-1", HC, count=1), steps("test-2", HC, count=20, lm=LATER), steps("test-3", HC, count=3))
        self.assertEqual(out, (1, 1, 1))
        self.assertEqual([self.row(f"test-{i}")[3] for i in (1, 2, 3)], [1, 20, 3])

    def test_the_same_batch_twice_is_idempotent(self):
        batch = [steps("test-1", HC, count=5, lm=LATER), steps("test-2", HC, count=6)]
        self.counted(steps("test-1", HC, count=1), steps("test-2", HC, count=6))
        self.assertEqual(self.counted(*batch), (0, 1, 1))
        self.assertEqual(self.counted(*batch), (0, 2, 0))

    def test_an_updated_canonical_record_keeps_its_mirror_stubs(self):
        self.ingest(kcal("TotalCaloriesBurned", "test-gf", GFIT, value=12.5))
        self.ingest(kcal("TotalCaloriesBurned", "test-hc", HC, value=12.5))
        self.assertEqual(self.stubs(), {self.k("test-gf"): self.k("test-hc")})
        self.assertEqual(self.counted(kcal("TotalCaloriesBurned", "test-hc", HC, value=13.5, lm=LATER)), (0, 0, 1))
        self.assertEqual(self.stubs(), {self.k("test-gf"): self.k("test-hc")})
        self.assertEqual(self.row("test-hc")[3], 13.5)

    def test_a_mirror_stub_has_no_values_and_is_never_replaced(self):
        self.ingest(kcal("TotalCaloriesBurned", "test-hc", HC, value=12.5))
        self.ingest(kcal("TotalCaloriesBurned", "test-gf", GFIT, value=12.5))
        self.assertEqual(self.counted(kcal("TotalCaloriesBurned", "test-gf", GFIT, value=99, lm=LATER)), (0, 1, 0))
        self.assertEqual(self.stubs(), {self.k("test-gf"): self.k("test-hc")})

    def test_a_verbatim_row_is_replaced_only_when_newer_and_different(self):
        self.counted(unknown_kind("test-raw", iso(T0)))
        newer = unknown_kind("test-raw", LATER)
        newer["data"]["x"] = 2
        self.assertEqual(self.counted(newer), (0, 0, 1))
        self.assertEqual(json.loads(self.db.db.execute("SELECT x FROM rec WHERE k=?", (self.k("test-raw"),)).fetchone()[0])["data"]["x"], 2)
        self.assertEqual(self.counted(unknown_kind("test-raw", iso(T0))), (0, 1, 0))  # older
        self.assertEqual(self.counted(newer), (0, 1, 0))  # identical

    def test_a_record_without_a_usable_time_is_a_plain_duplicate(self):
        self.counted(steps("test-1", HC, count=1))
        broken = steps("test-1", HC, count=9)
        broken["data"]["metadata"]["lastModifiedTime"] = "not a time"
        self.assertEqual(self.counted(broken), (0, 1, 0))
        del broken["data"]["metadata"]["lastModifiedTime"]
        self.assertEqual(self.counted(broken), (0, 1, 0))

    def test_ingest_keeps_its_three_value_result(self):
        self.db.begin()
        accepted, duplicates, rules = self.db.ingest([steps("test-1", HC)], datetime.now(timezone.utc))
        self.db.commit()
        self.assertEqual((len(accepted), duplicates, rules), (1, 0, {}))


class DeletionTests(Base):
    def exists(self, rid):
        return self.db.has(self.k(rid))

    def delete(self, *ids):
        self.db.begin()
        out = self.db.delete_ids(list(ids))
        self.db.commit()
        return out

    def test_known_and_unknown_ids(self):
        self.ingest(steps("test-1", HC), steps("test-2", HC))
        self.assertEqual(self.delete("test-1", "test-nope"), (1, 1))
        self.assertFalse(self.exists("test-1"))
        self.assertTrue(self.exists("test-2"))
        self.assertEqual(self.delete("test-1"), (0, 1))  # already gone

    def test_deleting_a_canonical_record_removes_its_mirror_stubs(self):
        self.ingest(kcal("TotalCaloriesBurned", "test-gf", GFIT, value=7))
        self.ingest(kcal("TotalCaloriesBurned", "test-hc", HC, value=7))
        self.ingest(steps("test-other", HC))
        self.assertEqual(self.delete("test-hc"), (1, 0))
        self.assertEqual([self.exists(i) for i in ("test-hc", "test-gf", "test-other")], [False, False, True])
        self.assertEqual(self.stubs(), {})

    def test_deleting_a_stub_leaves_the_canonical_record(self):
        self.ingest(kcal("TotalCaloriesBurned", "test-hc", HC, value=7))
        self.ingest(kcal("TotalCaloriesBurned", "test-gf", GFIT, value=7))
        self.assertEqual(self.delete("test-gf"), (1, 0))
        self.assertTrue(self.exists("test-hc"))

    def test_a_deleted_id_can_arrive_again_as_a_new_record(self):
        self.ingest(steps("test-1", HC, count=1))
        self.delete("test-1")
        accepted, duplicates, _ = self.ingest(steps("test-1", HC, count=2, lm=LATER))
        self.assertEqual((len(accepted), duplicates), (1, 0))

    def test_old_databases_get_the_audit_columns(self):
        if sqlite3.sqlite_version_info < (3, 35):
            self.skipTest("ALTER TABLE DROP COLUMN needs SQLite 3.35")
        self.db.db.execute("ALTER TABLE sync_audit DROP COLUMN updated")
        self.db.db.execute("ALTER TABLE sync_audit DROP COLUMN deleted")
        self.db.db.execute("INSERT INTO sync_audit(ts, received, accepted, duplicates, complete) VALUES ('t', 1, 1, 0, 0)")
        reopened = hs.SqliteStore(self.db_path)
        try:
            self.assertEqual(reopened.db.execute("SELECT updated, deleted FROM sync_audit").fetchall(), [(0, 0)])
            reopened.add_audit({"timestamp": "t2", "received": 1, "updated": 1, "deleted": 2})
            self.assertEqual(reopened.db.execute("SELECT updated, deleted FROM sync_audit ORDER BY seq DESC LIMIT 1").fetchone(), (1, 2))
        finally:
            reopened.close()


def changes(upserts=(), deleted=(), run="run-1"):
    return {"schema_version": 1, "run_id": run, "record_format": 2, "upserts": list(upserts), "deleted_ids": list(deleted)}


class ChangesEndpointTests(ReceiverTestCase):
    def setUp(self):
        super().setUp()
        # replace the JSONL API of the base class with the SQLite one, on the same data directory
        self.server.shutdown()
        self.server.server_close()
        self.db = hs.SqliteStore(self.root / "health_sync.sqlite3")
        self.addCleanup(self.db.close)
        self.api = receiver.SqliteHealthApi(receiver.Store(self.root, Path(self.tmp.name) / "run" / "lock"), self.db,
                                            readonly=self.readonly, mirror=False)
        self.server = receiver.make_server("127.0.0.1", 0, self.api, self.auth)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def envelope(self, records, chunk="c1"):
        return {"schema_version": 1, "run_id": "r", "chunk_id": chunk, "chunk_start": "2026-01-01T00:00:00Z",
                "chunk_end": "2026-02-01T00:00:00Z", "complete": False, "records": records}

    def test_config_announces_the_endpoint(self):
        status, body = self.call("GET", "/api/health/config", token=None)
        self.assertEqual(status, 200)
        self.assertIs(body["accepts_changes"], True)

    def test_requires_authentication(self):
        self.assertEqual(self.call("POST", "/api/health/changes", changes(), token=None)[0], 401)
        self.assertEqual(self.call("POST", "/api/health/changes", changes(), token="wrong")[0], 401)
        self.assertEqual(self.db.count(), 0)

    def test_new_updated_unchanged_and_deleted_records(self):
        self.call("POST", "/api/health/sync", self.envelope([steps("test-1", HC, count=1), steps("test-2", HC, count=2),
                                                             steps("test-3", HC, count=3)]))
        status, body = self.call("POST", "/api/health/changes", changes(
            upserts=[steps("test-1", HC, count=10, lm=LATER), steps("test-2", HC, count=2), steps("test-4", HC, count=4)],
            deleted=["test-3", "test-unknown"]))
        self.assertEqual(status, 200)
        self.assertEqual({k: body[k] for k in ("ok", "accepted", "updated", "duplicates", "received", "deleted", "missing",
                                               "received_deletions", "total")},
                         {"ok": True, "accepted": 1, "updated": 1, "duplicates": 1, "received": 3, "deleted": 1, "missing": 1,
                          "received_deletions": 2, "total": 3})
        self.assertEqual(self.db.db.execute("SELECT v FROM rec WHERE k=?", (hs.key_hash("test-1"),)).fetchone()[0], 10)
        audit = self.db.db.execute("SELECT chunk_id, received, accepted, duplicates, updated, deleted FROM sync_audit "
                                   "ORDER BY seq DESC LIMIT 1").fetchone()
        self.assertEqual(audit, ("changes", 3, 1, 1, 1, 1))

    def test_deletions_apply_after_upserts_of_the_same_batch(self):
        status, body = self.call("POST", "/api/health/changes", changes(upserts=[steps("test-1", HC)], deleted=["test-1"]))
        self.assertEqual((status, body["accepted"], body["deleted"], body["total"]), (200, 1, 1, 0))

    def test_gzip_bodies_are_accepted(self):
        raw = gzip.compress(json.dumps(changes(upserts=[steps("test-1", HC)])).encode())
        status, body = self.call("POST", "/api/health/changes", raw=raw,
                                 headers={"Content-Encoding": "gzip", "Content-Type": "application/json"})
        self.assertEqual((status, body["accepted"]), (200, 1))

    def test_an_empty_batch_is_a_no_op(self):
        status, body = self.call("POST", "/api/health/changes", changes())
        self.assertEqual((status, body["received"], body["deleted"], body["total"]), (200, 0, 0, 0))

    def test_invalid_envelopes_are_rejected_and_nothing_is_written(self):
        self.call("POST", "/api/health/sync", self.envelope([steps("test-1", HC)]))
        bad = [None, [], {}, {"schema_version": 2, "run_id": "r"}, {"schema_version": 1}, {"schema_version": 1, "run_id": ""},
               {**changes(), "upserts": "x"}, {**changes(), "deleted_ids": "x"}, changes(upserts=[1]), changes(deleted=[1]), changes(deleted=[""]),
               changes(deleted=["x" * 513])]
        for payload in bad:
            with self.subTest(payload=str(payload)[:60]):
                self.assertEqual(self.call("POST", "/api/health/changes", payload)[0], 400)
        self.assertEqual(self.call("POST", "/api/health/changes", changes(upserts=[{}] * 501))[0], 413)
        self.assertEqual(self.call("POST", "/api/health/changes", changes(deleted=["a"] * 501))[0], 413)
        self.assertEqual(self.db.count(), 1)

    def test_an_invalid_batch_deletes_nothing(self):
        self.call("POST", "/api/health/sync", self.envelope([steps("test-1", HC)]))
        self.assertEqual(self.call("POST", "/api/health/changes", changes(upserts=[1], deleted=["test-1"]))[0], 400)
        self.assertEqual(self.db.count(), 1)

    def test_the_classic_sync_endpoint_also_applies_newer_versions(self):
        self.call("POST", "/api/health/sync", self.envelope([steps("test-1", HC, count=1)]))
        status, body = self.call("POST", "/api/health/sync", self.envelope([steps("test-1", HC, count=8, lm=LATER)]))
        self.assertEqual((status, body["accepted"], body["duplicates"], body["updated"]), (200, 0, 0, 1))
        self.assertEqual(self.db.db.execute("SELECT v FROM rec").fetchone()[0], 8)

    def test_get_is_not_allowed(self):
        self.assertEqual(self.call("GET", "/api/health/changes")[0], 405)


class ChangesReadOnlyTests(ChangesEndpointTests):
    readonly = True

    def test_posts_are_refused_and_nothing_is_written(self):
        self.assertEqual(self.call("POST", "/api/health/changes", changes(upserts=[steps("test-1", HC)]))[0], 503)
        self.assertEqual(self.db.count(), 0)

    # the inherited tests assume a writable store
    test_new_updated_unchanged_and_deleted_records = None
    test_deletions_apply_after_upserts_of_the_same_batch = None
    test_gzip_bodies_are_accepted = None
    test_the_classic_sync_endpoint_also_applies_newer_versions = None
    test_an_empty_batch_is_a_no_op = None
    test_invalid_envelopes_are_rejected_and_nothing_is_written = None
    test_an_invalid_batch_deletes_nothing = None


class JsonlStoreTests(ReceiverTestCase):
    def test_the_jsonl_store_cannot_apply_updates_and_says_so(self):
        self.assertEqual(self.call("POST", "/api/health/changes", changes(deleted=["x"]))[0], 501)
        self.assertNotIn("accepts_changes", self.call("GET", "/api/health/config", token=None)[1])


if __name__ == "__main__":
    unittest.main()
