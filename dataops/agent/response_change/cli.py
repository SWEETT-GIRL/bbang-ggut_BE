"""Replay a frozen historical incident through the actual Sonnet repair graph.

python -m agent.response_change.cli --case-dir artifacts/response-agent/historical-menu-case --live
Without --live, validates historical failure and queues it, with zero model calls.
Repeated delivery uses the same isolated inbox and never re-spends automatically.
"""
import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from .ledger import Inbox, atomic_json, detect, digest
from .runtime import MODEL, MAX_OUTPUT, Runner
from .validator import TRUSTED, validate

ROOT = Path(__file__).resolve().parents[2]


def load_case(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    # Explicit fixed names; a manifest cannot cause arbitrary file reads.
    for name in ("case.json", "fixtures.json"):
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != manifest["files"][name]:
            raise ValueError("frozen input digest mismatch: " + name)
    case = json.loads((directory / "case.json").read_text())
    fixtures = json.loads((directory / "fixtures.json").read_text())["cases"]
    source_manifest = json.loads((TRUSTED / "source-manifest.json").read_text())
    if case["parser_base_commit"] != source_manifest["base_commit"]:
        raise ValueError("case and historical parser base differ")
    for name in ("NaverPlaceParser.java", "NaverPlaceParserTest.java"):
        data = (TRUSTED / "baseline" / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != source_manifest["sources"]["baseline/" + name]["sha256"]:
            raise ValueError("historical source digest mismatch")
    return case, fixtures, (TRUSTED / "baseline/NaverPlaceParser.java").read_text(), \
        (TRUSTED / "baseline/NaverPlaceParserTest.java").read_text()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case-dir", type=Path, required=True)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args(argv)
    os.umask(0o077)
    case, fixtures, source, legacy_test = load_case(args.case_dir)
    diagnostic = detect(case["before"]["raw"], case["after"]["raw"])
    if not diagnostic["eligible"]:
        print(json.dumps({"status": "NOT_ELIGIBLE", "diagnostic": diagnostic}))
        return 0
    work = ROOT / "artifacts/response-agent/historical-replay"
    inbox = Inbox(work / "inbox.sqlite3")
    manifest = {"case_id": case["case_id"], "diagnostic": diagnostic, "base_digest": digest(source),
                "contract_version": case.get("contract_version", "menu-repair-eval-v1"), "case_digest": digest(case),
                "oracle_digest": digest(fixtures)}
    fingerprint = inbox.enqueue(manifest)
    entry = inbox.get(fingerprint)
    if entry["state"] != "QUEUED":
        print(json.dumps({"status": "DEDUPLICATED", "incident_state": entry["state"],
                          "report": str(Path(entry["run_dir"]) / "report.json"), "new_model_calls": 0}))
        return 0
    if json.loads(entry["manifest"]) != manifest:
        raise ValueError("existing incident inputs differ; manual review required, no automatic retry")
    baseline_dir = work / (fingerprint + "-baseline")
    if baseline_dir.exists():
        baseline = json.loads((baseline_dir / "validation.json").read_text())
        if baseline.get("source_digest") != digest(source) or baseline.get("fixtures_digest") != digest(fixtures):
            raise ValueError("cached baseline inputs differ")
    else:
        baseline = validate(source, fixtures, baseline_dir)
    checks = {row["id"]: row for row in baseline["cases"]}
    if not (baseline["status"] == "ASSERTION_FAILED" and
            checks.get("actual-legacy-before", {}).get("passed") and
            checks.get("actual-format-after", {}).get("passed") is False and
            baseline.get("fixed_regression", {}).get("succeeded") == 11):
        raise ValueError("required historical failure and preserved legacy baseline not established")
    if not args.live:
        print(json.dumps({"status": "QUEUED", "fingerprint": fingerprint, "baseline": str(baseline_dir),
                          "new_model_calls": 0}))
        return 0
    from dotenv import dotenv_values
    from langchain_anthropic import ChatAnthropic
    key = os.environ.get("ANTHROPIC_API_KEY") or dotenv_values(ROOT / ".env").get("ANTHROPIC_API_KEY")
    if not key:
        parser.error("ANTHROPIC_API_KEY missing; value is never logged")
    directory = work / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    if not inbox.claim(fingerprint, directory):
        print(json.dumps({"status": "CLAIMED_ELSEWHERE", "new_model_calls": 0}))
        return 0
    # A crash after claim stays RUNNING and is not silently retried or given a fresh budget.
    runner = Runner(case, source, legacy_test, fixtures, directory, validate, budget=0.50)
    runner.report["incident_fingerprint"] = fingerprint
    runner.report["baseline_report"] = str(baseline_dir / "validation.json")
    atomic_json(directory / "reprocess-targets.json", {
        "case_id": case["case_id"], "scope": "stored sanitized source pair only",
        "source_digests": [case["before"]["source_payload_sha256"], case["after"]["source_payload_sha256"]],
        "operating_reprocess_authorized": False})
    model = ChatAnthropic(model=MODEL, api_key=key, base_url="https://api.anthropic.com",
                          max_tokens=MAX_OUTPUT, max_retries=0, timeout=90)
    report = runner.run(model)
    inbox.finish(fingerprint, report["status"])
    print(json.dumps({"status": report["status"], "report": str(directory / "report.json"),
                      "inference_requests": len(report["requests"]), "routing_quality": report["routing_quality"],
                      "total_usage_estimated_usd": report["total_usage_estimated_usd"],
                      "unpriced_reserved_usd": report["unpriced_reserved_usd"]}, ensure_ascii=False))
    return 0 if report["status"] == "AWAITING_REVIEW" else 1


if __name__ == "__main__":
    raise SystemExit(main())
