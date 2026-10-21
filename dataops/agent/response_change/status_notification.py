"""Durable informational comments on an already bound review PR.

Only the publisher calls GitHub. Immutable public summaries contain host status
codes/counts/opaque identities, never model prose, raw data, command output or
secrets. Unknown POST results are reconciled by a fixed marker, never reposted.
"""
from dataclasses import dataclass
import json
from pathlib import Path
import re
import tempfile

from psycopg2.extras import Json, RealDictCursor

from .approval import ApprovalHold, ID, SHA, PublicationBinding, OutboxLeaseLost, content_digest
from .github_review import DeliveryUnknown

KINDS = {'PREPARE_PARSER':'파서 검토안 준비','ADVANCE_REVIEW':'승인된 작업 처리',
         'PREPARE_DATA':'데이터 반영 검토안 준비','PROMOTE_BASELINE':'다음 수집 기준 버전 갱신'}
SAFE_CODE = re.compile(r'[A-Z0-9_]{1,160}\Z')


def require(condition, code):
    if not condition:
        raise ApprovalHold(code)


def summary(job, state, result):
    require(job['kind'] in KINDS and state in {'DONE','HELD','UNKNOWN'}, 'STATUS_NOTIFICATION_SCOPE')
    value = {'version':1, 'kind':job['kind'], 'state':state, 'run_id':str(job['run_id'])}
    for name in ('reason_code','hold_code','controller_state','outcome'):
        item = result.get(name)
        if isinstance(item,str):
            value[name] = item if SAFE_CODE.fullmatch(item) else 'DETAILS_REQUIRE_LOCAL_REVIEW'
    for name in ('operation_id','pending_action','review_id'):
        item = result.get(name)
        if isinstance(item,str) and ID.fullmatch(item):
            value[name] = item
    for name in ('manifest_digest','validation_digest','pointer_digest'):
        item = result.get(name)
        if isinstance(item,str) and SHA.fullmatch(item):
            value[name] = item
    for name in ('applied_count','operating_writes'):
        item = result.get(name)
        if type(item) is int and 0 <= item <= 1_000_000_000:
            value[name] = item
    return value


def enqueue_status(cursor, job, sequence, state, result):
    """Called inside the terminal job/event transaction; identical summaries dedupe."""
    if state not in {'DONE','HELD','UNKNOWN'}:
        return
    payload = summary(job,state,result)
    identity = content_digest({'job_id':job['job_id'],'event_sequence':sequence})
    digest = content_digest(payload)
    cursor.execute('''INSERT INTO response_agent_status_notification
        (event_key,job_id,event_sequence,run_id,manifest_digest,payload,payload_digest,marker)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING''',
        (identity,job['job_id'],sequence,str(job['run_id']),job['request'].get('manifest_digest'),
         Json(payload),digest,'<!-- response-agent-status:'+identity+' -->'))
    cursor.execute('SELECT payload FROM response_agent_status_notification WHERE job_id=%s AND payload_digest=%s', (job['job_id'],digest))
    row=cursor.fetchone()
    require(row is not None and (row['payload'] if isinstance(row,dict) else row[0]) == payload, 'STATUS_NOTIFICATION_CONFLICT')


def render_status(payload, marker):
    require(isinstance(payload,dict) and payload.get('kind') in KINDS and payload.get('state') in {'DONE','HELD','UNKNOWN'}, 'STATUS_NOTIFICATION_SCOPE')
    # Re-encoding through the allowlist proves no hidden/free-form field reaches GitHub.
    job={'kind':payload['kind'],'run_id':payload.get('run_id')}
    require(isinstance(payload.get('run_id'),str) and ID.fullmatch(payload['run_id'])
            and summary(job,payload['state'],payload)==payload, 'STATUS_NOTIFICATION_PAYLOAD')
    require(re.fullmatch(r'<!-- response-agent-status:[0-9a-f]{64} -->',marker), 'STATUS_NOTIFICATION_MARKER')
    state={'DONE':'해당 단계 완료','HELD':'검토 필요 · 보류','UNKNOWN':'결과 미확정 · 자동 재실행 중지'}[payload['state']]
    lines=[f"**에이전트 처리 상태: {state}**",'',f"- 단계: {KINDS[payload['kind']]}",f"- 실행: `{payload['run_id']}`"]
    labels={'controller_state':'실행 원장 상태','outcome':'처리 결과','reason_code':'보류 이유','hold_code':'실행 보류 이유',
        'operation_id':'작업 ID','pending_action':'미확정 동작','review_id':'검토안 ID','manifest_digest':'검토안 digest',
        'validation_digest':'검증 digest','pointer_digest':'기준 버전 digest','applied_count':'확정 반영 건수','operating_writes':'이 단계의 운영 쓰기 건수'}
    for name,label in labels.items():
        if name in payload:
            lines.append(f'- {label}: `{payload[name]}`')
    lines += ['', '이 알림은 처리 결과 설명입니다. 기존 검토 파일·승인 범위는 바뀌지 않습니다.', marker]
    return '\n'.join(lines)+'\n'


@dataclass(frozen=True)
class StatusClaim:
    event_key: str
    owner: str
    epoch: int
    marker: str
    payload: dict
    manifest_digest: str | None
    run_id: str
    reconciliation_required: bool


class PgStatusStore:
    def __init__(self,connect): self.connect=connect

    def claim(self,owner,lease_seconds=300):
        require(isinstance(owner,str) and ID.fullmatch(owner) and type(lease_seconds) is int and 60<=lease_seconds<=300, 'STATUS_LEASE_INVALID')
        connection=self.connect()
        try:
            with connection,connection.cursor(cursor_factory=RealDictCursor) as cursor:
                cursor.execute('''SELECT * FROM response_agent_status_notification
                    WHERE state IN ('PENDING','SENDING','DELIVERY_UNKNOWN') AND next_attempt_at<=CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at,created_at,event_key LIMIT 1 FOR UPDATE SKIP LOCKED''')
                row=cursor.fetchone()
                if row is None:return None
                require(content_digest(row['payload'])==row['payload_digest'],'STATUS_PAYLOAD_CHANGED')
                render_status(row['payload'],row['marker'])
                cursor.execute('''UPDATE response_agent_status_notification SET state='SENDING',owner=%s,
                    fencing_epoch=fencing_epoch+1,lease_until=CURRENT_TIMESTAMP+(%s*interval '1 second'),updated_at=CURRENT_TIMESTAMP
                    WHERE event_key=%s RETURNING fencing_epoch''',(owner,lease_seconds,row['event_key']))
                return StatusClaim(row['event_key'],owner,cursor.fetchone()['fencing_epoch'],row['marker'],row['payload'],
                    row['manifest_digest'],str(row['run_id']),row['state']!='PENDING')
        finally:connection.close()

    def binding(self,claim,repository):
        connection=self.connect()
        try:
            connection.set_session(readonly=True)
            with connection,connection.cursor() as cursor:
                if claim.manifest_digest:
                    cursor.execute('''SELECT p.repository,p.pr_number,p.head_sha,p.manifest_digest FROM response_agent_review_publication p
                        JOIN response_agent_review_manifest m USING(manifest_digest) WHERE p.manifest_digest=%s AND m.run_id=%s''',
                        (claim.manifest_digest,claim.run_id))
                else:
                    cursor.execute('''SELECT p.repository,p.pr_number,p.head_sha,p.manifest_digest FROM response_agent_review_manifest m
                        JOIN response_agent_review_publication p USING(manifest_digest)
                        WHERE m.run_id=%s AND m.kind='REPORT_ONLY' AND m.revision=1''',(claim.run_id,))
                row=cursor.fetchone()
                if row is None:return None
                require(row[0]==repository,'STATUS_REPOSITORY_CHANGED')
                return PublicationBinding(*row)
        finally:connection.close()

    def renew(self,claim):
        connection=self.connect()
        try:
            with connection,connection.cursor() as cursor:
                cursor.execute('''UPDATE response_agent_status_notification SET lease_until=CURRENT_TIMESTAMP+interval '300 seconds'
                    WHERE event_key=%s AND owner=%s AND fencing_epoch=%s AND state='SENDING' AND lease_until>CURRENT_TIMESTAMP''',
                    (claim.event_key,claim.owner,claim.epoch))
                if cursor.rowcount!=1:raise OutboxLeaseLost('STATUS_LEASE_LOST')
        finally:connection.close()

    def finish(self,claim,*,reference=None,code=None,not_sent=False):
        state='DELIVERED' if reference else 'PENDING' if not_sent else 'DELIVERY_UNKNOWN'
        connection=self.connect()
        try:
            with connection,connection.cursor() as cursor:
                cursor.execute('''UPDATE response_agent_status_notification SET state=%s,provider_reference=%s,error_code=%s,
                    lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+interval '60 seconds',updated_at=CURRENT_TIMESTAMP
                    WHERE event_key=%s AND owner=%s AND fencing_epoch=%s AND state='SENDING' AND lease_until>CURRENT_TIMESTAMP''',
                    (state,Json(reference) if reference else None,code,claim.event_key,claim.owner,claim.epoch))
                if cursor.rowcount!=1:raise OutboxLeaseLost('STATUS_LEASE_LOST')
        finally:connection.close()

    def preview(self):
        connection=self.connect()
        try:
            connection.set_session(readonly=True)
            with connection,connection.cursor() as cursor:
                cursor.execute('SELECT state,count(*) FROM response_agent_status_notification GROUP BY state')
                return {'mode':'PREVIEW','states':dict(cursor.fetchall()),'github_writes':0}
        finally:connection.close()


class StatusReadUnavailable(DeliveryUnknown): pass


class GitHubStatusPublisher:
    def __init__(self,adapter): self.adapter=adapter

    def _find(self,binding,body,marker):
        found=[]
        for page in range(1,11):
            rows=self.adapter._api(f'issues/{binding.pr_number}/comments?per_page=100&page={page}')
            require(isinstance(rows,list),'STATUS_COMMENTS_UNVERIFIED')
            for row in rows:
                if marker in (row.get('body') or ''):
                    require(row.get('body')==body and type(row.get('id')) is int and row['id']>0,'STATUS_COMMENT_CHANGED')
                    url=f'https://github.com/{binding.repository}/pull/{binding.pr_number}#issuecomment-{row["id"]}'
                    found.append({'repository':binding.repository,'pr_number':binding.pr_number,'comment_id':row['id'],'url':url})
            if len(rows)<100:break
        else:raise DeliveryUnknown('STATUS_COMMENT_SCAN_LIMIT')
        require(len(found)<=1,'STATUS_DUPLICATE_MARKER')
        return found[0] if found else None

    def publish(self,claim,binding,before_write):
        body=render_status(claim.payload,claim.marker)
        try:
            pr=self.adapter._api(f'pulls/{binding.pr_number}')
            require(pr.get('number')==binding.pr_number and pr['base']['repo']['full_name']==binding.repository
                    and pr['head']['sha']==binding.head_sha,'STATUS_PR_BINDING_CHANGED')
            existing=self._find(binding,body,claim.marker)
            if existing:return existing
        except (ApprovalHold,DeliveryUnknown):
            if claim.reconciliation_required:raise
            raise StatusReadUnavailable('STATUS_READ_UNAVAILABLE') from None
        if claim.reconciliation_required:
            raise DeliveryUnknown('STATUS_DELIVERY_UNCONFIRMED')
        with tempfile.TemporaryDirectory(prefix='response-agent-status-') as directory:
            path=Path(directory)/'comment.json';path.write_text(json.dumps({'body':body},ensure_ascii=False));path.chmod(0o600)
            before_write()
            self.adapter.transport(['api',f'repos/{binding.repository}/issues/{binding.pr_number}/comments','--method','POST','--input',str(path)])
        reference=self._find(binding,body,claim.marker)
        if reference is None:raise DeliveryUnknown('STATUS_DELIVERY_UNCONFIRMED')
        return reference


def deliver_status(store,publisher,claim,repository):
    try:
        binding=store.binding(claim,repository)
        if binding is None:
            store.finish(claim,code='STATUS_PR_NOT_PUBLISHED',not_sent=not claim.reconciliation_required)
            return None
        reference=publisher.publish(claim,binding,lambda:store.renew(claim))
        store.finish(claim,reference=reference)
        return reference
    except StatusReadUnavailable:
        store.finish(claim,code='STATUS_READ_UNAVAILABLE',not_sent=True)
    except (ApprovalHold,DeliveryUnknown):
        store.finish(claim,code='STATUS_DELIVERY_UNKNOWN')
    return None
