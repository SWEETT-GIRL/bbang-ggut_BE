"""Promote a completed analysis into an exact parser-review request.

No model, network publication, service mutation or serving write is available
here. PostgreSQL owns terminal analysis and publication; the private registry
owns immutable preparation inputs and validated artifacts. An interrupted
preparation can be inspected without repeating any model request.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid

from psycopg2.extras import RealDictCursor

from .approval import ApprovalHold, ID, ReviewManifest, content_digest
from . import current_bundle, current_manifest, current_validator
from .dispatch import Hold, MAX_PAYLOAD_BYTES
from .full_registry import PARSED_FIELDS, TARGET_FIELDS, file_digest, require
from .ledger import digest

VERSION = 'completed-current-run-to-parser-review-v1'


def _time(value):
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(result.tzinfo is not None, 'PREPARATION_TIMESTAMP_ZONE')
        return result.astimezone(timezone.utc)
    except (ValueError, TypeError, AttributeError):
        raise ApprovalHold('PREPARATION_TIMESTAMP_REQUIRED') from None


def _private_json(path, root, maximum):
    path, root = Path(path), Path(root)
    require(path.is_absolute() and path.resolve().is_relative_to(root.resolve())
            and path.resolve() == path and not any(p.is_symlink() for p in (path, *path.parents))
            and path.is_file() and path.stat().st_size <= maximum, 'PREPARATION_ARTIFACT_PATH')
    try:
        return json.loads(path.read_text())
    except (ValueError, OSError):
        raise ApprovalHold('PREPARATION_ARTIFACT_INVALID') from None


def reviewed_whole_capture_oracles(*, bundle, captures, base, manifest, output):
    """Execute only the committed base on a narrowly reviewed alias counterpart.

    Candidate source and candidate expected values are deliberately absent from
    this API. The whole original capture is retained for the later candidate
    check; only the baseline control's typename/cache refs are rewritten.
    """
    require(bundle.case.get('recipe') == current_bundle.RECIPE, 'REVIEWED_RECIPE_REQUIRED')
    require(base['source_commit'] == manifest['provenance']['git_commit']
            and base['artifact_sha256'] == manifest['jar_sha256']
            and base['parser_digest'] == manifest['parser_digest']
            and base['profile_digest'] == manifest['profile_digest'], 'ORACLE_BASE_RELEASE_CHANGED')
    snapshot = Path(bundle.manifest_path).parent / 'snapshot'
    require(all((snapshot / path).read_text() == bundle.files[path] for path in current_manifest.EDITABLE_PATHS),
            'ORACLE_BASE_SOURCE_CHANGED')
    profile = json.loads(bundle.files[current_manifest.PROFILE_PATH])
    by_id = {row['capture_id']: row for row in captures}
    targets = list(bundle.reprocess_targets)
    require(len(by_id) == len(captures) and set(by_id) == {row['capture_id'] for row in targets},
            'ORACLE_CAPTURE_SCOPE_CHANGED')
    fixtures, controls = [], {}
    for target in targets:
        sample = by_id[target['capture_id']]
        binding = {**{key: sample[key] for key in TARGET_FIELDS if key != 'observed_at'},
                   'observed_at': current_bundle._observation_time(sample['observed_at']).isoformat()}
        require(binding == target, 'ORACLE_CAPTURE_BINDING_CHANGED')
        require(sample.get('source') == 'NAVER' and sample.get('parser_digest') == bundle.deployment_parser_digest
                and sample.get('contract_version') == bundle.contract_version, 'ORACLE_CAPTURE_GENERATION_CHANGED')
        raw, evidence = current_bundle.reviewed_alias_counterpart(sample, profile)
        controls[target['capture_id']] = evidence
        fixtures.append({'id': target['capture_id'], 'external_id': target['external_id'], 'raw': raw})
    result = current_validator.compile_and_run(snapshot, {}, fixtures,
                                               Path(output), manifest['runtime'])
    require(result.get('status') == 'OK' and result.get('classes') == manifest['classes']
            and result.get('parser_digest') == base['parser_digest']
            and result.get('profile_digest') == base['profile_digest']
            and current_validator.regression_passes(result.get('regression', {}), manifest['baseline_regression']['found']),
            'ORACLE_COMMITTED_BASE_EXECUTION_FAILED')
    rows = result.get('cases', [])
    require(len(rows) == len(targets), 'ORACLE_BASE_CAPTURE_COUNT')
    oracles = {}
    for target, row in zip(targets, rows):
        require(row.get('id') == target['capture_id'] and 'error' not in row, 'ORACLE_BASE_CAPTURE_FAILED')
        actual = row.get('actual', {})
        expected = {key: actual[key] for key in PARSED_FIELDS if key in actual}
        diagnostic, evidence = actual.get('diagnostics', {}), actual.get('evidence', {})
        require(set(expected) == PARSED_FIELDS and diagnostic.get('assessment') == 'READY'
                and diagnostic.get('targetVerified') is True and diagnostic.get('menuAllowed') is True
                and diagnostic.get('modelEligible') is False and diagnostic.get('issueCodes') == []
                and evidence.get('menuComplete') is True and evidence.get('identityVerified') is True,
                'ORACLE_BASE_CAPTURE_NOT_READY')
        provenance = {'version': VERSION, 'recipe': current_bundle.RECIPE,
            'base_source_commit': base['source_commit'], 'base_artifact_sha256': base['artifact_sha256'],
            'base_parser_digest': base['parser_digest'], 'base_profile_digest': base['profile_digest'],
            'deployment_manifest_digest': bundle.manifest_digest, 'control': controls[target['capture_id']],
            'expected_digest': content_digest(expected),
            'recipe_source_sha256': file_digest(current_bundle.__file__), 'adapter_source_sha256': file_digest(__file__)}
        oracles[target['capture_id']] = {'target': target, 'payload_json': by_id[target['capture_id']]['payload'],
            'expected_parsed': expected, 'kind': 'REVIEWED_CONTRACT',
            'reference': current_bundle.RECIPE + ':whole-base-counterpart:' + content_digest(provenance),
            'provenance': provenance}
    return oracles


class CandidatePreparationService:
    def __init__(self, *, connect, registry, provider, reviews, repository, output_root, base_release,
                 revision=1, review_ttl_seconds=604800, canary_limit=20):
        require(isinstance(base_release, str) and ID.fullmatch(base_release), 'PREPARATION_BASE_RELEASE')
        require(type(revision) is int and 1 <= revision <= 100 and type(review_ttl_seconds) is int
                and 3600 <= review_ttl_seconds <= 604800 and type(canary_limit) is int and 1 <= canary_limit <= 20,
                'PREPARATION_POLICY_BOUNDS')
        self.connect, self.registry, self.provider, self.reviews = connect, registry, provider, reviews
        self.repository, self.output_root = Path(repository).resolve(), Path(output_root).resolve()
        self.base_release, self.revision = base_release, revision
        self.ttl, self.canary_limit = review_ttl_seconds, canary_limit

    def _key(self, run_id):
        try:
            require(str(uuid.UUID(run_id)) == run_id, 'PREPARATION_RUN_ID')
        except (ValueError, TypeError, AttributeError):
            raise ApprovalHold('PREPARATION_RUN_ID') from None
        return 'run-' + run_id + '-r' + str(self.revision)

    def _captures(self, ids):
        require(isinstance(ids, list) and 1 <= len(ids) <= 6, 'PREPARATION_CAPTURE_BOUNDS')
        db = self.connect()
        try:
            db.set_session(readonly=True)
            with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT capture_id,store_id,external_id,observed_at,attempt_seq,status,
                    source,parser_digest,contract_version,payload_digest,
                    CASE WHEN octet_length(payload)<=%s THEN payload ELSE NULL END AS payload
                    FROM response_capture WHERE capture_id=ANY(%s)''', (MAX_PAYLOAD_BYTES, ids))
                return list(cursor.fetchall())
        finally:
            db.close()

    def _run(self, db, run_id):
        with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('''SELECT r.*,i.source,i.diagnostics FROM response_agent_run r
                JOIN response_incident i ON i.fingerprint=r.fingerprint AND i.parser_digest=r.parser_digest
                    AND i.contract_version=r.contract_version WHERE r.run_id=%s''', (run_id,))
            row = cursor.fetchone()
        require(row is not None and row['state'] == 'AWAITING_REVIEW', 'COMPLETED_PATCH_RUN_REQUIRED')
        return row

    def _active_base(self, db, base):
        fields = ('release_id', 'generation', 'artifact_sha256', 'parser_digest', 'profile_digest',
                  'contract_digest', 'database_identity', 'dataops_database_identity', 'serving_database_identity')
        with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('SELECT state,' + ','.join(fields) +
                ' FROM response_agent_active_runtime WHERE service_id=%s', (base['service_id'],))
            live = cursor.fetchone()
        require(live is not None and live['state'] == 'ACTIVE' and all(live[key] == base[key] for key in fields),
                'BASELINE_PROMOTION_REQUIRED')

    def _inputs(self, row, key):
        report = row['checkpoint']
        require(isinstance(report, dict) and report.get('status') == 'AWAITING_REVIEW'
                and report.get('recipe') == current_bundle.RECIPE
                and report.get('dispatch_run_id') == str(row['run_id'])
                and report.get('incident_fingerprint') == row['fingerprint']
                and report.get('deployment_manifest_digest') == self.provider.expected_digest
                and not any('cost_usd' not in request for request in report.get('requests', [])),
                'COMPLETED_CURRENT_RUN_BINDING')
        incident = {name: row[name] for name in ('fingerprint', 'source', 'parser_digest', 'contract_version', 'diagnostics')}
        bundle = self.provider.restore(incident, report.get('frozen_current_bundle'), self._captures)
        require(bundle.digest() == row['bundle_digest'] == report.get('bundle_digest')
                and list(bundle.reprocess_targets) == report.get('reprocess_targets')
                and digest(bundle.fixtures) == report.get('oracle_digest'), 'COMPLETED_BUNDLE_CHANGED')
        # Reapply host eligibility to source rather than trusting the model's summary.
        captures = self._captures([target['capture_id'] for target in bundle.reprocess_targets])
        profile = json.loads(bundle.files[current_manifest.PROFILE_PATH])
        for sample in captures:
            current_bundle.reviewed_alias_counterpart(sample, profile)
        patches, validations = report.get('patches', []), report.get('validations', [])
        patch = report.get('recovery', {}).get('patch')
        require(bool(patches) and isinstance(patch, dict)
                and {k:v for k,v in patch.items() if k != 'cases'} == patches[-1]
                and bool(validations) and validations[-1].get('final') is True
                and validations[-1].get('patch_id') == patch.get('patch_id'), 'FINAL_VALIDATED_PATCH_REQUIRED')
        result = validations[-1]['result']
        require(result.get('passed') is True and result.get('status') == 'PASS'
                and result.get('report_digest') == content_digest({k:v for k,v in result.items() if k != 'report_digest'})
                and result.get('manifest_digest') == bundle.manifest_digest, 'FINAL_VALIDATION_BINDING_CHANGED')
        run_directory = self.output_root / str(row['run_id'])
        require(str(run_directory) == row['run_directory'], 'PREPARATION_RUN_DIRECTORY_CHANGED')
        folder = run_directory / ('patch-' + str(len(patches)))
        require(patch.get('directory') == str(folder), 'PREPARATION_PATCH_DIRECTORY_CHANGED')
        files = _private_json(folder / 'candidate-files.json', run_directory, 100000)
        require(isinstance(files, dict) and set(files) == set(current_manifest.EDITABLE_PATHS)
                and all(isinstance(v, str) and len(v.encode()) <= 40000 for v in files.values())
                and digest(files) == patch.get('workspace_digest')
                and digest(files[current_manifest.PARSER_PATH]) == patch.get('source_digest')
                and digest({'files': files, 'cases': patch.get('cases')}) == patch.get('patch_id')
                and content_digest(files) == result.get('source_digest')
                and content_digest(bundle.fixtures) == result.get('fixtures_digest'), 'COMPLETED_PATCH_CONTENT_CHANGED')
        manifest = current_manifest.verify_deployment(Path(bundle.manifest_path),
            expected_manifest_digest=bundle.manifest_digest, require_committed=True)
        base = self.registry.committed_release(self.base_release)
        require(base['source_commit'] == manifest['provenance']['git_commit']
                and base['artifact_sha256'] == manifest['jar_sha256']
                and base['parser_digest'] == bundle.deployment_parser_digest, 'PREPARATION_BASE_CHANGED')
        expiry = (_time(report['finished_at']) + timedelta(seconds=self.ttl)).isoformat()
        request = {'version': VERSION, 'run_id': str(row['run_id']), 'revision': self.revision,
            'incident_id': row['fingerprint'], 'base_release': self.base_release, 'base_record_digest': base['record_digest'],
            'repository': str(self.repository), 'github_repository': self.reviews.preparer.repository,
            'base_branch': self.reviews.base_branch, 'review_id': 'repair-' + key, 'release_id': 'candidate-' + key,
            'generation': 'generation-' + key, 'manifest_digest': bundle.manifest_digest,
            'bundle_digest': bundle.digest(), 'patch_id': patch['patch_id'], 'candidate_digest': content_digest(files),
            'final_validation_digest': result['report_digest'], 'targets': list(bundle.reprocess_targets),
            'canary_capture_ids': [target['capture_id'] for target in bundle.reprocess_targets][:self.canary_limit],
            'expires_at': expiry, 'recipe': current_bundle.RECIPE,
            'recipe_source_sha256': file_digest(current_bundle.__file__), 'adapter_source_sha256': file_digest(__file__)}
        return request, bundle, captures, files, manifest, base

    def prepare(self, run_id):
        key = self._key(run_id)
        db, locked = None, False
        try:
            db = self.connect()
            with db, db.cursor() as cursor:
                cursor.execute("SELECT pg_try_advisory_lock(hashtextextended(%s,0))", ('response-agent-candidate:' + key,))
                locked = cursor.fetchone()[0]
            if not locked:
                return {'outcome': 'UNKNOWN', 'reason_code': 'PREPARATION_BUSY', 'run_id': run_id}
            row = self._run(db, run_id)
            request, bundle, captures, files, manifest, base = self._inputs(row, key)
            self._active_base(db, base)
            self.registry._put('preparation-requests', key, request)
            previous = self._result(key)
            if previous is not None:
                return self._verify_result(db, request, previous)
            require(datetime.now(timezone.utc) < _time(request['expires_at']), 'PREPARATION_EXPIRED')
            request_digest = content_digest(request)
            candidate = self.registry.prepared_candidate(request_digest)
            if candidate is None:
                oracles = reviewed_whole_capture_oracles(bundle=bundle, captures=captures, base=base, manifest=manifest,
                    output=self.registry.root / 'oracle-work' / uuid.uuid4().hex)
                # Retain verifiable baseline provenance privately; full_registry's
                # public reference is its digest and never contains capture prose.
                self.registry._put('preparation-oracles', key, {capture: oracle['provenance'] for capture, oracle in oracles.items()})
                candidate = self.registry.validate_candidate(base_release=self.base_release, repository=self.repository,
                    review_id=request['review_id'], release_id=request['release_id'], generation=request['generation'],
                    deployment_manifest=bundle.manifest_path, deployment_manifest_digest=bundle.manifest_digest,
                    candidate_files=files, fixtures=bundle.fixtures, targets=request['targets'],
                    oracle_loader=lambda target: oracles[target['capture_id']], canary_capture_ids=request['canary_capture_ids'],
                    preparation_request_digest=request_digest)
            self._active_base(db, base)
            # Re-read the terminal checkpoint before authority leaves the private
            # registry. No model/compile runs while this transaction is open.
            with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT checkpoint,bundle_digest,state FROM response_agent_run
                    WHERE run_id=%s FOR SHARE''', (run_id,))
                current = cursor.fetchone()
                require(current is not None and current['state'] == 'AWAITING_REVIEW'
                        and current['checkpoint'] == row['checkpoint'] and current['bundle_digest'] == row['bundle_digest'],
                        'COMPLETED_RUN_CHANGED_DURING_PREPARATION')
                prepared = self.reviews.parser(cursor, validation_digest=candidate['validation_digest'],
                    incident_id=request['incident_id'], run_id=run_id, revision=self.revision, expires_at=request['expires_at'])
            result = {'outcome': 'PREPARED', 'run_id': run_id, 'request_digest': request_digest,
                'validation_digest': candidate['validation_digest'], 'manifest_digest': prepared.manifest.digest,
                'review_id': request['review_id']}
            return self.registry._put('preparation-results', key, result)
        except (ApprovalHold, Hold) as error:
            # Controlled public reason codes contain no raw/model text.
            return {'outcome': 'HELD', 'run_id': run_id, 'reason_code': str(error)}
        except Exception:
            return {'outcome': 'UNKNOWN', 'run_id': run_id, 'reason_code': 'PREPARATION_UNAVAILABLE'}
        finally:
            if db is not None:
                try:
                    db.rollback()
                    if locked:
                        with db, db.cursor() as cursor:
                            cursor.execute("SELECT pg_advisory_unlock(hashtextextended(%s,0))", ('response-agent-candidate:' + key,))
                finally:
                    db.close()

    def _result(self, key):
        path = self.registry.root / 'preparation-results' / (key + '.json')
        return self.registry._get('preparation-results', key) if path.exists() else None

    def _verify_result(self, db, request, result):
        require(result.get('request_digest') == content_digest(request) and result.get('outcome') == 'PREPARED',
                'PREPARATION_RESULT_CHANGED')
        candidate = self.registry.validated_candidate(result['validation_digest'])
        require(candidate.get('preparation_request_digest') == content_digest(request), 'PREPARATION_CANDIDATE_CHANGED')
        with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('''SELECT document FROM response_agent_review_manifest
                WHERE run_id=%s AND revision=%s AND kind='PARSER_REPAIR' ''', (request['run_id'], request['revision']))
            row = cursor.fetchone()
        require(row is not None, 'PREPARATION_PUBLICATION_RECEIPT_REQUIRED')
        manifest = ReviewManifest(row['document'])
        require(manifest.digest == result['manifest_digest'] and manifest.document['run_id'] == request['run_id']
                and manifest.document['revision'] == request['revision']
                and manifest.document['review_id'] == request['review_id']
                and manifest.document['targets'] == request['targets']
                and manifest.document['validation_digest'] == result['validation_digest'],
                'PREPARATION_PUBLICATION_RECEIPT_REQUIRED')
        return result

    def status(self, run_id):
        """Read-only recovery. Never recompiles or retries Git/outbox effects."""
        db = None
        try:
            key = self._key(run_id)
            path = self.registry.root / 'preparation-requests' / (key + '.json')
            if not path.exists():
                return {'outcome': 'NOT_STARTED', 'run_id': run_id}
            request = self.registry._get('preparation-requests', key)
            db = self.connect()
            db.set_session(readonly=True)
            result = self._result(key)
            if result is None:
                candidate = self.registry.prepared_candidate(content_digest(request))
                require(candidate is not None, 'PREPARATION_INCOMPLETE')
                with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
                    cursor.execute('''SELECT document FROM response_agent_review_manifest
                        WHERE run_id=%s AND revision=%s AND kind='PARSER_REPAIR' ''', (run_id, self.revision))
                    row = cursor.fetchone()
                require(row is not None, 'PREPARATION_INCOMPLETE')
                manifest = ReviewManifest(row['document'])
                require(manifest.document['validation_digest'] == candidate['validation_digest'], 'PREPARATION_RESULT_CHANGED')
                result = {'outcome': 'PREPARED', 'run_id': run_id, 'request_digest': content_digest(request),
                    'validation_digest': candidate['validation_digest'], 'manifest_digest': manifest.digest,
                    'review_id': request['review_id']}
            return self._verify_result(db, request, result)
        except (ApprovalHold, Hold) as error:
            return {'outcome': 'UNKNOWN', 'run_id': run_id, 'reason_code': str(error)}
        except Exception:
            return {'outcome': 'UNKNOWN', 'run_id': run_id, 'reason_code': 'PREPARATION_STATUS_UNAVAILABLE'}
        finally:
            if db is not None:
                db.close()
