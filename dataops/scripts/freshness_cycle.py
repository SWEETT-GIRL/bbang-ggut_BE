"""전체 최신화의 클라우드 실행기. 실행별 원본/입력 고정, 독립 트랙, 실패·미완료 재개."""
import argparse
import csv
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import sys
from zoneinfo import ZoneInfo

import requests
from batch_execution import AlreadyRunning, WorkflowRun, atomic_json, termination_signals, utc_now
from cloud_public_pull import DATASETS, file_hash
from cloud_runtime import cycle_id
from cloud_seed import FACTS, validate
from cloud_baseline import bootstrap
from dataops_server import server_headers
from sync_worker import safe_server
import weekly_refresh

TRACKS = ('refresh', 'public', 'discovery')


def scheduled_cycle(track, now=None):
    now = now or datetime.now(ZoneInfo('Asia/Seoul'))
    return now.strftime('%Y%m%d' if track == 'refresh' else '%Y%m' if track == 'public' else '%G-W%V')


def select_cycle(state_dir, track, explicit=None, now=None, persist=True):
    """예산 소진·장애로 미완료인 회차는 달력이 바뀌어도 버리지 않는다."""
    parent = state_dir / track
    if explicit:
        return parent / cycle_id(explicit)
    pointer = parent / 'active.json'
    current = scheduled_cycle(track, now)
    if pointer.exists():
        saved = json.loads(pointer.read_text())
        previous = cycle_id(saved['cycle_id'])
        if not saved['completed'] or saved.get('period', previous) == current:
            return parent / previous
    if persist:
        atomic_json(pointer, {'cycle_id': current, 'period': current, 'completed': False})
    return parent / current


def finish_cycle(root, explicit, code):
    if not explicit:
        pointer = root.parent / 'active.json'
        previous = json.loads(pointer.read_text()) if pointer.exists() else {}
        atomic_json(pointer, {'cycle_id': root.name, 'period': previous.get('period', root.name), 'completed': code == 0})


def start_cycle(state_dir, tracks, name, reason):
    """운영자가 새 회차를 선택한다. 이전 산출물은 삭제하지 않고 전환 이유를 남긴다."""
    if not name or not reason or not reason.strip(): raise ValueError('cycle ID and reason required')
    name = cycle_id(name)
    for track in tracks:
        parent = state_dir / track
        if (parent / name).exists(): raise ValueError('new cycle ID required')
    for track in tracks:
        parent = state_dir / track
        pointer = parent / 'active.json'
        previous = json.loads(pointer.read_text()) if pointer.exists() else None
        atomic_json(parent / name / 'transition.json', {'previous': previous, 'reason': reason, 'at': utc_now()})
        atomic_json(pointer, {'cycle_id': name, 'period': scheduled_cycle(track), 'completed': False})


def prepare(root, seed, manifest, scope):
    root.mkdir(parents=True, exist_ok=True)
    path = root / 'scope.json'
    if path.exists():
        if json.loads(path.read_text()) != scope: raise ValueError('cycle scope changed; use a new cycle ID')
    else:
        atomic_json(path, scope)
    matching = root / 'matching'
    matching.mkdir(exist_ok=True)
    for name in FACTS:
        target = matching / Path(name).name
        if not target.exists(): shutil.copyfile(seed / name, target)
        if file_hash(target) != manifest['files'][name]['sha256']: raise ValueError('cycle identity evidence changed')
    candidates = matching / 'localdata_new_candidates.tsv'
    if not candidates.exists():
        candidates.write_text('mgmt_no\tdataset\tcategory\tname\tlicensed_ymd\tclosed_ymd\tlat\tlon\n')
    return matching


def stage(name, script, arguments, outputs, depends=()):
    return dict(name=name, script=script, arguments=arguments, outputs=outputs, depends=depends)


def plan(track, root, seed, args):
    matching = root / 'matching'
    if track == 'refresh':
        return [stage('refresh', 'sync_worker.py', ['--server', args.server, '--max', args.max_items,
                      '--max-hours', args.crawl_hours, '--max-cooldowns', '0', '--summary', root / 'refresh-summary.json'],
                      ['refresh-summary.json'])]
    if track == 'discovery':
        sweep = matching / 'naver_bakery_sweep_current.jsonl'
        return [
            stage('search', 'naver_bakery_sweep.py', ['--out', sweep, '--queries', *args.queries,
                  '--max-regions', args.max_regions, '--max-cooldowns', '0', '--deep'], ['matching/' + sweep.name]),
            stage('match', 'naver_sweep_match.py', ['--sweep', sweep], ['matching/naver_sweep_new_candidates.tsv'], ['search']),
            stage('candidates', 'cloud_candidates.py', ['--source', 'discovery', '--input', matching / 'naver_sweep_new_candidates.tsv',
                  '--summary', root / 'candidate-summary.json'], ['candidate-summary.json'], ['match']),
        ]
    raw_outputs = [f'matching/localdata_raw/{name}.jsonl' for name in args.datasets]
    delta_outputs = [f'matching/localdata_raw/delta/{name}_{suffix}.jsonl' for name in args.datasets for suffix in ('new', 'upd')]
    return [
        stage('pull', 'cloud_public_pull.py', ['--root', root, '--seed', seed, '--datasets', *args.datasets,
              '--max-pages', args.max_pages, '--max-seconds', args.public_seconds], ['public-result.json', *raw_outputs, *delta_outputs]),
        stage('new-delta', 'localdata_new_delta.py', [], ['matching/localdata_new_candidates.tsv'], ['pull']),
        stage('verify', 'localdata_new_verify.py', ['--all', '--max', args.max_items, '--max-cooldowns', '0'],
              ['matching/new_verify_results.jsonl'], ['new-delta']),
        stage('bread', 'localdata_bread_check.py', ['--max', args.max_items], ['matching/bread_check_results.jsonl'], ['verify']),
        stage('confirm', 'localdata_new_confirmed.py', [], ['matching/localdata_new_confirmed.tsv',
              'matching/localdata_new_confirmed_hold.tsv', 'matching/localdata_new_confirmed_blocked.tsv',
              'matching/localdata_new_confirmed_need_menu.tsv', 'matching/confirmation-summary.json'], ['bread']),
        stage('candidates', 'cloud_candidates.py', ['--source', 'public', '--input', matching / 'localdata_new_confirmed.tsv',
              '--summary', root / 'candidate-summary.json'], ['candidate-summary.json'], ['confirm']),
        stage('closures', 'localdata_status_delta.py', ['--enqueue', '--max', args.max_items],
              ['matching/closure-summary.json', 'matching/status_delta_closures.tsv'], ['pull']),
    ]


def verify_completed(root, saved):
    for name, expected in saved['outputs'].items():
        path = root / name
        if not path.is_file() or file_hash(path) != expected:
            raise ValueError('completed stage artifact changed: ' + name)


def aggregate(codes):
    if any(code not in (0, 3, 4) for code in codes): return 1
    if 3 in codes: return 3
    if 4 in codes: return 4
    return 0


def item_metrics(root, baseline=False):
    metrics = {}
    file = root / 'identity-summary.json'
    if file.exists():
        metrics.update({'baseline_' + key: value for key, value in json.loads(file.read_text()).items()})
    file = root / 'matching/confirmation-summary.json'
    if file.exists():
        metrics.update({'gate_' + key: value for key, value in json.loads(file.read_text()).items()})
    file = root / 'refresh-summary.json'
    if file.exists(): metrics.update(json.loads(file.read_text()))
    file = root / 'candidate-summary.json'
    if file.exists():
        data = json.loads(file.read_text())
        metrics.update({f'candidates_{key}': data[key] for key in ('input', 'inserted', 'existing', 'registered', 'held')})
    file = root / 'matching/closure-summary.json'
    if file.exists():
        metrics.update({f'closures_{key}': value for key, value in json.loads(file.read_text()).items()})
    file = root / 'public-result.json'
    if file.exists():
        data = json.loads(file.read_text())
        metrics['pages_fetched'] = data['pages_fetched']
        for key in (('raw',) if baseline else ('raw', 'new', 'upd', 'unobserved')):
            metrics['public_' + key] = sum(row['counts'][key] for row in data['datasets'].values())
    return metrics


def execute_graph(root, stages, invoke, state=None):
    state = state or {'schema_version': 1, 'stages': {}}
    codes = {}
    for node in stages:
        name = node['name']
        if any(codes.get(dep) != 0 for dep in node['depends']):
            codes[name] = 4
            continue
        saved = state['stages'].get(name)
        if saved and saved['exit_code'] == 0:
            verify_completed(root, saved)
            codes[name] = 0
            continue
        code = invoke(node)
        if code == 0 and name == 'refresh':
            metrics = json.loads((root / 'refresh-summary.json').read_text())
            if metrics['failed']: code = 1
            elif metrics['attempted'] == 0: code = 4
        outputs = {}
        if code == 0:
            for filename in node['outputs']:
                path = root / filename
                if not path.is_file(): raise ValueError('stage did not write its required artifact: ' + filename)
                with path.open('rb') as stream: os.fsync(stream.fileno())
                outputs[filename] = file_hash(path)
        state['stages'][name] = {'exit_code': code, 'at': utc_now(), 'outputs': outputs}
        atomic_json(root / 'checkpoint.json', state)
        codes[name] = code
    return aggregate(list(codes.values()))


def execute_track(track, root, seed, manifest, args, lock_fd):
    scope = {'schema_version': 1, 'track': track, 'datasets': sorted(args.datasets),
             'seed_files': manifest['files'] if manifest else {}, 'queries': args.queries, 'max_regions': args.max_regions,
             'refresh_max_items': args.max_items if track == 'refresh' else None,
             'image': os.environ.get('DATAOPS_IMAGE_REF', 'local'), 'serving_database': 'soboro'}
    if track == 'refresh':
        root.mkdir(parents=True, exist_ok=True)
        if (root / 'scope.json').exists() and json.loads((root / 'scope.json').read_text()) != scope:
            raise ValueError('cycle scope changed; use a new cycle ID')
        atomic_json(root / 'scope.json', scope)
        matching = root / 'matching'
    else:
        matching = prepare(root, seed, manifest, scope)
    stages = plan(track, root, seed, args)
    path = root / 'checkpoint.json'
    state = json.loads(path.read_text()) if path.exists() else None
    if state and all(state['stages'].get(node['name'], {}).get('exit_code') == 0 for node in stages):
        for saved in state['stages'].values(): verify_completed(root, saved)
        print(f'{track}: cycle already completed; last success time is retained', flush=True)
        return 0
    env = {**os.environ, 'DATAOPS_MATCHING_DIR': str(matching),
           'DATAOPS_RAW_DIR': str((seed / 'matching/localdata_raw') if track == 'discovery' else matching / 'localdata_raw')}
    execution = WorkflowRun(weekly_refresh.LOG_DIR, 'freshness-' + track)
    execution.lock_fd = lock_fd
    with execution:
        execution.record['cycle_id'] = root.name
        execution.persist()
        def invoke(node):
            return weekly_refresh.run_script(node['script'], *node['arguments'], env=env)[0]
        try:
            code = execute_graph(root, stages, invoke, state)
        finally:
            execution.record['items'] = item_metrics(root)
            execution.persist()
        execution.finish(code)
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=['run', 'plan', 'baseline', 'seed-refresh', 'start-cycle'], default='run')
    parser.add_argument('--cycle-id', type=cycle_id)
    parser.add_argument('--reason', help='reason for explicitly replacing the scheduled active cycle')
    parser.add_argument('--tracks', nargs='+', choices=TRACKS, default=list(TRACKS))
    parser.add_argument('--datasets', nargs='+', choices=tuple(DATASETS),
                        default=os.environ.get('DATAOPS_PUBLIC_DATASETS', 'bakeries').split(','))
    parser.add_argument('--max-pages', type=int, default=int(os.environ.get('DATAOPS_PUBLIC_MAX_PAGES', '100')))
    parser.add_argument('--max-items', type=int, default=int(os.environ.get('DATAOPS_CYCLE_MAX_ITEMS', '100')))
    parser.add_argument('--max-regions', type=int, default=int(os.environ.get('DATAOPS_SWEEP_MAX_REGIONS', '5')))
    parser.add_argument('--public-seconds', type=int, default=1800)
    parser.add_argument('--crawl-hours', type=float, default=0.25)
    parser.add_argument('--queries', nargs='+', default=['베이커리', '빵집'])
    parser.add_argument('--server', default='http://127.0.0.1:8090')
    parser.add_argument('--seed', type=Path, help='optional explicit baseline directory; no local import is required')
    parser.add_argument('--baseline-version', type=cycle_id, default=os.environ.get('DATAOPS_BASELINE_VERSION', 'cloud-001'))
    parser.add_argument('--state-dir', type=Path, default=weekly_refresh.LOG_DIR.parent / 'freshness')
    args = parser.parse_args(argv)
    if min(args.max_pages, args.max_items, args.max_regions, args.public_seconds, args.crawl_hours) <= 0:
        parser.error('all budgets must be positive')
    if not args.datasets or set(args.datasets) - set(DATASETS): parser.error('invalid datasets')
    lock = WorkflowRun(weekly_refresh.LOG_DIR, 'freshness-cycle')
    try:
        with lock.exclusive(), lock, termination_signals():
            if args.command == 'start-cycle':
                start_cycle(args.state_dir, list(dict.fromkeys(args.tracks)), args.cycle_id, args.reason)
                lock.finish(0)
                return 0
            if args.command == 'seed-refresh':
                safe_server(args.server)
                response = requests.post(args.server + '/api/sync-states/seed', headers=server_headers(), timeout=120)
                response.raise_for_status()
                lock.record['seed_result'] = response.json()
                lock.finish(0)
                return 0
            seed = args.seed or args.state_dir / 'baselines' / args.baseline_version
            if args.command == 'plan':
                print(json.dumps({'baseline': str(seed), 'source': 'explicit' if args.seed else 'cloud API + serving read-only',
                                  'local_import_required': False}, ensure_ascii=False))
                for track in dict.fromkeys(args.tracks):
                    root = select_cycle(args.state_dir, track, args.cycle_id, persist=False)
                    print(json.dumps({'track': track, 'cycle': root.name, 'stages': [s['name'] for s in plan(track, root, seed, args)]}, ensure_ascii=False))
                lock.finish(0)
                return 0
            if args.command == 'run': safe_server(args.server)
            codes = []

            def run_track(track, manifest):
                root = select_cycle(args.state_dir, track, args.cycle_id, persist=args.command == 'run')
                try:
                    code = execute_track(track, root, seed, manifest, args, lock.lock_fd)
                    finish_cycle(root, args.cycle_id, code)
                    codes.append(code)
                except Exception as error:
                    # 한 트랙의 실패는 다른 독립 트랙의 실행을 막지 않는다. 비밀값 없는 오류 종류만 남긴다.
                    print(f'{track} failed: {type(error).__name__}', flush=True)
                    root.mkdir(parents=True, exist_ok=True)
                    atomic_json(root / 'error.json', {'type': type(error).__name__, 'at': utc_now()})
                    codes.append(1)

            # 기존 가게 수집은 공공데이터 최초 기준 생성이 끝나기 전에도 독립 실행한다.
            if args.command == 'run' and 'refresh' in args.tracks:
                run_track('refresh', None)
            if args.command == 'baseline' or set(args.tracks) - {'refresh'}:
                if not args.seed and not (seed / 'manifest.json').exists():
                    baseline_run = WorkflowRun(weekly_refresh.LOG_DIR, 'freshness-baseline')
                    baseline_run.lock_fd = lock.lock_fd
                    with baseline_run:
                        code = bootstrap(seed, args.datasets, args.max_pages, args.public_seconds)
                        baseline_run.record['items'] = item_metrics(seed, baseline=True)
                        baseline_run.finish(code)
                    if code:
                        code = aggregate([*codes, code])
                        lock.finish(code)
                        return code
                manifest = validate(seed, args.datasets)
                if args.command == 'run':
                    for track in dict.fromkeys(args.tracks):
                        if track != 'refresh': run_track(track, manifest)
            code = aggregate(codes)
            lock.finish(code)
            return code
    except AlreadyRunning:
        return 75
    except Exception as error:
        print('freshness cycle failed: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
