"""Existing entrypoint exit codes must reflect subordinate work (offline regression)."""
import datetime as dt
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import weekly_refresh as weekly


class WeeklyExitContractTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.object(weekly, "LOG_DIR", Path(directory.name))
        patcher.start()
        self.addCleanup(patcher.stop)

    def invoke(self, argv, crawl_code=0, apply_code=0):
        result = {"started": "2026-11-04 01:00:00", "dry_run": "--dry-run" in argv,
                  "steps": {"채움 승인": {"code": apply_code, "tail": []}}}
        with patch.object(sys, "argv", ["weekly", *argv]), \
                patch.object(weekly, "load_state", return_value={"last_apply_at": "2026-11-01"}), \
                patch.object(weekly, "preflight", return_value=[]), \
                patch.object(weekly, "crawl", return_value=(crawl_code, [])), \
                patch.object(weekly, "apply", return_value=result), \
                patch.object(weekly, "save_state") as save, \
                patch.object(weekly, "write_report"), \
                patch.object(weekly, "log"), \
                patch.object(weekly, "db_now", return_value=dt.datetime(2026, 11, 4)):
            code = weekly.main()
        return code, save

    def test_blocked_collection_is_not_reported_as_success(self):
        for command in ("nightly", "collect-only"):
            with self.subTest(command=command):
                code, save = self.invoke([command], crawl_code=3)
                self.assertEqual(code, 3)
                save.assert_not_called()

    def test_failed_dry_run_is_not_reported_as_success(self):
        code, save = self.invoke(["apply", "--dry-run"], apply_code=1)
        self.assertEqual(code, 1)
        save.assert_not_called()

    def test_failed_apply_does_not_advance_watermark(self):
        code, save = self.invoke(["apply"], apply_code=1)
        self.assertEqual(code, 1)
        save.assert_not_called()

    def test_successful_apply_advances_watermark(self):
        code, save = self.invoke(["apply"])
        self.assertEqual(code, 0)
        self.assertEqual(save.call_args.args[0]["last_apply_at"], "2026-11-04 01:00:00")


if __name__ == "__main__":
    unittest.main()
