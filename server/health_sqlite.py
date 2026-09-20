"""Compact SQLite storage for Health Connect records (stdlib only).

Design:

* One ``rec`` row per accepted record id. The id itself is stored only as a 56-bit hash
  (``k``); the row keeps one canonical unit per value (the unit conversions Health Connect
  adds -- feet/inches/km/miles, joules/kilojoules/calories, ... -- are deterministic and dropped),
  timestamps as epoch milliseconds and HeartRate/Speed samples as a packed blob.
* Anything that does not match the modelled shape *exactly* (unknown kind, extra key, odd time
  format, ...) is stored verbatim as JSON in ``extra`` (``kind = 0``) -- nothing is ever guessed
  or silently dropped.
* Google Fit mirrors are folded into "stub" rows (``canon`` = key of the canonical record) using
  exact-equality rules only (dedup-v1); a stub keeps its id, so re-sending it is still a duplicate.

This module has no dependency on the HTTP receiver; the receiver wraps it (locking, mirror files).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import struct
import time
from calendar import timegm
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Optional

SCHEMA_VERSION = 1
DEDUP_RULES = "dedup-v1"
APPLICATION_ID = 0x48435351  # 'HCSQ'

KIND_CODES = {
    "Steps": 1, "Distance": 2, "ActiveCaloriesBurned": 3, "TotalCaloriesBurned": 4,
    "HeartRate": 5, "Speed": 6, "SleepSession": 7, "ExerciseSession": 8,
    "BasalMetabolicRate": 9, "Weight": 10, "Height": 11, "BloodPressure": 12,
    "RestingHeartRate": 13,
}
KIND_NAMES = {v: k for k, v in KIND_CODES.items()}
RAW_KIND = 0  # verbatim fallback
DEDUP_KINDS = (1, 2, 3, 4)  # Steps, Distance, ActiveCalories, TotalCalories -- literal in the partial index

GFIT_PACKAGE = "com.google.android.apps.fitness"
XIAOMI_PACKAGE = "com.xiaomi.wearable"
HC_PHONE_PREFIX = "com.android.healthconnect.phone"
MIRROR_CANON_CLASSES = ("hc_phone", "xiaomi")  # classes a gfit record can be a mirror of


class Fallback(Exception):
    """Record does not match the modelled shape; store it verbatim."""


# --------------------------------------------------------------------------- keys / times

def record_key(record: dict) -> str:
    """Identical to receiver.record_key (kept separate so this module stays standalone)."""
    rid = record.get("id")
    if rid:
        return str(rid)
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def key_hash(key: str) -> int:
    return int.from_bytes(hashlib.sha256(key.encode("utf-8")).digest()[:7], "big")  # 56-bit: 8-byte rowid varint


_ISO = re.compile(r"(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{3}))?Z\Z")
_ZONE = re.compile(r"([+-])(\d\d):(\d\d)\Z")


def iso_to_ms(text: Any) -> int:
    if not isinstance(text, str):
        raise Fallback("time is not a string")
    m = _ISO.match(text)
    if not m:
        raise Fallback("time format")
    if m.group(7) == "000":  # ".000" would not survive the round trip
        raise Fallback("zero fraction")
    y, mo, d, h, mi, s = (int(m.group(i)) for i in range(1, 7))
    try:
        secs = timegm((y, mo, d, h, mi, s, 0, 0, 0))
    except (ValueError, OverflowError):
        raise Fallback("time range")
    return secs * 1000 + (int(m.group(7)) if m.group(7) else 0)


def ms_to_iso(ms: int) -> str:
    secs, frac = divmod(int(ms), 1000)
    base = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(secs))
    return f"{base}.{frac:03d}Z" if frac else f"{base}Z"


def zone_to_q(text: Any) -> Optional[int]:
    """'Z' -> None; '+03:00' -> 12 (units of 15 minutes)."""
    if text == "Z":
        return None
    if not isinstance(text, str):
        raise Fallback("zone")
    m = _ZONE.match(text)
    if not m or int(m.group(3)) % 15 or int(m.group(2)) > 23:
        raise Fallback("zone format")
    q = int(m.group(2)) * 4 + int(m.group(3)) // 15
    return -q if m.group(1) == "-" else q


def q_to_zone(q: Optional[int]) -> str:
    if q is None:
        return "Z"
    sign = "-" if q < 0 else "+"
    q = abs(q)
    return f"{sign}{q // 4:02d}:{(q % 4) * 15:02d}"


def _parse_ts(raw: str) -> datetime:
    value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value


def _to_us(value: datetime) -> int:
    delta = value - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return (delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds


def us_to_datetime(us: int) -> datetime:
    return datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(microseconds=us)


# --------------------------------------------------------------------------- varints / samples

def _uvarint(n: int) -> bytes:
    if n < 0:
        raise Fallback("negative varint")
    out = bytearray()
    while n > 0x7F:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def _read_uvarint(buf: bytes, i: int) -> tuple[int, int]:
    shift = n = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, i
        shift += 7


def _zz(n: int) -> int:
    return (n << 1) ^ (n >> 63)


def _unzz(n: int) -> int:
    return (n >> 1) ^ -(n & 1)


def pack_hr(start_ms: int, samples: list) -> bytes:
    out = bytearray()
    for smp in samples:
        if not isinstance(smp, dict) or set(smp) != {"time", "beatsPerMinute"}:
            raise Fallback("hr sample shape")
        bpm = smp["beatsPerMinute"]
        if not isinstance(bpm, int) or isinstance(bpm, bool):
            raise Fallback("bpm type")
        out += _uvarint(_zz(iso_to_ms(smp["time"]) - start_ms)) + _uvarint(bpm)
    return bytes(out)


def unpack_hr(start_ms: int, blob: bytes) -> list[tuple[int, int]]:
    out, i = [], 0
    while i < len(blob):
        dt, i = _read_uvarint(blob, i)
        bpm, i = _read_uvarint(blob, i)
        out.append((start_ms + _unzz(dt), bpm))
    return out


def pack_speed(start_ms: int, samples: list) -> bytes:
    out = bytearray()
    for smp in samples:
        if not isinstance(smp, dict) or set(smp) != {"time", "speed"}:
            raise Fallback("speed sample shape")
        sp = dict(smp["speed"])
        mps = sp.pop("metersPerSecond")
        sp.pop("kilometersPerHour", None)   # derived units are optional: record_format 2 omits them
        sp.pop("milesPerHour", None)
        if sp or not isinstance(mps, (int, float)) or isinstance(mps, bool):
            raise Fallback("speed value")
        out += _uvarint(_zz(iso_to_ms(smp["time"]) - start_ms)) + struct.pack("<d", float(mps))
    return bytes(out)


def unpack_speed(start_ms: int, blob: bytes) -> list[tuple[int, float]]:
    out, i = [], 0
    while i < len(blob):
        dt, i = _read_uvarint(blob, i)
        (mps,) = struct.unpack_from("<d", blob, i)
        i += 8
        out.append((start_ms + _unzz(dt), mps))
    return out


# --------------------------------------------------------------------------- compaction

def source_class(package: Optional[str]) -> str:
    package = package or ""
    if package == GFIT_PACKAGE:
        return "gfit"
    if package == XIAOMI_PACKAGE:
        return "xiaomi"
    if package.startswith(HC_PHONE_PREFIX):
        return "hc_phone"
    if package == "com.ihealthlabs.MyVitalsPro":
        return "ihealth"
    if package == "com.huami.watch.hmwatchmanager":
        return "huami"
    return "other"


class Row:
    """Column values of one ``rec`` row (``src`` is a tuple until resolved to an id)."""
    __slots__ = ("k", "kind", "s", "d", "v", "src", "lm", "z", "cv", "samples", "x", "raw", "canon", "dyn_raw")

    def __init__(self, k: int):
        self.k = k
        self.kind = RAW_KIND
        self.s = self.d = self.v = self.src = self.lm = self.z = None
        self.cv = self.samples = self.x = self.raw = self.canon = None
        self.dyn_raw: list[str] = []


def _pop_annotation(d: dict, key: str) -> None:
    if d.pop(key, None) is not None:
        raise Fallback("annotation is not null")


def _num(x: Any) -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)):
        raise Fallback("number expected")
    return x


def _pop_units(d: dict, keep: str, derived: Iterable[str]) -> Any:
    unit = d.pop(keep)
    for name in derived:
        d.pop(name, None)  # derived conversions are optional (record_format 2 omits them)
    return unit


def _small_int(x: Any) -> int:
    if not isinstance(x, int) or isinstance(x, bool):
        raise Fallback("int expected")
    return x


def compact(rec: dict, key: Optional[str] = None) -> Row:
    """Record -> Row. Falls back to a verbatim row when the shape is not exactly the modelled one."""
    key = key if key is not None else record_key(rec)
    row = Row(key_hash(key))
    try:
        _compact_modelled(rec, key, row)
    except (Fallback, KeyError, TypeError, ValueError, AttributeError, OverflowError):
        row = Row(key_hash(key))
        row.kind = RAW_KIND
        row.raw = json.dumps(rec, ensure_ascii=False, separators=(",", ":"))
    row.dyn_raw = _dyn_candidates(rec)
    return row


def _compact_modelled(rec: dict, key: str, row: Row) -> None:
    if set(rec) != {"id", "kind", "data"} or rec["id"] != key or not isinstance(rec["data"], dict):
        raise Fallback("envelope")
    kind = KIND_CODES.get(rec["kind"])
    if kind is None:
        raise Fallback("unknown kind")
    d = dict(rec["data"])
    row.kind = kind

    md = dict(d.pop("metadata"))
    if md.pop("id", key) != key:
        raise Fallback("metadata id")
    row.lm = iso_to_ms(md.pop("lastModifiedTime"))
    origin = dict(md.pop("dataOrigin"))
    package = origin.pop("packageName")
    if origin or not isinstance(package, str):
        raise Fallback("dataOrigin")
    cid = md.pop("clientRecordId")
    if cid is not None and not isinstance(cid, str):
        raise Fallback("clientRecordId")
    x: dict[str, Any] = {}
    if cid is not None:
        x["c"] = cid
    row.x = x
    cv = _small_int(md.pop("clientRecordVersion"))
    row.cv = cv or None
    method = _small_int(md.pop("recordingMethod"))
    _pop_annotation(md, "recordingMethod$annotations")
    dev = dict(md.pop("device"))
    mfr, model, dtype = dev.pop("manufacturer"), dev.pop("model"), _small_int(dev.pop("type"))
    _pop_annotation(dev, "type$annotations")
    if md or dev or not all(x is None or isinstance(x, str) for x in (mfr, model)):
        raise Fallback("metadata extras")
    row.src = (package, mfr, model, dtype, method)

    interval = kind in (1, 2, 3, 4, 5, 6, 7, 8)
    if interval:
        start, end = iso_to_ms(d.pop("startTime")), iso_to_ms(d.pop("endTime"))
        if end < start:
            raise Fallback("end before start")
        zs = d.pop("startZoneOffset")
        ze = d.pop("endZoneOffset", zs)  # record_format 2 drops the end offset when it equals the start offset
        if zs != ze:
            raise Fallback("zones differ")
        row.s, row.d, row.z = start, end - start, zone_to_q(zs)
    else:
        row.s = iso_to_ms(d.pop("time"))
        row.z = zone_to_q(d.pop("zoneOffset"))

    if kind == 1:
        row.v = _small_int(d.pop("count"))
    elif kind == 2:
        dist = dict(d.pop("distance"))
        row.v = _num(_pop_units(dist, "meters", ("feet", "inches", "kilometers", "miles")))
        _require_empty(dist)
    elif kind in (3, 4):
        e = dict(d.pop("energy"))
        row.v = _num(_pop_units(e, "kilocalories", ("calories", "joules", "kilojoules")))
        _require_empty(e)
    elif kind == 5:
        row.samples = pack_hr(row.s, d.pop("samples"))
    elif kind == 6:
        row.samples = pack_speed(row.s, d.pop("samples"))
    elif kind == 7:
        stages = d.pop("stages")
        packed = []
        for st in stages:
            st = dict(st)
            a, b, code = iso_to_ms(st.pop("startTime")), iso_to_ms(st.pop("endTime")), _small_int(st.pop("stage"))
            _pop_annotation(st, "stage$annotations")
            _require_empty(st)
            packed.append([a - row.s, b - a, code])
        x["st"] = packed
        for name in ("title", "notes"):
            val = d.pop(name, None)
            if val is not None:
                x[name] = val
    elif kind == 8:
        row.v = _small_int(d.pop("exerciseType"))
        _pop_annotation(d, "exerciseType$annotations")
        if d.pop("laps", []) != [] or d.pop("exerciseRouteResult", {}) != {}:
            raise Fallback("laps/route present")
        segs = []
        for sg in d.pop("segments"):
            sg = dict(sg)
            a, b = iso_to_ms(sg.pop("startTime")), iso_to_ms(sg.pop("endTime"))
            seg_type, reps = _small_int(sg.pop("segmentType")), _small_int(sg.pop("repetitions"))
            _pop_annotation(sg, "segmentType$annotations")
            _require_empty(sg)
            segs.append([a - row.s, b - a, seg_type, reps])
        if segs:
            x["sg"] = segs
        for name in ("title", "notes", "plannedExerciseSessionId"):
            val = d.pop(name, None)
            if val is not None:
                x[name] = val
    elif kind == 9:
        b = dict(d.pop("basalMetabolicRate"))
        row.v = _num(_pop_units(b, "kilocaloriesPerDay", ("watts",)))
        _require_empty(b)
    elif kind == 10:
        w = dict(d.pop("weight"))
        row.v = _num(_pop_units(w, "kilograms", ("grams", "micrograms", "milligrams", "ounces", "pounds")))
        _require_empty(w)
    elif kind == 11:
        h = dict(d.pop("height"))
        row.v = _num(_pop_units(h, "meters", ("feet", "inches", "kilometers", "miles")))
        _require_empty(h)
    elif kind == 12:
        sy, di = dict(d.pop("systolic")), dict(d.pop("diastolic"))
        row.v, x["v2"] = _num(sy.pop("millimetersOfMercury")), _num(di.pop("millimetersOfMercury"))
        _require_empty(sy), _require_empty(di)
        pos, loc = _small_int(d.pop("bodyPosition")), _small_int(d.pop("measurementLocation"))
        _pop_annotation(d, "bodyPosition$annotations")
        _pop_annotation(d, "measurementLocation$annotations")
        if not (0 <= pos < 256 and 0 <= loc < 256):
            raise Fallback("bp aux range")
        x["a"] = pos * 256 + loc
    elif kind == 13:
        row.v = _small_int(d.pop("beatsPerMinute"))
    _require_empty(d)


def _require_empty(d: dict) -> None:
    if d:
        raise Fallback("unexpected keys: " + ",".join(sorted(d)))


def _dyn_candidates(rec: dict) -> list[str]:
    """Mirror of the legacy dynamic_range scan: startTime first, endTime only as fallback."""
    data = rec.get("data")
    starts = [rec.get("startTime"), data.get("startTime") if isinstance(data, dict) else None]
    ends = [rec.get("endTime"), data.get("endTime") if isinstance(data, dict) else None]
    return [v for v in starts if isinstance(v, str)] or [v for v in ends if isinstance(v, str)]


# --------------------------------------------------------------------------- schema

SCHEMA = f"""
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS src (
  id INTEGER PRIMARY KEY, package TEXT NOT NULL, manufacturer TEXT, model TEXT, dtype INTEGER, method INTEGER,
  cls TEXT NOT NULL, UNIQUE (package, manufacturer, model, dtype, method));
CREATE TABLE IF NOT EXISTS kinds (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS rec (
  k INTEGER PRIMARY KEY, kind INTEGER NOT NULL, s INTEGER, d INTEGER, v NUMERIC, src INTEGER,
  lm INTEGER,      -- lastModified minus (s + d): small signed offset, 3-4 bytes instead of 6
  z INTEGER, cv INTEGER, samples BLOB,
  x TEXT,          -- rare fields as compact JSON: c=clientRecordId v2=diastolic a=BP aux st/sg=stages/segments ...; kind 0: verbatim record
  canon INTEGER);
CREATE INDEX IF NOT EXISTS ix_probe ON rec (s, d)
  WHERE kind IN ({",".join(map(str, DEDUP_KINDS))}) AND canon IS NULL;
CREATE TABLE IF NOT EXISTS future_ts (us INTEGER PRIMARY KEY) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS chunks (chunk_id TEXT PRIMARY KEY, doc TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS sync_audit (
  seq INTEGER PRIMARY KEY, ts TEXT, chunk_id TEXT, chunk_start TEXT, chunk_end TEXT,
  received INTEGER, accepted INTEGER, duplicates INTEGER, complete INTEGER);
CREATE TABLE IF NOT EXISTS diagnostics (
  seq INTEGER PRIMARY KEY, event_id TEXT, timestamp TEXT, phase TEXT, message TEXT,
  exception_type TEXT, stack_trace TEXT);
CREATE VIEW IF NOT EXISTS records_all AS
  SELECT r.k, kd.name AS kind, r.s, r.d, r.v, sr.cls AS source_class, sr.package, sr.model, r.canon
  FROM rec r LEFT JOIN kinds kd ON kd.id = r.kind LEFT JOIN src sr ON sr.id = r.src;
CREATE VIEW IF NOT EXISTS records_canonical AS SELECT * FROM records_all WHERE canon IS NULL;
CREATE VIEW IF NOT EXISTS daily_totals AS
  SELECT date(s / 1000, 'unixepoch') AS day_utc, kind, source_class, COUNT(*) AS n, SUM(v) AS total
  FROM records_canonical WHERE kind IN ('Steps','Distance','ActiveCaloriesBurned','TotalCaloriesBurned')
  GROUP BY 1, 2, 3;
"""

DIAG_FIELDS = ("event_id", "timestamp", "phase", "message", "exception_type", "stack_trace")
MAX_CHUNKS = 200  # newest chunk ids kept for sync/status; older entries only matter for interrupted long imports


def _raw_lm(text: Optional[str]) -> Optional[int]:
    """lastModifiedTime of a verbatim (unmodelled) row, or None."""
    try:
        return iso_to_ms(json.loads(text)["data"]["metadata"]["lastModifiedTime"])
    except (KeyError, TypeError, ValueError, AttributeError, Fallback):
        return None


class SqliteStore:
    """Plain database access. Callers serialise access (the receiver holds its store lock)."""

    def __init__(self, path: Path | str):
        self.path = Path(path)
        new = not self.path.exists()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600)
        os.close(fd)
        self.db = sqlite3.connect(str(self.path), isolation_level=None, check_same_thread=False, timeout=30)
        self.db.execute("PRAGMA journal_mode=DELETE")
        self.db.execute("PRAGMA synchronous=FULL")
        if new or self.db.execute("PRAGMA user_version").fetchone()[0] == 0:
            self.db.execute(f"PRAGMA application_id={APPLICATION_ID}")
            self.db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        elif self.db.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
            raise RuntimeError("not a health-sync database")
        self.db.executescript(SCHEMA)
        self._ensure_unique_diagnostics()
        self._ensure_audit_columns()
        self.db.executemany("INSERT OR IGNORE INTO kinds(id, name) VALUES (?, ?)", KIND_NAMES.items())
        self._src_cache: dict[tuple, int] = {}
        self._in_txn = False
        self._latest_us: Optional[int] = None
        self._load_latest()

    # -- transactions
    def begin(self) -> None:
        self.db.execute("BEGIN IMMEDIATE")
        self._in_txn = True

    def commit(self) -> None:
        self.db.execute("COMMIT")
        self._in_txn = False

    def rollback(self) -> None:
        if self._in_txn:
            self.db.execute("ROLLBACK")
            self._in_txn = False
            self._load_latest()  # in-memory cache may include rolled-back rows
            self._src_cache.clear()

    def close(self) -> None:
        self.db.close()

    # -- meta / latest observation
    def _load_latest(self) -> None:
        row = self.db.execute("SELECT value FROM meta WHERE key='latest_us'").fetchone()
        self._latest_us = int(row[0]) if row else None

    def _set_latest(self, us: int) -> None:
        self._latest_us = us
        self.db.execute("INSERT INTO meta(key, value) VALUES ('latest_us', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(us),))

    def get_meta(self, key: str) -> Optional[str]:
        row = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        self.db.execute("INSERT INTO meta(key, value) VALUES (?, ?) "
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, str(value)))

    def _observe(self, candidates: list[str], scan_now_us: int) -> None:
        try:
            for raw in candidates:
                us = _to_us(_parse_ts(raw))
                if us > scan_now_us:
                    self.db.execute("INSERT OR IGNORE INTO future_ts(us) VALUES (?)", (us,))
                elif self._latest_us is None or us > self._latest_us:
                    self._set_latest(us)
        except (ValueError, TypeError, OverflowError):
            return

    def latest_observation(self, now: datetime, persist: bool = True) -> Optional[datetime]:
        """Newest observation time <= now. ``persist=False`` (read-only mode) never writes."""
        now_us = _to_us(now)
        latest = self._latest_us
        promoted = self.db.execute("SELECT MAX(us) FROM future_ts WHERE us <= ?", (now_us,)).fetchone()[0]
        if promoted is not None:
            if latest is None or promoted > latest:
                latest = promoted
            if persist:
                self.db.execute("DELETE FROM future_ts WHERE us <= ?", (now_us,))
                if latest != self._latest_us:
                    self._set_latest(latest)
        return us_to_datetime(latest) if latest is not None else None

    # -- records
    def count(self) -> int:
        return self.db.execute("SELECT COUNT(*) FROM rec").fetchone()[0]

    def has(self, k: int) -> bool:
        return self.db.execute("SELECT 1 FROM rec WHERE k=?", (k,)).fetchone() is not None

    def _src_id(self, src: tuple) -> int:
        cached = self._src_cache.get(src)
        if cached is not None:
            return cached
        row = self.db.execute("SELECT id FROM src WHERE package IS ? AND manufacturer IS ? AND model IS ? "
                              "AND dtype IS ? AND method IS ?", src).fetchone()
        if row is None:
            cur = self.db.execute("INSERT INTO src(package, manufacturer, model, dtype, method, cls) "
                                  "VALUES (?, ?, ?, ?, ?, ?)", (*src, source_class(src[0])))
            sid = cur.lastrowid
        else:
            sid = row[0]
        self._src_cache[src] = sid
        return sid

    def insert(self, row: Row, scan_now_us: Optional[int] = None) -> Optional[tuple[str, int]]:
        """Insert a new record (caller checked the key is new). Returns (rule, canon_key) when it
        was folded into a mirror stub or when it converted existing mirrors; else None."""
        src_id = self._src_id(row.src) if row.src is not None else None
        cls = source_class(row.src[0]) if row.src is not None else None
        link = self._find_link(row, cls) if row.kind in DEDUP_KINDS and row.src is not None else None
        stub_of = link[1] if link and link[0] == "dup" else None
        if stub_of is not None:
            self.db.execute("INSERT INTO rec(k, kind, canon) VALUES (?, ?, ?)", (row.k, row.kind, stub_of))
        else:
            xtext = row.raw if row.kind == RAW_KIND else (
                json.dumps(row.x, ensure_ascii=False, separators=(",", ":")) if row.x else None)
            lm_rel = row.lm - (row.s + (row.d or 0)) if row.lm is not None and row.s is not None else None
            self.db.execute(
                "INSERT INTO rec(k, kind, s, d, v, src, lm, z, cv, samples, x) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (row.k, row.kind, row.s, row.d, row.v, src_id, lm_rel, row.z, row.cv, row.samples, xtext))
            if link and link[0] == "canon":  # this row is the canonical side: fold existing gfit mirrors
                for mirror_k in link[1]:
                    self._fold(mirror_k, row.k)
        if scan_now_us is not None and row.dyn_raw:
            self._observe(row.dyn_raw, scan_now_us)
        return (link[2], stub_of if stub_of is not None else row.k) if link else None

    def ingest(self, records: list[dict], now: datetime) -> tuple[list[dict], int, dict]:
        """Insert every record whose id is new. Returns (accepted records, duplicate count, rule counts).
        A known id whose record is newer (see ``_is_newer``) replaces the stored row; such updates are not counted here
        (use ``ingest_counted``). Caller owns the transaction. A 56-bit key collision would count as a duplicate
        (p ~ 1e-7 at 1e5 ids)."""
        accepted, duplicates, _updated, rules = self.ingest_counted(records, now)
        return accepted, duplicates, rules

    def ingest_counted(self, records: list[dict], now: datetime) -> tuple[list[dict], int, int, dict]:
        """Like ``ingest`` but also returns how many stored records were replaced by a newer version of the same id."""
        now_us = _to_us(now)
        accepted: list[dict] = []
        duplicates = updated = 0
        rules: dict[str, int] = {}
        for rec in records:
            key = record_key(rec)
            k = key_hash(key)
            stored = self._stored_version(k)
            if stored is None:
                link = self.insert(compact(rec, key), now_us)
                if link:
                    rules[link[0]] = rules.get(link[0], 0) + 1
                accepted.append(rec)
            elif self._replace_if_newer(rec, key, stored, now_us):
                updated += 1
            else:
                duplicates += 1  # cheap id/time probe first: unchanged duplicates never pay for compaction
        return accepted, duplicates, updated, rules

    # -- updates and deletions (Health Connect Changes API)
    def _stored_version(self, k: int):
        """None when the id is unknown, else (canon, lm_ms, cv, raw_text): what is needed to tell a newer version."""
        row = self.db.execute("SELECT canon, s, d, lm, cv, x, kind FROM rec WHERE k=?", (k,)).fetchone()
        if row is None:
            return None
        canon, s, d, lm, cv, x, kind = row
        if canon is not None:
            return (canon, None, None, None)  # a mirror stub keeps no values: it can never be compared
        if kind == RAW_KIND:
            return (None, _raw_lm(x), None, x)
        return (None, s + (d or 0) + lm if lm is not None and s is not None else None, cv or 0, None)

    @staticmethod
    def _incoming_lm(rec: dict) -> Optional[int]:
        try:
            return iso_to_ms(rec["data"]["metadata"]["lastModifiedTime"])
        except (KeyError, TypeError, ValueError, AttributeError, Fallback):
            return None

    def _replace_if_newer(self, rec: dict, key: str, stored, now_us: int) -> bool:
        canon, stored_lm, stored_cv, stored_raw = stored
        if canon is not None:
            return False
        incoming_lm = self._incoming_lm(rec)
        if incoming_lm is None or stored_lm is None or incoming_lm < stored_lm:
            return False
        row = compact(rec, key)
        if row.kind == RAW_KIND:
            if stored_raw is None or row.raw == stored_raw:
                return False  # the shape changed to something unmodelled, or nothing changed
            newer = incoming_lm > stored_lm
        else:
            newer = incoming_lm > stored_lm or (row.cv or 0) > (stored_cv or 0)
        if not newer:
            return False
        # Replace the row; mirror stubs that pointed at it keep pointing at the same key.
        self.db.execute("DELETE FROM rec WHERE k=?", (row.k,))
        link = self.insert(row, now_us)
        if link and link[0] == "dup" and link[1] != row.k:  # the new version is itself a mirror: hand over its stubs
            self.db.execute("UPDATE rec SET canon=? WHERE canon=?", (link[1], row.k))
        return True

    def delete_ids(self, ids: list[str]) -> tuple[int, int]:
        """Remove records deleted in Health Connect. Returns (deleted, unknown). Mirror stubs that pointed at a deleted
        record go with it (their values were dropped when they were folded, so nothing could be shown for them)."""
        deleted = missing = 0
        for rid in ids:
            k = key_hash(str(rid))
            if self.db.execute("SELECT 1 FROM rec WHERE k=?", (k,)).fetchone() is None:
                missing += 1
                continue
            self.db.execute("DELETE FROM rec WHERE canon=?", (k,))
            self.db.execute("DELETE FROM rec WHERE k=?", (k,))
            deleted += 1
        return deleted, missing

    def _same_key_rows(self, row: Row) -> list[tuple]:
        return self.db.execute(
            f"SELECT r.k, s.cls, r.s + COALESCE(r.d, 0) + r.lm FROM rec r JOIN src s ON s.id = r.src "
            f"WHERE r.kind IN ({','.join(map(str, DEDUP_KINDS))}) AND r.canon IS NULL AND r.s = ? AND r.d = ? "
            f"AND r.kind = {int(row.kind)} AND r.v = ? AND r.k <> ?",
            (row.s, row.d, row.v, row.k)).fetchall()

    def _find_link(self, row: Row, cls: str):
        """dedup-v1. Returns ('dup', canon_k, rule) | ('canon', [mirror_k...], rule) | None."""
        others = self._same_key_rows(row)
        if not others:
            return None
        if cls == "gfit":
            canon = [k for k, c, _ in others if c in MIRROR_CANON_CLASSES]
            if len(canon) == 1:
                rule = "R1" if [c for k, c, _ in others if k == canon[0]][0] == "hc_phone" else "R2"
                return ("dup", canon[0], rule)
            if len(canon) > 1:
                return None  # ambiguous: never guess
            gf = [(k, lm) for k, c, lm in others if c == "gfit"]
            if len(gf) == 1:  # R3: two versions of one Google Fit record; keep the newer one
                other_k, other_lm = gf[0]
                if (row.lm or 0, -row.k) > (other_lm or 0, -other_k):
                    return ("canon", [other_k], "R3")
                return ("dup", other_k, "R3")
            return None
        if cls in MIRROR_CANON_CLASSES:
            mirrors = [k for k, c, _ in others if c == "gfit"]
            competing = [k for k, c, _ in others if c in MIRROR_CANON_CLASSES]
            if mirrors and not competing:
                return ("canon", mirrors, "R1" if cls == "hc_phone" else "R2")
        return None

    def _fold(self, mirror_k: int, canon_k: int) -> None:
        """Turn a full row into a stub pointing at canon_k (and repoint stubs that pointed at it)."""
        self.db.execute("UPDATE rec SET s=NULL, d=NULL, v=NULL, src=NULL, lm=NULL, z=NULL, "
                        "cv=NULL, samples=NULL, x=NULL, canon=? WHERE k=?", (canon_k, mirror_k))
        self.db.execute("UPDATE rec SET canon=? WHERE canon=?", (canon_k, mirror_k))

    # -- manifest / audit / diagnostics
    def chunks(self) -> dict:
        return {cid: json.loads(doc) for cid, doc in
                self.db.execute("SELECT chunk_id, doc FROM chunks ORDER BY chunk_id")}

    def set_chunk(self, chunk_id: str, doc: dict) -> None:
        self.db.execute("INSERT INTO chunks(chunk_id, doc) VALUES (?, ?) "
                        "ON CONFLICT(chunk_id) DO UPDATE SET doc=excluded.doc",
                        (chunk_id, json.dumps(doc, ensure_ascii=False, sort_keys=True)))
        # Ids start with the month and an ISO start time, so they sort chronologically: keep only the newest ones.
        self.db.execute("DELETE FROM chunks WHERE chunk_id NOT IN (SELECT chunk_id FROM chunks ORDER BY chunk_id DESC LIMIT ?)",
                        (MAX_CHUNKS,))

    def add_audit(self, audit: dict) -> None:
        self.db.execute(
            "INSERT INTO sync_audit(ts, chunk_id, chunk_start, chunk_end, received, accepted, duplicates, complete, updated, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (audit.get("timestamp"), audit.get("chunk_id"), audit.get("chunk_start"), audit.get("chunk_end"),
             audit.get("received"), audit.get("accepted"), audit.get("duplicates"),
             1 if audit.get("complete") else 0, audit.get("updated", 0), audit.get("deleted", 0)))

    def _ensure_audit_columns(self) -> None:
        """Databases created before update/delete support have no such columns: add them once (old rows read 0)."""
        have = {row[1] for row in self.db.execute("PRAGMA table_info(sync_audit)")}
        for column in ("updated", "deleted"):
            if column not in have:
                self.db.execute(f"ALTER TABLE sync_audit ADD COLUMN {column} INTEGER NOT NULL DEFAULT 0")

    def _ensure_unique_diagnostics(self) -> None:
        """One row per event_id. Databases created before this rule may hold duplicates (the app used to upload the same
        outbox several times): remove them once, keeping the first copy, then enforce uniqueness with an index."""
        if self.db.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name='ux_diag_event'").fetchone():
            return
        self.db.execute("BEGIN IMMEDIATE")
        try:
            self.db.execute("DELETE FROM diagnostics WHERE event_id IS NOT NULL AND seq NOT IN "
                            "(SELECT MIN(seq) FROM diagnostics WHERE event_id IS NOT NULL GROUP BY event_id)")
            self.db.execute("CREATE UNIQUE INDEX ux_diag_event ON diagnostics(event_id) WHERE event_id IS NOT NULL")
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def add_diagnostics(self, events: list[dict]) -> None:
        """Events whose event_id is already stored are ignored (a retried upload must not create duplicates)."""
        self.db.executemany(
            f"INSERT OR IGNORE INTO diagnostics({','.join(DIAG_FIELDS)}) VALUES ({','.join('?' * len(DIAG_FIELDS))})",
            [tuple(e.get(f) for f in DIAG_FIELDS) for e in events])

    def diagnostics_status(self) -> tuple[int, Optional[str]]:
        count = self.db.execute("SELECT COUNT(*) FROM diagnostics").fetchone()[0]
        row = self.db.execute("SELECT timestamp FROM diagnostics ORDER BY seq DESC LIMIT 1").fetchone()
        return count, (row[0] if row else None)

    # -- decoding (tools/tests)
    def decode_samples(self, k: int) -> list:
        row = self.db.execute("SELECT kind, s, samples FROM rec WHERE k=?", (k,)).fetchone()
        if not row or row[2] is None:
            return []
        return unpack_hr(row[1], row[2]) if row[0] == KIND_CODES["HeartRate"] else unpack_speed(row[1], row[2])


# --------------------------------------------------------------------------- backup / verify

class BackupError(Exception):
    """A database file that cannot be backed up or does not pass verification (the message is safe to print)."""


def _open_readonly(path: Path) -> sqlite3.Connection:
    # Never creates the file, never runs the schema and never changes it: safe on the live database.
    try:
        return sqlite3.connect(f"{Path(path).resolve().as_uri()}?mode=ro", uri=True, isolation_level=None, timeout=30)
    except sqlite3.Error as exc:
        raise BackupError(f"cannot open {path}: {exc}") from exc


def verify_database(path: Path | str) -> dict:
    """Read-only structural check of a health-sync database. Returns counters, raises BackupError when it is not sound."""
    path = Path(path)
    if not path.is_file():
        raise BackupError(f"no such file: {path}")
    db = _open_readonly(path)
    try:
        try:
            if db.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
                raise BackupError(f"{path} is not a health-sync database")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version > SCHEMA_VERSION:
                raise BackupError(f"{path} has schema {version}; this program understands up to {SCHEMA_VERSION}")
            problems = [row[0] for row in db.execute("PRAGMA integrity_check(20)")]
            if problems != ["ok"]:
                raise BackupError("integrity_check failed: " + "; ".join(problems[:5]))
            records = db.execute("SELECT COUNT(*) FROM rec").fetchone()[0]
            stubs = db.execute("SELECT COUNT(*) FROM rec WHERE canon IS NOT NULL").fetchone()[0]
            chunks = db.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
            diag, distinct = db.execute("SELECT COUNT(*), COUNT(DISTINCT event_id) + SUM(event_id IS NULL) "
                                        "FROM diagnostics").fetchone()
        except sqlite3.Error as exc:
            raise BackupError(f"{path} is not readable as a health-sync database: {exc}") from exc
        return {"schema": version, "records": records, "mirror_stubs": stubs, "chunks": chunks,
                "diagnostics": diag, "diagnostics_duplicates": diag - (distinct or 0), "bytes": path.stat().st_size}
    finally:
        db.close()


def backup_database(source: Path | str, dest: Path | str) -> dict:
    """Write a consistent, compacted copy of ``source`` to the new file ``dest`` and verify it.

    Safe while the receiver is running: the source is opened read-only and copied with ``VACUUM INTO`` (one
    consistent snapshot; a writer waits for it for a moment). ``dest`` must not exist. The copy is written under a
    temporary name, checked, flushed to disk and only then renamed, so ``dest`` is either complete or absent.
    A ``<dest>.sha256`` file (``sha256sum -c`` format) is written next to it.
    """
    source, dest = Path(source), Path(dest)
    if not source.is_file():
        raise BackupError(f"no such database: {source}")
    if dest.exists():
        raise BackupError(f"refusing to overwrite {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    partial = dest.with_name(dest.name + ".partial")
    try:
        fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)  # VACUUM INTO wants a new or empty file
    except FileExistsError:
        raise BackupError(f"{partial} exists (an unfinished backup?); remove it and retry") from None
    os.close(fd)
    try:
        verify_database(source)  # a damaged source must not silently become "the" backup
        src = _open_readonly(source)
        try:
            src.execute("VACUUM INTO ?", (str(partial),))
        except sqlite3.Error as exc:
            raise BackupError(f"copy failed: {exc}") from exc
        finally:
            src.close()
        info = verify_database(partial)
        digest = hashlib.sha256()
        with open(partial, "rb") as fh:
            for block in iter(lambda: fh.read(1 << 20), b""):
                digest.update(block)
            os.fsync(fh.fileno())
        os.replace(partial, dest)
        sidecar = dest.with_name(dest.name + ".sha256")
        sidecar.write_text(f"{digest.hexdigest()}  {dest.name}\n")
        os.chmod(sidecar, 0o600)
        try:
            dfd = os.open(dest.parent, os.O_RDONLY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        except OSError:
            pass
        return {**info, "path": str(dest), "sha256": digest.hexdigest()}
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
