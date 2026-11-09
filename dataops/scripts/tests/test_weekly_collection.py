"""Collection must never inherit the old Sunday approval behavior."""
import datetime
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import weekly_refresh as weekly


class Sunday(datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 10, 18, 3, tzinfo=tz)


class WeeklyCollectionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.object(weekly, "LOG_DIR", Path(directory.name))
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_sunday_nightly_and_collect_only_never_apply(self):
        for command in ("nightly", "collect-only"):
            with self.subTest(command=command), \
                    patch.object(sys, "argv", ["weekly", command, "--max-stores", "100", "--crawl-hours", "0.5"]), \
                    patch.object(weekly.dt, "datetime", Sunday), \
                    patch.object(weekly, "load_state", return_value={"last_apply_at": "2026-10-01"}), \
                    patch.object(weekly, "preflight", return_value=[]) as preflight, \
                    patch.object(weekly, "crawl", return_value=(0, "ok")) as crawl, \
                    patch.object(weekly, "apply") as apply, \
                    patch.object(weekly, "save_state") as save, \
                    patch.object(weekly, "write_report"):
                self.assertEqual(weekly.main(), 0)
                self.assertTrue(preflight.call_args.kwargs["collect_only"])
                self.assertEqual(crawl.call_args.args[1:], (0.5, 100))
                apply.assert_not_called()
                save.assert_not_called()

    def test_old_apply_flag_is_rejected_before_any_work(self):
        with patch.object(sys, "argv", ["weekly", "nightly", "--apply"]), \
                patch.object(weekly, "preflight") as preflight, \
                patch.object(weekly, "apply") as apply, \
                self.assertRaises(SystemExit) as error:
            weekly.main()
        self.assertEqual(error.exception.code, 2)
        preflight.assert_not_called()
        apply.assert_not_called()

    def test_review_requires_explicit_or_saved_period(self):
        with patch.object(sys, "argv", ["weekly", "review-only"]), \
                patch.object(weekly, "load_state", return_value={}), \
                patch.object(weekly, "write_report") as report, \
                self.assertRaises(SystemExit) as error:
            weekly.main()
        self.assertEqual(error.exception.code, 2)
        report.assert_not_called()


if __name__ == "__main__":
    unittest.main()
