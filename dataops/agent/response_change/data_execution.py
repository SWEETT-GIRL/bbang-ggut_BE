"""Separate merged DATA_APPLY authority and one-shot private Java writer.

The service remains read-only. Java holds a shared release lock for the complete
apply lifetime. A durable launch intent prevents retrying an interrupted child;
status is derived from proposal operations, never from its exit code alone.
"""
from dataclasses import dataclass
import os
from pathlib import Path
import subprocess
import time
import uuid

from psycopg2.extras import RealDictCursor

from .approval import PgReviewStore, content_digest
from .host_runtime import atomic_write, private_file, private_directory, require, write_json
from .java_bridge import JavaBridgeConfig
from .release import _jar_digest, ReleaseHold


class ExactDataApplyController:
    def __init__(self, *, connect, registry, provider, verifier, config):
        config.validate()
        self.connect, self.registry, self.provider, self.verifier, self.config = connect, registry, provider, verifier, config
        self.store = PgReviewStore(connect)

    def _context(self, manifest):
        doc = manifest.document
        require(doc['kind'] == 'DATA_APPLY' and doc['scope'] == 'APPLY_EXACT_MANIFEST', 'DATA_APPROVAL_REQUIRED')
        candidate = self.registry.validated_candidate(doc['validation_digest'])
        require(doc['base_release'] == candidate['release_id'] and doc['artifact_sha256'] == candidate['artifact_sha256']
            and doc['structure_profile_digest'] == candidate['profile_digest'] and doc['semantic_contract_digest'] == candidate['contract_digest'],
            'DATA_CANDIDATE_CHANGED')
        require(all(item['release_id'] == candidate['release_id'] for item in doc['targets']), 'DATA_TARGET_RELEASE_CHANGED')
        replay_ids = {item['reprocess_item_id'] for item in doc['targets']}
        require(len(replay_ids) == 1, 'ONE_REPLAY_OPERATION_REQUIRED')
        connection = self.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT b.*,s.fencing_epoch FROM response_agent_execution_binding b
                    JOIN response_agent_release_service s USING(service_id)
                    WHERE b.replay_operation_id=%s AND b.validation_digest=%s''', (next(iter(replay_ids)), doc['validation_digest']))
                binding = cursor.fetchone()
                require(binding is not None and binding['release_id'] == candidate['release_id']
                    and binding['artifact_digest'] == candidate['artifact_sha256']
                    and all(binding[key] == candidate[key] for key in
                            ('generation', 'parser_digest', 'profile_digest', 'contract_digest', 'database_identity')),
                    'DATA_REPLAY_BINDING_CHANGED')
                return candidate, dict(binding)
        finally:
            connection.close()

    def advance(self, manifest, publication):
        candidate, binding = self._context(manifest)
        operation = str(uuid.uuid5(uuid.NAMESPACE_URL, 'response-data-apply:' + manifest.digest))
        frozen = {'manifest_digest': manifest.digest, 'publication': publication.__dict__, 'candidate': candidate,
                  'release_operation_id': binding['release_operation_id'], 'operation_id': operation}
        request_digest = content_digest(frozen)
        # Read before GitHub so even an expired/revoked approval's previous outcome can be reported.
        existing = self._existing(manifest.digest)
        if existing is not None:
            require(existing['request_digest'] == request_digest and str(existing['operation_id']) == operation, 'DATA_EXECUTION_REBOUND')
            return self.status(manifest)
        evidence = self.verifier.evaluate(manifest, publication, self.provider.read_snapshot(publication, manifest))
        require(evidence.scope == 'APPLY_EXACT_MANIFEST', 'DATA_APPROVAL_REQUIRED')
        connection = self.connect()
        try:
            with connection, connection.cursor() as cursor:
                approval = self.store.approve(cursor, evidence)
                cursor.execute('''INSERT INTO response_agent_data_execution(approval_digest,operation_id,manifest_digest,
                    release_operation_id,request_digest,state) VALUES (%s,%s,%s,%s,%s,'INTENT')
                    ON CONFLICT DO NOTHING RETURNING approval_digest''',
                    (approval, operation, manifest.digest, binding['release_operation_id'], request_digest))
                claimed = cursor.fetchone() is not None
        finally:
            connection.close()
        if not claimed:
            existing = self._existing(manifest.digest)
            require(existing is not None and existing['request_digest'] == request_digest, 'DATA_EXECUTION_REBOUND')
            return self.status(manifest)
        directory = private_directory(self.config.directory / ('apply-' + operation))
        write_json(directory / 'intent.json', frozen)
        exit_code, state = None, 'UNKNOWN'
        try:
            exit_code = self._invoke(candidate, binding, evidence.digest, operation, directory)
            state = 'FINISHED'
        finally:
            connection = self.connect()
            try:
                with connection, connection.cursor() as cursor:
                    cursor.execute('''UPDATE response_agent_data_execution SET state=%s,exit_code=%s,updated_at=CURRENT_TIMESTAMP
                        WHERE approval_digest=%s AND request_digest=%s AND state='INTENT' ''', (state, exit_code, evidence.digest, request_digest))
                    require(cursor.rowcount == 1, 'DATA_EXECUTION_OUTCOME_UNKNOWN')
            finally:
                connection.close()
        return self.status(manifest)

    def _invoke(self, candidate, binding, approval, operation, directory):
        artifact = candidate['artifact_path']
        require(_jar_digest(artifact) == candidate['artifact_sha256'], 'DATA_ARTIFACT_CHANGED')
        argv = [str(self.config.java), '-Dloader.main=sweetgirl.dataops.agentapply.AgentManifestApplyMain', '-cp', artifact,
            'org.springframework.boot.loader.launch.PropertiesLauncher',
            '--spring.config.additional-location=file:' + str(self.config.properties),
            '--spring.main.web-application-type=none', '--spring.flyway.enabled=false', '--spring.sql.init.mode=never',
            '--spring.jpa.hibernate.ddl-auto=validate', '--dataops.serving.write-enabled=false', '--dataops.reconcile.mode=PROPOSAL_ONLY',
            '--dataops.agent.runtime.enabled=false', '--dataops.agent.execution.enabled=true',
            '--dataops.agent.replay.enabled=false', '--dataops.agent.replay.action=STATUS', '--dataops.agent.apply.enabled=true',
            '--dataops.agent.execution.release-operation-id=' + binding['release_operation_id'],
            '--dataops.agent.execution.manifest-digest=' + binding['manifest_digest'],
            '--dataops.agent.execution.generation=' + binding['generation'],
            '--dataops.agent.execution.fence-epoch=' + str(binding['fencing_epoch']),
            '--dataops.agent.apply.approval-digest=' + approval, '--dataops.agent.apply.operation-id=' + operation]
        # jdbc-url/username/password for the private writer are only in the private properties file.
        write_json(directory / 'invocation.json', {'argv': argv})
        log = directory / 'runner.log'; atomic_write(log, b''); process = None
        try:
            with log.open('ab') as output:
                process = subprocess.Popen(argv, cwd=self.config.directory, stdout=output, stderr=subprocess.STDOUT,
                    env={'PATH': '/usr/bin:/bin', 'HOME': str(Path.home()), 'LANG': 'C.UTF-8'}, start_new_session=True)
                try:
                    return process.wait(timeout=self.config.timeout_seconds)
                except subprocess.TimeoutExpired:
                    raise ReleaseHold('DATA_RUNNER_TIMEOUT_UNKNOWN') from None
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)

    def _existing(self, manifest_digest):
        connection = self.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('SELECT * FROM response_agent_data_execution WHERE manifest_digest=%s', (manifest_digest,))
                rows = cursor.fetchall(); require(len(rows) <= 1, 'DATA_EXECUTION_NOT_UNIQUE')
                return dict(rows[0]) if rows else None
        finally:
            connection.close()

    def status(self, manifest):
        require(manifest.document['kind'] == 'DATA_APPLY', 'DATA_APPROVAL_REQUIRED')
        execution = self._existing(manifest.digest)
        if execution is None:
            return {'state': 'NOT_STARTED', 'manifest_digest': manifest.digest, 'items': []}
        connection = self.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT i.proposal_id,i.proposal_operation_id,q.operation_id,q.status,q.apply_outcome_reason
                    FROM response_agent_data_apply_item i JOIN change_proposal q ON q.id=i.proposal_id
                    WHERE i.approval_digest=%s AND i.operation_id=%s''', (execution['approval_digest'], execution['operation_id']))
                saved = {row['proposal_id']: row for row in cursor.fetchall()}
        finally:
            connection.close()
        ids = [row['proposal_id'] for row in manifest.document['targets']]
        require(set(saved) <= set(ids), 'DATA_EXECUTION_OUTSIDE_SCOPE')
        items = []
        for proposal_id in ids:
            row = saved.get(proposal_id)
            if row is None:
                status, reason = ('NOT_ATTEMPTED' if execution['state'] == 'FINISHED' else 'UNKNOWN'), None
            elif row['proposal_operation_id'] != row['operation_id']:
                status, reason = 'UNKNOWN', 'PROPOSAL_OPERATION_CHANGED'
            else:
                status = {'APPROVED': 'APPLIED', 'FAILED': 'FAILED', 'UNKNOWN': 'UNKNOWN', 'APPLYING': 'UNKNOWN'}.get(row['status'], 'UNKNOWN')
                reason = row['apply_outcome_reason']
            items.append({'proposal_id': proposal_id, 'status': status, 'reason': reason})
        if any(row['status'] == 'UNKNOWN' for row in items): state = 'RECOVERY_REQUIRED'
        elif all(row['status'] == 'APPLIED' for row in items): state = 'APPLIED'
        else: state = 'HELD'
        return {'state': state, 'manifest_digest': manifest.digest, 'operation_id': str(execution['operation_id']),
                'process_state': execution['state'], 'exit_code': execution['exit_code'], 'items': items,
                'applied_count': sum(row['status'] == 'APPLIED' for row in items)}
