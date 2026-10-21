"""Consume one PostgreSQL incident using a verified current parser or historical catalog.

Default is read-only preview. --live --budget-usd enables one bounded run, never
a loop or automatic retry of HELD work. AGENT_DISPATCH_DSN must identify a role
with SELECT on captures/incidents and SELECT/INSERT/UPDATE on response_agent_run.
This worker has no serving writer or parser deployment capability.
"""
import argparse
import hashlib
import json
import os
import socket
import uuid
from pathlib import Path

import psycopg2

from .cli import ROOT, load_case
from .dispatch import ApprovedBundleCatalog, FrozenBundle, Hold, PgDispatcher
from .ledger import atomic_json, digest
from .runtime import MAX_OUTPUT, MODEL, PolicyError, Runner
from .validator import validate


def load_catalog(path):
    """Operator-owned config, never loaded from a captured external response."""
    path = Path(path).resolve()
    config = json.loads(path.read_text())
    bundles = {}
    for item in config["entries"]:
        fingerprint = item["fingerprint"]
        if (len(fingerprint) != 64 or any(c not in "0123456789abcdef" for c in fingerprint)
                or fingerprint in bundles):
            raise ValueError("invalid or duplicate incident fingerprint")
        directory = (path.parent / item["case_dir"]).resolve()
        if hashlib.sha256((directory / "manifest.json").read_bytes()).hexdigest() != item["manifest_sha256"]:
            raise ValueError("approved manifest changed")
        case, fixtures, source, legacy_test = load_case(directory)
        bundles[fingerprint] = FrozenBundle(case, source, legacy_test, fixtures,
            config["deployment_parser_digest"], config["contract_version"],
            tuple(item["capture_payload_digests"]))
    return ApprovedBundleCatalog(bundles, current_parser_digest=config["deployment_parser_digest"],
                                 contract_version=config["contract_version"])


def make_executor(model_factory, validator=validate):
    def execute(bundle, claim, checkpoint):
        baseline_dir = claim.run_directory.with_name(claim.run_directory.name + "-baseline")
        if baseline_dir.exists():
            try:
                baseline = json.loads((baseline_dir / "validation.json").read_text())
            except (OSError, ValueError):
                raise Hold("BASELINE_INTERRUPTED_REVIEW_REQUIRED") from None
            if (baseline.get("source_digest") != digest(bundle.source)
                    or baseline.get("fixtures_digest") != digest(bundle.fixtures)):
                raise Hold("BASELINE_INPUTS_CHANGED")
        else:
            baseline = validator(bundle.source, bundle.fixtures, baseline_dir)
        checks = {row["id"]: row for row in baseline.get("cases", [])}
        if not (baseline["status"] == "ASSERTION_FAILED"
                and checks.get("actual-legacy-before", {}).get("passed")
                and checks.get("actual-format-after", {}).get("passed") is False
                and baseline.get("fixed_regression", {}).get("succeeded") == 11):
            raise Hold("HISTORICAL_BASELINE_NOT_ESTABLISHED")
        try:
            runner = Runner(bundle.case, bundle.source, bundle.legacy_test, bundle.fixtures,
                            claim.run_directory, validator, budget=claim.budget_usd,
                            checkpoint=checkpoint, prior_report=claim.prior_report or None)
        except PolicyError as error:
            raise Hold(str(error)) from None
        runner.report.update(scope="PostgreSQL-dispatched isolated historical replay",
            dispatch_run_id=claim.run_id, incident_fingerprint=claim.fingerprint,
            dispatch_epoch=claim.epoch, baseline_report=str(baseline_dir / "validation.json"),
            deployment_parser_digest=bundle.deployment_parser_digest,
            deployment_contract_version=bundle.contract_version, bundle_digest=bundle.digest(),
            capture_payload_digests=list(bundle.capture_payload_digests), case_id=bundle.case["case_id"])
        atomic_json(claim.run_directory / "reprocess-targets.json", {
            "case_id": bundle.case["case_id"], "scope": "approved stored source pair only",
            "source_digests": list(bundle.capture_payload_digests),
            "operating_reprocess_authorized": False})
        runner.save()
        return runner.run(model_factory())
    return execute


def make_current_executor(model_factory, validator=None):
    from . import current_validator
    from .current_manifest import EDITABLE_PATHS
    from .runtime import LEGACY_TEST
    validator = validator or current_validator.validate

    def execute(bundle, claim, checkpoint):
        files = {path: bundle.files[path] for path in EDITABLE_PATHS}
        baseline_dir = claim.run_directory.with_name(claim.run_directory.name + "-baseline")
        if baseline_dir.exists():
            try:
                baseline = json.loads((baseline_dir / "validation.json").read_text())
            except (OSError, ValueError):
                raise Hold("BASELINE_INTERRUPTED_REVIEW_REQUIRED") from None
            if (baseline.get("source_digest") != digest(files)
                    or baseline.get("fixtures_digest") != digest(bundle.fixtures)
                    or baseline.get("manifest_digest") != bundle.manifest_digest):
                raise Hold("BASELINE_INPUTS_CHANGED")
        else:
            baseline = validator(Path(bundle.manifest_path), files, bundle.fixtures, baseline_dir,
                                 expected_manifest_digest=bundle.manifest_digest)
        observed = baseline.get("baseline", {})
        checks = {row["id"]: row for row in observed.get("cases", [])}
        roles = {row["role"] for row in bundle.fixtures}
        if (baseline.get("status") != "ASSERTION_FAILED" or baseline.get("passed") is not False
                or not {"NORMAL_REGRESSION", "NEW_STRUCTURE"} <= roles
                or not current_validator.regression_passes(observed.get("regression", {}))
                or any(checks.get(row["id"], {}).get("passed") is not (row["role"] != "NEW_STRUCTURE")
                       for row in bundle.fixtures)):
            raise Hold("CURRENT_BASELINE_NOT_ESTABLISHED")

        def bound_validator(candidate_files, fixtures, output_dir, proposed_fixtures=None):
            return validator(Path(bundle.manifest_path), candidate_files, fixtures, output_dir,
                expected_manifest_digest=bundle.manifest_digest, proposed_fixtures=proposed_fixtures)
        try:
            runner = Runner(bundle.case, bundle.source, bundle.legacy_test, bundle.fixtures,
                claim.run_directory, bound_validator, budget=claim.budget_usd, checkpoint=checkpoint,
                prior_report=claim.prior_report or None, files=bundle.files,
                editable_paths=EDITABLE_PATHS, required_refs=(*EDITABLE_PATHS, LEGACY_TEST))
        except PolicyError as error:
            raise Hold(str(error)) from None
        runner.report.update(scope="PostgreSQL-dispatched current parser repair evaluation",
            dispatch_run_id=claim.run_id, incident_fingerprint=claim.fingerprint, dispatch_epoch=claim.epoch,
            budget_incident_id=claim.budget_incident_id, baseline_report=str(baseline_dir / "validation.json"),
            deployment_parser_digest=bundle.deployment_parser_digest, deployment_contract_version=bundle.contract_version,
            deployment_manifest_digest=bundle.manifest_digest, bundle_digest=bundle.digest(),
            capture_payload_digests=list(bundle.capture_payload_digests), case_id=bundle.case["case_id"],
            reprocess_targets=list(bundle.reprocess_targets), recipe=bundle.case["recipe"])
        atomic_json(claim.run_directory / "reprocess-targets.json", {
            "case_id": bundle.case["case_id"], "scope": "exact captured observations only",
            "targets": list(bundle.reprocess_targets), "operating_reprocess_authorized": False})
        runner.save()
        return runner.run(model_factory())
    return execute


def previous_complete_loader(connect):
    """Only prior complete observations for the same identity and parser generation."""
    from psycopg2.extras import RealDictCursor
    from .dispatch import MAX_PAYLOAD_BYTES
    def load(sample):
        db = connect()
        try:
            db.set_session(readonly=True)
            with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute("""SELECT capture_id,store_id,source,external_id,observed_at,attempt_seq,status,
                    parser_digest,contract_version,payload_digest,payload FROM response_capture
                    WHERE store_id=%s AND source=%s AND external_id=%s AND status='READY'
                      AND observed_at<%s AND parser_digest=%s AND contract_version=%s
                      AND octet_length(payload)<=%s ORDER BY observed_at DESC,capture_id LIMIT 2""",
                    (sample["store_id"], sample["source"], sample["external_id"], sample["observed_at"],
                     sample["parser_digest"], sample["contract_version"], MAX_PAYLOAD_BYTES))
                return list(cursor.fetchall())
        finally:
            db.close()
    return load


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--catalog", type=Path)
    mode.add_argument("--deployment-manifest", type=Path)
    mode.add_argument("--baseline-pointer", type=Path, help="Trusted atomically promoted service baseline")
    parser.add_argument("--registry-directory", type=Path, help="Required with --baseline-pointer")
    parser.add_argument("--service-id", help="Required with --baseline-pointer")
    parser.add_argument("--repository-path", type=Path, help="Git checkout distinct from immutable installed Python code; disables .env fallback")
    parser.add_argument("--manifest-digest", help="Externally trusted digest, never inferred from the supplied file")
    parser.add_argument("--review-config", type=Path, help="Trusted local report-only publication settings")
    parser.add_argument("--review-ttl-seconds", type=int, default=604800, help="60..2592000; fixed from each run's durable creation time")
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--budget-usd", type=float)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts/response-agent/dispatch")
    args = parser.parse_args(argv)
    os.umask(0o077)
    if not 60 <= args.review_ttl_seconds <= 2592000:
        parser.error("--review-ttl-seconds must be between 60 and 2592000")
    from dotenv import dotenv_values
    repository_path = args.repository_path.resolve() if args.repository_path else ROOT
    settings = {} if args.repository_path else dotenv_values(ROOT / ".env")
    dsn = os.environ.get("AGENT_DISPATCH_DSN") or settings.get("AGENT_DISPATCH_DSN")
    if not dsn:
        parser.error("AGENT_DISPATCH_DSN required; credentials are never logged")
    connect = lambda: psycopg2.connect(dsn, connect_timeout=5)
    baseline = None
    if args.baseline_pointer:
        if not args.registry_directory or not args.service_id or args.manifest_digest:
            parser.error("--baseline-pointer requires --registry-directory and --service-id; do not provide --manifest-digest")
        from .baseline_promotion import BaselinePointerStore
        from .full_registry import FullReleaseRegistry
        baseline = BaselinePointerStore(registry=FullReleaseRegistry(args.registry_directory),connect=connect,
            service_id=args.service_id,path=args.baseline_pointer).read()
        args.deployment_manifest = Path(baseline['deployment_manifest'])
        args.manifest_digest = baseline['deployment_manifest_digest']
    elif args.registry_directory or args.service_id:
        parser.error("--registry-directory and --service-id only apply to --baseline-pointer")
    if args.deployment_manifest:
        if not args.manifest_digest:
            parser.error("--manifest-digest from the trusted release registry is required")
        from .current_bundle import CurrentParserBundleProvider
        provider = CurrentParserBundleProvider(args.deployment_manifest, args.manifest_digest,
                                              normal_loader=previous_complete_loader(connect))
    else:
        if args.manifest_digest:
            parser.error("--manifest-digest only applies to --deployment-manifest")
        provider = load_catalog(args.catalog)
    if not args.live:
        db = connect()
        try:
            db.set_session(readonly=True)
            with db, db.cursor() as cursor:
                cursor.execute("SELECT count(*) FROM response_incident WHERE model_eligible=TRUE")
                count = cursor.fetchone()[0]
            print(json.dumps({"status": "PREVIEW", "eligible_incidents": count,
                              "mode": "current" if args.deployment_manifest else "historical",
                              "approved_catalog_entries": len(getattr(provider, "bundles", {})), "new_model_calls": 0}))
        finally:
            db.close()
        return 0
    if args.budget_usd is None or not 0 < args.budget_usd <= .50:
        parser.error("this pilot requires an explicit budget between $0 and $0.50")
    key = os.environ.get("ANTHROPIC_API_KEY") or settings.get("ANTHROPIC_API_KEY")
    if not key:
        parser.error("ANTHROPIC_API_KEY missing; value is never logged")
    from langchain_anthropic import ChatAnthropic
    def model_factory():
        return ChatAnthropic(model=MODEL, api_key=key, base_url="https://api.anthropic.com",
                             max_tokens=MAX_OUTPUT, max_retries=0, timeout=90)
    sink = None
    if args.review_config:
        from .approval import PgReviewStore
        from .publication import GitReportPreparer, ReportPublicationSink
        review_config = json.loads(args.review_config.read_text())
        required = {"repository", "base_commit", "base_branch", "evidence_origin"}
        if set(review_config) not in (required, required | {"expires_at"}):
            parser.error("review config fields differ from trusted publication contract")
        config = dict(review_config)
        if 'expires_at' not in config:
            config['review_ttl_seconds'] = args.review_ttl_seconds
        repository = config.pop("repository")
        if baseline:
            config['base_commit'] = baseline['source_commit']
        sink = ReportPublicationSink(store=PgReviewStore(connect),
            preparer=GitReportPreparer(repository_path, repository=repository), **config)
    dispatcher = PgDispatcher(connect, provider, args.output_dir, terminal_sink=sink)
    executor = make_current_executor(model_factory) if args.deployment_manifest else make_executor(model_factory)
    result = dispatcher.run_one(executor,
        owner=socket.gethostname() + ":" + uuid.uuid4().hex, budget_usd=args.budget_usd)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["status"] in {"IDLE", "AWAITING_REVIEW"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
