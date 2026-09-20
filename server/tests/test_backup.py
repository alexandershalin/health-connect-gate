"""Backup / verify tests (``receiver.py backup`` and ``receiver.py verify``). Every payload is synthetic.

Run:  python3 -m unittest tests.test_backup -v
"""
import hashlib
import os
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import health_sqlite as hs  # noqa: E402
from test_sqlite_store import Base, HC, steps  # noqa: E402


class BackupTests(Base):
    def setUp(self):
        super().setUp()
        self.ingest(*[steps(f"test-{i}", HC, count=i + 1) for i in range(5)])
        self.dest_dir = Path(self.tmp.name) / "out"

    def cli(self, *args, env=None):
        """Run the real command line; returns (exit status, stdout, stderr)."""
        full = {**os.environ, "HEALTH_RECEIVER_ROOT": str(self.root), "HEALTH_RECEIVER_LOCK": str(Path(self.tmp.name) / "lock")}
        full.update(env or {})
        proc = subprocess.run([sys.executable, str(ROOT / "receiver.py"), *args], env=full, capture_output=True, text=True, timeout=60)
        return proc.returncode, proc.stdout, proc.stderr

    def test_backup_is_a_complete_verified_copy(self):
        dest = self.dest_dir / "b.sqlite3"
        info = hs.backup_database(self.db_path, dest)
        self.assertEqual(info["records"], 5)
        self.assertEqual(hs.verify_database(dest)["records"], 5)
        copy = sqlite3.connect(dest)
        self.assertEqual(copy.execute("SELECT COUNT(*) FROM rec").fetchone()[0], self.db.count())
        copy.close()
        self.assertEqual(stat.S_IMODE(dest.stat().st_mode), 0o600)
        self.assertFalse(list(self.dest_dir.glob("*.partial")))

    def test_checksum_file_matches(self):
        dest = self.dest_dir / "b.sqlite3"
        info = hs.backup_database(self.db_path, dest)
        line = (self.dest_dir / "b.sqlite3.sha256").read_text().split()
        self.assertEqual(line, [hashlib.sha256(dest.read_bytes()).hexdigest(), "b.sqlite3"])
        self.assertEqual(line[0], info["sha256"])

    def test_never_overwrites(self):
        dest = self.dest_dir / "b.sqlite3"
        hs.backup_database(self.db_path, dest)
        before = dest.read_bytes()
        with self.assertRaises(hs.BackupError):
            hs.backup_database(self.db_path, dest)
        self.assertEqual(dest.read_bytes(), before)

    def test_leftover_partial_file_is_reported_not_reused(self):
        self.dest_dir.mkdir()
        (self.dest_dir / "b.sqlite3.partial").write_bytes(b"junk")
        with self.assertRaises(hs.BackupError):
            hs.backup_database(self.db_path, self.dest_dir / "b.sqlite3")
        self.assertEqual((self.dest_dir / "b.sqlite3.partial").read_bytes(), b"junk")  # not ours to delete

    def test_missing_source_is_not_created(self):
        missing = Path(self.tmp.name) / "nope.sqlite3"
        with self.assertRaises(hs.BackupError):
            hs.backup_database(missing, self.dest_dir / "b.sqlite3")
        self.assertFalse(missing.exists())
        self.assertFalse(self.dest_dir.exists() and list(self.dest_dir.iterdir()))

    def test_source_is_left_untouched(self):
        before = self.db_path.read_bytes()
        hs.backup_database(self.db_path, self.dest_dir / "b.sqlite3")
        hs.verify_database(self.db_path)
        self.assertEqual(self.db_path.read_bytes(), before)
        self.assertFalse((self.db_path.parent / (self.db_path.name + "-journal")).exists())

    def test_foreign_and_garbage_files_are_rejected_and_leave_no_backup(self):
        other = Path(self.tmp.name) / "other.sqlite3"
        c = sqlite3.connect(other)
        c.execute("CREATE TABLE t (x)")
        c.commit()
        c.close()
        garbage = Path(self.tmp.name) / "garbage.sqlite3"
        garbage.write_bytes(b"this is not a database" * 100)
        for bad in (other, garbage):
            with self.subTest(bad=bad.name):
                with self.assertRaises(hs.BackupError):
                    hs.backup_database(bad, self.dest_dir / (bad.name + ".bak"))
                self.assertFalse((self.dest_dir / (bad.name + ".bak")).exists())
                self.assertFalse(list(self.dest_dir.glob("*.partial")) if self.dest_dir.exists() else [])

    def test_verify_detects_corruption(self):
        dest = self.dest_dir / "b.sqlite3"
        hs.backup_database(self.db_path, dest)
        data = bytearray(dest.read_bytes())
        page = 4096
        for offset in range(page * 2, len(data), 97):  # damage the table pages, keep the header
            data[offset] ^= 0xFF
        dest.write_bytes(bytes(data))
        with self.assertRaises(hs.BackupError):
            hs.verify_database(dest)

    def test_newer_schema_is_refused(self):
        dest = self.dest_dir / "b.sqlite3"
        hs.backup_database(self.db_path, dest)
        c = sqlite3.connect(dest)
        c.execute(f"PRAGMA user_version={hs.SCHEMA_VERSION + 1}")
        c.close()
        with self.assertRaises(hs.BackupError):
            hs.verify_database(dest)

    def test_duplicate_diagnostics_are_reported(self):
        dest = self.dest_dir / "b.sqlite3"
        hs.backup_database(self.db_path, dest)
        info = hs.verify_database(dest)
        self.assertEqual(info["diagnostics_duplicates"], 0)

    def test_restored_copy_is_a_working_store(self):
        dest = self.dest_dir / "b.sqlite3"
        hs.backup_database(self.db_path, dest)
        restored = hs.SqliteStore(dest)  # exactly what the server does on start
        try:
            self.assertEqual(restored.count(), 5)
            restored.begin()
            _, accepted, _ = restored.ingest([steps("test-0", HC, count=1), steps("test-new", HC, count=9)],
                                             datetime.now(timezone.utc))
            restored.commit()
            self.assertEqual(restored.count(), 6)  # the known id is a duplicate, the new one is stored
        finally:
            restored.close()

    def test_consistent_while_the_server_keeps_writing(self):
        stop = threading.Event()
        errors = []

        def writer():
            n = 0
            while not stop.is_set():
                try:
                    self.ingest(steps(f"test-live-{n}", HC, count=n + 1))
                except Exception as exc:  # pragma: no cover - reported below
                    errors.append(exc)
                    return
                n += 1

        thread = threading.Thread(target=writer)
        thread.start()
        try:
            infos = [hs.backup_database(self.db_path, self.dest_dir / f"b{i}.sqlite3") for i in range(5)]
        finally:
            stop.set()
            thread.join()
        self.assertEqual(errors, [])
        counts = [i["records"] for i in infos]
        self.assertEqual(counts, sorted(counts))  # snapshots move forward, each one verified sound
        self.assertGreaterEqual(counts[0], 5)

    # -- command line
    def test_cli_backup_into_default_directory_and_verify(self):
        code, out, err = self.cli("backup")
        self.assertEqual(code, 0, err)
        made = list((self.root / "backups").glob("health_sync-*.sqlite3"))
        self.assertEqual(len(made), 1)
        self.assertIn("5 records", out)
        self.assertNotIn("test-", out)  # counters only, never record contents
        code, out, err = self.cli("verify", str(made[0]))
        self.assertEqual(code, 0, err)
        self.assertIn("ok:", out)

    def test_cli_backup_into_directory_and_explicit_file(self):
        self.dest_dir.mkdir()
        code, _, err = self.cli("backup", str(self.dest_dir))
        self.assertEqual(code, 0, err)
        self.assertEqual(len(list(self.dest_dir.glob("health_sync-*.sqlite3"))), 1)
        target = self.dest_dir / "named.sqlite3"
        self.assertEqual(self.cli("backup", str(target))[0], 0)
        self.assertTrue(target.exists())
        self.assertEqual(self.cli("backup", str(target))[0], 1)  # never overwrites

    def test_cli_verify_live_database_and_failures(self):
        code, out, _ = self.cli("verify")
        self.assertEqual(code, 0)
        self.assertIn("5 records", out)
        code, _, err = self.cli("verify", str(self.root / "missing.sqlite3"))
        self.assertEqual(code, 1)
        self.assertIn("verify failed", err)
        code, _, err = self.cli("backup", env={"HEALTH_RECEIVER_DB": str(self.root / "missing.sqlite3")})
        self.assertEqual(code, 1)
        self.assertFalse((self.root / "missing.sqlite3").exists())

    def test_cli_uses_HEALTH_RECEIVER_DB(self):
        other = self.root / "elsewhere.sqlite3"
        hs.SqliteStore(other).close()
        code, out, _ = self.cli("verify", env={"HEALTH_RECEIVER_DB": str(other)})
        self.assertEqual(code, 0)
        self.assertIn("0 records", out)

    def test_no_arguments_still_means_serve(self):
        # The old invocation (no sub-command) must reach the server set-up. A bogus store name stops it there
        # with status 2, before anything is bound or written.
        code, _, err = self.cli(env={"HEALTH_RECEIVER_STORE": "bogus"})
        self.assertEqual(code, 2)
        self.assertIn("unknown HEALTH_RECEIVER_STORE", err)
        self.assertEqual(self.cli("serve", env={"HEALTH_RECEIVER_STORE": "bogus"})[0], 2)

    def test_unknown_option_is_a_usage_error(self):
        code, _, err = self.cli("--nonsense")
        self.assertEqual(code, 2)
        self.assertIn("usage:", err)


if __name__ == "__main__":
    unittest.main()
