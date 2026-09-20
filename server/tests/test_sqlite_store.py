"""SQLite storage tests (stage 11). Every payload is synthetic (ids ``test-*``, zero/placeholder values).

Run:  python3 -m unittest tests.test_sqlite_store -v
"""
import copy
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import health_sqlite as hs  # noqa: E402
import receiver  # noqa: E402

GFIT, XIAOMI, HC = hs.GFIT_PACKAGE, hs.XIAOMI_PACKAGE, hs.HC_PHONE_PREFIX + ".abc123"
T0 = datetime(2026, 1, 1, 10, 0, 0, tzinfo=timezone.utc)


def iso(dt, ms=0):
    text = dt.strftime("%Y-%m-%dT%H:%M:%S")
    return f"{text}.{ms:03d}Z" if ms else f"{text}Z"


def meta(rid, pkg, model=None, mfr=None, lm=None, cid=None, cv=0):
    return {"clientRecordId": cid, "clientRecordVersion": cv, "dataOrigin": {"packageName": pkg},
            "device": {"manufacturer": mfr, "model": model, "type": 0, "type$annotations": None},
            "id": rid, "lastModifiedTime": lm or iso(T0 + timedelta(hours=3), 123),
            "recordingMethod": 0, "recordingMethod$annotations": None}


def interval(kind, rid, pkg, extra, start=T0, minutes=30, model=None, lm=None, **kw):
    end = start + timedelta(minutes=minutes)
    data = {"startTime": iso(start), "endTime": iso(end, 500), "startZoneOffset": "+03:00",
            "endZoneOffset": "+03:00", "metadata": meta(rid, pkg, model, lm=lm, **kw)}
    data.update(extra)
    return {"id": rid, "kind": kind, "data": data}


def steps(rid, pkg, count=100, **kw):
    return interval("Steps", rid, pkg, {"count": count}, **kw)


def distance(rid, pkg, meters=50.5, **kw):
    return interval("Distance", rid, pkg, {"distance": {"meters": meters, "kilometers": meters / 1000,
                                                        "feet": meters * 3.28, "inches": meters * 39.4,
                                                        "miles": meters / 1609}}, **kw)


def kcal(kind, rid, pkg, value=12.5, **kw):
    return interval(kind, rid, pkg, {"energy": {"kilocalories": value, "calories": value * 1000,
                                                "joules": value * 4184, "kilojoules": value * 4.184}}, **kw)


def heart(rid, pkg=GFIT, bpm=(60, 61)):
    return interval("HeartRate", rid, pkg, {"samples": [
        {"time": iso(T0 + timedelta(seconds=10 * i), 250), "beatsPerMinute": b} for i, b in enumerate(bpm)]},
        minutes=1)


def speed(rid, pkg=GFIT):
    return interval("Speed", rid, pkg, {"samples": [
        {"time": iso(T0 + timedelta(seconds=5 * i)), "speed": {"metersPerSecond": 1.25 + i, "kilometersPerHour": 4.5,
                                                              "milesPerHour": 2.8}} for i in range(3)]}, minutes=1)


def sleep(rid, pkg=XIAOMI):
    return interval("SleepSession", rid, pkg, {"notes": None, "title": "nap", "stages": [
        {"startTime": iso(T0), "endTime": iso(T0 + timedelta(minutes=10)), "stage": 4, "stage$annotations": None},
        {"startTime": iso(T0 + timedelta(minutes=10)), "endTime": iso(T0 + timedelta(minutes=20)), "stage": 5,
         "stage$annotations": None}]}, minutes=20)


def exercise(rid, pkg=XIAOMI):
    return interval("ExerciseSession", rid, pkg, {
        "exerciseType": 8, "exerciseType$annotations": None, "laps": [], "exerciseRouteResult": {},
        "notes": None, "title": None, "plannedExerciseSessionId": None,
        "segments": [{"startTime": iso(T0), "endTime": iso(T0 + timedelta(minutes=5)), "segmentType": 3,
                      "segmentType$annotations": None, "repetitions": 0}]}, minutes=30)


def weight(rid, pkg="com.huami.watch.hmwatchmanager"):
    return {"id": rid, "kind": "Weight", "data": {
        "time": iso(T0, 7), "zoneOffset": "Z", "metadata": meta(rid, pkg),
        "weight": {"kilograms": 70.5, "grams": 70500, "micrograms": 7.05e10, "milligrams": 7.05e7,
                   "ounces": 2486.7, "pounds": 155.4}}}


def blood_pressure(rid, pkg="com.ihealthlabs.MyVitalsPro"):
    return {"id": rid, "kind": "BloodPressure", "data": {
        "time": iso(T0), "zoneOffset": "+03:00", "metadata": meta(rid, pkg),
        "systolic": {"millimetersOfMercury": 120.0}, "diastolic": {"millimetersOfMercury": 80.0},
        "bodyPosition": 1, "bodyPosition$annotations": None,
        "measurementLocation": 2, "measurementLocation$annotations": None}}


def envelope(records, chunk="c1", complete=False):
    return {"schema_version": 1, "run_id": "r1", "chunk_id": chunk, "chunk_start": "2026-01-01T00:00:00Z",
            "chunk_end": "2026-02-01T00:00:00Z", "complete": complete, "records": records}


class CompactTests(unittest.TestCase):
    def test_input_is_never_mutated(self):
        for rec in (steps("test-1", GFIT), distance("test-2", GFIT), kcal("TotalCaloriesBurned", "test-3", GFIT),
                    heart("test-4"), speed("test-5"), sleep("test-6"), exercise("test-7"), weight("test-8"),
                    blood_pressure("test-9")):
            before = copy.deepcopy(rec)
            row = hs.compact(rec)
            self.assertNotEqual(row.kind, hs.RAW_KIND, rec["kind"])
            self.assertEqual(rec, before, rec["kind"])

    def test_values_use_canonical_units(self):
        row = hs.compact(distance("test-d", XIAOMI, meters=1234.5))
        self.assertEqual((row.kind, row.v, row.d), (hs.KIND_CODES["Distance"], 1234.5, 30 * 60 * 1000 + 500))
        self.assertEqual(hs.compact(kcal("ActiveCaloriesBurned", "test-k", XIAOMI, 7.25)).v, 7.25)
        self.assertEqual(hs.compact(weight("test-w")).v, 70.5)

    def test_time_and_zone_roundtrip(self):
        for text in ("2026-09-19T10:07:12Z", "2026-09-19T10:07:12.234Z", "1999-12-31T23:59:59.999Z"):
            self.assertEqual(hs.ms_to_iso(hs.iso_to_ms(text)), text)
        for zone in ("+03:00", "-05:30", "+00:00", "+05:45"):
            self.assertEqual(hs.q_to_zone(hs.zone_to_q(zone)), zone)
        self.assertIsNone(hs.zone_to_q("Z"))
        for bad in ("2026-09-19T10:07:12.000Z", "2026-09-19T10:07:12.2345Z", "2026-09-19 10:07:12Z",
                    "2026-09-19T10:07:12+03:00"):
            with self.assertRaises(hs.Fallback):
                hs.iso_to_ms(bad)
        with self.assertRaises(hs.Fallback):
            hs.zone_to_q("+03:07")

    def test_unknown_shapes_fall_back_to_verbatim(self):
        cases = [
            {"id": "test-u1", "kind": "OxygenSaturation", "data": {"time": iso(T0), "value": 97}},
            {**steps("test-u2", GFIT), "surprise": 1},
            steps("test-u3", GFIT),
            steps("test-u4", GFIT),
        ]
        cases[2]["data"]["newField"] = "x"                           # unexpected key inside data
        cases[3]["data"]["startTime"] = "2026-01-01T10:00:00.000Z"   # non-round-trippable time
        for rec in cases:
            row = hs.compact(rec)
            self.assertEqual(row.kind, hs.RAW_KIND, rec["id"])
            self.assertEqual(json.loads(row.raw), rec)

    def test_samples_roundtrip(self):
        row = hs.compact(heart("test-h", bpm=(60, 200, 0)))
        start = hs.iso_to_ms(iso(T0))
        self.assertEqual(hs.unpack_hr(start, row.samples),
                         [(hs.iso_to_ms(iso(T0 + timedelta(seconds=10 * i), 250)), b) for i, b in enumerate((60, 200, 0))])
        row = hs.compact(speed("test-s"))
        self.assertEqual([v for _, v in hs.unpack_speed(start, row.samples)], [1.25, 2.25, 3.25])

    def test_key_matches_receiver(self):
        for rec in ({"id": "abc", "kind": "Steps"}, {"kind": "Steps", "data": {"x": 1}}):
            self.assertEqual(hs.record_key(rec), receiver.record_key(rec))


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "data"
        self.root.mkdir()
        self.db_path = self.root / "health_sync.sqlite3"
        self.files = receiver.Store(self.root, Path(self.tmp.name) / "run" / "lock")
        self.db = hs.SqliteStore(self.db_path)
        self.addCleanup(lambda: self.db.close())
        self.api = receiver.SqliteHealthApi(self.files, self.db, mirror=True)

    def ingest(self, *records):
        self.db.begin()
        out = self.db.ingest(list(records), datetime.now(timezone.utc))
        self.db.commit()
        return out

    def stubs(self):
        return {k: c for k, c in self.db.db.execute("SELECT k, canon FROM rec WHERE canon IS NOT NULL")}

    def k(self, rid):
        return hs.key_hash(rid)


class DedupTests(Base):
    def test_r1_gfit_first_then_phone_folds_existing_row(self):
        self.ingest(steps("test-g", GFIT, model="Pixel 8 Pro"))
        self.assertEqual(self.stubs(), {})
        self.ingest(steps("test-p", HC))
        self.assertEqual(self.stubs(), {self.k("test-g"): self.k("test-p")})

    def test_r1_phone_first_then_gfit_is_stub_on_arrival(self):
        self.ingest(steps("test-p", HC))
        _, dups, rules = self.ingest(steps("test-g", GFIT, model="Pixel 8 Pro"))
        self.assertEqual((dups, rules), (0, {"R1": 1}))
        self.assertEqual(self.stubs(), {self.k("test-g"): self.k("test-p")})
        self.assertEqual(self.db.count(), 2)

    def test_r2_watch_mirror_for_distance_and_calories(self):
        self.ingest(distance("test-xd", XIAOMI), kcal("ActiveCaloriesBurned", "test-xk", XIAOMI))
        self.ingest(distance("test-gd", GFIT), kcal("ActiveCaloriesBurned", "test-gk", GFIT))
        self.assertEqual(self.stubs(), {self.k("test-gd"): self.k("test-xd"), self.k("test-gk"): self.k("test-xk")})

    def test_r3_newer_google_fit_version_wins_in_both_orders(self):
        old, new = "2026-01-01T13:00:00.100Z", "2026-01-01T13:00:00.900Z"
        for first, second in ((("test-old", old), ("test-new", new)), (("test-new", new), ("test-old", old))):
            self.tearDown_db()
            self.ingest(kcal("TotalCaloriesBurned", first[0], GFIT, lm=first[1]))
            self.ingest(kcal("TotalCaloriesBurned", second[0], GFIT, lm=second[1]))
            self.assertEqual(self.stubs(), {self.k("test-old"): self.k("test-new")})

    def tearDown_db(self):
        self.db.close()
        for f in self.root.glob("health_sync.sqlite3*"):
            f.unlink()
        self.db = hs.SqliteStore(self.db_path)

    def test_no_link_when_not_exact_or_ambiguous(self):
        self.ingest(steps("test-p", HC, count=100), steps("test-g1", GFIT, count=101))          # value differs
        self.ingest(steps("test-g2", GFIT, start=T0 + timedelta(seconds=1)))                    # start differs
        self.ingest(steps("test-p2", HC, count=7), steps("test-x2", XIAOMI, count=7),
                    steps("test-g3", GFIT, count=7))                                             # two canon candidates
        self.assertEqual(self.stubs(), {})
        self.assertEqual(self.db.count(), 6)

    def test_other_sources_and_kinds_are_never_linked(self):
        self.ingest(heart("test-h1"), heart("test-h2"), speed("test-s1", XIAOMI), speed("test-s2", GFIT),
                    steps("test-w", "com.huami.watch.hmwatchmanager"), steps("test-i", "com.ihealthlabs.MyVitalsPro"))
        self.assertEqual(self.stubs(), {})

    def test_resend_of_a_folded_mirror_is_a_duplicate(self):
        self.ingest(steps("test-p", HC))
        self.ingest(steps("test-g", GFIT))
        accepted, dups, _ = self.ingest(steps("test-g", GFIT), steps("test-p", HC))
        self.assertEqual((accepted, dups), ([], 2))
        self.assertEqual(self.db.count(), 2)

    def test_folding_repoints_stubs_no_chains(self):
        self.ingest(kcal("TotalCaloriesBurned", "test-a", GFIT, lm="2026-01-01T13:00:00.100Z"))
        self.ingest(kcal("TotalCaloriesBurned", "test-b", GFIT, lm="2026-01-01T13:00:00.900Z"))   # a -> b
        self.ingest(kcal("TotalCaloriesBurned", "test-x", XIAOMI, ))                                # canon arrives late
        self.assertFalse(set(self.stubs().values()) & set(self.stubs()), "chain detected")
        self.assertEqual(set(self.stubs().values()), {self.k("test-x")})

    def test_probe_uses_partial_index(self):
        plan = " ".join(r[3] for r in self.db.db.execute(
            "EXPLAIN QUERY PLAN SELECT r.k FROM rec r JOIN src s ON s.id=r.src WHERE r.kind IN (1,2,3,4) "
            "AND r.canon IS NULL AND r.s=1 AND r.d=2 AND r.kind=1 AND r.v=3 AND r.k<>4"))
        self.assertIn("ix_probe", plan)


class GoogleFitReturnsTests(Base):
    """Google Fit sync may be switched off and on again: its records must always be handled safely."""

    def steps_total(self):
        """Sum of Steps over the canonical view: every mirror that was folded away must not be counted twice."""
        return round(self.db.db.execute("SELECT COALESCE(SUM(v), 0) FROM records_canonical WHERE kind = 'Steps'").fetchone()[0])

    def canonical_count(self):
        return self.db.db.execute("SELECT COUNT(*) FROM records_canonical").fetchone()[0]

    def base_day(self):
        watch = [steps(f"test-w{i}", XIAOMI, count=100 + i, start=T0 + timedelta(minutes=30 * i)) for i in range(4)]
        phone = [steps(f"test-p{i}", HC, count=50 + i, start=T0 + timedelta(hours=6, minutes=10 * i), minutes=10)
                 for i in range(3)]
        self.api.sync(envelope(watch + phone, "base", complete=True))
        return watch, phone

    def test_backfill_after_a_long_pause_is_folded_and_changes_no_totals(self):
        watch, phone = self.base_day()
        canonical, total = self.canonical_count(), self.steps_total()
        # weeks later Google Fit is switched on again and re-delivers mirrors of the old data under NEW ids
        mirrors = [steps(f"test-gw{i}", GFIT, count=100 + i, start=T0 + timedelta(minutes=30 * i)) for i in range(4)]
        mirrors += [steps(f"test-gp{i}", GFIT, count=50 + i, start=T0 + timedelta(hours=6, minutes=10 * i), minutes=10,
                          model="Pixel 8 Pro") for i in range(3)]
        out = self.api.sync(envelope(mirrors, "later", complete=True))
        self.assertEqual((out["accepted"], out["duplicates"], out["total"]), (7, 0, 14))
        self.assertEqual(len(self.stubs()), 7)
        self.assertEqual(self.canonical_count(), canonical)
        self.assertEqual(self.steps_total(), total)
        again = self.api.sync(envelope(mirrors, "later2"))          # the same batch re-sent
        self.assertEqual((again["accepted"], again["duplicates"], again["total"]), (0, 7, 14))

    def test_gfit_arriving_before_its_counterpart_is_folded_later(self):
        gf = [steps(f"test-g{i}", GFIT, count=10 + i, start=T0 + timedelta(minutes=5 * i), minutes=5) for i in range(3)]
        self.api.sync(envelope(gf, "g1"))
        self.assertEqual(self.canonical_count(), 3)
        before = self.steps_total()
        self.api.sync(envelope([steps(f"test-c{i}", HC, count=10 + i, start=T0 + timedelta(minutes=5 * i), minutes=5)
                                for i in range(3)], "c1"))
        self.assertEqual(len(self.stubs()), 3)
        self.assertEqual(self.canonical_count(), 3)                  # canonical count unchanged: mirrors folded
        self.assertEqual(self.steps_total(), before)                 # and no step is counted twice

    def test_mixed_order_inside_one_batch(self):
        batch = [steps("test-g", GFIT, count=9), steps("test-p", HC, count=9), steps("test-g2", GFIT, count=9)]
        out = self.api.sync(envelope(batch, "mix"))
        self.assertEqual((out["accepted"], out["total"]), (3, 3))
        self.assertEqual(self.stubs(), {self.k("test-g"): self.k("test-p"), self.k("test-g2"): self.k("test-p")})

    def test_google_fit_only_data_is_kept_and_never_lost(self):
        self.api.sync(envelope([heart("test-h1"), speed("test-s1"), steps("test-lone", GFIT, count=77),
                                kcal("TotalCaloriesBurned", "test-tc", GFIT), sleep("test-sl", GFIT)], "only"))
        self.assertEqual(self.stubs(), {})                            # nothing to mirror: all five stay full rows
        self.assertEqual(self.db.decode_samples(self.k("test-h1"))[0][1], 60)
        self.assertEqual(self.steps_total(), 77)                     # unmatched Google Fit steps are kept

    def test_new_phone_model_or_missing_device_still_matches(self):
        self.api.sync(envelope([steps("test-p", HC, count=5)], "p"))
        self.api.sync(envelope([steps("test-g1", GFIT, count=5, model="Pixel 10 Pro"),
                                steps("test-g2", GFIT, count=5, model=None)], "g"))
        self.assertEqual(set(self.stubs()), {self.k("test-g1"), self.k("test-g2")})

    def test_ambiguity_is_never_guessed_when_google_fit_returns(self):
        self.api.sync(envelope([steps("test-p", HC, count=5), steps("test-x", XIAOMI, count=5)], "both"))
        self.api.sync(envelope([steps("test-g", GFIT, count=5)], "g"))
        self.assertEqual(self.stubs(), {})                            # two possible originals -> keep, don't guess


class ApiParityTests(unittest.TestCase):
    """The SQLite API must answer exactly like the JSONL API for the same request sequence."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.roots = []
        for name in ("jsonl", "sqlite"):
            root = base / name
            root.mkdir()
            (root / "health_connect_config.json").write_text(json.dumps(
                {"dynamic_range": True, "chunk_months": 99, "batch_size": 10, "history_start": "2020-01-01T00:00:00Z"}))
            self.roots.append(root)
        self.legacy = receiver.HealthApi(receiver.Store(self.roots[0], base / "l1"))
        self.db = hs.SqliteStore(self.roots[1] / "health_sync.sqlite3")
        self.addCleanup(self.db.close)
        self.new = receiver.SqliteHealthApi(receiver.Store(self.roots[1], base / "l2"), self.db, mirror=True)

    def both(self, method, *args):
        outs = []
        for api in (self.legacy, self.new):
            try:
                outs.append(("ok", getattr(api, method)(*args)))
            except receiver.HttpError as exc:
                outs.append(("err", exc.status, exc.detail))
        if method == "config":  # history_end is "now": compare everything else, require it to be a timestamp
            for out in outs:
                self.assertRegex(out[1].pop("history_end"), r"^\d{4}-\d\d-\d\dT.*Z$")
            self.assertNotIn("accepts_changes", outs[0][1])  # only the SQLite store can apply updates and deletions
            self.assertIs(outs[1][1].pop("accepts_changes"), True)
        if method == "sync":  # the SQLite API adds a counter for replaced records; nothing is replaced in these sequences
            for out in outs[1:]:
                if out[0] == "ok":
                    self.assertEqual(out[1].pop("updated"), 0)
        self.assertEqual(outs[0], outs[1], (method, args))
        return outs[0]

    def test_request_sequence_matches(self):
        past = [steps(f"test-{i}", HC, count=i + 1, start=T0 + timedelta(minutes=40 * i)) for i in range(5)]
        past.append(weight("test-w"))
        future = steps("test-future", HC, start=datetime.now(timezone.utc) + timedelta(days=30))
        self.both("config")
        self.both("sync_status")
        self.both("sync", envelope(past[:3], "c1"))
        self.both("sync", envelope(past[2:], "c1", complete=True))            # 1 duplicate + new ones
        self.both("sync", envelope([future, past[0]], "c2"))                  # future-dated + dup
        self.both("sync", past[:2])                                           # legacy bare list
        self.both("sync", envelope([], "c3", complete=True))                  # empty complete chunk
        self.both("sync", envelope([past[0], past[0], steps("test-new", HC, count=9)], "c4"))  # dup inside batch
        self.both("sync_status")
        self.both("config")
        for bad in (None, {}, {"records": "x"}, envelope([1]), {"schema_version": 2, "records": []},
                    envelope([{}] * 501)):
            self.both("sync", bad)
        good_after_bad = self.both("sync", envelope([steps("test-tail", HC, count=3)], "c5"))
        self.assertEqual(good_after_bad[1]["accepted"], 1)
        self.both("sync_status")

    def test_diagnostics_match(self):
        ev = {"events": [{"event_id": "e1", "timestamp": "2026-01-01T00:00:00Z", "phase": "sync", "message":
                          "Authorization: Bearer abc.def token=xyz", "exception_type": "X", "stack_trace": "tb",
                          "ignored": 1}, {"unknown": 1}]}
        self.both("diagnostics_status")
        self.both("diagnostics", ev)
        self.both("diagnostics", {"events": []})
        self.both("diagnostics_status")
        for bad in (None, {"events": "x"}, {"events": [1]}, {"events": [{}] * 51}):
            self.both("diagnostics", bad)
        text = json.dumps(self.db.db.execute("SELECT message FROM diagnostics").fetchall())
        self.assertNotIn("abc.def", text)
        self.assertNotIn("xyz", text)

    def test_jsonl_mirror_is_byte_identical_to_legacy_files(self):
        recs = [steps(f"test-{i}", HC, count=i + 1) for i in range(4)] + [weight("test-w")]
        self.both("sync", envelope(recs[:3], "c1"))
        self.both("sync", envelope(recs[2:], "c1", complete=True))
        for name in ("health_connect_sync.jsonl", "health_connect_manifest.json"):
            self.assertEqual((self.roots[0] / name).read_bytes(), (self.roots[1] / name).read_bytes(), name)
        a = [json.loads(x) for x in (self.roots[0] / "health_connect_sync_audit.jsonl").read_text().splitlines()]
        b = [json.loads(x) for x in (self.roots[1] / "health_connect_sync_audit.jsonl").read_text().splitlines()]
        for row in a + b:
            row.pop("timestamp")
        self.assertEqual(a, b)


class StoreBehaviourTests(Base):
    def test_mirror_off_writes_no_legacy_files(self):
        api = receiver.SqliteHealthApi(self.files, self.db, mirror=False)
        api.sync(envelope([steps("test-1", HC)], "c1", complete=True))
        api.diagnostics({"events": [{"event_id": "e"}]})
        for name in ("health_connect_sync.jsonl", "health_connect_manifest.json", "health_connect_sync_audit.jsonl",
                     "health_connect_diagnostics.jsonl"):
            self.assertFalse((self.root / name).exists(), name)
        self.assertEqual(api.sync_status()["records"], 1)
        self.assertEqual(list(api.sync_status()["chunks"]), ["c1"])

    def test_restart_keeps_everything(self):
        self.api.sync(envelope([steps("test-1", HC), steps("test-2", GFIT)], "c1", complete=True))
        self.api.diagnostics({"events": [{"event_id": "e", "timestamp": "t1"}]})
        self.db.close()
        db2 = hs.SqliteStore(self.db_path)
        self.addCleanup(db2.close)
        api2 = receiver.SqliteHealthApi(self.files, db2, mirror=True)
        self.assertEqual(api2.sync_status()["records"], 2)
        again = api2.sync(envelope([steps("test-1", HC), steps("test-2", GFIT), steps("test-3", HC, count=5)], "c2"))
        self.assertEqual((again["accepted"], again["duplicates"], again["total"]), (1, 2, 3))
        self.assertEqual(api2.diagnostics_status(), {"ok": True, "count": 1, "latest_timestamp": "t1"})
        self.assertIn("c1", api2.sync_status()["chunks"])

    def test_failure_after_insert_rolls_everything_back(self):
        self.api.sync(envelope([steps("test-1", HC)], "c1"))
        original = self.files.append_lines

        def boom(*a, **k):
            raise OSError("disk full")
        self.files.append_lines = boom
        with self.assertRaises(OSError):
            self.api.sync(envelope([steps("test-2", HC, count=5)], "c2", complete=True))
        self.files.append_lines = original
        self.assertEqual(self.db.count(), 1)
        self.assertEqual(self.api.sync_status()["chunks"], {})
        retry = self.api.sync(envelope([steps("test-2", HC, count=5)], "c2", complete=True))
        self.assertEqual((retry["accepted"], retry["total"]), (1, 2))

    def test_failed_batch_does_not_poison_latest_cache(self):
        self.api.sync(envelope([steps("test-1", HC, start=T0)], "c1"))
        before = self.db.latest_observation(datetime.now(timezone.utc))
        self.files.append_lines = lambda *a, **k: (_ for _ in ()).throw(OSError("x"))
        with self.assertRaises(OSError):
            self.api.sync(envelope([steps("test-2", HC, start=T0 + timedelta(days=2))], "c2"))
        self.assertEqual(self.db.latest_observation(datetime.now(timezone.utc)), before)

    def test_readonly_config_does_not_write(self):
        (self.root / "health_connect_config.json").write_text(json.dumps({"dynamic_range": True}))
        self.api.sync(envelope([steps("test-f", HC, start=datetime.now(timezone.utc) + timedelta(seconds=1))], "c1"))
        ro = receiver.SqliteHealthApi(self.files, self.db, readonly=True, mirror=True)
        before = self.db.db.execute("SELECT COUNT(*) FROM future_ts").fetchone()[0]
        self.assertEqual(before, 1)
        import time
        time.sleep(1.2)
        self.assertNotEqual(ro.config()["history_start"], "1970-01-01T00:00:00Z")
        self.assertEqual(self.db.db.execute("SELECT COUNT(*) FROM future_ts").fetchone()[0], 1)

    def test_concurrent_writers_exactly_once(self):
        recs = [steps(f"test-{i}", HC, count=i + 1, start=T0 + timedelta(minutes=i)) for i in range(60)]
        results, errors = [], []

        def worker(offset):
            try:
                for i in range(0, 60, 10):
                    batch = recs[(i + offset) % 60:(i + offset) % 60 + 10] or recs[:10]
                    results.append(self.api.sync(envelope(batch, f"c{offset}")))
            except Exception as exc:  # pragma: no cover
                errors.append(exc)
        threads = [threading.Thread(target=worker, args=(o,)) for o in (0, 5, 10, 15)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        self.assertEqual(errors, [])
        self.assertEqual(self.db.count(), 60)
        self.assertEqual(sum(r["accepted"] for r in results), 60)
        lines = (self.root / "health_connect_sync.jsonl").read_text().splitlines()
        self.assertEqual(len(lines), 60)
        self.assertEqual(len({json.loads(x)["id"] for x in lines}), 60)

    def test_file_is_private_and_single_file(self):
        self.api.sync(envelope([steps("test-1", HC)], "c1"))
        self.assertEqual(oct(self.db_path.stat().st_mode & 0o777), "0o600")
        self.assertEqual(sorted(p.name for p in self.root.glob("health_sync.sqlite3*")), ["health_sync.sqlite3"])
        self.assertEqual(self.db.db.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def test_refuses_to_open_foreign_database(self):
        foreign = Path(self.tmp.name) / "other.db"
        con = sqlite3.connect(foreign)
        con.execute("PRAGMA application_id=42")
        con.execute("PRAGMA user_version=1")
        con.execute("CREATE TABLE t(x)")
        con.commit()
        con.close()
        with self.assertRaises(RuntimeError):
            hs.SqliteStore(foreign)

    def test_main_refuses_empty_db_next_to_existing_jsonl(self):
        (self.root / "health_connect_sync.jsonl").write_text('{"id":"x","kind":"Steps","data":{}}\n')
        env = {**os.environ, "HEALTH_RECEIVER_STORE": "sqlite", "HEALTH_RECEIVER_ROOT": str(self.root),
               "HEALTH_RECEIVER_LOCK": str(Path(self.tmp.name) / "l"), "HEALTH_RECEIVER_PORT": "0",
               "HEALTH_RECEIVER_DB": str(Path(self.tmp.name) / "fresh.sqlite3")}
        out = subprocess.run([sys.executable, str(ROOT / "receiver.py")], env=env, capture_output=True, text=True,
                             timeout=20)
        self.assertEqual(out.returncode, 2, out.stderr)
        self.assertIn("refusing to start", out.stderr)


DERIVED_UNITS = {"distance": ("feet", "inches", "kilometers", "miles"), "energy": ("calories", "joules", "kilojoules"),
                 "basalMetabolicRate": ("watts",), "weight": ("grams", "micrograms", "milligrams", "ounces", "pounds"),
                 "height": ("feet", "inches", "kilometers", "miles")}


def canonical(record, drop_nulls=False):
    """What the app sends as record_format 2: canonical units only, no null annotations, no duplicated id or end offset."""
    r = copy.deepcopy(record)
    d = r["data"]
    for key, names in DERIVED_UNITS.items():
        for name in names:
            d.get(key, {}).pop(name, None)
    for sample in d.get("samples", []):
        if isinstance(sample.get("speed"), dict):
            sample["speed"].pop("kilometersPerHour", None)
            sample["speed"].pop("milesPerHour", None)

    def strip(o):
        if isinstance(o, dict):
            for k in [k for k in o if k.endswith("$annotations") and o[k] is None]:
                del o[k]
            for v in o.values():
                strip(v)
        elif isinstance(o, list):
            for v in o:
                strip(v)
    strip(d)
    d["metadata"].pop("id", None)
    if d.get("endZoneOffset") == d.get("startZoneOffset"):
        d.pop("endZoneOffset", None)
    if drop_nulls:
        for name in ("title", "notes", "plannedExerciseSessionId"):
            if d.get(name) is None:
                d.pop(name, None)
        if d.get("laps") == []:
            d.pop("laps")
    return r


class CanonicalFormatTests(unittest.TestCase):
    FIELDS = ("k", "kind", "s", "d", "v", "src", "lm", "z", "cv", "samples", "raw", "canon")

    def samples(self):
        return [steps("test-1", GFIT), distance("test-2", XIAOMI), kcal("TotalCaloriesBurned", "test-3", GFIT),
                kcal("ActiveCaloriesBurned", "test-4", XIAOMI), heart("test-5"), speed("test-6"), sleep("test-7"),
                exercise("test-8"), weight("test-9"), blood_pressure("test-10")]

    def test_the_canonical_record_is_stored_exactly_like_the_full_one(self):
        for full in self.samples():
            for drop_nulls in (False, True):
                small = canonical(full, drop_nulls)
                self.assertLess(len(json.dumps(small)), len(json.dumps(full)), full["kind"])
                a, b = hs.compact(full), hs.compact(small)
                self.assertNotEqual(b.kind, hs.RAW_KIND, f"{full['kind']} fell back to a raw row")
                for name in self.FIELDS:
                    self.assertEqual(getattr(a, name), getattr(b, name), f"{full['kind']}.{name}")
                self.assertEqual(a.x or {}, b.x or {}, full["kind"])

    def test_a_canonical_record_with_a_different_end_zone_keeps_it(self):
        full = steps("test-z", GFIT)
        full["data"]["endZoneOffset"] = "+04:00"
        small = canonical(full)
        self.assertIn("endZoneOffset", small["data"])                        # different offsets are never merged
        self.assertEqual(hs.compact(small).kind, hs.RAW_KIND)                # ...and the unusual shape is kept verbatim


class DiagnosticsAndChunksTests(Base):
    def test_duplicate_event_ids_are_stored_once(self):
        ev = {"event_id": "e1", "timestamp": "2026-01-01T00:00:00Z", "phase": "sync", "message": "m"}
        first = self.api.diagnostics({"events": [ev, {"event_id": "e2", "message": "x"}]})
        again = self.api.diagnostics({"events": [ev]})                    # the app retried the same outbox
        self.assertEqual((first["accepted"], again["accepted"]), (2, 1))  # the response contract is unchanged
        self.assertEqual(self.api.diagnostics_status()["count"], 2)
        self.api.diagnostics({"events": [{"message": "no id"}, {"message": "no id"}]})   # no id -> cannot be de-duplicated
        self.assertEqual(self.api.diagnostics_status()["count"], 4)

    def test_duplicates_from_an_old_database_are_removed_when_it_is_opened(self):
        self.db.db.execute("DROP INDEX ux_diag_event")                    # what a pre-rule database looks like
        rows = [("dup", "t1"), ("dup", "t2"), ("dup", "t3"), ("solo", "t4")]
        self.db.db.executemany("INSERT INTO diagnostics(event_id, timestamp) VALUES (?, ?)", rows)
        self.db.close()
        db2 = hs.SqliteStore(self.db_path)
        self.addCleanup(db2.close)
        self.assertEqual(db2.db.execute("SELECT event_id, timestamp FROM diagnostics ORDER BY seq").fetchall(),
                         [("dup", "t1"), ("solo", "t4")])                # the first copy of each id survives
        self.assertIsNotNone(db2.db.execute("SELECT 1 FROM sqlite_master WHERE name='ux_diag_event'").fetchone())
        db2.begin(); db2.add_diagnostics([{"event_id": "dup", "timestamp": "later"}]); db2.commit()
        self.assertEqual(db2.diagnostics_status()[0], 2)

    def test_chunk_list_is_bounded_to_the_newest_entries(self):
        self.db.begin()
        for i in range(hs.MAX_CHUNKS + 50):
            self.db.set_chunk(f"2026-01|2026-01-01T00:{i // 60:02d}:{i % 60:02d}Z|open", {"complete": True, "n": i})
        self.db.commit()
        chunks = self.db.chunks()
        self.assertEqual(len(chunks), hs.MAX_CHUNKS)
        self.assertIn(f"2026-01|2026-01-01T00:{(hs.MAX_CHUNKS + 49) // 60:02d}:{(hs.MAX_CHUNKS + 49) % 60:02d}Z|open", chunks)
        self.assertNotIn("2026-01|2026-01-01T00:00:00Z|open", chunks)

    def test_a_stable_open_chunk_id_is_overwritten_not_added(self):
        for _ in range(3):
            self.api.sync(envelope([], "2026-09|2026-09-20T07:30:00Z|open", complete=True))
        self.assertEqual(list(self.api.sync_status()["chunks"]), ["2026-09|2026-09-20T07:30:00Z|open"])


if __name__ == "__main__":
    unittest.main()
