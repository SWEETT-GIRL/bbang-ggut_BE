"""Pure controller tests with in-memory adapters; these do not prove real deployment."""
import copy
from contextlib import contextmanager
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import subprocess
import time
import unittest
import uuid
from unittest.mock import patch

import psycopg2

from agent.response_change.approval import (ApprovalHold, ApprovalVerifier, PublicationBinding,
    PullRequestSnapshot, ReviewManifest, DIGEST_FIELDS, content_digest)
from agent.response_change.release import AdapterUnavailable, PgReleaseJournal, ReleaseController, ReleaseHold, _identity
from agent.response_change.approval import PgReviewStore

NOW = datetime.now(timezone.utc)
SHA = "a" * 64
COMMIT = "b" * 40
REPOSITORY = "example/dataops"


class MemoryJournal:
    """A restart simulation inside one test process, explicitly not durable storage."""
    def __init__(self):
        self.rows = {}
        self.lock = threading.Lock()
        self.sequence = 0

    @contextmanager
    def service_lock(self, service):
        if not self.lock.acquire(blocking=False):
            raise ReleaseHold("SERVICE_BUSY")
        self.sequence += 1
        outer = self
        sequence = self.sequence
        class Fence:
            token = sequence
            def assert_held(self):
                if not outer.lock.locked() or sequence != outer.sequence:
                    raise ReleaseHold("FENCE_LOST")
        try:
            yield Fence()
        finally:
            self.lock.release()

    def claim(self, service, operation, approval, request_digest, fence):
        fence.assert_held()
        if operation not in self.rows:
            self.rows[operation] = {"operation_id": operation, "service_id": service, "state": "QUEUED", "revision": 0,
                                    "approval_digest": approval.digest, "request_digest": request_digest, "pending_action": None,
                                    "approval_state": "CONSUMED", "approval_scope": approval.scope,
                                    "manifest_digest": approval.manifest_digest}
        row = self.rows[operation]
        if row["approval_digest"] != approval.digest or row["request_digest"] != request_digest:
            raise ReleaseHold("OPERATION_BINDING_CHANGED")
        return copy.deepcopy(row)

    def load(self, service, operation, fence):
        fence.assert_held()
        return copy.deepcopy(self.rows.get(operation))

    def save(self, record, changes, fence):
        fence.assert_held()
        current = self.rows[record["operation_id"]]
        if current["revision"] != record["revision"]:
            raise ReleaseHold("STALE_REVISION")
        current.update(copy.deepcopy(changes))
        current["revision"] += 1
        return copy.deepcopy(current)


class FakeHost:
    def __init__(self, base):
        self.live = {**base, "healthy": True, "writes_enabled": False, "drained": False}
        self.effects = []
        self.receipts = {}
        self.raise_after = set()
        self.unhealthy_start = False
        self.raise_before = set()

    def observe(self, service, fence):
        fence.assert_held()
        return copy.deepcopy(self.live)

    def _effect(self, command, fence):
        fence.assert_held()
        action = command["action"]
        if action in self.raise_before:
            raise OSError("transport outcome unavailable")
        self.effects.append(action)
        receipt = {"operation_id": command["operation_id"]}
        if action == "prepare":
            receipt.update(candidate_path=command["candidate"]["artifact_path"], base_path=command["base"]["artifact_path"])
        if action == "drain":
            self.live["drained"] = True
        if action == "start":
            if self.live["generation"] != command["expected_generation"]:
                raise ReleaseHold("GENERATION_CHANGED")
            self.live = {**command["candidate"], "service_id": command["service_id"],
                         "healthy": not self.unhealthy_start, "writes_enabled": False, "drained": False}
        if action == "rollback":
            if self.live["generation"] != command["expected_generation"]:
                raise ReleaseHold("GENERATION_CHANGED")
            self.live = {**command["base"], "healthy": True, "writes_enabled": False,
                         "drained": False, "generation": command["rollback_generation"], "release_id": command["rollback_release_id"]}
        self.receipts[(command["operation_id"], action)] = receipt
        if action in self.raise_after:
            raise OSError("response lost after effect")
        return receipt

    prepare = drain = start = rollback = _effect

    def reconcile(self, operation, action, fence):
        fence.assert_held()
        receipt = self.receipts.get((operation, action))
        return {"outcome": "DONE", "result": copy.deepcopy(receipt)} if receipt else {"outcome": "UNKNOWN"}


class FakeReplay:
    def __init__(self):
        self.effects = []
        self.receipts = {}
        self.canary_passes = True
        self.item_status = "PROPOSED"
        self.lose_response = False
        self.extra_target = False

    def _effect(self, command, fence):
        fence.assert_held()
        self.effects.append(command["action"])
        result = {"operation_id": command["operation_id"], "manifest_digest": command["manifest_digest"],
                  "targets_digest": command["targets_digest"], "runtime": _identity(command["candidate"]),
                  "operating_writes": 0, "passed": self.canary_passes, "result_digest": command["expected_result_digest"],
                  "items": [{"capture_id": target["capture_id"], "status": self.item_status, "proposal_ids": [1]}
                            for target in command["targets"]]}
        if self.extra_target:
            result["items"].append({"capture_id": "unapproved", "status": "PROPOSED"})
        self.receipts[(command["operation_id"], command["action"])] = result
        if self.lose_response:
            raise OSError("replay receipt response lost")
        return result

    canary = replay = _effect

    def reconcile(self, operation, action, fence):
        return {"outcome": "DONE", "result": copy.deepcopy(self.receipts[(operation, action)])}


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        base_path, candidate_path = root / "base.jar", root / "candidate.jar"
        base_path.write_bytes(b"frozen base artifact")
        candidate_path.write_bytes(b"frozen candidate artifact")
        self.base = {"attestation_kind": "COMMITTED_DEPLOYED_SERVICE", "source_commit": COMMIT, "release_id": "release-1",
                     "service_id": "dataops", "generation": "generation-1", "artifact_path": str(base_path),
                     "artifact_sha256": hashlib.sha256(base_path.read_bytes()).hexdigest(), "parser_digest": "1" * 64,
                     "profile_digest": "2" * 64, "contract_digest": SHA, "database_identity": "isolated-db-1",
                     "schema_compatible": True, "backup_verified": True}
        self.candidate = {"release_id": "release-2", "generation": "generation-2", "artifact_path": str(candidate_path),
                          "artifact_sha256": hashlib.sha256(candidate_path.read_bytes()).hexdigest(), "parser_digest": "3" * 64,
                          "profile_digest": "4" * 64, "contract_digest": SHA, "database_identity": "isolated-db-1",
                          "base_artifact_sha256": self.base["artifact_sha256"], "base_commit": COMMIT, "schema_changes": [],
                          "validation_digest": SHA, "candidate_tree_digest": SHA, "patch_digest": SHA, "build_recipe_digest": SHA}
        target = {"capture_id": "capture-1", "payload_digest": SHA, "store_id": 1, "external_id": "123", "observed_at": NOW.isoformat(), "attempt_seq": 1}
        self.policy = {"mode": "READ_ONLY", "targets": [target], "expected_result_digest": "5" * 64}
        prefix = "docs/response-agent-reviews/review-1/"
        self.document = {"schema_version": 1, "review_id": "review-1", "kind": "PARSER_REPAIR", "scope": "DEPLOY_REPLAY_PROPOSE",
                         "incident_id": "incident-1", "run_id": "run-1", "revision": 1, "base_release": "release-1", "base_commit": COMMIT,
                         "expires_at": (NOW + timedelta(days=1)).isoformat(), "files": {prefix + "review.md": SHA,
                         "src/main/java/sweetgirl/dataops/snapshot/parser/NaverPlaceParser.java": SHA},
                         "targets": [target], "row_limit": 1, "preview_digest": SHA, **{key: SHA for key in DIGEST_FIELDS}}
        self.document.update(artifact_sha256=self.candidate["artifact_sha256"], structure_profile_digest=self.candidate["profile_digest"],
                             replay_target_manifest_digest=content_digest([target]), canary_policy_digest=content_digest(self.policy))
        self.freeze_review()
        self.journal, self.host, self.replay = MemoryJournal(), FakeHost(self.base), FakeReplay()
        outer = self
        class Registry:
            def committed_release(self, release_id): return copy.deepcopy(outer.base)
            def validated_candidate(self, validation): return copy.deepcopy(outer.candidate)
            def canary_policy(self, policy): return copy.deepcopy(outer.policy)
        class Provider:
            def read_snapshot(self, binding, manifest): return outer.snapshot
        self.registry, self.provider = Registry(), Provider()
        self.verifier = ApprovalVerifier(allowed_repository=REPOSITORY, allowed_actor_ids={7})
        self.controller = self.new_controller()

    def freeze_review(self):
        self.manifest = ReviewManifest(self.document)
        self.binding = PublicationBinding(REPOSITORY, 9, "c" * 40, self.manifest.digest)
        files = json.dumps(self.document["files"])
        self.snapshot = PullRequestSnapshot(REPOSITORY, 9, "c" * 40, True, "d" * 40, 7, NOW.isoformat(), False,
                                             files, SHA, SHA, REPOSITORY, files)

    def new_controller(self):
        return ReleaseController(journal=self.journal, registry=self.registry, host=self.host,
                                 replay=self.replay, provider=self.provider, verifier=self.verifier)

    def advance(self):
        return self.controller.advance(self.manifest, self.binding, now=NOW)

    def until(self, state):
        for _ in range(10):
            row = self.advance()
            if row["state"] == state:
                return row
        self.fail("did not reach " + state + ": " + row["state"])

    def test_concrete_adapters_required_no_implicit_live_service(self):
        with self.assertRaises(AdapterUnavailable):
            ReleaseController(journal=None, registry=None, host=None, replay=None, provider=None, verifier=None)

    def test_approval_to_readonly_canary_and_exact_proposals_is_idempotent(self):
        row = self.until("ACTIVE")
        self.assertEqual(["prepare", "drain", "start"], self.host.effects)
        self.assertEqual(["canary", "replay"], self.replay.effects)
        self.assertFalse(row["incident_resolved"])
        self.assertEqual(0, row["operating_writes"])
        self.assertEqual(row, self.advance())
        self.assertEqual(["prepare", "drain", "start"], self.host.effects)
        self.assertEqual("generation-2", self.host.live["generation"])

    def test_parser_only_source_manifest_is_not_full_deployed_base_authority(self):
        self.base["attestation_kind"] = "PARSER_FAMILY_AND_VALIDATION_INPUTS"
        with self.assertRaisesRegex(ReleaseHold, "FULL_COMMITTED_BASE_REQUIRED"):
            self.advance()
        self.assertEqual([], self.host.effects)

    def test_replay_adapter_fences_the_live_candidate_generation_after_start(self):
        admitted = []
        def checked(action, delegate):
            def invoke(command, fence):
                live = self.host.observe(command["service_id"], fence)
                if command["expected_generation"] != live["generation"]:
                    raise ReleaseHold("REPLAY_GENERATION_CHANGED")
                self.assertEqual(self.candidate["generation"], command["expected_generation"])
                self.assertNotEqual(self.base["generation"], command["expected_generation"])
                admitted.append(action)
                return delegate(command, fence)
            return invoke
        self.replay.canary = checked("canary", self.replay.canary)
        self.replay.replay = checked("replay", self.replay.replay)
        row = self.until("ACTIVE")
        self.assertEqual(["canary", "replay"], admitted)
        self.assertEqual(["prepare", "drain", "start"], self.host.effects)
        self.assertEqual(0, row["operating_writes"])

    def test_wrong_actor_head_and_data_scope_have_no_effects(self):
        for snapshot in (replace(self.snapshot, merged_by_id=99), replace(self.snapshot, head_sha="f" * 40), replace(self.snapshot, merged=False)):
            with self.subTest(snapshot=snapshot), self.assertRaises(ApprovalHold):
                self.snapshot = snapshot
                self.advance()
        self.assertEqual([], self.host.effects)

    def test_artifact_or_validation_binding_change_blocks_before_prepare(self):
        self.candidate["validation_digest"] = "f" * 64
        with self.assertRaisesRegex(ReleaseHold, "BINDING_CHANGED"):
            self.advance()
        self.candidate["validation_digest"] = SHA
        Path(self.candidate["artifact_path"]).write_bytes(b"replaced")
        with self.assertRaisesRegex(ReleaseHold, "ARTIFACT_DIGEST_CHANGED"):
            self.advance()
        self.assertEqual([], self.host.effects)

    def test_live_base_changed_blocks_start_without_stopping_foreign_release(self):
        self.until("DRAINED")
        self.host.live["generation"] = "foreign-generation"
        row = self.advance()
        self.assertEqual("STALE", row["state"])
        self.assertNotIn("start", self.host.effects)
        self.assertNotIn("rollback", self.host.effects)

    def test_health_failure_restores_exact_base_with_fresh_generation(self):
        self.host.unhealthy_start = True
        row = self.until("ROLLED_BACK")
        self.assertEqual(self.base["artifact_sha256"], self.host.live["artifact_sha256"])
        self.assertNotEqual(self.base["generation"], self.host.live["generation"])
        self.assertNotEqual(self.base["release_id"], self.host.live["release_id"])
        self.assertEqual(row["rollback_runtime"]["release_id"], self.host.live["release_id"])
        self.assertEqual(row["rollback_runtime"]["generation"], self.host.live["generation"])
        self.assertEqual([], self.replay.effects)

    def test_canary_failure_rolls_back_but_replay_failure_keeps_healthy_parser(self):
        self.replay.canary_passes = False
        self.until("ROLLED_BACK")
        self.assertEqual(["canary"], self.replay.effects)

    def test_replay_not_attempted_remains_held_without_fabricating_a_result(self):
        self.replay.item_status = "NOT_ATTEMPTED"
        row = self.until("REPLAY_HELD")
        self.assertEqual("NOT_ATTEMPTED", row["replay_receipt"]["items"][0]["status"])
        self.assertEqual(0, row["operating_writes"])

    def test_replay_held_does_not_rollback_or_apply_data(self):
        self.replay.item_status = "HELD"
        row = self.until("REPLAY_HELD")
        self.assertNotIn("rollback", self.host.effects)
        self.assertEqual("release-2", self.host.live["release_id"])
        self.assertEqual(0, row["operating_writes"])

    def test_start_ack_loss_reconciles_receipt_without_second_start(self):
        self.host.raise_after.add("start")
        row = self.until("RECOVERY_REQUIRED")
        self.assertEqual("start", row["pending_action"])
        self.controller = self.new_controller()  # Same test journal simulates loading persisted state.
        self.assertEqual("RECOVERY_REQUIRED", self.advance()["state"])
        row = self.controller.reconcile(self.manifest, self.binding, now=NOW)
        self.assertEqual("VERIFYING", row["state"])
        self.until("ACTIVE")
        self.assertEqual(1, self.host.effects.count("start"))

    def test_unknown_start_not_retried_without_positive_nonexecution_receipt(self):
        self.host.raise_before.add("start")
        self.until("RECOVERY_REQUIRED")
        self.assertEqual("RECOVERY_REQUIRED", self.controller.reconcile(self.manifest, self.binding, now=NOW)["state"])
        self.assertEqual("RECOVERY_REQUIRED", self.advance()["state"])
        self.host.reconcile = lambda operation, action, fence: {"outcome": "NOT_STARTED"}
        self.assertEqual("DRAINED", self.controller.reconcile(self.manifest, self.binding, now=NOW)["state"])
        self.host.raise_before.clear()
        self.until("ACTIVE")
        self.assertEqual(1, self.host.effects.count("start"))

    def test_replay_ack_loss_reconciles_without_second_proposal_run(self):
        self.until("REPLAY")
        self.replay.lose_response = True
        row = self.advance()
        self.assertEqual("replay", row["pending_action"])
        row = self.controller.reconcile(self.manifest, self.binding, now=NOW)
        self.assertEqual("ACTIVE", row["state"])
        self.assertEqual(1, self.replay.effects.count("replay"))

    def test_unknown_replay_item_retains_reconcile_boundary(self):
        self.replay.item_status = "UNKNOWN"
        row = self.until("RECOVERY_REQUIRED")
        self.assertEqual("replay", row["pending_action"])
        self.assertEqual("RECOVERY_REQUIRED", self.controller.reconcile(self.manifest, self.binding, now=NOW)["state"])
        receipt = self.replay.receipts[(row["operation_id"], "replay")]
        receipt["items"][0]["status"] = "PROPOSED"
        self.assertEqual("ACTIVE", self.controller.reconcile(self.manifest, self.binding, now=NOW)["state"])

    def test_rollback_failure_stops_in_recovery_required(self):
        self.host.unhealthy_start = True
        self.host.raise_before.add("rollback")
        row = self.until("RECOVERY_REQUIRED")
        self.assertEqual("rollback", row["pending_action"])
        self.assertEqual([], self.replay.effects)
        self.assertFalse(self.host.live["writes_enabled"])

    def test_service_lock_blocks_overlapping_controller_effects(self):
        with self.journal.service_lock("dataops"):
            with self.assertRaisesRegex(ReleaseHold, "SERVICE_BUSY"):
                self.advance()
        self.assertEqual([], self.host.effects)

    def test_canary_targets_over_twenty_or_changed_scope_fail_closed(self):
        self.policy["targets"] *= 21
        self.document["canary_policy_digest"] = content_digest(self.policy)
        self.freeze_review()
        with self.assertRaisesRegex(ReleaseHold, "CANARY_BOUNDS_OR_MODE"):
            self.advance()
        self.assertEqual([], self.host.effects)

    def test_canary_success_then_runtime_change_blocks_replay(self):
        self.until("REPLAY")
        self.host.live["parser_digest"] = "f" * 64
        row = self.advance()
        self.assertEqual("REPLAY_HELD", row["state"])
        self.assertEqual(["canary"], self.replay.effects)
        self.assertNotIn("rollback", self.host.effects)

    def test_out_of_scope_replay_item_never_counts_as_success(self):
        self.replay.extra_target = True
        row = self.until("RECOVERY_REQUIRED")
        self.assertEqual("replay", row["pending_action"])
        self.assertNotIn("rollback", self.host.effects)

    def test_expired_approval_still_allows_exact_base_rollback_after_health_failure(self):
        self.host.unhealthy_start = True
        self.until("ROLLBACK_NEEDED")
        row = self.controller.advance(self.manifest, self.binding, now=NOW + timedelta(days=2))
        self.assertEqual("ROLLED_BACK", row["state"])
        self.assertEqual(self.base["artifact_sha256"], self.host.live["artifact_sha256"])

    def test_expired_approval_allows_readonly_reconcile_but_blocks_new_effect(self):
        self.host.raise_after.add("start")
        self.until("RECOVERY_REQUIRED")
        expired = NOW + timedelta(days=2)
        row = self.controller.reconcile(self.manifest, self.binding, now=expired)
        self.assertEqual("VERIFYING", row["state"])
        self.assertEqual("CANARY", self.controller.advance(self.manifest, self.binding, now=expired)["state"])
        with self.assertRaisesRegex(ApprovalHold, "APPROVAL_EXPIRED"):
            self.controller.advance(self.manifest, self.binding, now=expired)
        self.assertEqual(1, self.host.effects.count("start"))
        self.assertEqual([], self.replay.effects)

    def test_unavailable_health_is_not_proof_that_start_failed(self):
        self.until("VERIFYING")
        original = self.host.observe
        self.host.observe = lambda *args: (_ for _ in ()).throw(OSError("unreachable"))
        row = self.advance()
        self.assertEqual("VERIFYING", row["state"])
        self.assertEqual("RUNTIME_READINESS_UNKNOWN", row["hold_code"])
        self.host.observe = original
        self.until("ACTIVE")
        self.assertEqual(1, self.host.effects.count("start"))

    def test_owned_candidate_runtime_digest_failure_restores_base(self):
        self.until("VERIFYING")
        self.host.live["parser_digest"] = "f" * 64
        self.assertEqual("ROLLBACK_NEEDED", self.advance()["state"])
        self.assertEqual("ROLLED_BACK", self.advance()["state"])

    def test_reconcile_cannot_use_another_publication_binding_even_after_expiry(self):
        self.host.raise_after.add("start")
        self.until("RECOVERY_REQUIRED")
        other = replace(self.binding, head_sha="f" * 40)
        with self.assertRaisesRegex(ReleaseHold, "CONSUMED_APPROVAL_RECEIPT_CHANGED"):
            self.controller.reconcile(self.manifest, other, now=NOW + timedelta(days=2))

    def test_reconcile_without_consumed_approval_cannot_create_authority(self):
        with self.assertRaisesRegex(ReleaseHold, "CONSUMED_APPROVAL_RECEIPT_REQUIRED"):
            self.controller.reconcile(self.manifest, self.binding, now=NOW)
        self.assertEqual([], self.host.effects)


class PgReleaseTests(ReleaseTests):
    """Same controller safety cases with a real isolated PostgreSQL durable journal."""
    @classmethod
    def setUpClass(cls):
        cls.container = "agent-release-test-" + uuid.uuid4().hex[:10]
        try:
            subprocess.run(["docker", "run", "--rm", "-d", "--name", cls.container,
                            "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "-e", "POSTGRES_DB=release_test",
                            "-p", "127.0.0.1::5432", "postgres:17-alpine"], check=True, capture_output=True, timeout=60)
            port = subprocess.run(["docker", "port", cls.container, "5432"], check=True, capture_output=True, text=True).stdout.strip().split(":")[-1]
            cls.dsn = f"host=127.0.0.1 port={port} dbname=release_test user=postgres connect_timeout=2"
            for _ in range(40):
                try:
                    with psycopg2.connect(cls.dsn):
                        break
                except psycopg2.OperationalError:
                    time.sleep(.2)
            else:
                raise RuntimeError("isolated postgres unavailable")
            with psycopg2.connect(cls.dsn) as db, db.cursor() as cursor:
                cursor.execute("CREATE TABLE response_agent_run(run_id uuid PRIMARY KEY)")
                cursor.execute("INSERT INTO response_agent_run VALUES ('01000000-0000-0000-0000-000000000001')")
                cursor.execute(Path("src/main/resources/db/migration/V20261014170000__agent_review_outbox.sql").read_text())
                cursor.execute(Path("src/main/resources/db/migration/V20261014190000__agent_release_journal.sql").read_text())
        except (OSError, subprocess.SubprocessError, RuntimeError, psycopg2.Error) as error:
            subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True)
            raise unittest.SkipTest(type(error).__name__) from None

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True)

    def setUp(self):
        super().setUp()
        self.document["run_id"] = "01000000-0000-0000-0000-000000000001"
        self.freeze_review()
        self.connect = lambda: psycopg2.connect(self.dsn)
        self.journal = PgReleaseJournal(self.connect)
        store = PgReviewStore(self.connect)
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute("TRUNCATE response_agent_review_manifest CASCADE")
            store.freeze(cursor, self.manifest, validated=True)
            store.bind_publication(cursor, self.binding)
        self.controller = self.new_controller()

    def evidence(self):
        return self.verifier.evaluate(self.manifest, self.binding, self.snapshot, now=NOW)

    def test_approval_consume_and_journal_insert_rollback_together(self):
        original = self.journal.approvals.consume
        def interrupted(cursor, *args, **kwargs):
            original(cursor, *args, **kwargs)
            raise RuntimeError("simulated crash before release insert")
        with self.journal.service_lock("dataops") as fence:
            with patch.object(self.journal.approvals, "consume", side_effect=interrupted):
                with self.assertRaisesRegex(RuntimeError, "simulated crash"):
                    self.journal.claim("dataops", "operation-atomic", self.evidence(), SHA, fence)
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute("SELECT count(*) FROM response_agent_review_approval")
            self.assertEqual(0, cursor.fetchone()[0])
            cursor.execute("SELECT count(*) FROM response_agent_release_operation")
            self.assertEqual(0, cursor.fetchone()[0])
        with self.journal.service_lock("dataops") as fence:
            row = self.journal.claim("dataops", "operation-atomic", self.evidence(), SHA, fence)
            self.assertEqual("CONSUMED", row["approval_state"])
            self.assertEqual(row, self.journal.claim("dataops", "operation-atomic", self.evidence(), SHA, fence))
            with self.assertRaisesRegex(ReleaseHold, "OPERATION_BINDING_CHANGED"):
                self.journal.claim("dataops", "operation-atomic", self.evidence(), "f" * 64, fence)

    def test_same_approval_cannot_authorize_another_service_operation(self):
        with self.journal.service_lock("dataops") as fence:
            self.journal.claim("dataops", "operation-one", self.evidence(), SHA, fence)
        with self.journal.service_lock("other-service") as fence:
            with self.assertRaisesRegex(ReleaseHold, "APPROVAL_ALREADY_CONSUMED_OR_INVALID"):
                self.journal.claim("other-service", "operation-two", self.evidence(), SHA, fence)

    def test_lost_lock_connection_fences_old_worker_after_new_epoch(self):
        with self.journal.service_lock("dataops") as first:
            row = self.journal.claim("dataops", "operation-fenced", self.evidence(), SHA, first)
            first.connection.close()  # Session advisory lock is released; stale object must never reconnect.
            with self.journal.service_lock("dataops") as second:
                self.assertGreater(second.token, first.token)
                with self.assertRaisesRegex(ReleaseHold, "SERVICE_FENCE_LOST"):
                    self.journal.save(row, {"state": "PREPARED"}, first)
                saved = self.journal.save(row, {"state": "PREPARE_IN_FLIGHT", "pending_action": "prepare"}, second)
                with self.assertRaisesRegex(ReleaseHold, "STALE_RELEASE_REVISION"):
                    self.journal.save(row, {"state": "HELD"}, second)
                self.assertEqual("PREPARE_IN_FLIGHT", saved["state"])

    def test_new_journal_instance_recovers_real_persisted_unknown_receipt(self):
        self.host.raise_after.add("start")
        self.until("RECOVERY_REQUIRED")
        self.journal = PgReleaseJournal(self.connect)
        self.controller = self.new_controller()
        self.assertEqual("RECOVERY_REQUIRED", self.advance()["state"])
        self.assertEqual("VERIFYING", self.controller.reconcile(self.manifest, self.binding, now=NOW)["state"])
        self.until("ACTIVE")
        self.assertEqual(1, self.host.effects.count("start"))

    def test_expired_database_review_keeps_consumed_receipt_for_rollback(self):
        self.host.unhealthy_start = True
        self.until("ROLLBACK_NEEDED")
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute("UPDATE response_agent_review_manifest SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute'")
        row = self.controller.advance(self.manifest, self.binding, now=NOW + timedelta(days=2))
        self.assertEqual("ROLLED_BACK", row["state"])
        with self.connect() as db, db.cursor() as cursor:
            cursor.execute("SELECT state FROM response_agent_review_approval")
            self.assertEqual("CONSUMED", cursor.fetchone()[0])

    def test_receipt_json_cannot_rewrite_approval_or_operation_scope(self):
        with self.journal.service_lock("dataops") as fence:
            row = self.journal.claim("dataops", "operation-immutable", self.evidence(), SHA, fence)
            with self.assertRaisesRegex(ReleaseHold, "IMMUTABLE_RELEASE_RECEIPT"):
                self.journal.save(row, {"approval_scope": "APPLY_EXACT_MANIFEST"}, fence)
            with self.connect() as db, db.cursor() as cursor:
                cursor.execute("""UPDATE response_agent_release_operation SET receipt='{"approval_scope":"APPLY_EXACT_MANIFEST"}'::jsonb
                    WHERE operation_id='operation-immutable'""")
            self.assertEqual("DEPLOY_REPLAY_PROPOSE", self.journal.load("dataops", "operation-immutable", fence)["approval_scope"])

    def test_revoked_review_stops_next_effect_but_retains_readonly_reconciliation(self):
        self.until("CANARY")
        with self.connect() as db, db.cursor() as cursor:
            PgReviewStore(self.connect).invalidate(cursor, self.manifest.digest, state="HELD")
        with self.assertRaisesRegex(ReleaseHold, "APPROVAL_EXECUTION_REVOKED_OR_EXPIRED"):
            self.advance()
        self.assertEqual([], self.replay.effects)

    def test_connection_alive_but_advisory_lock_released_loses_fence(self):
        with self.journal.service_lock("dataops") as fence:
            row = self.journal.claim("dataops", "operation-unlocked", self.evidence(), SHA, fence)
            with fence.connection, fence.connection.cursor() as cursor:
                cursor.execute("SELECT pg_advisory_unlock_all()")
            with self.assertRaisesRegex(ReleaseHold, "SERVICE_FENCE_LOST"):
                self.journal.save(row, {"state": "HELD"}, fence)


if __name__ == "__main__":
    unittest.main()
