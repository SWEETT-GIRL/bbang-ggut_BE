"""DataOps invocation notices. Only allowlisted counts are sent; delivery never reruns work."""
from datetime import datetime, timedelta, timezone
from html import escape
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
AUTO_COUNTS = ('auto_apply_applied', 'auto_apply_already_applied', 'auto_apply_held', 'auto_apply_failed', 'auto_apply_unknown')
SOURCE_COUNTS = {'menu_fallback_completed': '대체 출처 반영 완료', 'menu_source_not_provided': '메뉴 원본 미제공',
                 'recovery_pending': '실제 복구 대기', 'menu_source_unverified': '출처 판정 미확인'}
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
                   'automatic': counts(record.get('items_current'), AUTO_COUNTS),
                   'latest_sources': counts(record.get('items_current'), SOURCE_COUNTS),
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
    icon, color = ('▶️', '#3498DB') if event == 'start' else (
        ('✅', '#2ECC71') if record['status'] == 'COMPLETED' else
        ('❌', '#E74C3C') if record['status'] == 'FAILED' else ('⚠️', '#F2C744'))
    title = f'{icon} 배치 시작 [DataOps]' if event == 'start' else f"{icon} 배치 종료 · {LABELS[record['status']]} [DataOps]"

    def plain(text):
        return {'type': 'plain_text', 'text': str(text)[:2900], 'emoji': True}

    def field(label, value):
        return {'type': 'mrkdwn', 'text': f'*{label}*\n' + escape(str(value)[:350], quote=False), 'verbatim': True}

    def context(text):
        return {'type': 'context', 'elements': [plain(text)]}

    blocks = [{'type': 'header', 'text': plain(title)},
              {'type': 'section', 'fields': [field('작업', record['command']),
                                            field('시작 시간', started.strftime('%Y-%m-%d %H:%M:%S KST'))]}]
    fallback = [title, record['command']]
    if event == 'start':
        blocks.append(context('서버 준비를 포함한 실행 시작 · 처리 결과는 종료 알림으로 안내합니다.'))
    else:
        minutes, seconds = divmod(record['duration_seconds'], 60)
        duration = (f'{int(minutes)}분 ' if minutes else '') + f'{seconds:.1f}초'
        blocks.append({'type': 'section', 'fields': [field('소요 시간', duration), field('종료 코드', record['exit_code'])]})
        has_counts = False
        workflows = record.get('workflows', [])
        # 긴 실행도 Slack block 제한을 넘기지 않는다. 전체 이력은 JSON에 남는다.
        for row in workflows[:7]:
            label = row['workflow'] + ((' / ' + row['cycle_id']) if row.get('cycle_id') else '')
            blocks.append({'type': 'section', 'text': plain(label + ' · ' + LABELS.get(row['status'], '미확인'))})
            current = row['current']
            if set(current) == set(COUNTS):
                normal = current['changed'] + current['unchanged']
                accounted = normal + current['partial'] + current['held'] + current['failed']
                if accounted <= current['attempted']:
                    has_counts = True
                    blocks.append({'type': 'section', 'fields': [
                        field('이번 수집 · 시도', f"{current['attempted']}건"),
                        field('정상', f"{normal}건 (변경 {current['changed']} · 무변경 {current['unchanged']})"),
                        field('실패', f"{current['failed']}건"), field('부분 수집', f"{current['partial']}건"),
                        field('보류', f"{current['held']}건"), field('미확정', f"{current['attempted'] - accounted}건")
                    ]})
                    fallback.append(f"{row['workflow']}: 시도 {current['attempted']}건 · 정상 {normal} · 실패 {current['failed']}")
            automatic = row.get('automatic', {})
            if set(automatic) == set(AUTO_COUNTS):
                blocks.append({'type': 'section', 'fields': [
                    field('운영 반영 · 변경 항목', f"{automatic['auto_apply_applied']}건"),
                    field('이미 반영됨', f"{automatic['auto_apply_already_applied']}건"),
                    field('반영 보류', f"{automatic['auto_apply_held']}건"),
                    field('반영 실패 / 결과 미확정', f"{automatic['auto_apply_failed']} / {automatic['auto_apply_unknown']}건")
                ]})
            latest_sources = row.get('latest_sources', {})
            if set(latest_sources) == set(SOURCE_COUNTS):
                blocks.append({'type': 'section',
                    'text': plain('보관된 가게·출처별 최신 상태 · 이번 실행 처리량과 별도'),
                    'fields': [field(label, f'{latest_sources[key]}곳') for key, label in SOURCE_COUNTS.items()]})
            if row['artifacts']:
                blocks.append(context('회차 산출물(이번 실행 처리량 아님): ' + ' · '.join(
                    ARTIFACT_COUNTS[key] + ' ' + str(value) for key, value in row['artifacts'].items())))
            if row['skipped_tracks']:
                blocks.append(context('이미 완료되어 재처리하지 않음: ' + ', '.join(row['skipped_tracks'])))
        if len(workflows) > 7:
            blocks.append(context(f'추가 작업 {len(workflows) - 7}개의 결과는 실행 이력 JSON에서 확인하세요.'))
        if not has_counts:
            blocks.append(context('이번 수집 건수: 미집계 또는 수집 단계 미실행(0건으로 추정하지 않음)'))
        if record.get('invalid_journals'):
            blocks.append(context('일부 실행 이력을 읽지 못했습니다. SSM·원본 JSON을 확인하세요.'))
        blocks.append(context('집계 범위: 이번 실행 · 전체 가게 최신화·운영 반영 여부는 별도 확인이 필요합니다.'))
        blocks.append({'type': 'actions', 'elements': [{'type': 'button', 'text': plain('대시보드 보기'),
                                                      'url': 'https://grafana.bbangmapbbang.shop/d/bbang-dataops-v0'}]})
    blocks.append(context('실행 ID: ' + record['invocation_id']))
    return {'mrkdwn': False, 'unfurl_links': False, 'unfurl_media': False,
            'attachments': [{'color': color, 'fallback': ' · '.join(fallback)[:2900], 'blocks': blocks}]}


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
