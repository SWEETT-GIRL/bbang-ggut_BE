"""Reject a candidate with independently reproduced review evidence.

This module is deliberately not a model tool and has no accept/deploy action.
The graph report is immutable; later review results are separate linked evidence.
"""
import json
from pathlib import Path

from .ledger import atomic_json, digest, now


def hold_candidate(inbox, run_dir, validation_path, reason, reviewer):
    run_dir = Path(run_dir).resolve()
    validation_path = Path(validation_path).resolve()
    report = json.loads((run_dir / "report.json").read_text())
    validation = json.loads(validation_path.read_text())
    patch = report["patches"][-1]
    if report["status"] != "AWAITING_REVIEW" or validation["passed"]:
        raise ValueError("review rejection needs a pending candidate and failed independent validation")
    if validation["source_digest"] != patch["source_digest"]:
        raise ValueError("review evidence must evaluate the exact submitted source")
    entry = inbox.get(report["incident_fingerprint"])
    if entry["state"] != "AWAITING_REVIEW" or Path(entry["run_dir"]).resolve() != run_dir:
        raise ValueError("review does not match current inbox state")
    review_path = run_dir / "post-review.json"
    if review_path.exists():
        raise ValueError("review evidence already exists; do not overwrite")
    review = {"status": "HELD", "reviewed_at": now(), "reviewer": reviewer,
              "reason": reason, "patch_id": patch["patch_id"], "source_digest": patch["source_digest"],
              "graph_report_digest": digest((run_dir / "report.json").read_text()),
              "review_validation": str(validation_path), "review_validation_digest": digest(validation),
              "post_hoc_counterexample": True, "original_oracle_unchanged": True,
              "automatic_retry_authorized": False}
    # Evidence first. If the subsequent CAS fails, the original awaiting state remains
    # non-approved and the persisted rejection can be reconciled without another call.
    atomic_json(review_path, review)
    inbox.hold_after_review(report["incident_fingerprint"], entry["run_dir"])
    with (run_dir / "review.md").open("a") as file:
        file.write("\n## Subsequent independent review: HELD\n\n" + reason +
                   "\n\nSee post-review.json. The original frozen evaluation was not changed.\n")
    return review
