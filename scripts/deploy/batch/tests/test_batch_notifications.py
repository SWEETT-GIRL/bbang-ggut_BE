"""Offline invocation/Slack tests. All HTTP and AWS boundaries are replaced."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import batch_notifications as notices
import runner

WEBHOOK = 'https://hooks.slack.com/services/TEST/TEST/test-secret'
ITEMS = dict(attempted=20, changed=7, unchanged=10, partial=1, held=1, failed=1)


class NotificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.logs = Path(self.temp.name)
        self.events = []
        self.sender = patch.object(notices, 'send', side_effect=lambda url, payload: self.events.append(payload))
        self.sender.start()
        self.addCleanup(self.sender.stop)

    def journal(self, identity, name, **values):
        directory = self.logs / 'weekly/runs'
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f'{identity}_{name}.json').write_text(json.dumps({
            'invocation_id': identity, 'command': 'freshness-refresh', 'status': 'FAILED', **values}))

    def test_start_finish_once_with_current_counts_and_without_sensitive_fields(self):
        notice = notices.BatchNotification(self.logs, 'dataops-nightly', WEBHOOK)
        self.journal(notice.record['invocation_id'], 'a', items_current=ITEMS,
                     items={'candidates_inserted': 8, 'secret': WEBHOOK}, error_message=WEBHOOK)
        self.journal('other-invocation', 'b', items_current={**ITEMS, 'attempted': 9999})
        notice.notify('start'); notice.notify('start')
        notice.finish(1); notice.finish(0)
        self.assertEqual(len(self.events), 2)
        text = self.events[1]['blocks'][0]['text']['text']
        self.assertIn('실패', self.events[1]['text'])
        self.assertIn('시도 20건 · 정상 17 · 실패 1 · 부분 1 · 보류 1 · 미확정 0', text)
        self.assertIn('회차 산출물(이번 실행 처리량 아님): 후보 저장 8', text)
        self.assertIn('KST', text)
        self.assertNotIn('9999', text)
        self.assertNotIn(WEBHOOK, notice.path.read_text() + json.dumps(self.events))
        self.assertEqual(notice.record['status'], 'FAILED')

    def test_failures_and_missing_counts_do_not_become_success_or_zero(self):
        for code, label in ((0, '성공'), (1, '실패'), (3, '차단'), (4, '미완료'), (75, '중복 실행 거절'), (143, '중단')):
            with self.subTest(code=code):
                notice = notices.BatchNotification(self.logs, 'dataops-nightly', WEBHOOK)
                notice.notify('start'); notice.finish(code)
                self.assertIn(label, self.events[-1]['text'])
                self.assertIn('미집계 또는 수집 단계 미실행', self.events[-1]['blocks'][0]['text']['text'])

    def test_delivery_failure_keeps_result_and_does_not_leak_or_retry(self):
        notice = notices.BatchNotification(self.logs, 'dataops-nightly', WEBHOOK)
        with patch.object(notices, 'send', side_effect=OSError(WEBHOOK)) as send, redirect_stdout(io.StringIO()) as log:
            notice.notify('start'); notice.finish(0); notice.finish(0)
        self.assertEqual(send.call_count, 2)
        self.assertEqual(notice.record['status'], 'COMPLETED')
        self.assertEqual(notice.record['delivery']['finish'], 'FAILED')
        self.assertNotIn(WEBHOOK, log.getvalue() + notice.path.read_text())

    def test_no_webhook_and_broken_journal_do_not_stop_execution(self):
        notice = notices.BatchNotification(self.logs, 'dataops-nightly', '')
        self.journal(notice.record['invocation_id'], 'broken')
        file = next((self.logs / 'weekly/runs').glob('*.json')); file.write_text('invalid')
        notice.notify('start'); notice.finish(0)
        self.assertFalse(self.events)
        self.assertEqual(notice.record['invalid_journals'], 1)
        self.assertEqual(notice.record['delivery']['finish'], 'SKIPPED')
        with patch.object(notices.tempfile, 'NamedTemporaryFile', side_effect=OSError('disk full')):
            notice.persist()

    def test_only_supported_collection_commands_notify(self):
        self.assertEqual(notices.selected_command(['dataops-cycle', '--tracks', 'refresh']), 'dataops-cycle run')
        self.assertEqual(notices.selected_command(['dataops-cycle', 'baseline']), 'dataops-cycle baseline')
        for command in (['job', 'ranking-snapshot'], ['dataops-migrate'], ['dataops-cycle', 'plan'],
                        ['dataops-cycle', 'start-cycle'], ['dataops-cycle', 'seed-refresh']):
            self.assertIsNone(notices.selected_command(command))

    def test_completed_cycle_does_not_reuse_previous_attempt_counts(self):
        notice = notices.BatchNotification(self.logs, 'dataops-cycle run', WEBHOOK)
        self.journal(notice.record['invocation_id'], 'a', command='freshness-cycle', status='COMPLETED',
                     skipped_tracks=['refresh', 'public'], items=ITEMS)
        notice.finish(0)
        text = self.events[-1]['blocks'][0]['text']['text']
        self.assertIn('이미 완료되어 재처리하지 않음: refresh, public', text)
        self.assertNotIn('시도 20건', text)


class TransportTest(unittest.TestCase):
    def test_only_slack_https_without_redirects_is_allowed(self):
        with patch.object(notices, 'build_opener') as factory:
            for url in ('http://hooks.slack.com/services/test', 'https://example.com/services/test',
                        WEBHOOK + '?token=test', 'https://user:password@hooks.slack.com/services/test'):
                with self.assertRaises(ValueError): notices.send(url, {})
            factory.assert_not_called()
            response = factory.return_value.open.return_value.__enter__.return_value
            response.status = 200; response.read.return_value = b'ok'
            notices.send(WEBHOOK, {'text': 'test'})
            self.assertIsInstance(factory.call_args.args[0], notices.NoRedirect)
            self.assertEqual(factory.return_value.open.call_args.kwargs['timeout'], 3)
            response.read.return_value = b'not-ok'
            with self.assertRaises(OSError): notices.send(WEBHOOK, {})


class RunnerNotificationTest(unittest.TestCase):
    def test_runner_passes_only_invocation_id_and_reports_process_result(self):
        for code in (0, 1, 3, 4, 75, 143, 'launch-error'):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                env = {key: 'test' for key in runner.REQUIRED | runner.DATAOPS_REQUIRED}
                env.update(DATAOPS_DB_URL='jdbc:postgresql://db/dataops?sslmode=require', DATAOPS_SLACK_WEBHOOK_URL=WEBHOOK)
                events = []
                def process(args, **kwargs):
                    app_env = Path(args[args.index('--env-file') + 1]).read_text()
                    self.assertNotIn(WEBHOOK, app_env)
                    self.assertIn('DATAOPS_INVOCATION_ID=', app_env)
                    if code == 'launch-error': raise OSError('docker unavailable')
                    return subprocess.CompletedProcess(args, code)
                with patch.dict(sys.modules, {'boto3': Mock()}), patch.object(runner, 'BASE', base), \
                        patch.object(runner, 'load_parameters', return_value=(env, {}, {})), \
                        patch.object(runner.os, 'chown'), patch.object(runner.subprocess, 'run', side_effect=process) as docker, \
                        patch.object(notices, 'send', side_effect=lambda url, payload: events.append(payload)):
                    config = {'region': 'ap-northeast-2', 'parameter_prefix': '/sweetgirl/prod/v2/batch',
                              'image': 'example/batch@sha256:' + 'a'*64}
                    if code == 'launch-error':
                        with self.assertRaises(OSError): runner.execute(config, ['dataops-nightly'])
                    else: self.assertEqual(runner.execute(config, ['dataops-nightly']), code)
                docker.assert_called_once()
                self.assertEqual(len(events), 2)
                record = json.loads(next((base / 'dataops-logs/notifications').glob('*.json')).read_text())
                self.assertEqual(record['exit_code'], 1 if code == 'launch-error' else code)
