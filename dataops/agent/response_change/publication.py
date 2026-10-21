"""Prepare a report-only Git head and enqueue publication with the terminal transaction.

No network operation runs here. A separate trusted publisher may push this exact
head and deliver its outbox item. Report-only documents grant no release/write
authority even after merge. Current deployment binding is still required before
parser repair manifests can be prepared.
"""
from hashlib import sha256
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import subprocess
import tempfile

from .approval import (ApprovalHold, COMMON, DIGEST_FIELDS, ReviewManifest, canonical, COMMIT,
                       REPO, content_digest)
from .github_review import PreparedReview, render_review_body, render_terminal_review
from .ledger import digest


class GitReportPreparer:
    def __init__(self, repository_path, *, repository):
        self.root = Path(repository_path).resolve()
        if not REPO.fullmatch(repository):
            raise ApprovalHold('REPOSITORY')
        self.repository = repository
        origin = self._git('remote', 'get-url', 'origin').decode().strip()
        if origin not in {f'https://github.com/{repository}.git', f'https://github.com/{repository}',
                          f'git@github.com:{repository}.git'}:
            raise ApprovalHold('ORIGIN_REPOSITORY_MISMATCH')

    def _git(self, *arguments, data=None, env=None):
        result = subprocess.run(['git', '-C', str(self.root), '-c', 'core.hooksPath=/dev/null', *arguments],
            input=data, capture_output=True, timeout=30, env=env)
        if result.returncode:
            raise ApprovalHold('GIT_PREPARATION_FAILED')
        return result.stdout

    def prepare(self, *, base_commit, review_id, text):
        if not COMMIT.fullmatch(base_commit):
            raise ApprovalHold('BASE_COMMIT')
        branch = 'docs/response-agent-' + review_id
        if not all(c.isascii() and (c.isalnum() or c in '-_') for c in review_id) or not 1 <= len(review_id) <= 80:
            raise ApprovalHold('REVIEW_ID')
        path = f'docs/response-agent-reviews/{review_id}/review.md'
        raw = text.encode()
        if len(raw) > 256 * 1024:
            raise ApprovalHold('REVIEW_DOCUMENT_TOO_LARGE')
        if self._git('rev-parse', base_commit + '^{commit}').decode().strip() != base_commit:
            raise ApprovalHold('BASE_COMMIT')
        # Keep the user's index, checkout and branch completely separate. Plumbing
        # commands use a private index and do not run candidate repository hooks.
        with tempfile.TemporaryDirectory(prefix='response-agent-git-') as directory:
            env = {**os.environ, 'GIT_INDEX_FILE': str(Path(directory) / 'index')}
            self._git('read-tree', base_commit, env=env)
            blob = self._git('hash-object', '-w', '--stdin', data=raw, env=env).decode().strip()
            self._git('update-index', '--add', '--cacheinfo', f'100644,{blob},{path}', env=env)
            tree = self._git('write-tree', env=env).decode().strip()
            reference = 'refs/heads/' + branch
            existing = subprocess.run(['git', '-C', str(self.root), 'show-ref', '--verify', '--hash', reference],
                                      capture_output=True, text=True, timeout=10)
            if existing.returncode == 0:
                head = existing.stdout.strip()
                if (self._git('rev-parse', head + '^{tree}').decode().strip() != tree
                        or self._git('rev-parse', head + '^').decode().strip() != base_commit):
                    raise ApprovalHold('PREPARED_BRANCH_CHANGED')
            else:
                head = self._git('commit-tree', tree, '-p', base_commit,
                    '-m', '📝 docs: 외부 응답 변경 에이전트 검수 결과 기록', env=env).decode().strip()
                # Create-only CAS: another writer's ref must never be overwritten.
                self._git('update-ref', reference, head, '0' * 40, env=env)
        return {'head_branch': branch, 'head_sha': head, 'path': path, 'sha256': sha256(raw).hexdigest()}

    def push_prepared(self, request):
        """Explicit trusted publication step; exact new ref only, never force push."""
        if request.manifest.document['kind'] != 'REPORT_ONLY':
            raise ApprovalHold('REPORT_PUBLISHER_SCOPE')
        head = self._git('rev-parse', 'refs/heads/' + request.head_branch).decode().strip()
        if head != request.head_sha:
            raise ApprovalHold('PREPARED_HEAD_CHANGED')
        self._git('push', 'origin', request.head_sha + ':refs/heads/' + request.head_branch)


class ReportPublicationSink:
    """Injected into PgDispatcher; all DB writes share its terminal transaction.

    Git objects/refs may survive a rolled-back transaction. prepare() reconciles
    their exact tree and parent on retry, without a new model call or overwritten
    branch. No network/PR creation occurs while a database lock is held.
    """
    def __init__(self, *, store, preparer, base_commit, base_branch, evidence_origin, expires_at=None, review_ttl_seconds=None):
        if evidence_origin not in {'CONTROLLED_SYNTHETIC_NEW_TYPENAME', 'CAPTURED_PRODUCTION_INCIDENT', 'HISTORICAL_REPLAY'}:
            raise ApprovalHold('EVIDENCE_ORIGIN')
        self.store, self.preparer = store, preparer
        self.base_commit, self.base_branch = base_commit, base_branch
        self.expires_at, self.evidence_origin = expires_at, evidence_origin
        if expires_at is None:
            if type(review_ttl_seconds) is not int or not 60 <= review_ttl_seconds <= 2592000:
                raise ApprovalHold('REVIEW_TTL_INVALID')
        elif review_ttl_seconds is not None:
            raise ApprovalHold('CHOOSE_REVIEW_EXPIRY_OR_TTL')
        self.review_ttl_seconds = review_ttl_seconds

    def __call__(self, cursor, claim, report):
        expires_at = self.expires_at
        if expires_at is None:
            # The immutable DB creation time also covers unsupported cases that
            # never created a Runner report. Process restart cannot renew TTL.
            cursor.execute('SELECT created_at FROM response_agent_run WHERE run_id=%s', (claim.run_id,))
            row = cursor.fetchone()
            created = row.get('created_at') if isinstance(row,dict) else row[0] if row else None
            if not isinstance(created,datetime) or created.tzinfo is None:
                raise ApprovalHold('REVIEW_CREATION_TIME_REQUIRED')
            expires_at = (created + timedelta(seconds=self.review_ttl_seconds)).isoformat()
        case_path = Path(claim.run_directory) / 'case.json'
        case = json.loads(case_path.read_text()) if case_path.exists() else {}
        if (case_path.exists() or report.get('case_digest')) and digest(case) != report.get('case_digest'):
            raise ApprovalHold('CASE_BINDING_CHANGED')
        differences = []
        if case.get('recipe') == 'complete-fixed-won-menu-entity-v1':
            for typename in sorted({row['unfamiliar_entity'] for row in case.get('samples', [])}):
                differences.append({'path': 'menus[].__typename', 'before_type': 'STRING', 'after_type': 'STRING',
                    'before_typename': 'PlaceMenuItem', 'after_typename': typename})
        # Fenced recovery changes delivery epoch/path and recalculates account
        # summaries. Those are transport metadata, not a new analysis revision.
        # Keep the execution evidence immutable so a pre-commit crash can reuse
        # the exact Git head rather than changing its document hash forever.
        execution = {key: value for key, value in report.items()
                     if key not in {'dispatch_export', 'dispatch_epoch', 'budget_ledger'}}
        review = render_terminal_review(execution, case, differences)
        origin = {'CONTROLLED_SYNTHETIC_NEW_TYPENAME': '통제한 합성 타입 변경 시험이며 자연발생 운영 사건이 아닙니다.',
                  'CAPTURED_PRODUCTION_INCIDENT': '저장된 운영 수집 원본에서 감지한 사건입니다.',
                  'HISTORICAL_REPLAY': '저장된 과거 원본을 이용한 재현 시험입니다.'}[self.evidence_origin]
        review += '\n## 원본과 승인 범위\n\n' + origin + '\n\n'
        review += '현재 문서는 REPORT_ONLY입니다. 병합해도 배포·재처리·운영 데이터 반영 권한이 생기지 않습니다.\n'
        review_id = 'run-' + claim.run_id
        prepared = self.preparer.prepare(base_commit=self.base_commit, review_id=review_id, text=review)
        document = {key: None for key in DIGEST_FIELDS}
        document.update(schema_version=1, review_id=review_id, kind='REPORT_ONLY', scope='NONE',
            incident_id=claim.fingerprint, run_id=claim.run_id, revision=1,
            base_release='unverified-runtime', base_commit=self.base_commit, expires_at=expires_at,
            files={prepared['path']: prepared['sha256']}, targets=[], row_limit=0, preview_digest=prepared['sha256'])
        manifest = ReviewManifest(document)
        request = PreparedReview(manifest, prepared['head_branch'], prepared['head_sha'], self.base_branch,
            '[DOCS] 외부 응답 변경 에이전트 실행 결과 검토', render_review_body(manifest))
        self.store.freeze(cursor, manifest, validated=False)
        self.store.enqueue(cursor, manifest_digest=manifest.digest, event_kind='TERMINAL_REVIEW',
            destination=self.preparer.repository, revision=1, payload={'manifest': manifest.document,
                'prepared': {key: getattr(request, key) for key in
                    ('head_branch', 'head_sha', 'base_branch', 'title', 'body')}})
        return manifest
