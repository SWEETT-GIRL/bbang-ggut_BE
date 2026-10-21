#!/usr/bin/env python3
"""One real saved-source replay via PostgreSQL incident dispatch, never serving writes.

python -m scripts.run_response_agent_pg_replay --case-dir PATH --saved-sample PATH --output PATH --budget 0.50
Add --live --budget 0.50 for one explicitly bounded Sonnet run and a duplicate-delivery check.
The disposable database is initialized from historical evidence, not represented
as a newly detected production incident. Production databases are never accessed.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import psycopg2
from psycopg2.extras import RealDictCursor

from agent.response_change.cli import ROOT, load_case
from agent.response_change.dispatch import ApprovedBundleCatalog, FrozenBundle, PgDispatcher
from agent.response_change.ledger import atomic_json, detect, digest
from agent.response_change.validator import validate

CONTRACT = 'menu-repair-eval-v2'


def private_json(path, value):
    atomic_json(path, value)
    Path(path).chmod(0o600)


def historical_bundle(case_directory, saved_sample):
    case, fixtures, source, legacy_test = load_case(Path(case_directory))
    manifest = json.loads((Path(case_directory) / 'manifest.json').read_text())
    if case.get('contract_version') != CONTRACT or manifest.get('contract_version') != CONTRACT:
        raise ValueError('case and frozen manifest must both declare menu-repair-eval-v2')
    if manifest.get('real_source_pair') is not True:
        raise ValueError('a proven stored historical source pair is required')
    expected_digest = case['after']['source_payload_sha256']
    payload = None
    snapshot_id = None
    with Path(saved_sample).open() as source_file:
        for line in source_file:
            row = json.loads(line)
            encoded = json.dumps(row['payload'], ensure_ascii=False, sort_keys=True, separators=(',', ':'))
            if hashlib.sha256(encoded.encode()).hexdigest() == expected_digest:
                payload, snapshot_id = encoded, row['sourceSnapshotId']
                break
    if payload is None:
        raise ValueError('frozen source payload digest not found in saved evidence')
    diagnostic = detect(case['before']['raw'], case['after']['raw'])
    if not diagnostic['eligible']:
        raise ValueError('historical source pair no longer qualifies as a structural change')
    parser_identity = 'isolated-historical:' + digest(source)
    bundle = FrozenBundle(case, source, legacy_test, fixtures, parser_identity, CONTRACT, (expected_digest,))
    fingerprint = digest({'scope': 'isolated-historical-postgresql-replay', 'diagnostic': diagnostic,
                          'parser_digest': parser_identity, 'contract_version': CONTRACT})
    return bundle, fingerprint, payload, snapshot_id


@contextmanager
def disposable_postgres(*, keep=False):
    name = 'agent-historical-replay-' + uuid.uuid4().hex[:12]
    subprocess.run(['docker', 'run', '-d', '--name', name,
                    '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-e', 'POSTGRES_DB=agent_historical_replay',
                    '-p', '127.0.0.1::5432', 'postgres:16-alpine'], check=True, capture_output=True)
    yielded = completed = False
    try:
        port = subprocess.check_output(['docker', 'port', name, '5432/tcp'], text=True).strip().rsplit(':', 1)[1]
        dsn = f'host=127.0.0.1 port={port} dbname=agent_historical_replay user=postgres connect_timeout=3'
        deadline = time.monotonic() + 30
        while True:
            try:
                with psycopg2.connect(dsn) as db:
                    with db.cursor() as cursor:
                        cursor.execute('SELECT 1')
                break
            except psycopg2.OperationalError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.2)
        with psycopg2.connect(dsn) as db:
            with db.cursor() as cursor:
                for migration in ('V20261013100000__response_capture_and_incident.sql',
                                  'V20261014111000__agent_dispatch.sql',
                                  'V20261014160000__agent_cumulative_budget.sql'):
                    cursor.execute((ROOT / 'src/main/resources/db/migration' / migration).read_text())
                cursor.execute('CREATE ROLE agent_worker LOGIN')
                cursor.execute('GRANT SELECT ON response_capture,response_incident,response_incident_sample TO agent_worker')
                cursor.execute('GRANT SELECT,INSERT,UPDATE ON response_agent_run TO agent_worker')
                cursor.execute('GRANT SELECT ON response_agent_budget_policy TO agent_worker')
                cursor.execute('GRANT SELECT,INSERT,UPDATE ON response_agent_budget_incident,response_agent_budget_day TO agent_worker')
                cursor.execute('GRANT SELECT,INSERT ON response_agent_budget_alias,response_agent_budget_request,response_agent_budget_settlement TO agent_worker')
                cursor.execute('CREATE TABLE serving_sentinel (id INTEGER PRIMARY KEY, value TEXT)')
                cursor.execute("INSERT INTO serving_sentinel VALUES (1,'unchanged')")
        yielded = True
        yield dsn, {'container': name, 'host': '127.0.0.1', 'port': int(port),
                    'database': 'agent_historical_replay', 'worker_role': 'agent_worker'}
        completed = True
    finally:
        # Preserve the authoritative journal after a worker/process/export failure.
        # Its connection metadata was saved before any paid work. Setup failures
        # contain no paid journal and can be removed immediately.
        if not keep and (completed or not yielded):
            subprocess.run(['docker', 'rm', '-f', name], check=True, capture_output=True)


def seed_historical(dsn, bundle, fingerprint, payload):
    with psycopg2.connect(dsn) as db:
        with db.cursor() as cursor:
            # Explicit test capture identity; stored_at remains the actual source time.
            cursor.execute('''INSERT INTO response_capture
                (capture_id,request_digest,store_id,source,external_id,observed_at,payload,payload_digest,
                 status,parser_digest,contract_version,diagnostics)
                VALUES ('historical-replay-capture',%s,1,'NAVER','historical-fixture',%s,%s,%s,
                        'HELD',%s,%s,%s)''',
                (digest({'isolated': True, 'payload_digest': bundle.capture_payload_digests[0]}),
                 bundle.case['after']['observed_at'], payload, bundle.capture_payload_digests[0],
                 bundle.deployment_parser_digest, bundle.contract_version,
                 json.dumps([{'code': 'HISTORICAL_MENU_STRUCTURE_CHANGE', 'modelEligible': True}])))
            cursor.execute('''INSERT INTO response_incident
                (fingerprint,source,parser_digest,contract_version,diagnostics,model_eligible)
                VALUES (%s,'NAVER',%s,%s,%s,TRUE)''',
                (fingerprint, bundle.deployment_parser_digest, bundle.contract_version,
                 json.dumps([{'code': 'HISTORICAL_MENU_STRUCTURE_CHANGE', 'modelEligible': True}])))
            cursor.execute("INSERT INTO response_incident_sample VALUES (%s,'historical-replay-capture')", (fingerprint,))


def snapshot_queue(dsn):
    with psycopg2.connect(dsn) as db:
        with db.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute('''SELECT run_id::text,fingerprint,parser_digest,contract_version,state,owner,
                              fencing_epoch,budget_usd::text,bundle_digest,checkpoint,stop_reason
                              FROM response_agent_run ORDER BY created_at''')
            rows = [dict(row) for row in cursor.fetchall()]
            cursor.execute('SELECT value FROM serving_sentinel WHERE id=1')
            sentinel = cursor.fetchone()['value']
    return rows, sentinel


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case-dir', type=Path, required=True)
    parser.add_argument('--saved-sample', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--budget', type=float, help='explicit per-run USD cap; required even for dry dispatch')
    parser.add_argument('--keep-db', action='store_true', help='keep this isolated database for manual inspection')
    args = parser.parse_args(argv)
    if args.budget is None or not 0 < args.budget <= .50:
        parser.error('--budget must explicitly be greater than zero and at most $0.50')
    os.umask(0o077)
    if args.output.exists():
        parser.error('output already exists; do not overwrite prior evidence or silently spend again')
    args.output.mkdir(parents=True)
    bundle, fingerprint, payload, source_snapshot_id = historical_bundle(args.case_dir, args.saved_sample)
    # Prove the known old parser failure with fixed tests before allowing any live request.
    baseline = validate(bundle.source, bundle.fixtures, args.output / 'historical-baseline')
    checks = {row['id']: row for row in baseline.get('cases', [])}
    if (baseline.get('status') != 'ASSERTION_FAILED'
            or not checks.get('actual-legacy-before', {}).get('passed')
            or checks.get('actual-format-after', {}).get('passed') is not False
            or baseline.get('fixed_regression', {}).get('succeeded') != 11):
        raise ValueError('required original failure/legacy baseline not established')
    provider = ApprovedBundleCatalog({fingerprint: bundle},
                                     current_parser_digest=bundle.deployment_parser_digest,
                                     contract_version=bundle.contract_version)
    model_constructed = 0
    with disposable_postgres(keep=args.keep_db) as (admin_dsn, database):
        private_json(args.output / 'database.private.json', database)
        seed_historical(admin_dsn, bundle, fingerprint, payload)
        worker_dsn = admin_dsn.replace('user=postgres', 'user=agent_worker')
        dispatcher = PgDispatcher(lambda: psycopg2.connect(worker_dsn), provider, args.output / 'runs')
        if args.live:
            # Local key is read only by the model factory, after all eligibility gates.
            from dotenv import dotenv_values
            from langchain_anthropic import ChatAnthropic
            from agent.response_change.runtime import MODEL, MAX_OUTPUT
            from agent.response_change.worker_cli import make_executor
            def model_factory():
                nonlocal model_constructed
                key = os.environ.get('ANTHROPIC_API_KEY') or dotenv_values(ROOT / '.env').get('ANTHROPIC_API_KEY')
                if not key:
                    raise ValueError('ANTHROPIC_API_KEY is missing')
                model_constructed += 1
                return ChatAnthropic(model=MODEL, api_key=key, base_url='https://api.anthropic.com',
                                     max_tokens=MAX_OUTPUT, max_retries=0, timeout=90)
            executor = make_executor(model_factory)
        else:
            def executor(value, claim, checkpoint):
                if value.digest() != bundle.digest():
                    raise ValueError('handoff bundle changed')
                return {'status': 'HELD', 'stop_reason': 'DRY_RUN_NO_MODEL_AUTHORIZED', 'requests': [],
                        'case_digest': digest(value.case), 'base_digest': digest(value.source),
                        'oracle_digest': digest(value.fixtures), 'production_writes': 0}
        first = dispatcher.run_one(executor, owner='isolated-historical-replay', budget_usd=args.budget)
        second = dispatcher.run_one(executor, owner='duplicate-delivery-check', budget_usd=args.budget)
        rows, sentinel = snapshot_queue(admin_dsn)
        if second != {'status': 'IDLE', 'new_model_calls': 0} or len(rows) != 1 or sentinel != 'unchanged':
            raise AssertionError('deduplication/serving isolation check failed')
        checkpoint = rows[0]['checkpoint']
        summary = {'scope': 'isolated PostgreSQL historical replay, not production incident dispatch',
                   'started_date_utc': datetime.now(timezone.utc).isoformat(), 'live': args.live,
                   'first_delivery': first, 'duplicate_delivery': second,
                   'source_snapshot_id': source_snapshot_id, 'source_payload_sha256': bundle.capture_payload_digests[0],
                   'incident_fingerprint': fingerprint, 'bundle_digest': bundle.digest(),
                   'contract_version': bundle.contract_version, 'budget_usd': args.budget,
                   'inference_requests': len(checkpoint.get('requests', [])), 'model_clients_constructed': model_constructed,
                   'confirmed_usage_estimated_usd': checkpoint.get('confirmed_usage_estimated_usd', 0),
                   'unpriced_reserved_usd': checkpoint.get('unpriced_reserved_usd', 0),
                   'routing_quality': checkpoint.get('routing_quality'), 'production_writes': 0,
                   'serving_sentinel_unchanged': True, 'database_retained': args.keep_db}
        private_json(args.output / 'queue.private.json', rows)
        private_json(args.output / 'summary.json', summary)
        if args.keep_db:
            private_json(args.output / 'database.private.json', database)
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if first['status'] in {'AWAITING_REVIEW', 'HELD'} else 1


if __name__ == '__main__':
    raise SystemExit(main())
