import copy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import time
import unittest
from unittest.mock import patch
import tempfile
import uuid

import psycopg2

from agent.response_change.approval import (ApprovalHold, ApprovalVerifier, DIGEST_FIELDS, PARSER_PATHS,
    PublicationBinding, PullRequestSnapshot, ReviewManifest, PgReviewStore, OutboxLeaseLost, canonical,
    content_digest, code_tree_digest, render_data_manifest, render_data_preview)

REPO = "SWEETT-GIRL/bbang-ggut_dataops_BE"
NOW = datetime.now(timezone.utc)
SHA = "a"*64
HEAD = "b"*40
BASE = "c"*40
MERGE = "d"*40
RUN = "01000000-0000-0000-0000-000000000001"


def manifest_document(kind="PARSER_REPAIR"):
    prefix = "docs/response-agent-reviews/review-1/"
    scopes = {"PARSER_REPAIR":"DEPLOY_REPLAY_PROPOSE", "DATA_APPLY":"APPLY_EXACT_MANIFEST", "REPORT_ONLY":"NONE"}
    document = {"schema_version":1,"review_id":"review-1","kind":kind,"scope":scopes[kind],
                "incident_id":"incident-1","run_id":RUN,"revision":1,"base_release":"release-1",
                "base_commit":BASE,"expires_at":(NOW+timedelta(days=1)).isoformat(),
                "files":{prefix+"review.md":SHA},"targets":[],"row_limit":0,"preview_digest":SHA}
    document.update({field:SHA if kind != "REPORT_ONLY" else None for field in DIGEST_FIELDS})
    if kind == "PARSER_REPAIR":
        document["files"][sorted(PARSER_PATHS)[0]] = SHA
        document["targets"] = [{"capture_id":"capture-1","payload_digest":SHA,"store_id":1,
                                "external_id":"123","observed_at":NOW.isoformat(),"attempt_seq":0}]
    if kind == "DATA_APPLY":
        document["files"][prefix+"data-manifest.json"] = SHA
        document["targets"] = [{"proposal_id":123,"store_id":1,"external_id":"123","operation":"UPDATE",
            "field":"name","old_value":"이전","new_value":"새 이름","capture_id":"capture-1",
            "capture_digest":SHA,"release_id":"release-1","reprocess_item_id":"item-1"}]
    if kind == "DATA_APPLY":
        document["files"][prefix+"data-manifest.json"] = sha256(render_data_manifest(document["targets"]).encode()).hexdigest()
        document["preview_digest"] = sha256(render_data_preview(document["targets"]).encode()).hexdigest()
        document["files"][prefix+"review.md"] = document["preview_digest"]
    if kind != "REPORT_ONLY":
        document["row_limit"] = len(document["targets"])
        document["replay_target_manifest_digest"] = content_digest(document["targets"])
    return document


def binding(manifest):
    return PublicationBinding(REPO,19,HEAD,manifest.digest)


def snapshot(manifest):
    return PullRequestSnapshot(REPO,19,HEAD,True,MERGE,7,NOW.isoformat(),False,
        canonical(manifest.document["files"]),SHA,SHA,REPO,canonical(manifest.document["files"]))


class ManifestTests(unittest.TestCase):
    def test_freeze_is_deep_and_canonical(self):
        document = manifest_document()
        frozen = ReviewManifest(document)
        digest = frozen.digest
        document["targets"][0]["store_id"] = 2
        frozen.document["targets"][0]["store_id"] = 3
        self.assertEqual(frozen.digest,digest)
        self.assertEqual(frozen.document["targets"][0]["store_id"],1)
        self.assertEqual(ReviewManifest(json.loads(frozen._canonical)).digest,digest)

    def test_wrong_scope_code_in_data_report_and_unknown_manifest_fields_rejected(self):
        for kind in ("DATA_APPLY","REPORT_ONLY"):
            doc = manifest_document(kind)
            doc["files"][sorted(PARSER_PATHS)[0]] = SHA
            with self.assertRaisesRegex(ApprovalHold,"FILE_ALLOWLIST"): ReviewManifest(doc)
        doc = manifest_document()
        doc["scope"] = "APPLY_EXACT_MANIFEST"
        with self.assertRaisesRegex(ApprovalHold,"MANIFEST_SCOPE"): ReviewManifest(doc)
        doc = manifest_document()
        doc["approved"] = True
        with self.assertRaisesRegex(ApprovalHold,"MANIFEST_FIELDS"): ReviewManifest(doc)

    def test_targets_duplicate_modified_values_row_count_and_preview_are_bound(self):
        for mutation in (lambda d:d["targets"].append(copy.deepcopy(d["targets"][0])),
                         lambda d:d["targets"][0].update(new_value="tampered"),
                         lambda d:d.update(row_limit=1000),
                         lambda d:d.update(preview_digest="0"*64)):
            doc = manifest_document("DATA_APPLY")
            mutation(doc)
            with self.assertRaises(ApprovalHold): ReviewManifest(doc)
        self.assertEqual(ReviewManifest(manifest_document("DATA_APPLY")).document["scope"],"APPLY_EXACT_MANIFEST")

    def test_tree_digest_excludes_only_own_docs_and_preserves_file_modes_and_other_reviews(self):
        tree = {"code.java":{"sha":HEAD,"mode":"100644"},
                "docs/response-agent-reviews/review-1/review.md":{"sha":HEAD,"mode":"100644"}}
        original = code_tree_digest(tree,"review-1")
        tree["docs/response-agent-reviews/review-1/review.md"]["sha"] = MERGE
        self.assertEqual(original,code_tree_digest(tree,"review-1"))
        tree["code.java"]["mode"] = "100755"
        self.assertNotEqual(original,code_tree_digest(tree,"review-1"))
        with self.assertRaises(ApprovalHold): code_tree_digest({"../escape":{"sha":HEAD,"mode":"100644"}},"review-1")


class ApprovalTests(unittest.TestCase):
    def setUp(self):
        self.manifest = ReviewManifest(manifest_document())
        self.verifier = ApprovalVerifier(allowed_repository=REPO,allowed_actor_ids={7})

    def test_only_exact_authenticated_merge_yields_scope_authority(self):
        evidence = self.verifier.evaluate(self.manifest,binding(self.manifest),snapshot(self.manifest),now=NOW)
        self.assertEqual(evidence.scope,"DEPLOY_REPLAY_PROPOSE")
        self.assertEqual(evidence.event_key,f"{REPO}:19:{MERGE}")
        self.assertEqual(evidence,self.verifier.evaluate(self.manifest,binding(self.manifest),snapshot(self.manifest),now=NOW))

    def test_actor_head_repo_merge_tree_extra_files_and_unmerged_rejected(self):
        for changes in ({"merged_by_id":8},{"head_sha":MERGE},{"head_repository":"other/repo"},
                        {"merge_code_tree_digest":"0"*64},{"files_json":canonical({".github/workflows/pwn.yml":SHA})},
                        {"merge_files_json":canonical({"review.md":"changed"})},
                        {"merged":False},{"draft":True}):
            with self.subTest(changes=changes),self.assertRaises(ApprovalHold):
                self.verifier.evaluate(self.manifest,binding(self.manifest),replace(snapshot(self.manifest),**changes),now=NOW)

    def test_expiry_and_report_only_cannot_authorize(self):
        with self.assertRaisesRegex(ApprovalHold,"APPROVAL_EXPIRED"):
            self.verifier.evaluate(self.manifest,binding(self.manifest),snapshot(self.manifest),now=NOW+timedelta(days=2))
        report = ReviewManifest(manifest_document("REPORT_ONLY"))
        with self.assertRaisesRegex(ApprovalHold,"REPORT_ONLY_NOT_AUTHORITY"):
            self.verifier.evaluate(report,binding(report),snapshot(report),now=NOW)


class PgReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "agent-review-test-"+uuid.uuid4().hex[:10]
        try:
            subprocess.run(["docker","run","--rm","-d","--name",cls.container,"-e","POSTGRES_HOST_AUTH_METHOD=trust",
                "-e","POSTGRES_DB=review_test","-p","127.0.0.1::5432","postgres:17-alpine"],check=True,capture_output=True,timeout=60)
            port = subprocess.run(["docker","port",cls.container,"5432"],check=True,capture_output=True,text=True).stdout.strip().split(":")[-1]
            cls.dsn = f"host=127.0.0.1 port={port} dbname=review_test user=postgres connect_timeout=2"
            for _ in range(40):
                try:
                    with psycopg2.connect(cls.dsn): break
                except psycopg2.OperationalError: time.sleep(.2)
            else: raise RuntimeError("postgres did not start")
            with psycopg2.connect(cls.dsn) as db,db.cursor() as cur:
                cur.execute("""CREATE TABLE response_agent_run(run_id uuid primary key, state text DEFAULT 'RUNNING',
                    owner text DEFAULT 'worker', fencing_epoch bigint DEFAULT 1,
                    lease_until timestamptz DEFAULT CURRENT_TIMESTAMP+INTERVAL '5 minute',stop_reason text,
                    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP)""")
                cur.execute("INSERT INTO response_agent_run VALUES (%s)",(RUN,))
                cur.execute(Path("src/main/resources/db/migration/V20261014170000__agent_review_outbox.sql").read_text())
        except (OSError,subprocess.SubprocessError,RuntimeError,psycopg2.Error) as error:
            subprocess.run(["docker","rm","-f",cls.container],capture_output=True)
            raise unittest.SkipTest(type(error).__name__) from None

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker","rm","-f",cls.container],capture_output=True)

    def setUp(self):
        self.store = PgReviewStore(lambda:psycopg2.connect(self.dsn))
        self.manifest = ReviewManifest(manifest_document())
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            cur.execute("TRUNCATE response_agent_review_manifest CASCADE")
            cur.execute("UPDATE response_agent_run SET state='RUNNING',lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minute'")
            self.store.freeze(cur,self.manifest,validated=True)

    def enqueue(self,payload=None):
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            return self.store.enqueue(cur,manifest_digest=self.manifest.digest,event_kind="READY_FOR_REVIEW",destination=REPO,
                                      revision=1,payload=payload or {"stored":"artifact"})

    def test_transaction_rollback_has_no_notification_and_duplicate_payload_does_not_create_another(self):
        with self.assertRaises(RuntimeError):
            with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
                self.store.enqueue(cur,manifest_digest=self.manifest.digest,event_kind="READY_FOR_REVIEW",destination=REPO,
                                   revision=1,payload={"stored":"artifact"})
                raise RuntimeError("business transaction aborted")
        self.assertIsNone(self.store.claim(owner="worker"))
        self.assertEqual(self.enqueue(),self.enqueue())
        with self.assertRaisesRegex(ApprovalHold,"OUTBOX_CONTENT_CONFLICT"): self.enqueue({"different":"artifact"})

    def test_unknown_delivery_is_reconciliation_only_and_expired_worker_cannot_settle(self):
        self.enqueue()
        first = self.store.claim(owner="worker-1")
        self.assertFalse(first.reconciliation_required)
        self.assertIsNone(self.store.claim(owner="worker-2"))
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            cur.execute("UPDATE response_agent_notification_outbox SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        recovered = self.store.claim(owner="worker-2")
        self.assertTrue(recovered.reconciliation_required)
        with self.assertRaises(OutboxLeaseLost): self.store.finish(first,provider_reference={"pr":19})
        self.store.finish(recovered,error_code="GITHUB_TRANSPORT_UNKNOWN")
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            cur.execute("UPDATE response_agent_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP")
        retry = self.store.claim(owner="worker-3")
        self.assertTrue(retry.reconciliation_required)
        self.store.finish(retry,provider_reference={"pr":19})
        self.assertIsNone(self.store.claim(owner="worker-4"))

    def test_dispatch_terminal_transition_and_outbox_commit_or_rollback_together(self):
        from agent.response_change.dispatch import PgDispatcher,Claim
        with tempfile.TemporaryDirectory() as directory:
            claim = Claim(RUN,"a"*64,"worker",1,.5,Path(directory),{},None,False)
            report = {"status":"HELD","requests":[]}
            def sink(cur,claim,report):
                self.store.enqueue(cur,manifest_digest=self.manifest.digest,event_kind="HELD",destination=REPO,
                                   revision=1,payload={"status":report["status"]})
                raise RuntimeError("outbox boundary failed")
            dispatch = PgDispatcher(lambda:psycopg2.connect(self.dsn),None,directory,terminal_sink=sink)
            with patch.object(dispatch,"checkpoint"),patch.object(dispatch,"_persist_terminal"):
                with self.assertRaises(RuntimeError): dispatch.finish(claim,report)
            with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
                cur.execute("SELECT state FROM response_agent_run WHERE run_id=%s",(RUN,))
                self.assertEqual(cur.fetchone()[0],"RUNNING")
                cur.execute("SELECT count(*) FROM response_agent_notification_outbox")
                self.assertEqual(cur.fetchone()[0],0)
            dispatch.terminal_sink=lambda cur,claim,report:self.store.enqueue(cur,
                manifest_digest=self.manifest.digest,event_kind="HELD",destination=REPO,revision=1,payload={"status":report["status"]})
            with patch.object(dispatch,"checkpoint"),patch.object(dispatch,"_persist_terminal"):
                dispatch.finish(claim,report)
            with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
                cur.execute("SELECT state FROM response_agent_run WHERE run_id=%s",(RUN,))
                self.assertEqual(cur.fetchone()[0],"HELD")
                cur.execute("SELECT count(*) FROM response_agent_notification_outbox")
                self.assertEqual(cur.fetchone()[0],1)

    def test_duplicate_merge_consumes_exact_scope_once_and_held_review_stops(self):
        verifier = ApprovalVerifier(allowed_repository=REPO,allowed_actor_ids={7})
        evidence = verifier.evaluate(self.manifest,binding(self.manifest),snapshot(self.manifest),now=NOW)
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            self.store.bind_publication(cur,binding(self.manifest))
            approval = self.store.approve(cur,evidence)
            self.assertEqual(self.store.approve(cur,evidence),approval)
            self.assertIsNone(self.store.consume(cur,approval,scope="APPLY_EXACT_MANIFEST",operation_id="wrong"))
            self.assertEqual(self.store.consume(cur,approval,scope="DEPLOY_REPLAY_PROPOSE",operation_id="release-1"),self.manifest.digest)
            self.assertIsNone(self.store.consume(cur,approval,scope="DEPLOY_REPLAY_PROPOSE",operation_id="release-2"))
        with psycopg2.connect(self.dsn) as db,db.cursor() as cur:
            self.store.invalidate(cur,self.manifest.digest,state="SUPERSEDED")
            with self.assertRaisesRegex(ApprovalHold,"APPROVAL_STORE_BINDING"): self.store.approve(cur,evidence)


if __name__ == "__main__": unittest.main()
