"""One durable orchestration step, with no model or publication capability.

The scheduler role needs SELECT on captures/incidents/runs/replay/proposals and
review/release/execution tables; INSERT/UPDATE on orchestration_job, INSERT on
orchestration_event; review manifest/publication/outbox and release-controller
grants remain separately provisioned. It needs no budget-ledger mutation, no
capture/proposal UPDATE and no serving schema permissions. The private Java
apply process uses its own configured role. No migration grants broad rights.

A session advisory lock spans the selected step; its loss cannot authorize a
second execution. The next owner freezes interrupted RUNNING jobs as UNKNOWN.
Only an explicit read-only reconciliation can settle one of those jobs.
"""
from contextlib import contextmanager
from datetime import timedelta
import hashlib
from pathlib import Path
import subprocess

from psycopg2.extras import Json, RealDictCursor

from .approval import (ApprovalEvidence, ApprovalHold, COMMIT, ID, ReviewManifest,
                       PublicationBinding, code_tree_digest, content_digest)
from .full_registry import git_inventory
from .github_review import DeliveryUnknown
from .release import ReleaseHold, TERMINAL
from .review_preparation import PgReplayProposalLoader

KINDS = ('PREPARE_PARSER', 'ADVANCE_REVIEW', 'PREPARE_DATA', 'PROMOTE_BASELINE')
PHASES = {'all': KINDS, 'prepare': KINDS[:1], 'approval': KINDS[1:2], 'data-review': KINDS[2:3], 'baseline': KINDS[3:]}
CURRENT_SCOPE = 'PostgreSQL-dispatched current parser repair evaluation'


def require(value, code):
    if not value:
        raise ApprovalHold(code)


def key(kind, subject):
    return content_digest({'kind': kind, 'subject': subject})


class PgOrchestrationStore:
    def __init__(self, connect, service_id):
        require(isinstance(service_id, str) and ID.fullmatch(service_id), 'ORCHESTRATION_SERVICE_ID')
        self.connect, self.service_id = connect, service_id

    @contextmanager
    def lock(self):
        connection = self.connect()
        number = int.from_bytes(hashlib.sha256(('agent-orchestration:' + self.service_id).encode()).digest()[:8], 'big') & ((1 << 63)-1)
        try:
            with connection, connection.cursor() as cursor:
                cursor.execute("SET statement_timeout='5s'")
                cursor.execute('SELECT pg_try_advisory_lock(%s)', (number,))
                require(cursor.fetchone()[0], 'ORCHESTRATION_BUSY')
            yield connection
        finally:
            # Closing this exact session releases its advisory lock. Never reconnect.
            connection.close()

    def preview(self):
        connection = self.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor() as cursor:
                cursor.execute('SELECT state,count(*) FROM response_agent_orchestration_job WHERE service_id=%s GROUP BY state', (self.service_id,))
                return {'mode': 'PREVIEW', 'states': dict(cursor.fetchall()), 'side_effects': 0}
        finally:
            connection.close()

    def enqueue(self, cursor, kind, subject, run_id, request):
        identity = key(kind, subject)
        cursor.execute('''INSERT INTO response_agent_orchestration_job(job_id,service_id,kind,subject,run_id,request)
            VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING''',
            (identity, self.service_id, kind, subject, run_id, Json(request)))
        cursor.execute('SELECT service_id,kind,subject,run_id,request FROM response_agent_orchestration_job WHERE job_id=%s', (identity,))
        row = cursor.fetchone()
        require(row is not None and tuple(row[:3]) == (self.service_id, kind, subject)
                and str(row[3]) == str(run_id) and row[4] == request, 'ORCHESTRATION_REQUEST_CHANGED')

    def discover(self, connection, kinds, limit):
        remaining = limit
        with connection, connection.cursor() as cursor:
            if 'PROMOTE_BASELINE' in kinds and remaining:
                cursor.execute('''SELECT r.manifest_digest,m.run_id FROM response_agent_release_operation r
                    JOIN response_agent_review_manifest m USING(manifest_digest)
                    WHERE r.service_id=%s AND r.state='ACTIVE' AND r.pending_action IS NULL
                    AND NOT EXISTS (SELECT 1 FROM response_agent_orchestration_job j WHERE j.kind='PROMOTE_BASELINE' AND j.subject=r.manifest_digest)
                    ORDER BY r.updated_at,r.operation_id LIMIT %s''', (self.service_id, remaining))
                rows = cursor.fetchall()
                for digest, run in rows:
                    self.enqueue(cursor, 'PROMOTE_BASELINE', digest, str(run), {'manifest_digest': digest})
                remaining -= len(rows)
            if 'PREPARE_PARSER' in kinds and remaining:
                cursor.execute('''SELECT run_id FROM response_agent_run r WHERE state='AWAITING_REVIEW'
                    AND checkpoint->>'scope'=%s AND checkpoint ? 'frozen_current_bundle'
                    AND NOT EXISTS (SELECT 1 FROM response_agent_orchestration_job j WHERE j.kind='PREPARE_PARSER' AND j.subject=r.run_id::text)
                    ORDER BY created_at,run_id LIMIT %s''', (CURRENT_SCOPE, remaining))
                rows = cursor.fetchall()
                for (run,) in rows:
                    self.enqueue(cursor, 'PREPARE_PARSER', str(run), str(run), {'run_id': str(run)})
                remaining -= len(rows)
            if 'ADVANCE_REVIEW' in kinds and remaining:
                cursor.execute('''SELECT m.manifest_digest,m.run_id FROM response_agent_review_manifest m
                    JOIN response_agent_review_publication p USING(manifest_digest)
                    WHERE m.kind IN ('PARSER_REPAIR','DATA_APPLY') AND m.review_state='VALIDATED'
                    AND NOT EXISTS (SELECT 1 FROM response_agent_orchestration_job j WHERE j.kind='ADVANCE_REVIEW' AND j.subject=m.manifest_digest)
                    ORDER BY p.published_at,m.manifest_digest LIMIT %s''', (remaining,))
                rows = cursor.fetchall()
                for digest, run in rows:
                    self.enqueue(cursor, 'ADVANCE_REVIEW', digest, str(run), {'manifest_digest': digest})
                remaining -= len(rows)
            if 'PREPARE_DATA' in kinds and remaining:
                cursor.execute('''SELECT r.operation_id,r.manifest_digest,m.run_id FROM response_agent_release_operation r
                    JOIN response_agent_review_manifest m USING(manifest_digest)
                    WHERE r.service_id=%s AND r.state IN ('ACTIVE','REPLAY_HELD') AND r.pending_action IS NULL
                    AND r.receipt->'replay_receipt'->>'outcome'='DONE'
                    AND r.receipt->'replay_receipt'->>'complete'='true'
                    AND NOT EXISTS (SELECT 1 FROM response_agent_orchestration_job j WHERE j.kind='PREPARE_DATA' AND j.subject=r.operation_id)
                    ORDER BY r.updated_at,r.operation_id LIMIT %s''', (self.service_id, remaining))
                rows = cursor.fetchall()
                for operation, digest, run in rows:
                    self.enqueue(cursor, 'PREPARE_DATA', operation, str(run), {'operation_id': operation, 'manifest_digest': digest})
                remaining -= len(rows)
        return limit - remaining

    def _event(self, cursor, job_id, event, detail):
        cursor.execute('''INSERT INTO response_agent_orchestration_event(job_id,sequence,event,detail)
            SELECT %s,COALESCE(MAX(sequence),0)+1,%s,%s FROM response_agent_orchestration_event WHERE job_id=%s RETURNING sequence''',
            (job_id, event, Json(detail), job_id))
        row = cursor.fetchone()
        return row['sequence'] if isinstance(row,dict) else row[0]

    def interrupted(self, connection):
        from .status_notification import enqueue_status
        with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute("UPDATE response_agent_orchestration_job SET state='UNKNOWN',result=%s,updated_at=CURRENT_TIMESTAMP WHERE service_id=%s AND state='RUNNING' RETURNING *",
                           (Json({'reason_code': 'INTERRUPTED_OUTCOME_UNKNOWN'}), self.service_id))
            for job in cursor.fetchall():
                sequence = self._event(cursor, job['job_id'], 'INTERRUPTED', {'state': 'UNKNOWN'})
                enqueue_status(cursor, job, sequence, 'UNKNOWN', job['result'])

    def select(self, connection, kinds, reconcile=False):
        with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('''SELECT * FROM response_agent_orchestration_job WHERE service_id=%s AND kind=ANY(%s)
                AND ((%s AND state='UNKNOWN') OR (NOT %s AND state IN ('READY','WAITING') AND next_attempt_at<=CURRENT_TIMESTAMP))
                ORDER BY CASE WHEN kind='PROMOTE_BASELINE' THEN 0 ELSE 1 END,next_attempt_at,created_at,job_id LIMIT 1 FOR UPDATE''', (self.service_id, list(kinds), reconcile, reconcile))
            row = cursor.fetchone()
            if not row:
                return None
            if not reconcile:
                cursor.execute("UPDATE response_agent_orchestration_job SET state='RUNNING',attempt=attempt+1,updated_at=CURRENT_TIMESTAMP WHERE job_id=%s RETURNING attempt", (row['job_id'],))
                row['attempt'] = cursor.fetchone()['attempt']
                self._event(cursor, row['job_id'], 'START', {'attempt': row['attempt']})
            return dict(row)

    def finish(self, connection, job, state, result, delay, reconcile=False):
        from .status_notification import enqueue_status
        with connection, connection.cursor() as cursor:
            cursor.execute('''UPDATE response_agent_orchestration_job SET state=%s,result=%s,
                next_attempt_at=CURRENT_TIMESTAMP+(%s * interval '1 second'),updated_at=CURRENT_TIMESTAMP
                WHERE job_id=%s AND state=%s AND attempt=%s''',
                (state, Json(result), delay, job['job_id'], 'UNKNOWN' if reconcile else 'RUNNING', job['attempt']))
            require(cursor.rowcount == 1, 'ORCHESTRATION_ATTEMPT_CHANGED')
            sequence = self._event(cursor, job['job_id'], 'RECONCILED' if reconcile else 'FINISH', {'state': state, **result})
            enqueue_status(cursor, job, sequence, state, result)
        return {'job_id': job['job_id'], 'kind': job['kind'], 'state': state, **result}


class VerifiedMergeResolver:
    """Fetch only a previously authenticated consumed merge SHA into a fixed repo."""
    def __init__(self, preparer):
        self.preparer = preparer

    def resolve(self, manifest, approval):
        evidence = ApprovalEvidence(**approval['evidence'])
        require(evidence.digest == approval['approval_digest'] and evidence.manifest_digest == manifest.digest
                and evidence.repository == self.preparer.repository and evidence.scope == 'DEPLOY_REPLAY_PROPOSE'
                and approval['state'] == 'CONSUMED' and COMMIT.fullmatch(evidence.merge_commit_sha), 'CONSUMED_MERGE_REQUIRED')
        commit = evidence.merge_commit_sha
        origin = self.preparer._git('remote', 'get-url', 'origin').decode().strip()
        repository = self.preparer.repository
        require(origin in {f'https://github.com/{repository}.git', f'https://github.com/{repository}', f'git@github.com:{repository}.git'}, 'ORIGIN_REPOSITORY_MISMATCH')
        check = subprocess.run(['git', '-C', str(self.preparer.root), 'cat-file', '-e', commit+'^{commit}'], capture_output=True, timeout=10)
        if check.returncode:
            # No remote branch/reset/checkout/merge and no candidate-provided command.
            result = subprocess.run(['git', '-C', str(self.preparer.root), '-c', 'core.hooksPath=/dev/null',
                'fetch', '--no-tags', 'origin', commit], capture_output=True, timeout=60,
                env={'PATH': '/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin', 'HOME': str(Path.home()), 'GIT_TERMINAL_PROMPT': '0'})
            require(result.returncode == 0, 'VERIFIED_MERGE_UNAVAILABLE')
        require(self.preparer._git('rev-parse', commit+'^{commit}').decode().strip() == commit, 'VERIFIED_MERGE_CHANGED')
        require(code_tree_digest(git_inventory(self.preparer.root, commit), manifest.document['review_id'])
                == manifest.document['candidate_tree_digest'], 'VERIFIED_MERGE_TREE_CHANGED')
        return commit


class Orchestrator:
    def __init__(self, *, store, candidate, release, data, reviews, merge_resolver, promoter=None,
                 poll_seconds=60, review_ttl_seconds=604800, discovery_limit=100):
        require(type(poll_seconds) is int and 10 <= poll_seconds <= 86400, 'POLL_INTERVAL_INVALID')
        require(type(review_ttl_seconds) is int and 3600 <= review_ttl_seconds <= 604800, 'REVIEW_TTL_INVALID')
        require(type(discovery_limit) is int and 1 <= discovery_limit <= 100, 'DISCOVERY_BOUND_INVALID')
        self.store, self.candidate, self.release, self.data = store, candidate, release, data
        self.reviews, self.merge_resolver = reviews, merge_resolver
        self.promoter = promoter
        self.poll_seconds, self.review_ttl_seconds, self.discovery_limit = poll_seconds, review_ttl_seconds, discovery_limit

    def _review(self, digest):
        connection = self.store.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT m.document,p.repository,p.pr_number,p.head_sha FROM response_agent_review_manifest m
                    JOIN response_agent_review_publication p USING(manifest_digest) WHERE m.manifest_digest=%s''', (digest,))
                row = cursor.fetchone()
                require(row is not None, 'PUBLISHED_MANIFEST_REQUIRED')
                manifest = ReviewManifest(row['document'])
                require(manifest.digest == digest and manifest.document['kind'] in {'PARSER_REPAIR', 'DATA_APPLY'}, 'EXACT_MANIFEST_REQUIRED')
                return manifest, PublicationBinding(row['repository'], row['pr_number'], row['head_sha'], digest)
        finally:
            connection.close()

    def _data_context(self, job):
        connection = self.store.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT r.*,m.document,a.evidence,a.state AS approval_state,a.operation_id AS approval_operation
                    FROM response_agent_release_operation r JOIN response_agent_review_manifest m USING(manifest_digest)
                    JOIN response_agent_review_approval a USING(approval_digest)
                    WHERE r.operation_id=%s AND r.manifest_digest=%s AND r.service_id=%s''',
                    (job['request']['operation_id'], job['request']['manifest_digest'], self.store.service_id))
                row = cursor.fetchone()
                require(row is not None and row['state'] in {'ACTIVE','REPLAY_HELD'} and row['pending_action'] is None
                        and row['approval_state'] == 'CONSUMED' and row['approval_operation'] == row['operation_id'], 'REPLAY_APPROVAL_COMPLETION_REQUIRED')
                manifest = ReviewManifest(row['document'])
                require(manifest.digest == job['request']['manifest_digest'] and manifest.document['kind'] == 'PARSER_REPAIR'
                        and manifest.document['run_id'] == str(job['run_id']), 'REPLAY_MANIFEST_CHANGED')
                evidence = {'evidence': row['evidence'], 'approval_digest': row['approval_digest'], 'state': row['approval_state']}
                return manifest, row['receipt'].get('replay_receipt', {}), evidence
        finally:
            connection.close()

    def _prepare_data(self, job, reconcile):
        manifest, receipt, approval = self._data_context(job)
        review_id = 'data-' + content_digest({'parser_manifest': manifest.digest, 'operation': job['subject']})[:40]
        connection = self.store.connect()
        try:
            # Outbox and manifest commit atomically; a lost response can read the exact durable result.
            with connection, connection.cursor() as cursor:
                cursor.execute("SELECT manifest_digest,document FROM response_agent_review_manifest WHERE run_id=%s AND kind='DATA_APPLY' AND revision=%s", (job['run_id'], manifest.document['revision']))
                existing = cursor.fetchone()
                if existing:
                    value = ReviewManifest(existing[1])
                    require(value.digest == existing[0] and value.document['review_id'] == review_id
                            and value.document['validation_digest'] == manifest.document['validation_digest'], 'DATA_REVIEW_IDENTITY_CHANGED')
                    cursor.execute('SELECT 1 FROM response_agent_notification_outbox WHERE manifest_digest=%s', (value.digest,))
                    require(cursor.fetchone() is not None, 'DATA_REVIEW_OUTBOX_MISSING')
                    return 'DONE', {'manifest_digest': value.digest, 'outcome': 'PREPARED'}
            if reconcile:
                return 'UNKNOWN', {'reason_code': 'DATA_REVIEW_COMPLETION_UNCONFIRMED'}
            base_commit = self.merge_resolver.resolve(manifest, approval)
            expires = (job['created_at'] + timedelta(seconds=self.review_ttl_seconds)).isoformat()
            with connection, connection.cursor() as cursor:
                prepared = self.reviews.data(cursor, validation_digest=manifest.document['validation_digest'], replay_receipt=receipt,
                    proposal_loader=PgReplayProposalLoader(self.store.connect), review_id=review_id, base_commit=base_commit,
                    incident_id=manifest.document['incident_id'], run_id=str(job['run_id']), revision=manifest.document['revision'], expires_at=expires)
                return 'DONE', {'manifest_digest': prepared.manifest.digest, 'outcome': 'PREPARED'}
        finally:
            connection.close()

    def _run(self, job, reconcile):
        if job['kind'] == 'PROMOTE_BASELINE':
            require(self.promoter is not None, 'BASELINE_PROMOTION_ADAPTER_REQUIRED')
            result = (self.promoter.status(job['subject']) if reconcile else self.promoter.promote(job['subject']))
            outcome = result.get('outcome')
            require(outcome in {'PROMOTED','HELD','UNKNOWN','NOT_STARTED'}, 'BASELINE_PROMOTION_RESULT')
            state = {'PROMOTED':'DONE','HELD':'HELD','UNKNOWN':'UNKNOWN','NOT_STARTED':'UNKNOWN'}[outcome]
            return state, {k: v for k, v in result.items() if k in {'outcome','pointer_digest','reason_code'}}
        if job['kind'] == 'PREPARE_PARSER':
            result = self.candidate.status(str(job['run_id'])) if reconcile else self.candidate.prepare(str(job['run_id']))
            outcome = result.get('outcome')
            require(outcome in {'PREPARED','HELD','UNKNOWN','NOT_STARTED'}, 'CANDIDATE_PREPARATION_RESULT')
            state = {'PREPARED':'DONE','HELD':'HELD','UNKNOWN':'UNKNOWN','NOT_STARTED':'UNKNOWN'}[outcome]
            return state, {k: v for k, v in result.items() if k in {'outcome','manifest_digest','validation_digest','reason_code','review_id'}}
        if job['kind'] == 'PREPARE_DATA':
            return self._prepare_data(job, reconcile)
        manifest, binding = self._review(job['request']['manifest_digest'])
        if manifest.document['kind'] == 'DATA_APPLY':
            result = self.data.status(manifest) if reconcile else self.data.advance(manifest, binding)
            state = 'DONE' if result.get('state') == 'APPLIED' else 'UNKNOWN' if result.get('state') in {'RECOVERY_REQUIRED','UNKNOWN','NOT_STARTED'} else 'HELD'
        else:
            result = self.release.reconcile(manifest, binding) if reconcile else self.release.advance(manifest, binding)
            if result.get('pending_action') or result.get('state') == 'RECOVERY_REQUIRED':
                state = 'UNKNOWN'
            elif result.get('state') in TERMINAL:
                state = 'DONE' if result['state'] == 'ACTIVE' else 'HELD'
                # Neither a failed replay nor a fresh rollback generation has
                # authority to become the next parser baseline automatically.
                # Keep the worker's generation guard closed and notify the
                # operator of the recovery required before the next incident.
                if result['state'] in {'REPLAY_HELD', 'ROLLED_BACK'}:
                    result = {**result, 'reason_code': {
                        'REPLAY_HELD': 'REPLAY_REVIEW_REQUIRED',
                        'ROLLED_BACK': 'BASELINE_RECOVERY_REQUIRED',
                    }[result['state']]}
            else:
                state = 'WAITING'
        return state, {k: v for k, v in result.items() if k in {'state','operation_id','pending_action','hold_code','reason_code','applied_count','operating_writes'}} | {'controller_state': result.get('state')}

    def tick(self, *, phase='all', reconcile=False):
        require(phase in PHASES, 'ORCHESTRATION_PHASE')
        with self.store.lock() as connection:
            self.store.interrupted(connection)
            if not reconcile:
                self.store.discover(connection, PHASES[phase], self.discovery_limit)
            job = self.store.select(connection, PHASES[phase], reconcile)
            if job is None:
                return {'state': 'IDLE', 'mode': 'RECONCILE' if reconcile else 'LIVE'}
            try:
                state, result = self._run(job, reconcile)
            except (ApprovalHold, ReleaseHold) as error:
                code = str(error)
                # These failures occur before any effect. All other violations stop this job.
                state = 'WAITING' if code in {'PR_NOT_MERGED', 'SERVICE_BUSY'} and not reconcile else 'HELD'
                if reconcile or any(word in code for word in ('UNKNOWN', 'UNCONFIRMED')):
                    state = 'UNKNOWN'
                result = {'reason_code': code}
            except DeliveryUnknown:
                # The GitHub adapter only reads here. No approval was manufactured.
                state, result = ('UNKNOWN' if reconcile else 'WAITING'), {'reason_code': 'APPROVAL_READ_UNAVAILABLE'}
            except Exception:
                state, result = 'UNKNOWN', {'reason_code': 'ORCHESTRATION_OUTCOME_UNKNOWN'}
            result.pop('state', None)
            return self.store.finish(connection, job, state, result, self.poll_seconds, reconcile)
