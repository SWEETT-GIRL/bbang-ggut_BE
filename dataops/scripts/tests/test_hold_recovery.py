import json
from datetime import datetime, timezone
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from capture_spool import CaptureSpool
from hold_recovery import HoldRecovery, classify, receipt_reasons, BACKOFF_SECONDS, MAX_AGE_SECONDS
import sync_worker as worker


class HoldRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.spool = CaptureSpool(Path(self.temp.name) / "captures")
        self.now = datetime.now(timezone.utc).timestamp()

    def receipt(self, capture, reasons=(), observation=(), **counts):
        return {"captureId": capture, "assessment": "PARTIAL" if observation else "READY", "changed": False,
                "reasons": list(observation), "automaticApply": {"captureId": capture, "applied": 0,
                "alreadyApplied": 0, "held": len(reasons), "failed": 0, "unknown": 0,
                "items": [{"proposalId": i, "status": "HELD", "reason": reason} for i, reason in enumerate(reasons)], **counts}}

    def record(self, reasons=("EXACT_HOURS_LOCK_PERMISSION_REQUIRED",), observation=(), store=1, **counts):
        capture = str(uuid4())
        record = self.spool.retain(capture, {"storeId": store, "source": "NAVER", "externalId": "123",
            "observationId": capture, "observedAt": datetime.fromtimestamp(self.now - 1800, timezone.utc).isoformat(),
            "attemptSeq": 7, "payload": {}}, {"kind": "SUCCESS"})
        self.spool.acknowledge(record, self.receipt(capture, reasons, observation, **counts))
        return self.spool.read(self.spool.path(capture))

    def test_production_18_holds_and_one_observation_are_distinguished(self):
        for store in range(1, 10):
            reasons = ["EXACT_HOURS_LOCK_PERMISSION_REQUIRED"]
            if store == 9: reasons += ["SOURCE_UNKNOWN"] * 7
            if store == 8: reasons += ["DELETION_EVIDENCE_REQUIRES_REVIEW"] * 2
            self.record(reasons, store=store)
        self.record((), ("MENU_INCOMPLETE", "BAEMIN_GROUP_OUT_OF_SCOPE"), store=10)
        recovery = HoldRecovery(self.spool, self.now)
        counts = recovery.counts()
        self.assertEqual(counts['hold_permission_required'], 9)
        self.assertEqual(counts['hold_source_unknown'], 7)
        self.assertEqual(counts['hold_deletion_review'], 2)
        self.assertEqual(counts['recovery_configuration_blocked'], 9)
        self.assertEqual(len(recovery.due(self.now, False)), 1)
        self.assertEqual(len(recovery.due(self.now, True)), 10)

    def test_normal_null_source_unknown_deletion_and_structural_changes_do_not_refetch(self):
        cases = [((), ("MENU_INCOMPLETE",), "INCOMPLETE"),
                 (("SOURCE_UNKNOWN",), (), "REVIEW_REQUIRED"),
                 (("SOURCE_CONFLICT",), (), "REVIEW_REQUIRED"),
                 (("DELETION_EVIDENCE_REQUIRES_REVIEW",), (), "REVIEW_REQUIRED"),
                 (("MENU_POLICY_REVIEW_REQUIRED",), (), "MENU_DECISION_REQUIRED"),
                 ((), ("MENU_CONTAINER_MISSING",), "AGENT_REQUIRED"),
                 ((), ("PRICE_SHAPE_UNSUPPORTED",), "AGENT_REQUIRED"),
                 ((), ("STALE_ATTEMPT",), "SUPERSEDED")]
        for store, (reasons, observations, expected) in enumerate(cases):
            record = self.record(reasons, observations, store=store)
            self.assertEqual(classify(record['receipt'], receipt_reasons(record['receipt'])), expected)
        recovery = HoldRecovery(self.spool, self.now)
        self.assertEqual(recovery.due(self.now, True), [])

    def test_unknown_and_failed_override_even_retryable_reasons(self):
        self.record(unknown=1)
        self.record(store=2, failed=1)
        recovery = HoldRecovery(self.spool, self.now)
        self.assertEqual(recovery.due(self.now, True), [])
        self.assertEqual(recovery.counts()['recovery_audit_required'], 2)

    def test_crash_after_reservation_consumes_budget_and_backoff_across_restarts(self):
        self.record()
        for index in range(3):
            recovery = HoldRecovery(CaptureSpool(self.spool.root), self.now)
            due = recovery.due(self.now, True)
            self.assertEqual(len(due), 1)
            reserved = recovery.reserve(due[0], self.now)
            restarted = HoldRecovery(CaptureSpool(self.spool.root), self.now)
            self.assertEqual(restarted.due(self.now, True), [])
            self.assertEqual(reserved['recovery']['attempts'], index + 1)
            self.now += BACKOFF_SECONDS[min(index + 1, 2)]
        exhausted = HoldRecovery(self.spool, self.now)
        self.assertEqual(exhausted.due(self.now, True), [])
        self.assertEqual(exhausted.counts()['recovery_exhausted'], 1)

    def test_expiry_and_missing_dispatch_generation_prevent_recollection(self):
        self.record()
        self.assertEqual(HoldRecovery(self.spool, self.now + MAX_AGE_SECONDS).due(self.now + MAX_AGE_SECONDS, True), [])
        self.assertEqual(HoldRecovery(self.spool, self.now + MAX_AGE_SECONDS).counts()['recovery_expired'], 1)

    def test_reservation_expiry_is_skipped_without_spending_an_attempt(self):
        old = self.record()
        recovery = HoldRecovery(self.spool, self.now)
        due = recovery.due(self.now, True)[0]
        expires_at = due['recovery']['startedAt'] + MAX_AGE_SECONDS
        self.assertIsNone(recovery.reserve(due, expires_at))
        persisted = self.spool.read(self.spool.path(old['capture_id']))['recovery']
        self.assertEqual(persisted['status'], 'EXPIRED')
        self.assertEqual(persisted['attempts'], 0)

    def test_counts_use_publication_time_and_preserve_non_retryable_states(self):
        self.record()
        self.record(['VALUE_DRIFTED'], store=2)
        self.record(['SOURCE_UNKNOWN'], store=3)
        self.record((), store=4, applied=1)
        recovery = HoldRecovery(self.spool, self.now)
        expires_at = self.now - 1800 + MAX_AGE_SECONDS
        with patch('time.time', return_value=expires_at - 1):
            counts = recovery.counts()
            self.assertEqual(counts['recovery_configuration_blocked'], 1)
            self.assertEqual(counts['recovery_waiting'], 1)
            self.assertEqual(counts['recovery_expired'], 0)
        with patch('time.time', return_value=expires_at):
            counts = recovery.counts()
            self.assertEqual(counts['recovery_configuration_blocked'], 0)
            self.assertEqual(counts['recovery_waiting'], 0)
            self.assertEqual(counts['recovery_expired'], 2)
            self.assertEqual(counts['recovery_review_required'], 1)
            self.assertEqual(counts['hold_permission_required'], 1)
        self.assertEqual(recovery.due(expires_at, True), [])

    def test_queued_expiry_does_not_stop_other_recoveries_or_server_due_items(self):
        old = self.record()
        self.now += 3600
        self.record(store=2)
        self.now += MAX_AGE_SECONDS - 5401
        summary = Path(self.temp.name) / 'expiry-summary.json'
        clock = [self.now]
        original_reserve = HoldRecovery.reserve
        def reserve(recovery, record, now):
            if record['request']['storeId'] == 1:
                clock[0] += 1  # The queued record expires after selection, before I/O.
            return original_reserve(recovery, record, clock[0])
        def submit(server, record, auto_apply):
            self.assertIn(record['request']['storeId'], (2, 3))
            return self.receipt(record['capture_id'], applied=1)
        server_due = {'storeId': 3, 'source': 'NAVER', 'externalId': '456', 'attemptSeq': 8}
        with patch.object(worker.time, 'time', side_effect=lambda: clock[0]), \
             patch.object(worker.time, 'sleep'), patch.object(HoldRecovery, 'reserve', new=reserve), \
             patch.object(worker, 'fetch_due', side_effect=[[server_due], []]) as due, \
             patch.object(worker, 'fetch_place', return_value=worker.FetchResult({}, False, 'SUCCESS')) as fetch, \
             patch.object(worker, 'submit_retained', side_effect=submit):
            self.assertEqual(worker.run('unused', 50, 2, 0, 0, spool=self.spool, summary_path=summary,
                auto_apply=True, capabilities={'automaticHoursPermissionsReady': True}), 0)
        self.assertEqual(fetch.call_count, 2)
        due.assert_called_once()
        persisted = self.spool.read(self.spool.path(old['capture_id']))['recovery']
        self.assertEqual(persisted['attempts'], 0)
        self.assertEqual(persisted['status'], 'EXPIRED')
        data = json.loads(summary.read_text())
        self.assertEqual(data['attempted'], 2)
        self.assertEqual(data['recovery_attempted'], 1)
        self.assertEqual(data['recovery_resolved'], 1)
        self.assertEqual(data['recovery_expired'], 1)

    def test_legacy_receipt_without_a_dispatch_generation_is_not_recollected(self):
        capture = str(uuid4())
        request = {'storeId': 1, 'source': 'NAVER', 'externalId': '123', 'payload': {},
                   'observedAt': datetime.fromtimestamp(self.now - 1800, timezone.utc).isoformat()}
        record = self.spool.retain(capture, request, {})
        self.spool.acknowledge(record, self.receipt(capture, ['EXACT_HOURS_LOCK_TIMEOUT']))
        recovery = HoldRecovery(self.spool, self.now)
        self.assertEqual(recovery.due(self.now, True), [])

    def test_collection_only_receipts_do_not_opt_into_automatic_writes(self):
        capture = str(uuid4())
        record = self.spool.retain(capture, {'storeId': 1, 'source': 'NAVER', 'externalId': '123',
            'payload': {}, 'attemptSeq': 1, 'observedAt': datetime.fromtimestamp(self.now - 1800, timezone.utc).isoformat()}, {})
        self.spool.acknowledge(record, {'assessment': 'HELD', 'reasons': ['BAEMIN_GROUP_OUT_OF_SCOPE']})
        recovery = HoldRecovery(self.spool, self.now)
        self.assertEqual(recovery.due(self.now, True), [])

    def test_semantic_and_structural_analysis_tasks_are_separate_even_with_a_permission_hold(self):
        record = self.record(['MENU_POLICY_REVIEW_REQUIRED', 'EXACT_HOURS_LOCK_PERMISSION_REQUIRED'])
        HoldRecovery(self.spool, self.now)
        metadata = self.spool.read(self.spool.path(record['capture_id']))['recovery']
        self.assertEqual(metadata['status'], 'CONFIGURATION_BLOCKED')
        self.assertEqual(metadata['analysisTasks'], ['MENU_CLASSIFICATION'])

    def test_new_capture_rejudges_but_keeps_original_success_and_dispatch_generation(self):
        old = self.record()
        summary = Path(self.temp.name) / 'summary.json'
        def submit(server, record, auto_apply):
            self.assertNotEqual(record['capture_id'], old['capture_id'])
            self.assertEqual(record['request']['attemptSeq'], 7)
            self.assertEqual(record['recovery']['attempts'], 1)
            return self.receipt(record['capture_id'], applied=1)
        with patch.object(worker.time, 'time', return_value=self.now), \
             patch.object(worker.time, 'sleep'), patch.object(worker, 'fetch_due') as due, \
             patch.object(worker, 'fetch_place', return_value=worker.FetchResult({}, False, 'SUCCESS')) as fetch, \
             patch.object(worker, 'submit_retained', side_effect=submit):
            self.assertEqual(worker.run('unused', 50, 1, 0, 0, spool=self.spool, summary_path=summary,
                auto_apply=True, capabilities={'automaticHoursPermissionsReady': True}), 0)
        due.assert_not_called()
        fetch.assert_called_once_with('123')
        self.assertEqual(self.spool.read(self.spool.path(old['capture_id']))['receipt'], old['receipt'])
        data = json.loads(summary.read_text())
        self.assertEqual(data['attempted'], 1)
        self.assertEqual(data['recovery_attempted'], 1)
        self.assertEqual(data['recovery_resolved'], 1)
        self.assertEqual(data['auto_apply_applied'], 1)

    def test_lost_ack_child_carries_budget_and_replays_same_identity_after_restart(self):
        self.record()
        summary = Path(self.temp.name) / 'replay-summary.json'
        with patch.object(worker.time, 'time', return_value=self.now), patch.object(worker.time, 'sleep'), \
             patch.object(worker, 'fetch_place', return_value=worker.FetchResult({}, False, 'SUCCESS')), \
             patch.object(worker, 'submit_retained', side_effect=worker.requests.Timeout()):
            self.assertEqual(worker.run('unused', 1, 1, 0, 0, spool=self.spool,
                auto_apply=True, capabilities={'automaticHoursPermissionsReady': True}), 1)
        pending = list(CaptureSpool(self.spool.root).pending())
        self.assertEqual(len(pending), 1)
        self.assertEqual(pending[0]['recovery']['attempts'], 1)
        capture = pending[0]['capture_id']
        with patch.object(worker.time, 'time', return_value=self.now), \
             patch.object(worker, 'fetch_due', return_value=[]), patch.object(worker, 'fetch_place') as fetch, \
             patch.object(worker, 'submit_retained', return_value=self.receipt(capture, alreadyApplied=1)) as submit:
            self.assertEqual(worker.run('unused', 1, 1, 0, 0, spool=self.spool, summary_path=summary, auto_apply=True), 0)
        self.assertEqual(submit.call_args.args[1]['capture_id'], capture)
        fetch.assert_not_called()
        self.assertEqual(HoldRecovery(self.spool, self.now).counts()['recovery_configuration_blocked'], 0)
        data = json.loads(summary.read_text())
        self.assertEqual(data['recovery_resolved'], 1)
        self.assertEqual(data['recovery_attempted'], 0)
        self.assertEqual(data['attempted'], 0)
        self.assertEqual(data['auto_apply_already_applied'], 1)
        # A completed delivery is not counted again in the following run.
        with patch.object(worker, 'fetch_due', return_value=[]), patch.object(worker, 'fetch_place') as fetch, \
             patch.object(worker, 'submit_retained') as submit:
            self.assertEqual(worker.run('unused', 1, 1, 0, 0, spool=self.spool, summary_path=summary, auto_apply=True), 0)
        fetch.assert_not_called()
        submit.assert_not_called()
        self.assertEqual(json.loads(summary.read_text())['recovery_resolved'], 0)

    def test_fetch_failure_keeps_budget_and_a_new_due_observation_does_not_reset_it(self):
        self.record()
        with patch.object(worker.time, 'time', return_value=self.now), patch.object(worker.time, 'sleep'), \
             patch.object(worker, 'fetch_place', side_effect=worker.requests.Timeout()), \
             patch.object(worker, 'report_failure') as failure:
            worker.run('unused', 1, 1, 0, 0, spool=self.spool, auto_apply=True,
                       capabilities={'automaticHoursPermissionsReady': True})
        failure.assert_not_called()  # Recovery uses its own budget, not an unowned sync-state lease.
        restarted = HoldRecovery(self.spool, self.now)
        self.assertEqual(restarted.due(self.now, True), [])
        self.assertEqual(restarted.counts()['hold_permission_required'], 1)
        self.assertEqual(restarted.metadata({'storeId': 1, 'source': 'NAVER', 'externalId': '123'})['attempts'], 1)

    def test_pending_delivery_counts_only_resolved_recovery_captures(self):
        cases = [(False, (), {}, 0),
                 (True, ('SOURCE_UNKNOWN',), {}, 0),
                 (True, (), {'unknown': 1}, 1),
                 (True, (), {'failed': 1}, 1)]
        for index, (is_recovery, reasons, counts, exit_code) in enumerate(cases):
            with self.subTest(is_recovery=is_recovery, reasons=reasons, counts=counts):
                self.spool = CaptureSpool(Path(self.temp.name) / f'pending-{index}')
                old = self.record()
                recovery = HoldRecovery(self.spool, self.now)
                parent = recovery.reserve(recovery.due(self.now, True)[0], self.now) if is_recovery else None
                capture = str(uuid4())
                request = {**old['request'], 'observationId': capture,
                           'observedAt': datetime.fromtimestamp(self.now, timezone.utc).isoformat()}
                self.spool.retain(capture, request, {},
                                  recovery=recovery.metadata(request, parent) if is_recovery else None)
                summary = Path(self.temp.name) / f'pending-{index}.json'
                with patch.object(worker, 'fetch_due', return_value=[]), patch.object(worker, 'fetch_place') as fetch, \
                     patch.object(worker, 'submit_retained', return_value=self.receipt(capture, reasons, **counts)):
                    self.assertEqual(worker.run('unused', 1, 1, 0, 0, spool=self.spool,
                        summary_path=summary, auto_apply=True), exit_code)
                fetch.assert_not_called()
                data = json.loads(summary.read_text())
                self.assertEqual(data['recovery_resolved'], 0)
                self.assertEqual(data['recovery_attempted'], 0)

    def test_time_box_blocks_recovery_before_new_fetch(self):
        self.record()
        with patch.object(worker.time, 'time', return_value=self.now), \
             patch.object(worker.time, 'monotonic', side_effect=[0, 3601]), \
             patch.object(worker, 'fetch_place') as fetch:
            self.assertEqual(worker.run('unused', 1, 1, 0, 0, max_hours=1, spool=self.spool, auto_apply=True,
                capabilities={'automaticHoursPermissionsReady': True}), 0)
        fetch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
