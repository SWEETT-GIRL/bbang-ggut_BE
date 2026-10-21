"""Promote an approved, running parser generation to the next trusted baseline.

Promotion preserves the deployed candidate JAR. It verifies the merged source,
transitive complete-service build, consumed approval, replay and live runtime;
then switches one generation-bound worker/preparation pointer. It does not
deploy, change service permissions, call a model, or write serving data.
"""
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import uuid

from psycopg2.extras import RealDictCursor

from .approval import ApprovalHold, ID, SHA, PublicationBinding, ReviewManifest, content_digest
from . import current_manifest
from .full_registry import require, git, git_inventory
from .host_runtime import atomic_write, private_directory, private_file
from .release import ReleaseHold

ACTIVE_FIELDS = ('generation', 'release_id', 'artifact_sha256', 'parser_digest', 'profile_digest',
                 'contract_digest', 'database_identity', 'dataops_database_identity', 'serving_database_identity')
POINTER_FIELDS = frozenset({'schema_version', 'service_id', 'generation', 'base_release', 'source_commit',
    'artifact_sha256', 'deployment_manifest', 'deployment_manifest_digest', 'record_digest'})


class BaselinePointerStore:
    def __init__(self, *, registry, connect, service_id, path=None):
        require(isinstance(service_id, str) and ID.fullmatch(service_id), 'BASELINE_SERVICE_ID')
        self.registry, self.connect, self.service_id = registry, connect, service_id
        self.path = Path(path) if path is not None else registry.root / 'active-baseline.json'
        require(self.path.is_absolute(), 'BASELINE_POINTER_ABSOLUTE_PATH')
        private_directory(self.path.parent)

    def _stored(self):
        if not self.path.exists():
            return None
        try:
            row = json.loads(private_file(self.path).read_text())
        except ValueError:
            raise ApprovalHold('BASELINE_POINTER_INVALID') from None
        require(isinstance(row, dict) and set(row) == POINTER_FIELDS and row['schema_version'] == 1
                and row['service_id'] == self.service_id and row['record_digest'] == content_digest(
                    {k:v for k,v in row.items() if k != 'record_digest'}), 'BASELINE_POINTER_CHANGED')
        return row

    def _verify(self, row, db):
        base = self.registry.committed_release(row['base_release'])
        require(base['service_id'] == self.service_id and all(base[key] == row[key] for key in
            ('generation', 'source_commit', 'artifact_sha256')), 'BASELINE_POINTER_RELEASE_CHANGED')
        manifest = current_manifest.verify_deployment(Path(row['deployment_manifest']),
            expected_manifest_digest=row['deployment_manifest_digest'], require_committed=True)
        require(manifest['provenance']['git_commit'] == base['source_commit']
                and manifest['jar_sha256'] == base['artifact_sha256']
                and manifest['parser_digest'] == base['parser_digest']
                and manifest['profile_digest'] == base['profile_digest'], 'BASELINE_POINTER_MANIFEST_CHANGED')
        with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('SELECT state,' + ','.join(ACTIVE_FIELDS) +
                ' FROM response_agent_active_runtime WHERE service_id=%s', (self.service_id,))
            active = cursor.fetchone()
        require(active is not None and active['state'] == 'ACTIVE'
                and all(active[key] == base[key] for key in ACTIVE_FIELDS), 'BASELINE_PROMOTION_REQUIRED')
        return row

    def read(self):
        row = self._stored()
        require(row is not None, 'BASELINE_POINTER_REQUIRED')
        db = self.connect()
        try:
            db.set_session(readonly=True)
            return self._verify(row, db)
        finally:
            db.close()

    def activate(self, *, base_release, deployment_manifest, deployment_manifest_digest, expected_previous, fence):
        require(fence.service_id == self.service_id, 'BASELINE_SERVICE_FENCE')
        fence.assert_held()
        base = self.registry.committed_release(base_release)
        row = {'schema_version': 1, 'service_id': self.service_id, 'generation': base['generation'],
            'base_release': base_release, 'source_commit': base['source_commit'], 'artifact_sha256': base['artifact_sha256'],
            'deployment_manifest': str(Path(deployment_manifest).resolve()),
            'deployment_manifest_digest': deployment_manifest_digest}
        row['record_digest'] = content_digest(row)
        self._verify(row, fence.connection)
        current = self._stored()
        if current == row:
            return row  # Recover rename committed before the promotion outcome.
        require(current == expected_previous, 'BASELINE_POINTER_CAS_CONFLICT')
        self.registry._put('baseline-pointer-records', row['record_digest'], row)
        fence.assert_held()
        atomic_write(self.path, json.dumps(row, ensure_ascii=False, sort_keys=True) + '\n')
        return row

    def initialize(self, *, base_release, deployment_manifest, deployment_manifest_digest, fence):
        return self.activate(base_release=base_release, deployment_manifest=deployment_manifest,
            deployment_manifest_digest=deployment_manifest_digest, expected_previous=None, fence=fence)


class BaselinePromotionService:
    def __init__(self, *, connect, registry, repository, pointers, host, journal, provider, verifier, verify_recovery):
        require(all(adapter is not None for adapter in (connect, registry, pointers, host, journal, provider, verifier, verify_recovery)),
                'PROMOTION_TRUSTED_ADAPTERS_REQUIRED')
        self.connect, self.registry, self.repository, self.pointers = connect, registry, Path(repository).resolve(), pointers
        self.host, self.journal, self.provider, self.verifier, self.recovery = host, journal, provider, verifier, verify_recovery
        require(self.provider.workspace == self.repository and self.provider.repository == self.verifier.repository,
                'PROMOTION_REPOSITORY_CHANGED')

    def _review(self, digest):
        require(isinstance(digest, str) and SHA.fullmatch(digest), 'PROMOTION_MANIFEST_DIGEST')
        db = self.connect()
        try:
            db.set_session(readonly=True)
            with db, db.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT m.document,p.repository,p.pr_number,p.head_sha
                    FROM response_agent_review_manifest m JOIN response_agent_review_publication p USING(manifest_digest)
                    WHERE m.manifest_digest=%s AND m.kind='PARSER_REPAIR' ''', (digest,))
                row = cursor.fetchone()
            require(row is not None, 'PROMOTION_PUBLISHED_REVIEW_REQUIRED')
            manifest = ReviewManifest(row['document'])
            require(manifest.digest == digest, 'PROMOTION_MANIFEST_CHANGED')
            binding = PublicationBinding(repository=row['repository'], pr_number=row['pr_number'],
                                         head_sha=row['head_sha'], manifest_digest=digest)
            return manifest, binding
        finally:
            db.close()

    def _ensure_commit(self, merge_sha):
        # A verified GitHub merge may not have reached the local object database.
        # Fetch only that exact commit, never reset/check out the working service.
        exists = subprocess.run(['git', '-C', str(self.repository), 'cat-file', '-e', merge_sha + '^{commit}'],
                                capture_output=True, timeout=10)
        if exists.returncode:
            git(self.repository, 'fetch', '--no-tags', 'origin', merge_sha)
        require(git(self.repository, 'rev-parse', merge_sha + '^{commit}').decode().strip() == merge_sha,
                'PROMOTION_MERGE_COMMIT_UNAVAILABLE')

    def _freeze(self, release_id, commit, artifact):
        destination = self.registry.root / 'parser-manifests' / release_id
        if destination.exists():
            path = destination / 'manifest.json'
            require(path.is_file(), 'PROMOTION_MANIFEST_INCOMPLETE')
            saved = json.loads(path.read_text())
            verified = current_manifest.verify_deployment(path, expected_manifest_digest=saved['manifest_digest'], require_committed=True)
            require(verified['provenance']['git_commit'] == commit
                    and verified['jar_sha256'] == self.registry.build_receipt(artifact['receipt_digest'])['artifact_sha256'],
                    'PROMOTION_MANIFEST_CHANGED')
            return path, verified['manifest_digest']
        checkout = self.registry.root / 'promotion-source' / uuid.uuid4().hex
        checkout.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.registry.root / 'promotion-manifest-work' / uuid.uuid4().hex
        temporary.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        added = False
        try:
            git(self.repository, 'worktree', 'add', '--detach', str(checkout), commit)
            added = True
            manifest = current_manifest.freeze_deployment(checkout, Path(artifact['artifact_path']), temporary)
            require(manifest['provenance']['git_commit'] == commit and manifest['jar_sha256'] == artifact['artifact_sha256'],
                    'PROMOTION_FROZEN_SOURCE_CHANGED')
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            temporary.rename(destination)
            return destination / 'manifest.json', manifest['manifest_digest']
        finally:
            if added:
                git(self.repository, 'worktree', 'remove', '--force', str(checkout))
            if temporary.exists():
                shutil.rmtree(temporary)

    def promote(self, manifest_digest):
        try:
            manifest, binding = self._review(manifest_digest)
            candidate = self.registry.validated_candidate(manifest.document['validation_digest'])
            base = self.registry.committed_release(candidate['base_release'])
            operation = 'release-' + uuid.uuid5(uuid.NAMESPACE_URL, 'response-release:' + manifest.digest).hex
            with self.journal.service_lock(base['service_id']) as fence:
                record = self.journal.load(base['service_id'], operation, fence)
                require(record is not None and record['state'] == 'ACTIVE' and record['pending_action'] is None,
                        'PROMOTION_ACTIVE_RELEASE_REQUIRED')
                # Authenticated merge evidence is checked independently of labels
                # and before any source is fetched or promoted.
                now = datetime.now(timezone.utc)
                snapshot = self.provider.read_snapshot(binding, manifest)
                evidence = self.verifier.evaluate(manifest, binding, snapshot, now=now)
                require(record['approval_digest'] == evidence.digest and record['manifest_digest'] == manifest.digest,
                        'PROMOTION_CONSUMED_APPROVAL_CHANGED')
                live = self.host.observe(base['service_id'], fence)
                require(live.get('healthy') is True and live.get('writes_enabled') is False
                        and all(live.get(key) == candidate[key] for key in ACTIVE_FIELDS), 'PROMOTION_LIVE_IDENTITY_CHANGED')
                self._ensure_commit(evidence.merge_commit_sha)
                proof_path = self.registry.root / 'promotion-authority' / (manifest.digest + '.json')
                if proof_path.exists():
                    proof = self.registry._get('promotion-authority', manifest.digest)
                    require(proof['approval'] == evidence.__dict__ and proof['publication'] == binding.__dict__
                            and proof['manifest'] == manifest.document, 'PROMOTION_AUTHORITY_CHANGED')
                else:
                    proof = {'version': 1, 'repository': self.provider.repository, 'repository_path': str(self.repository),
                        'allowed_actor_ids': sorted(self.verifier.actors), 'manifest': manifest.document,
                        'publication': binding.__dict__, 'snapshot': snapshot.__dict__, 'approval': evidence.__dict__,
                        'verified_at': now.isoformat(), 'release_operation': record, 'runtime': live}
                    self.registry._put('promotion-authority', manifest.digest, proof)
                build = self.registry.register_approved_parser_build(proof)
                path, digest = self._freeze(candidate['release_id'], evidence.merge_commit_sha, build)
                # Source freezing is offline and may take time. Re-measure the
                # owned generation before registering or switching the pointer.
                fence.assert_held()
                base_next = self.registry.register_base(build_receipt_digest=build['receipt_digest'],
                    release_id=candidate['release_id'], observe_runtime=lambda:self.host.observe(base['service_id'],fence),
                    verify_recovery=self.recovery)
                previous = self.pointers._stored()
                require(previous is not None and previous['base_release'] in {base['release_id'], base_next['release_id']},
                        'PROMOTION_POINTER_ANCESTRY_CHANGED')
                pointer = self.pointers.activate(base_release=base_next['release_id'], deployment_manifest=path,
                    deployment_manifest_digest=digest, expected_previous=previous, fence=fence)
                result = {'outcome': 'PROMOTED', 'manifest_digest': manifest.digest,
                    'pointer_digest': pointer['record_digest'], 'base_release': base_next['release_id'],
                    'generation': base_next['generation'], 'deployment_manifest_digest': digest}
                return self.registry._put('baseline-promotion-results', manifest.digest, result)
        except (ApprovalHold, ReleaseHold) as error:
            return {'outcome': 'HELD', 'manifest_digest': manifest_digest, 'reason_code': str(error)}
        except Exception:
            return {'outcome': 'UNKNOWN', 'manifest_digest': manifest_digest, 'reason_code': 'BASELINE_PROMOTION_UNAVAILABLE'}

    def status(self, manifest_digest):
        """Read prior promotion authority; never fetch/build/retry work."""
        try:
            require(isinstance(manifest_digest, str) and SHA.fullmatch(manifest_digest), 'PROMOTION_MANIFEST_DIGEST')
            path = self.registry.root / 'baseline-promotion-results' / (manifest_digest + '.json')
            if path.exists():
                result = self.registry._get('baseline-promotion-results', manifest_digest)
                record = self.registry._get('baseline-pointer-records', result['pointer_digest'])
                require(record['base_release'] == result['base_release'] and record['generation'] == result['generation'],
                        'PROMOTION_RESULT_CHANGED')
                self.registry.committed_release(result['base_release'])
                return result
            proof_path = self.registry.root / 'promotion-authority' / (manifest_digest + '.json')
            if not proof_path.exists():
                return {'outcome': 'NOT_STARTED', 'manifest_digest': manifest_digest}
            proof = self.registry._get('promotion-authority', manifest_digest)
            candidate = self.registry.validated_candidate(proof['manifest']['validation_digest'])
            pointer = self.pointers.read()
            require(pointer['base_release'] == candidate['release_id'] and pointer['generation'] == candidate['generation'],
                    'BASELINE_PROMOTION_INCOMPLETE')
            return {'outcome': 'PROMOTED', 'manifest_digest': manifest_digest, 'pointer_digest': pointer['record_digest'],
                'base_release': candidate['release_id'], 'generation': candidate['generation'],
                'deployment_manifest_digest': pointer['deployment_manifest_digest']}
        except (ApprovalHold, ReleaseHold) as error:
            return {'outcome': 'UNKNOWN', 'manifest_digest': manifest_digest, 'reason_code': str(error)}
        except Exception:
            return {'outcome': 'UNKNOWN', 'manifest_digest': manifest_digest, 'reason_code': 'BASELINE_PROMOTION_STATUS_UNAVAILABLE'}
