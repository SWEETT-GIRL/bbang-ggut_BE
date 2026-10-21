"""Host-owned full-service provenance. No function is exposed as a model tool.

The private registry directory and its writer are part of the trusted controller
boundary. Digests detect drift; they do not authenticate an attacker with write
access to that directory. A parser-family manifest alone can never register a
base release. Building, observing a deployment, and approving it are separate.
"""
from __future__ import annotations

from datetime import datetime
from hashlib import sha256
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET

from .approval import (ApprovalHold, ApprovalEvidence, ApprovalVerifier, PublicationBinding, PullRequestSnapshot,
                       ReviewManifest, COMMIT, ID, SHA, canonical, code_tree_digest, content_digest)
from . import current_manifest, current_validator

PROBE = Path(__file__).parent / 'trusted/current/ReleaseArtifactProbe.java'
TARGET_FIELDS = ('capture_id', 'payload_digest', 'store_id', 'external_id', 'observed_at', 'attempt_seq')
PARSED_FIELDS = frozenset({'name', 'phone', 'address', 'roadAddress', 'longitude', 'latitude',
    'businessStatusDescription', 'businessHoursRaw', 'instagramHandle', 'menus', 'baeminMenus'})
ORACLE_KINDS = frozenset({'HUMAN_CONFIRMED', 'REVIEWED_CONTRACT', 'APPROVED_BASELINE'})
MAX_CAPTURE_TARGETS = 100  # The bounded whole-capture harness validates this complete set in one run.
INIT_SCRIPT = "allprojects { buildDir = file('/output/build') }\n"
BUILD_SCRIPT = ("cp -R /source /tmp/source && chmod -R u+w /tmp/source && cd /tmp/source && "
    "/opt/gradle/bin/gradle --offline --no-daemon --max-workers=2 --project-cache-dir /tmp/project-cache "
    "-g /tmp/gradle-home --init-script /recipe/build.init.gradle test bootJar")
RECIPE = {'version': 'full-committed-service-v1', 'script': BUILD_SCRIPT, 'init_script': INIT_SCRIPT,
    'network': 'none', 'uid': 10001, 'readonly_root': True, 'tasks': ['test', 'bootJar'],
    'memory': '2g', 'cpus': 2, 'timeout_seconds': 300}


def require(condition, code):
    if not condition:
        raise ApprovalHold(code)


def file_digest(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'REGISTRY_REGULAR_FILE_REQUIRED')
    h = sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def tree_digest(root):
    root = Path(root)
    require(root.is_dir() and not root.is_symlink(), 'BUILD_INPUT_DIRECTORY')
    entries = {}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(), 'BUILD_INPUT_SYMLINK')
        if path.is_file():
            entries[str(path.relative_to(root))] = file_digest(path)
    require(bool(entries), 'EMPTY_BUILD_INPUT')
    return content_digest(entries)


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), '-c', 'core.hooksPath=/dev/null', *args],
                            capture_output=True, timeout=30)
    require(result.returncode == 0, 'REGISTRY_GIT_FAILED')
    return result.stdout


def git_inventory(root, commit, candidates=None):
    require(isinstance(commit, str) and COMMIT.fullmatch(commit), 'BASE_COMMIT')
    result = {}
    for row in git(root, 'ls-tree', '-rz', commit).split(b'\0'):
        if not row:
            continue
        metadata, path = row.split(b'\t', 1)
        mode, kind, identity = metadata.decode().split()
        require(kind == 'blob' and mode in {'100644', '100755'}, 'UNSUPPORTED_TREE_ENTRY')
        result[path.decode()] = {'sha': identity, 'mode': mode}
    for path, text in (candidates or {}).items():
        require(path in current_manifest.EDITABLE_PATHS and path in result, 'CANDIDATE_SOURCE_ALLOWLIST')
        blob = text.encode()
        from hashlib import sha1
        result[path] = {'sha': sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest(),
                        'mode': result[path]['mode']}
    return result


def _write_once(path, raw):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(not path.is_symlink(), 'REGISTRY_SYMLINK')
    # Atomic create-only link: interrupted writers leave a private temp, never a
    # partially authoritative JSON file. A retry must have identical bytes.
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        try:
            os.link(temporary, path)
        except FileExistsError:
            require(path.read_bytes() == raw, 'IMMUTABLE_REGISTRY_CONFLICT')
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def _docker(command, name, log, timeout=300):
    try:
        with Path(log).open('wb') as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        require(result.returncode == 0, 'OFFLINE_BUILD_OR_PROBE_FAILED')
    except subprocess.TimeoutExpired:
        raise ApprovalHold('OFFLINE_BUILD_TIMEOUT') from None
    finally:
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=15)


def _sandbox(name, image, memory='2g'):
    require(isinstance(image, str) and image.startswith('sha256:') and SHA.fullmatch(image[7:]), 'PINNED_BUILD_IMAGE')
    return ['docker', 'run', '--rm', '--name', name, '--network', 'none', '--read-only',
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pids-limit', '256',
        '--memory', memory, '--memory-swap', memory, '--cpus', '2', '--user', '10001:10001',
        '--tmpfs', '/tmp:rw,nosuid,size=1073741824,mode=1777', '--env', 'HOME=/tmp']


def probe_artifact(artifact, image, output):
    """Execute only the frozen probe inside an offline container, using JAR bytes."""
    artifact, output = Path(artifact).resolve(), Path(output).resolve()
    entries = current_manifest._jar_entries(artifact)
    require(all(not name.startswith('/') and '..' not in name.split('/') and '\\' not in name
                for name in entries), 'UNSAFE_JAR_ENTRY')
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    mounted = output / 'service.jar'
    # Registry artifacts stay 0600. The container gets one read-only 0644 copy
    # inside its private work directory, never a directory of other releases.
    mounted.write_bytes(artifact.read_bytes()); mounted.chmod(0o644)
    require(file_digest(mounted) == file_digest(artifact), 'PROBE_ARTIFACT_CHANGED')
    source = output / PROBE.name
    source.write_bytes(PROBE.read_bytes()); source.chmod(0o644)
    name = 'agent-artifact-probe-' + uuid.uuid4().hex[:16]
    command = _sandbox(name, image, '512m') + [
        '--mount', f'type=bind,src={mounted},dst=/input/service.jar,readonly',
        '--mount', f'type=bind,src={source},dst=/probe/ReleaseArtifactProbe.java,readonly', image,
        'sh', '-c', "mkdir /tmp/service && cd /tmp/service && jar -xf /input/service.jar && "
        "javac -cp 'BOOT-INF/classes:BOOT-INF/lib/*' -d /tmp /probe/ReleaseArtifactProbe.java && "
        "java -Xmx256m -cp '/tmp:BOOT-INF/classes:BOOT-INF/lib/*' ReleaseArtifactProbe"]
    _docker(command, name, output / 'probe.log', timeout=60)
    try:
        result = json.loads((output / 'probe.log').read_text())
        require(set(result) == {'parser_digest', 'profile_digest', 'contract_digest', 'contract_version'}, 'ARTIFACT_PROBE_SHAPE')
        require(all(SHA.fullmatch(result[k]) for k in ('parser_digest', 'profile_digest', 'contract_digest')), 'ARTIFACT_PROBE_DIGEST')
        return result
    except (ValueError, TypeError):
        raise ApprovalHold('ARTIFACT_PROBE_INVALID') from None


class FullReleaseRegistry:
    def __init__(self, directory):
        self.root = Path(directory).absolute()
        require(not any(p.is_symlink() for p in (self.root, *self.root.parents)), 'REGISTRY_SYMLINK')
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        require(self.root.stat().st_uid == os.getuid() and self.root.stat().st_mode & 0o077 == 0,
                'PRIVATE_REGISTRY_REQUIRED')

    def _put(self, kind, key, document):
        require(ID.fullmatch(key) or SHA.fullmatch(key), 'REGISTRY_KEY')
        raw = (canonical(document) + '\n').encode()
        _write_once(self.root / kind / (key + '.json'), raw)
        return json.loads(raw)

    def _get(self, kind, key):
        require(isinstance(key, str) and (ID.fullmatch(key) or SHA.fullmatch(key)), 'REGISTRY_KEY')
        path = self.root / kind / (key + '.json')
        require(path.is_file() and not path.is_symlink() and not path.parent.is_symlink(), 'REGISTRY_ENTRY_UNAVAILABLE')
        try:
            return json.loads(path.read_text())
        except ValueError:
            raise ApprovalHold('REGISTRY_ENTRY_INVALID') from None

    def build_committed_base(self, *, repository, source_commit, image_id, gradle_home, dependency_cache):
        """Build exact committed tree with the fixed offline recipe; no live claim."""
        require(isinstance(source_commit, str) and COMMIT.fullmatch(source_commit), 'BASE_COMMIT')
        root, gradle, cache = Path(repository).resolve(), Path(gradle_home).resolve(), Path(dependency_cache).resolve()
        require(git(root, 'rev-parse', source_commit + '^{commit}').decode().strip() == source_commit, 'BASE_COMMIT')
        inputs = {'image_id': image_id, 'gradle_digest': tree_digest(gradle), 'dependency_cache_digest': tree_digest(cache),
                  'builder_digest': file_digest(__file__), 'recipe_digest': content_digest(RECIPE)}
        key = content_digest({'source_commit': source_commit, 'inputs': inputs})
        work = self.root / 'build-work' / uuid.uuid4().hex
        source, output, recipe = (work / n for n in ('source', 'output', 'recipe'))
        for directory in (source, output, recipe):
            directory.mkdir(parents=True, mode=0o755)
        output.chmod(0o777)  # The sandbox UID can write only this owned output directory.
        archive = git(root, 'archive', '--format=tar', source_commit)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            require(all(m.isfile() or m.isdir() for m in tar.getmembers()), 'COMMITTED_TREE_LINK_UNSUPPORTED')
            tar.extractall(source, filter='data')
        (recipe / 'build.init.gradle').write_text(INIT_SCRIPT)
        name = 'agent-full-build-' + uuid.uuid4().hex[:16]
        command = _sandbox(name, image_id) + [
            '--mount', f'type=bind,src={source},dst=/source,readonly',
            '--mount', f'type=bind,src={output},dst=/output',
            '--mount', f'type=bind,src={recipe},dst=/recipe,readonly',
            '--mount', f'type=bind,src={cache},dst=/dependency-cache/modules-2,readonly',
            '--mount', f'type=bind,src={gradle},dst=/opt/gradle,readonly',
            '--workdir', '/source', '--env', 'GRADLE_RO_DEP_CACHE=/dependency-cache', image_id, 'sh', '-c', BUILD_SCRIPT]
        _docker(command, name, work / 'build.log')
        require(tree_digest(gradle) == inputs['gradle_digest'] and tree_digest(cache) == inputs['dependency_cache_digest'],
                'BUILD_DEPENDENCIES_CHANGED')
        counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
        reports = sorted((output / 'build/test-results/test').glob('TEST-*.xml'))
        require(bool(reports), 'FULL_BUILD_TEST_REPORTS_REQUIRED')
        for report in reports:
            suite = ET.parse(report).getroot()
            for field in counts:
                counts[field] += int(suite.attrib.get(field, 0))
        require(counts['tests'] > counts['skipped'] and counts['failures'] == counts['errors'] == 0, 'FULL_BUILD_TESTS_FAILED')
        jars = [p for p in (output / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
        require(len(jars) == 1, 'FULL_BUILD_ARTIFACT_AMBIGUOUS')
        artifact_sha = file_digest(jars[0])
        artifact = self.root / 'artifacts' / (artifact_sha + '.jar')
        _write_once(artifact, jars[0].read_bytes())
        receipt = {'schema_version': 1, 'kind': 'COMMITTED_OFFLINE_SERVICE_BUILD', 'source_commit': source_commit,
            'git_tree': git(root, 'rev-parse', source_commit + '^{tree}').decode().strip(),
            'code_tree_digest': code_tree_digest(git_inventory(root, source_commit), 'no-review'),
            'source_archive_sha256': sha256(archive).hexdigest(), 'inputs': inputs,
            'artifact_path': str(artifact), 'artifact_sha256': artifact_sha, 'tests': counts,
            'log_digest': file_digest(work / 'build.log'), 'build_work': str(work), 'build_key': key,
            'operating_writes': 0, 'live_runtime_verified': False}
        receipt['receipt_digest'] = content_digest(receipt)
        return self._put('builds', receipt['receipt_digest'], receipt)

    def build_receipt(self, identity, _ancestors=()):
        require(identity not in _ancestors and len(_ancestors) < 64, 'BUILD_ANCESTRY_INVALID')
        receipt = self._get('builds', identity)
        require(receipt.get('kind') in {'COMMITTED_OFFLINE_SERVICE_BUILD', 'APPROVED_PARSER_SERVICE_BUILD'}
                and receipt.get('receipt_digest') == identity
                and content_digest({k:v for k,v in receipt.items() if k != 'receipt_digest'}) == identity, 'FULL_BUILD_RECEIPT_REQUIRED')
        require(file_digest(receipt['artifact_path']) == receipt['artifact_sha256'], 'FULL_BUILD_ARTIFACT_CHANGED')
        if receipt['kind'] == 'APPROVED_PARSER_SERVICE_BUILD':
            self._verify_promoted_build(receipt, (*_ancestors, identity))
        return receipt

    def _verify_promoted_build(self, receipt, ancestors=()):
        proof = self._get('promotion-proofs', receipt['promotion_proof_digest'])
        require(content_digest(proof) == receipt['promotion_proof_digest'], 'PROMOTION_PROOF_CHANGED')
        parent = self.build_receipt(receipt['parent_build_receipt_digest'], ancestors)
        candidate = self._get('candidates', receipt['validation_digest'])
        validation = self._get('validations', receipt['validation_digest'])
        parent_base = self._get('releases', candidate['base_release'])
        require(candidate.get('record_digest') == content_digest({k:v for k,v in candidate.items() if k != 'record_digest'})
                and content_digest(validation) == receipt['validation_digest']
                and candidate['validation_digest'] == receipt['validation_digest']
                and parent_base.get('record_digest') == content_digest({k:v for k,v in parent_base.items() if k != 'record_digest'})
                and parent_base.get('attestation_kind') == 'COMMITTED_DEPLOYED_SERVICE'
                and parent_base['build_receipt_digest'] == parent['receipt_digest']
                and parent_base['source_commit'] == parent['source_commit']
                and parent_base['artifact_sha256'] == parent['artifact_sha256'], 'PROMOTION_PARENT_BASE_CHANGED')
        manifest = ReviewManifest(proof['manifest'])
        require(manifest.document['kind'] == 'PARSER_REPAIR' and manifest.document['validation_digest'] == receipt['validation_digest']
                and manifest.document['base_release'] == candidate['base_release']
                and manifest.document['base_commit'] == parent['source_commit']
                and manifest.document['artifact_sha256'] == candidate['artifact_sha256']
                and manifest.document['candidate_tree_digest'] == candidate['candidate_tree_digest']
                and manifest.document['targets'] == candidate['targets']
                and validation['base_build_receipt_digest'] == parent['receipt_digest']
                and candidate['base_artifact_sha256'] == parent['artifact_sha256']
                and receipt['artifact_sha256'] == candidate['artifact_sha256']
                and receipt['inputs'] == parent['inputs'], 'PROMOTION_BUILD_LINEAGE_CHANGED')
        verifier = ApprovalVerifier(allowed_repository=proof['repository'], allowed_actor_ids=proof['allowed_actor_ids'])
        # Revalidate the historical authenticated snapshot at its recorded check
        # time. This proves consumed provenance, never grants a fresh operation.
        evidence = verifier.evaluate(manifest, PublicationBinding(**proof['publication']),
            PullRequestSnapshot(**proof['snapshot']), now=datetime.fromisoformat(proof['verified_at']))
        require(evidence.__dict__ == proof['approval'] and evidence.digest == receipt['approval_digest']
                and evidence.merge_commit_sha == receipt['source_commit'], 'PROMOTION_APPROVAL_CHANGED')
        record = proof['release_operation']
        require(record['manifest_digest'] == manifest.digest and record['approval_digest'] == evidence.digest
                and record['operation_id'] == 'release-' + uuid.uuid5(uuid.NAMESPACE_URL, 'response-release:' + manifest.digest).hex
                and record['service_id'] == parent_base['service_id']
                and record['approval_state'] == 'CONSUMED' and record['approval_scope'] == 'DEPLOY_REPLAY_PROPOSE'
                and record['state'] == 'ACTIVE' and record['pending_action'] is None, 'PROMOTION_COMPLETED_RELEASE_REQUIRED')
        policy = self.canary_policy(candidate['canary_policy_digest'])
        canary, replay = record.get('canary_receipt', {}), record.get('replay_receipt', {})
        require(canary.get('passed') is True and canary.get('result_digest') == policy['expected_result_digest']
                and replay.get('phase') == 'REPLAY' and replay.get('outcome') == 'DONE'
                and replay.get('complete') is True and replay.get('operating_writes') == 0
                and replay.get('manifest_digest') == manifest.digest
                and replay.get('targets_digest') == content_digest(candidate['targets']), 'PROMOTION_REPLAY_PROOF_REQUIRED')
        live = proof['runtime']
        require(live.get('healthy') is True and live.get('writes_enabled') is False
                and live.get('service_id') == record['service_id'] and live.get('policy_digest') == candidate['policy_digest']
                and all(live.get(key) == candidate[key] for key in ('release_id', 'generation', 'artifact_sha256',
                    'parser_digest', 'profile_digest', 'contract_digest', 'database_identity',
                    'dataops_database_identity', 'serving_database_identity')), 'PROMOTION_LIVE_IDENTITY_CHANGED')
        repository = Path(proof['repository_path'])
        require(git(repository, 'rev-parse', receipt['source_commit'] + '^{commit}').decode().strip() == receipt['source_commit']
                and code_tree_digest(git_inventory(repository, receipt['source_commit']), candidate['review_id'])
                    == candidate['candidate_tree_digest'], 'PROMOTION_MERGED_TREE_CHANGED')
        require(all(sha256(git(repository, 'show', receipt['source_commit'] + ':' + path)).hexdigest() == expected
                    for path, expected in manifest.document['files'].items()), 'PROMOTION_MERGED_FILES_CHANGED')
        before, after = current_manifest._jar_entries(Path(parent['artifact_path'])), current_manifest._jar_entries(Path(candidate['artifact_path']))
        parser_root = current_manifest.PARSER_PATH.removeprefix('src/main/java/').removesuffix('.java')
        resource = 'BOOT-INF/classes/' + current_manifest.PROFILE_PATH.removeprefix('src/main/resources/')
        def editable(name):
            return name == resource or (name.startswith('BOOT-INF/classes/') and name.endswith('.class')
                and name.removeprefix('BOOT-INF/classes/').split('$', 1)[0].removesuffix('.class') == parser_root)
        require({k:v for k,v in before.items() if not editable(k)} == {k:v for k,v in after.items() if not editable(k)},
                'PROMOTION_UNRELATED_SERVICE_BYTES_CHANGED')
        require(receipt['source_archive_sha256'] == sha256(git(repository, 'archive', '--format=tar', receipt['source_commit'])).hexdigest(),
                'PROMOTION_SOURCE_ARCHIVE_CHANGED')

    def register_approved_parser_build(self, proof):
        """Host-only transitive full-service receipt; never rebuild/redeploy a JAR.

        The promotion service supplies a real authenticated snapshot, consumed
        journal entry and measured runtime under the service lock. Every stored
        receipt subsequently rechecks the complete immutable lineage and bytes.
        """
        manifest = ReviewManifest(proof['manifest'])
        candidate = self.validated_candidate(manifest.document['validation_digest'])
        parent = self.build_receipt(self.validation_document(candidate['validation_digest'])['base_build_receipt_digest'])
        evidence = ApprovalEvidence(**proof['approval'])
        proof_digest = content_digest(proof)
        self._put('promotion-proofs', proof_digest, proof)
        receipt = {'schema_version': 1, 'kind': 'APPROVED_PARSER_SERVICE_BUILD',
            'source_commit': evidence.merge_commit_sha, 'artifact_path': candidate['artifact_path'],
            'artifact_sha256': candidate['artifact_sha256'], 'inputs': parent['inputs'],
            'parent_build_receipt_digest': parent['receipt_digest'], 'validation_digest': candidate['validation_digest'],
            'approval_digest': evidence.digest, 'promotion_proof_digest': proof_digest,
            'source_archive_sha256': sha256(git(Path(proof['repository_path']), 'archive', '--format=tar', evidence.merge_commit_sha)).hexdigest(),
            'operating_writes': 0, 'rebuilt': False}
        self._verify_promoted_build(receipt)
        receipt['receipt_digest'] = content_digest(receipt)
        return self._put('builds', receipt['receipt_digest'], receipt)

    def register_base(self, *, build_receipt_digest, release_id, observe_runtime, verify_recovery):
        """Callbacks belong to the host controller, not JSON/model input.

        observe_runtime must measure the owned live process and both databases;
        verify_recovery must verify actual backup and schema compatibility receipts.
        A bool from a CLI flag is not an implementation of these capabilities.
        """
        require(isinstance(release_id, str) and ID.fullmatch(release_id), 'RELEASE_ID')
        build = self.build_receipt(build_receipt_digest)
        measured = probe_artifact(build['artifact_path'], build['inputs']['image_id'],
                                  self.root / 'probes' / uuid.uuid4().hex)
        live = observe_runtime()
        for key in ('artifact_sha256', 'parser_digest', 'profile_digest', 'contract_digest'):
            require(live.get(key) == (build['artifact_sha256'] if key == 'artifact_sha256' else measured[key]), 'LIVE_BUILD_IDENTITY_MISMATCH')
        require(live.get('release_id') == release_id and isinstance(live.get('service_id'), str) and ID.fullmatch(live['service_id'])
                and isinstance(live.get('generation'), str) and ID.fullmatch(live['generation'])
                and live.get('healthy') is True, 'LIVE_SERVICE_UNVERIFIED')
        require(all(isinstance(live.get(k), str) and SHA.fullmatch(live[k]) for k in
                    ('dataops_database_identity', 'serving_database_identity', 'database_identity', 'policy_digest')), 'LIVE_DATABASE_UNVERIFIED')
        require(live['database_identity'] == content_digest({'dataops': live['dataops_database_identity'],
                    'serving': live['serving_database_identity']}), 'DATABASE_COMPOSITE_MISMATCH')
        recovery = verify_recovery(build, live)
        require(recovery.get('schema_compatible') is True and recovery.get('backup_verified') is True
                and all(isinstance(recovery.get(k), str) and SHA.fullmatch(recovery[k]) for k in
                        ('schema_receipt_digest', 'backup_receipt_digest')), 'RECOVERY_UNVERIFIED')
        base = {k: live[k] for k in ('service_id', 'generation', 'database_identity',
                                    'dataops_database_identity', 'serving_database_identity', 'policy_digest')}
        base.update(measured, **{k:recovery[k] for k in ('schema_compatible','backup_verified','schema_receipt_digest','backup_receipt_digest')},
            release_id=release_id, source_commit=build['source_commit'],
            artifact_path=build['artifact_path'], artifact_sha256=build['artifact_sha256'],
            build_receipt_digest=build_receipt_digest, attestation_kind='COMMITTED_DEPLOYED_SERVICE')
        base['record_digest'] = content_digest(base)
        return self._put('releases', release_id, base)

    def committed_release(self, release_id):
        base = self._get('releases', release_id)
        require(base.get('attestation_kind') == 'COMMITTED_DEPLOYED_SERVICE' and base.get('release_id') == release_id,
                'DEPLOYED_FULL_SERVICE_REQUIRED')
        require(base.get('record_digest') == content_digest({k:v for k,v in base.items() if k!='record_digest'}), 'BASE_RECORD_CHANGED')
        build = self.build_receipt(base['build_receipt_digest'])
        require(base['artifact_sha256'] == build['artifact_sha256'] and base['source_commit'] == build['source_commit'],
                'BASE_BUILD_BINDING_CHANGED')
        return base

    def validated_candidate(self, validation_digest):
        candidate = self._get('candidates', validation_digest)
        require(candidate.get('record_digest') == content_digest({k:v for k,v in candidate.items() if k!='record_digest'}),
                'CANDIDATE_RECORD_CHANGED')
        validation = self._get('validations', validation_digest)
        require(content_digest(validation) == validation_digest and candidate['validation_digest'] == validation_digest,
                'CANDIDATE_VALIDATION_CHANGED')
        require(file_digest(candidate['artifact_path']) == candidate['artifact_sha256'], 'CANDIDATE_ARTIFACT_CHANGED')
        self.committed_release(candidate['base_release'])
        return candidate

    def canary_policy(self, policy_digest):
        policy = self._get('policies', policy_digest)
        require(content_digest(policy) == policy_digest, 'CANARY_POLICY_CHANGED')
        return policy

    def validation_document(self, validation_digest):
        self.validated_candidate(validation_digest)
        return self._get('validations', validation_digest)

    def replay_plan(self, validation_digest):
        candidate = self.validated_candidate(validation_digest)
        plan = self._get('plans', validation_digest)
        require(content_digest(plan) == candidate['plan_digest'], 'REPLAY_PLAN_CHANGED')
        return plan

    def prepared_candidate(self, request_digest):
        """Recover a completed validation after a host crash before DB publication.

        Only complete candidate records count. Orphaned validation/build files
        are not authority. The caller serializes a request with its DB lock.
        """
        require(isinstance(request_digest, str) and SHA.fullmatch(request_digest), 'PREPARATION_REQUEST_DIGEST')
        directory = self.root / 'candidates'
        require(not directory.is_symlink(), 'REGISTRY_SYMLINK')
        matches = []
        for path in sorted(directory.glob('*.json')):
            require(not path.is_symlink(), 'REGISTRY_SYMLINK')
            row = self._get('candidates', path.stem)
            if row.get('preparation_request_digest') == request_digest:
                matches.append(self.validated_candidate(path.stem))
        require(len(matches) <= 1, 'PREPARATION_CANDIDATE_AMBIGUOUS')
        return matches[0] if matches else None

    def validate_candidate(self, *, base_release, repository, review_id, release_id, generation,
            deployment_manifest, deployment_manifest_digest, candidate_files, fixtures, targets,
            oracle_loader, canary_capture_ids, preparation_request_digest=None):
        """Run protected tests and independent *whole capture* checks before freezing.

        oracle_loader is an injected trusted capability returning a target-bound
        raw capture plus a complete independently reviewed expected ParsedPlace.
        Candidate/model outputs and reduced fixtures cannot implement this oracle.
        No raw values or expected values are persisted in the public validation.
        """
        require(isinstance(targets, list) and 0 < len(targets) <= MAX_CAPTURE_TARGETS, 'CAPTURE_TARGET_BOUNDS')
        if preparation_request_digest is not None:
            require(isinstance(preparation_request_digest, str) and SHA.fullmatch(preparation_request_digest),
                    'PREPARATION_REQUEST_DIGEST')
        for identity in (review_id, release_id, generation):
            require(isinstance(identity, str) and ID.fullmatch(identity), 'CANDIDATE_ID')
        base = self.committed_release(base_release)
        require(release_id != base_release and generation != base['generation'], 'CANDIDATE_GENERATION_REUSED')
        build = self.build_receipt(base['build_receipt_digest'])
        deployment = current_manifest.verify_deployment(Path(deployment_manifest),
            expected_manifest_digest=deployment_manifest_digest, require_committed=True)
        require(deployment['provenance']['git_commit'] == base['source_commit']
                and deployment['jar_sha256'] == base['artifact_sha256'], 'PARSER_MANIFEST_FULL_BUILD_MISMATCH')
        ids = []
        for target in targets:
            require(isinstance(target, dict) and set(target) == set(TARGET_FIELDS), 'CAPTURE_TARGET_FIELDS')
            require(isinstance(target['capture_id'], str) and ID.fullmatch(target['capture_id'])
                    and isinstance(target['payload_digest'], str) and SHA.fullmatch(target['payload_digest']), 'CAPTURE_TARGET_IDENTITY')
            ids.append(target['capture_id'])
        require(len(set(ids)) == len(ids), 'DUPLICATE_CAPTURE_TARGET')
        require(isinstance(canary_capture_ids, list) and 0 < len(canary_capture_ids) <= 20
                and len(set(canary_capture_ids)) == len(canary_capture_ids)
                and all(identity in ids for identity in canary_capture_ids), 'CANARY_FIXED_TARGETS')
        require(set(candidate_files) == set(current_manifest.EDITABLE_PATHS), 'CANDIDATE_SOURCE_ALLOWLIST')
        work = self.root / 'validation-work' / uuid.uuid4().hex
        work.mkdir(parents=True, mode=0o700)
        report = current_validator.validate(Path(deployment_manifest), candidate_files, fixtures, work / 'current',
            expected_manifest_digest=deployment_manifest_digest, require_committed=True)
        require(report.get('passed') is True and report.get('status') == 'PASS', 'CURRENT_VALIDATION_FAILED')
        require(report['report_digest'] == content_digest({k:v for k,v in report.items() if k != 'report_digest'}),
                'CURRENT_VALIDATION_DIGEST')
        artifact = report['artifact']
        require(artifact['base_jar_sha256'] == base['artifact_sha256']
                and file_digest(artifact['path']) == artifact['sha256'], 'CANDIDATE_BASE_BINDING')
        before = current_manifest._jar_entries(Path(base['artifact_path']))
        after = current_manifest._jar_entries(Path(artifact['path']))
        root = current_manifest.PARSER_PATH.removeprefix('src/main/java/').removesuffix('.java')
        resource = 'BOOT-INF/classes/' + current_manifest.PROFILE_PATH.removeprefix('src/main/resources/')
        def editable(name):
            return name == resource or (name.startswith('BOOT-INF/classes/') and name.endswith('.class')
                and name.removeprefix('BOOT-INF/classes/').split('$', 1)[0].removesuffix('.class') == root)
        require({k:v for k,v in before.items() if not editable(k)} == {k:v for k,v in after.items() if not editable(k)},
                'UNRELATED_SERVICE_BYTES_CHANGED')
        measured = probe_artifact(artifact['path'], build['inputs']['image_id'], work / 'artifact-probe')
        require(measured['contract_digest'] == base['contract_digest'] and measured['parser_digest'] == artifact['parser_digest']
                and measured['profile_digest'] == artifact['profile_digest'], 'CANDIDATE_RUNTIME_CONTRACT_CHANGED')

        full_fixtures, oracles = [], []
        for target in targets:
            oracle = oracle_loader(json.loads(canonical(target)))
            payload = oracle.get('payload_json')
            require(oracle.get('target') == target and isinstance(payload, str)
                    and sha256(payload.encode()).hexdigest() == target['payload_digest'], 'ORIGINAL_CAPTURE_BINDING_CHANGED')
            raw = json.loads(payload)
            require(isinstance(raw, dict), 'ORIGINAL_CAPTURE_OBJECT_REQUIRED')
            expected = oracle.get('expected_parsed')
            require(isinstance(expected, dict) and set(expected) == PARSED_FIELDS, 'COMPLETE_INDEPENDENT_ORACLE_REQUIRED')
            require(oracle.get('kind') in ORACLE_KINDS and isinstance(oracle.get('reference'), str)
                    and 0 < len(oracle['reference']) <= 300 and not any(ord(c)<32 for c in oracle['reference']), 'ORACLE_PROVENANCE_REQUIRED')
            evidence = {'target': target, 'expected_parsed': expected, 'kind': oracle['kind'], 'reference': oracle['reference']}
            expected_digest = content_digest(expected)
            oracles.append({**target, 'expected_parsed_digest': expected_digest, 'oracle_kind': oracle['kind'],
                'oracle_reference': oracle['reference'], 'oracle_digest': content_digest(evidence)})
            full_fixtures.append({'id': target['capture_id'], 'raw': raw, 'external_id': target['external_id'],
                'expected': expected, 'oracle': {'kind': oracle['kind'], 'reference': oracle['reference']}, 'role': 'NEW_STRUCTURE'})
        current_validator.check_fixtures(full_fixtures)
        full = current_validator.compile_and_run(Path(deployment_manifest).resolve().parent / 'snapshot', candidate_files,
            full_fixtures, work / 'whole-captures', deployment['runtime'])
        require(full.get('status') == 'OK' and len(full.get('cases', [])) == len(targets), 'WHOLE_CAPTURE_EXECUTION_FAILED')
        # The second invocation must execute exactly the same compiled candidate.
        require(full.get('classes') == report['candidate']['classes'], 'WHOLE_CAPTURE_BINARY_CHANGED')
        for oracle, fixture, actual in zip(oracles, full_fixtures, full['cases']):
            value = actual.get('actual', {})
            parsed = {key:value[key] for key in PARSED_FIELDS if key in value}
            require(actual.get('id') == oracle['capture_id'] and 'error' not in actual
                    and set(parsed) == PARSED_FIELDS and content_digest(parsed) == content_digest(fixture['expected']),
                    'WHOLE_CAPTURE_ORACLE_FAILED')
            require(isinstance(actual.get('parsed_digest'), str) and SHA.fullmatch(actual['parsed_digest']), 'JAVA_PARSED_DIGEST_REQUIRED')
            # Only after comparing every independent expected field do we adopt
            # the Java serialization hash. Python/Jackson exponent formatting
            # can differ despite equal JSON values; replay uses Java canonical.
            oracle['expected_parsed_digest'] = actual['parsed_digest']
            diag = value.get('diagnostics', {})
            require(diag.get('assessment') == 'READY' and diag.get('targetVerified') is True
                    and diag.get('menuAllowed') is True, 'WHOLE_CAPTURE_NOT_READY')
        selected = [row for row in oracles if row['capture_id'] in canary_capture_ids]
        policy = {'mode': 'READ_ONLY', 'targets': [{k:row[k] for k in TARGET_FIELDS} for row in selected],
            'expected_result_digest': content_digest([{'capture_id': row['capture_id'], 'parsed_digest': row['expected_parsed_digest']} for row in selected])}
        plan = {'schema_version': 1, 'targets': oracles, 'canary_capture_ids': [row['capture_id'] for row in selected]}
        inventory = git_inventory(repository, base['source_commit'], candidate_files)
        patch = {}
        for path, text in candidate_files.items():
            old = git(repository, 'show', base['source_commit'] + ':' + path)
            if old != text.encode():
                patch[path] = {'old_sha256': sha256(old).hexdigest(), 'new_sha256': sha256(text.encode()).hexdigest()}
        require(bool(patch), 'EMPTY_PARSER_REPAIR')
        recipe_digest = content_digest({'base_build_recipe': build['inputs']['recipe_digest'],
            'candidate_build_input': report['build_input_digest'], 'probe': file_digest(PROBE), 'registry': file_digest(__file__)})
        validation = {'schema_version': 1, 'kind': 'FULL_SERVICE_CANDIDATE_VALIDATION',
            'base_build_receipt_digest': base['build_receipt_digest'], 'base_artifact_sha256': base['artifact_sha256'],
            'base_commit': base['source_commit'], 'candidate_artifact_sha256': artifact['sha256'],
            'candidate_parser_digest': measured['parser_digest'], 'candidate_profile_digest': measured['profile_digest'],
            'runtime_contract_digest': measured['contract_digest'], 'semantic_source_contract_digest': report['contract_digest'],
            'current_validation_digest': report['report_digest'], 'candidate_tree_digest': code_tree_digest(inventory, review_id),
            'patch_digest': content_digest(patch), 'build_recipe_digest': recipe_digest, 'capture_oracles': oracles}
        identity = content_digest(validation)
        frozen_jar = self.root / 'artifacts' / (artifact['sha256'] + '.jar')
        _write_once(frozen_jar, Path(artifact['path']).read_bytes())
        candidate = {'release_id': release_id, 'generation': generation, 'base_release': base_release,
            'base_commit': base['source_commit'], 'base_artifact_sha256': base['artifact_sha256'],
            'artifact_path': str(frozen_jar), 'artifact_sha256': artifact['sha256'],
            'parser_digest': measured['parser_digest'], 'profile_digest': measured['profile_digest'],
            'contract_digest': measured['contract_digest'], 'database_identity': base['database_identity'],
            'contract_version': measured['contract_version'], 'policy_digest': base['policy_digest'],
            'dataops_database_identity': base['dataops_database_identity'],
            'serving_database_identity': base['serving_database_identity'],
            'validation_digest': identity, 'candidate_tree_digest': validation['candidate_tree_digest'],
            'patch_digest': validation['patch_digest'], 'build_recipe_digest': recipe_digest, 'schema_changes': [],
            'canary_policy_digest': content_digest(policy), 'plan_digest': content_digest(plan),
            'review_id': review_id, 'targets': targets,
            'candidate_files': {path: candidate_files[path] for path in patch}, 'validation_work': str(work)}
        if preparation_request_digest is not None:
            candidate['preparation_request_digest'] = preparation_request_digest
        candidate['record_digest'] = content_digest(candidate)
        self._put('validations', identity, validation)
        self._put('plans', identity, plan)
        self._put('policies', content_digest(policy), policy)
        return self._put('candidates', identity, candidate)
