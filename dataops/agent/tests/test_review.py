import json
from pathlib import Path
import tempfile
import unittest

from agent.response_change.ledger import Inbox, atomic_json
from agent.response_change.review import hold_candidate


class ReviewTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.inbox = Inbox(self.root / "inbox.db")
        self.fingerprint = self.inbox.enqueue({"diagnostic": {"eligible": True}, "base_digest": "base",
                                              "contract_version": "v1"})
        self.run = self.root / "run"
        self.run.mkdir()
        self.inbox.claim(self.fingerprint, self.run)
        self.inbox.finish(self.fingerprint, "AWAITING_REVIEW")
        self.report = {"status": "AWAITING_REVIEW", "incident_fingerprint": self.fingerprint,
                       "patches": [{"patch_id": "patch", "source_digest": "source"}]}
        atomic_json(self.run / "report.json", self.report)
        self.validation = self.root / "validation.json"
        atomic_json(self.validation, {"passed": False, "source_digest": "source"})

    def test_rejection_binds_source_and_preserves_original_graph_result(self):
        hold_candidate(self.inbox, self.run, self.validation, "Unsupported price accepted", "code-review")
        self.assertEqual(self.inbox.get(self.fingerprint)["state"], "HELD")
        self.assertEqual(json.loads((self.run / "report.json").read_text()), self.report)
        self.assertFalse(self.inbox.claim(self.fingerprint, self.root / "another-paid-run"))
        review = json.loads((self.run / "post-review.json").read_text())
        self.assertTrue(review["original_oracle_unchanged"])
        self.assertFalse(review["automatic_retry_authorized"])

    def test_review_of_other_source_cannot_change_incident(self):
        atomic_json(self.validation, {"passed": False, "source_digest": "different"})
        with self.assertRaises(ValueError):
            hold_candidate(self.inbox, self.run, self.validation, "Wrong candidate", "code-review")
        self.assertEqual(self.inbox.get(self.fingerprint)["state"], "AWAITING_REVIEW")


if __name__ == "__main__":
    unittest.main()
