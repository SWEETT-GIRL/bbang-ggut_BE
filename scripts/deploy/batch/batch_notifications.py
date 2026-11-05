"""DataOps invocation notices. Only allowlisted counts are sent; delivery never reruns work."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4

COUNTS = ('attempted', 'changed', 'unchanged', 'partial', 'held', 'failed')
ARTIFACT_COUNTS = {
    'public_raw': '공공 원본', 'public_new': '신규 관리번호', 'public_upd': '상태 변경',
    'candidates_inserted': '후보 저장', 'candidates_existing': '기존 후보', 'candidates_held': '후보 보류',
    'closures_proposed': '폐업 검토 제안', 'closures_unknown': '폐업 판단 불가',
    'baseline_stores': '비교 대상 가게', 'baseline_mapped': '인허가 연결',
}
WORKFLOWS = {'freshness-cycle', 'freshness-baseline', 'freshness-refresh', 'freshness-public',
             'freshness-discovery', 'nightly', 'collect-only'}
LABELS = {'COMPLETED': '성공', 'FAILED': '실패', 'BLOCKED': '차단', 'INCOMPLETE': '미완료',
          'INTERRUPTED': '중단', 'REJECTED_OVERLAP': '중복 실행 거절', 'RUNNING': '종료 기록 미확인'}


def selected_command(command):
    if command[0] == 'dataops-nightly':
        return 'dataops-nightly'
    if command[0] == 'dataops-cycle':
        operation = command[1] if len(command) > 1 and not command[1].startswith('-') else 'run'
        if operation in ('run', 'baseline'):
            return 'dataops-cycle ' + operation
    return None


def status(code):
    if code == 0: return 'COMPLETED'
    if code == 3: return 'BLOCKED'
    if code == 4: return 'INCOMPLETE'
    if code == 75: return 'REJECTED_OVERLAP'
    if code < 0 or code in (130, 143): return 'INTERRUPTED'
    return 'FAILED'


def counts(values, keys):
    if not isinstance(values, dict): return {}
    return {key: values[key] for key in keys if type(values.get(key)) is int and values[key] >= 0}


def summaries(logs, invocation_id):
    result, invalid = [], 0
    # Invocation-prefixed filenames avoid scanning years of previous journals.
    for path in sorted((Path(logs) / 'weekly/runs').glob(invocation_id + '_*.json')):
        try:
            if path.stat().st_size > 1024 * 1024: raise ValueError('oversized journal')
            record = json.loads(path.read_text())
            if not isinstance(record, dict) or not isinstance(record.get('status'), str) or record['status'] not in LABELS:
                raise ValueError('invalid journal status')
            if record.get('invocation_id') != invocation_id or record.get('command') not in WORKFLOWS:
                raise ValueError('mismatched journal')
            row = {'workflow': record['command'], 'status': record.get('status', 'RUNNING'),
                   'current': counts(record.get('items_current'), COUNTS),
                   'artifacts': counts(record.get('items'), ARTIFACT_COUNTS)}
            if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', str(record.get('cycle_id', ''))):
                row['cycle_id'] = record['cycle_id']
            skipped = record.get('skipped_tracks', [])
            if not isinstance(skipped, list): raise ValueError('invalid skipped tracks')
            row['skipped_tracks'] = [t for t in skipped
                                     if t in ('refresh', 'public', 'discovery')]
            result.append(row)
        except (OSError, ValueError, TypeError, KeyError):
            invalid += 1
    return result, invalid


def message(record, event):
    started = datetime.fromisoformat(record['started_at']).astimezone(timezone(timedelta(hours=9)))
    title = '[DataOps] 배치 실행 시작' if event == 'start' else '[DataOps] 배치 종료 · ' + LABELS[record['status']]
    lines = [title, '작업: ' + record['command'], '실행 ID: ' + record['invocation_id'],
             '시작: ' + started.strftime('%Y-%m-%d %H:%M:%S KST')]
    if event == 'start':
        lines.append('서버 준비를 포함한 실행 시작. 업무 완료는 종료 알림에서 확인합니다.')
    else:
        lines.append(f"소요: {record['duration_seconds']:.1f}초 · 종료 코드: {record['exit_code']}")
        has_counts = False
        for row in record.get('workflows', []):
            label = row['workflow'] + ((' / ' + row['cycle_id']) if row.get('cycle_id') else '')
            lines.append(label + ': ' + LABELS.get(row['status'], '미확인'))
            current = row['current']
            if set(current) == set(COUNTS):
                normal = current['changed'] + current['unchanged']
                accounted = normal + current['partial'] + current['held'] + current['failed']
                if accounted <= current['attempted']:
                    has_counts = True
                    lines.append(f"이번 수집: 시도 {current['attempted']}건 · 정상 {normal} · 실패 {current['failed']}"
                                 f" · 부분 {current['partial']} · 보류 {current['held']} · 미확정 {current['attempted'] - accounted}")
                    lines.append(f"정상 수집 상세: 변경 {current['changed']} · 무변경 {current['unchanged']}")
            if row['artifacts']:
                lines.append('회차 산출물(이번 실행 처리량 아님): ' + ' · '.join(
                    ARTIFACT_COUNTS[key] + ' ' + str(value) for key, value in row['artifacts'].items()))
            if row['skipped_tracks']:
                lines.append('이미 완료되어 재처리하지 않음: ' + ', '.join(row['skipped_tracks']))
        if not has_counts:
            lines.append('이번 수집 건수: 미집계 또는 수집 단계 미실행(0건으로 추정하지 않음)')
        if record.get('invalid_journals'):
            lines.append('일부 실행 이력을 읽지 못했습니다. SSM·원본 JSON을 확인하세요.')
        lines.append('성공은 이번 명령의 완료이며, 전체 가게 최신화·운영 반영 완료를 뜻하지 않습니다.')
        lines.append('https://grafana.bbangmapbbang.shop/d/bbang-dataops-v0')
    return {'text': title, 'mrkdwn': False, 'unfurl_links': False, 'unfurl_media': False,
            'blocks': [{'type': 'section', 'text': {'type': 'plain_text', 'text': '\n'.join(lines)[:2900], 'emoji': True}}]}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def send(webhook, payload):
    url = urlsplit(webhook)
    if (url.scheme != 'https' or url.hostname != 'hooks.slack.com' or url.port not in (None, 443)
            or not url.path.startswith('/services/') or url.username or url.password or url.query or url.fragment):
        raise ValueError('invalid Slack endpoint')
    request = Request(webhook, data=json.dumps(payload, ensure_ascii=False).encode(), method='POST',
                      headers={'Content-Type': 'application/json; charset=utf-8'})
    with build_opener(NoRedirect()).open(request, timeout=3) as response:
        if response.status != 200 or response.read(32).strip() != b'ok':
            raise OSError('Slack rejected notification')


class BatchNotification:
    def __init__(self, logs, command, webhook):
        self.logs, self.webhook = Path(logs), webhook
        self.started = time.monotonic()
        self.record = {'schema_version': 1, 'invocation_id': str(uuid4()), 'command': command,
                       'started_at': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING', 'delivery': {}}
        self.path = self.logs / 'notifications' / (self.record['invocation_id'] + '.json')

    def persist(self):
        temporary = None
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(mode='w', dir=self.path.parent, delete=False) as stream:
                temporary = stream.name
                json.dump(self.record, stream, ensure_ascii=False, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        except OSError as error:
            print('[notification] journal failed: ' + type(error).__name__, flush=True)
        finally:
            if temporary:
                try: os.unlink(temporary)
                except OSError: pass

    def notify(self, event):
        if event in self.record['delivery']: return
        self.record['delivery'][event] = 'SKIPPED' if not self.webhook else 'ATTEMPTING'
        self.persist()
        if not self.webhook: return
        try:
            send(self.webhook, message(self.record, event))
            self.record['delivery'][event] = 'SENT'
        except Exception as error:
            self.record['delivery'][event] = 'FAILED'
            self.record['delivery'][event + '_error_type'] = type(error).__name__
            print('[notification] Slack delivery failed: ' + type(error).__name__, flush=True)
        self.persist()

    def finish(self, code):
        if 'finish' in self.record['delivery']: return
        self.record.update(status=status(code), exit_code=code,
                           finished_at=datetime.now(timezone.utc).isoformat(),
                           duration_seconds=max(0.0, time.monotonic() - self.started))
        try:
            self.record['workflows'], self.record['invalid_journals'] = summaries(self.logs, self.record['invocation_id'])
        except Exception as error:
            self.record['workflows'], self.record['invalid_journals'] = [], 1
            print('[notification] summary failed: ' + type(error).__name__, flush=True)
        self.notify('finish')
