"""Prepare exact parser/data approval files from trusted registry/journal evidence.

This module creates local Git objects and freezes an outbox request in the
caller's DB transaction. It never pushes, creates/merges a PR, consumes an
approval, deploys, or writes serving data. The existing authenticated
ApprovalVerifier and publisher remain the only path from merge to authority.
"""
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import tempfile

from psycopg2.extras import RealDictCursor

from .approval import (ApprovalHold, ID, ReviewManifest, content_digest, code_tree_digest,
                       render_data_manifest, render_data_preview)
from .full_registry import require, git_inventory
from .github_review import PreparedReview, render_review_body
from .publication import GitReportPreparer


class GitExactReviewPreparer(GitReportPreparer):
    def prepare_exact(self, manifest, files):
        doc = manifest.document
        require(doc['kind'] in {'PARSER_REPAIR', 'DATA_APPLY'}, 'EXACT_REVIEW_SCOPE')
        require(set(files) == set(doc['files']) and all(isinstance(v, str) and len(v.encode()) <= 256*1024 for v in files.values()),
                'EXACT_REVIEW_FILES')
        require({path: sha256(value.encode()).hexdigest() for path,value in files.items()} == doc['files'], 'EXACT_REVIEW_FILE_DIGEST')
        require(self._git('rev-parse', doc['base_commit']+'^{commit}').decode().strip() == doc['base_commit'], 'BASE_COMMIT')
        branch = ('fix/' if doc['kind'] == 'PARSER_REPAIR' else 'docs/') + 'response-agent-' + doc['review_id']
        with tempfile.TemporaryDirectory(prefix='response-agent-exact-git-') as directory:
            env = {**os.environ, 'GIT_INDEX_FILE': str(Path(directory)/'index')}
            self._git('read-tree', doc['base_commit'], env=env)
            for path,value in files.items():
                blob = self._git('hash-object','-w','--stdin',data=value.encode(),env=env).decode().strip()
                self._git('update-index','--add','--cacheinfo',f'100644,{blob},{path}',env=env)
            tree = self._git('write-tree',env=env).decode().strip()
            entries = {}
            for row in self._git('ls-tree','-rz',tree).split(b'\0'):
                if not row: continue
                meta,path = row.split(b'\t',1); mode,kind,identity=meta.decode().split()
                require(kind=='blob','UNSUPPORTED_TREE_ENTRY')
                entries[path.decode()]={'sha':identity,'mode':mode}
            require(code_tree_digest(entries,doc['review_id']) == doc['candidate_tree_digest'], 'EXACT_REVIEW_TREE_CHANGED')
            changed = self._git('diff-tree','--no-commit-id','--name-only','-r',doc['base_commit'],tree).decode().splitlines()
            require(set(changed)==set(files), 'EXACT_REVIEW_CHANGED_FILES')
            reference = 'refs/heads/'+branch
            existing = subprocess.run(['git','-C',str(self.root),'show-ref','--verify','--hash',reference],capture_output=True,text=True,timeout=10)
            if existing.returncode == 0:
                head=existing.stdout.strip()
                require(self._git('rev-parse',head+'^{tree}').decode().strip()==tree
                        and self._git('rev-parse',head+'^').decode().strip()==doc['base_commit'], 'PREPARED_BRANCH_CHANGED')
            else:
                message = ('🐛 fix: 외부 응답 구조 변경 파서 검수안' if doc['kind']=='PARSER_REPAIR'
                           else '📝 docs: 저장 원본 재처리 변경 값 검수안')
                head=self._git('commit-tree',tree,'-p',doc['base_commit'],'-m',message,env=env).decode().strip()
                self._git('update-ref',reference,head,'0'*40,env=env)
        return {'head_branch':branch,'head_sha':head}

    def push_prepared(self, request):
        require(request.manifest.document['kind'] in {'PARSER_REPAIR','DATA_APPLY'}, 'EXACT_PUBLISHER_SCOPE')
        head=self._git('rev-parse','refs/heads/'+request.head_branch).decode().strip()
        require(head==request.head_sha,'PREPARED_HEAD_CHANGED')
        self._git('push','origin',request.head_sha+':refs/heads/'+request.head_branch)


class ExactReviewPreparation:
    def __init__(self, *, registry, preparer, store, base_branch):
        self.registry, self.preparer, self.store, self.base_branch = registry, preparer, store, base_branch

    def _freeze(self, cursor, document, files):
        document['files']={p:sha256(v.encode()).hexdigest() for p,v in files.items()}
        preview=f"docs/response-agent-reviews/{document['review_id']}/review.md"
        document['preview_digest']=document['files'][preview]
        document['row_limit']=len(document['targets'])
        document['replay_target_manifest_digest']=content_digest(document['targets'])
        manifest=ReviewManifest(document)
        prepared=self.preparer.prepare_exact(manifest,files)
        title='[FIX] 외부 응답 구조 변경 파서 검토' if document['kind']=='PARSER_REPAIR' else '[DOCS] 재처리 가게 정보 변경 검토'
        request=PreparedReview(manifest,prepared['head_branch'],prepared['head_sha'],self.base_branch,title,render_review_body(manifest))
        self.store.freeze(cursor,manifest,validated=True)
        self.store.enqueue(cursor,manifest_digest=manifest.digest,event_kind='TERMINAL_REVIEW',destination=self.preparer.repository,
            revision=document['revision'],payload={'manifest':manifest.document,'prepared':{
                key:getattr(request,key) for key in ('head_branch','head_sha','base_branch','title','body')}})
        return request

    def parser(self, cursor, *, validation_digest, incident_id, run_id, revision, expires_at):
        candidate=self.registry.validated_candidate(validation_digest)
        validation=self.registry.validation_document(validation_digest)
        policy=self.registry.canary_policy(candidate['canary_policy_digest'])
        review_id=candidate['review_id']
        # Values below are host-produced identity/count evidence, never model prose.
        review=("# 외부 응답 파서 변경 검토\n\n"
            "병합 승인 범위는 고정 JAR 배포, 표본 검증, 저장 원본 재처리와 변경 제안 생성입니다. "
            "실제 가게 정보 쓰기는 별도 DATA_APPLY 검토가 필요합니다.\n\n"
            f"- 전체 서비스 기준 빌드: `{validation['base_build_receipt_digest']}`\n"
            f"- 후보 JAR: `{candidate['artifact_sha256']}`\n"
            f"- 독립 검증: `{validation_digest}`\n"
            f"- 파서 기존 정상 회귀·새 형식 고정 검증: 통과\n"
            f"- 저장 원본 전체 ParsedPlace 독립 비교: {len(candidate['targets'])}건 통과\n"
            f"- 승인 후 canary: {len(policy['targets'])}건\n"
            "- 실제 운영 배포·원본 재처리·운영 반영: 이 문서 생성 시점에는 실행하지 않음\n\n"
            "## 고정 재처리 대상과 독립 oracle 근거\n\n```json\n"
            +json.dumps(validation['capture_oracles'],ensure_ascii=False,sort_keys=True,indent=2)+"\n```\n")
        # References are not executable instructions; reject markup breakout in this fixed renderer.
        require(all('`' not in row['oracle_reference'] for row in validation['capture_oracles']), 'ORACLE_REFERENCE_REVIEW_ENCODING')
        files={**candidate['candidate_files'],f'docs/response-agent-reviews/{review_id}/review.md':review}
        document={'schema_version':1,'review_id':review_id,'kind':'PARSER_REPAIR','scope':'DEPLOY_REPLAY_PROPOSE',
            'incident_id':incident_id,'run_id':run_id,'revision':revision,'base_release':candidate['base_release'],
            'base_commit':candidate['base_commit'],'expires_at':expires_at,'targets':candidate['targets'],
            'candidate_tree_digest':candidate['candidate_tree_digest'],'patch_digest':candidate['patch_digest'],
            'structure_profile_digest':candidate['profile_digest'],'semantic_contract_digest':candidate['contract_digest'],
            'validation_digest':validation_digest,'build_recipe_digest':candidate['build_recipe_digest'],
            'artifact_sha256':candidate['artifact_sha256'],'canary_policy_digest':candidate['canary_policy_digest']}
        return self._freeze(cursor,document,files)

    def data(self, cursor, *, validation_digest, replay_receipt, proposal_loader, review_id, base_commit,
             incident_id, run_id, revision, expires_at):
        """proposal_loader reads durable CREATED lineage and current proposal values.

        A ReplayBridgeRunner response only lists IDs; it cannot invent old/new
        values or make a REUSED proposal a child of this replay operation.
        The execution gate must still recheck freshness and CAS at apply time.
        """
        candidate=self.registry.validated_candidate(validation_digest)
        receipt=replay_receipt
        require(receipt.get('outcome')=='DONE' and receipt.get('complete') is True and receipt.get('phase')=='REPLAY'
                and receipt.get('operating_writes')==0, 'REPLAY_COMPLETION_REQUIRED')
        require(receipt.get('targets_digest')==content_digest(candidate['targets']), 'REPLAY_TARGET_SCOPE_CHANGED')
        runtime=receipt.get('runtime',{})
        require(all(runtime.get(k)==candidate[k] for k in ('release_id','artifact_sha256','parser_digest','profile_digest',
                    'contract_digest','database_identity')), 'REPLAY_RUNTIME_CHANGED')
        captures={row['capture_id']:row for row in candidate['targets']}
        items=receipt.get('items',[])
        require(len(items)==len(captures) and len({row['capture_id'] for row in items})==len(items)
                and {row['capture_id'] for row in items}==set(captures), 'REPLAY_ITEMS_CHANGED')
        targets=[]
        for item in items:
            require(item.get('status') in {'PROPOSED','NO_CHANGE','STALE','HELD','NOT_ATTEMPTED'}, 'REPLAY_ITEM_UNRESOLVED')
            created=item.get('created_proposal_ids') or []
            reused=item.get('reused_proposal_ids') or []
            require(not created or item['status']=='PROPOSED', 'REPLAY_CREATED_STATE')
            require(not set(created)&set(reused), 'REPLAY_PROPOSAL_RELATION_CONFLICT')
            for proposal_id in created:
                row=proposal_loader(receipt['replay_operation_id'],item['capture_id'],proposal_id)
                target=row.get('target',{})
                capture=captures[item['capture_id']]
                require(row.get('relation')=='CREATED' and row.get('state')=='PENDING', 'CREATED_PENDING_PROPOSAL_REQUIRED')
                require(row.get('validation_digest')==validation_digest and row.get('manifest_digest')==receipt.get('manifest_digest'),
                        'REPLAY_APPROVAL_LINEAGE_CHANGED')
                expected={'proposal_id':proposal_id,'capture_id':item['capture_id'],'capture_digest':capture['payload_digest'],
                    'store_id':capture['store_id'],'external_id':capture['external_id'],'release_id':candidate['release_id'],
                    'reprocess_item_id':receipt['replay_operation_id']}
                require(all(target.get(k)==v for k,v in expected.items()), 'DATA_PROPOSAL_LINEAGE_CHANGED')
                targets.append(target)
        require(bool(targets), 'NO_NEW_PENDING_DATA_TARGETS')
        targets.sort(key=lambda row:row['proposal_id'])
        data=render_data_manifest(targets); preview=render_data_preview(targets)
        prefix=f'docs/response-agent-reviews/{review_id}/'
        document={'schema_version':1,'review_id':review_id,'kind':'DATA_APPLY','scope':'APPLY_EXACT_MANIFEST',
            'incident_id':incident_id,'run_id':run_id,'revision':revision,'base_release':candidate['release_id'],
            'base_commit':base_commit,'expires_at':expires_at,'targets':targets,
            'candidate_tree_digest':code_tree_digest(git_inventory(self.preparer.root,base_commit),review_id),
            'patch_digest':content_digest({'data-manifest':sha256(data.encode()).hexdigest()}),
            'structure_profile_digest':candidate['profile_digest'],'semantic_contract_digest':candidate['contract_digest'],
            'validation_digest':validation_digest,'build_recipe_digest':candidate['build_recipe_digest'],
            'artifact_sha256':candidate['artifact_sha256'],'canary_policy_digest':candidate['canary_policy_digest']}
        return self._freeze(cursor,document,{prefix+'review.md':preview,prefix+'data-manifest.json':data})


class PgReplayProposalLoader:
    """Read-only source for exact replay proposal values. No write/approval capability."""
    def __init__(self, connect): self.connect=connect

    def __call__(self, operation_id, capture_id, proposal_id):
        connection=self.connect()
        try:
            connection.set_session(readonly=True)
            with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute("""SELECT p.relation,p.original_proposal_json,r.release_id,r.manifest_digest,m.document,c.store_id,c.external_id,c.payload_digest,
                    c.payload,q.status,q.target,q.old_value::text AS old_value,q.new_value::text AS new_value
                    FROM response_replay_proposal p JOIN response_replay_run r USING(operation_id)
                    JOIN response_agent_review_manifest m ON m.manifest_digest=r.manifest_digest AND m.kind='PARSER_REPAIR'
                    JOIN response_replay_receipt receipt ON receipt.operation_id=p.operation_id AND receipt.capture_id=p.capture_id AND receipt.phase='REPLAY'
                    JOIN response_replay_completion done ON done.operation_id=p.operation_id AND done.phase='REPLAY'
                    JOIN response_replay_completion canary ON canary.operation_id=p.operation_id AND canary.phase='CANARY' AND canary.passed
                    JOIN response_capture c ON c.capture_id=p.capture_id
                    JOIN change_proposal q ON q.id=p.proposal_id AND q.store_id=c.store_id
                    WHERE p.operation_id=%s AND p.capture_id=%s AND p.proposal_id=%s AND receipt.status='PROPOSED'""",
                    (operation_id,capture_id,proposal_id))
                row=cursor.fetchone()
                require(row is not None, 'REPLAY_PROPOSAL_UNAVAILABLE')
                old=json.loads(row['old_value']) if row['old_value'] is not None else None
                new=json.loads(row['new_value']) if row['new_value'] is not None else None
                original=row['original_proposal_json']
                require(original['target']==row['target'] and
                    (json.loads(original['oldValue']) if original['oldValue'] is not None else None)==old and
                    (json.loads(original['newValue']) if original['newValue'] is not None else None)==new, 'REPLAY_PROPOSAL_VALUE_CHANGED')
                require(sha256(row['payload'].encode()).hexdigest()==row['payload_digest'], 'REPLAY_CAPTURE_CHANGED')
                return {'relation':row['relation'],'state':row['status'],'manifest_digest':row['manifest_digest'],
                    'validation_digest':row['document']['validation_digest'],'target':{
                    'proposal_id':proposal_id,'store_id':row['store_id'],'external_id':row['external_id'],
                    'operation':'UPDATE','field':row['target'],'old_value':old,'new_value':new,'capture_id':capture_id,
                    'capture_digest':row['payload_digest'],'release_id':row['release_id'],'reprocess_item_id':operation_id}}
        finally:
            connection.close()
