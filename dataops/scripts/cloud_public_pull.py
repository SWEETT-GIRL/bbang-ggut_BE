"""공공데이터 전량 범위의 페이지 재개·검증·diff. 부분 결과는 후속 판정에 넘기지 않는다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time

from batch_execution import atomic_json, utc_now
from localdata_fetch import DATASETS, PAGE_SIZE, QuotaExceeded, call, items_of, load_service_key, tls_session

FIELDS = ('SALS_STTS_NM', 'DTL_SALS_STTS_NM', 'CLSBIZ_YMD', 'BPLC_NM', 'ROAD_NM_ADDR',
          'LOTNO_ADDR', 'TELNO', 'CRD_INFO_X', 'CRD_INFO_Y', 'BZSTAT_SE_NM', 'SNTTN_BZSTAT_NM', 'LCPMT_YMD')


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        for line in stream:
            if line.strip():
                row = json.loads(line)
                if not isinstance(row, dict) or not str(row.get('MNG_NO', '')).strip():
                    raise ValueError('public record has no management number')
                yield row


def write_lines(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.partial')
    with temporary.open('w', encoding='utf-8') as stream:
        for value in values: stream.write(json.dumps(value, ensure_ascii=False) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def validated_page(path, dataset, page, total):
    item = json.loads(path.read_text())
    if (item.get('dataset') != dataset or item.get('page') != page or item.get('total') != total
            or item.get('digest') != digest(item.get('rows'))):
        raise ValueError('public page checkpoint mismatch')
    expected = max(0, min(PAGE_SIZE, total - (page - 1) * PAGE_SIZE))
    if len(item['rows']) != expected: raise ValueError('public page row count mismatch')
    return item['rows']


def materialize(dataset, pages_dir, total, baseline, matching):
    """디스크 인덱스로 메모리 사용을 제한하고 관리번호 중복·누락을 검사한다."""
    with tempfile.TemporaryDirectory(dir=pages_dir) as temporary:
        conn = sqlite3.connect(str(Path(temporary) / 'diff.sqlite'))
        try:
            conn.execute('CREATE TABLE old (id TEXT PRIMARY KEY, semantic TEXT)')
            conn.execute('CREATE TABLE current (id TEXT PRIMARY KEY, body TEXT, semantic TEXT)')
            for row in rows(baseline):
                key, semantic = str(row['MNG_NO']), digest({f: row.get(f) for f in FIELDS})
                prior = conn.execute('SELECT semantic FROM old WHERE id=?', (key,)).fetchone()
                if prior and prior[0] != semantic: raise ValueError('conflicting baseline management number')
                conn.execute('INSERT OR IGNORE INTO old VALUES (?,?)', (key, semantic))
            for number in range(1, max(1, (total + PAGE_SIZE - 1) // PAGE_SIZE) + 1):
                for row in validated_page(pages_dir / f'{number}.json', dataset, number, total):
                    key = str(row.get('MNG_NO') or '').strip()
                    if not key: raise ValueError('public record has no management number')
                    try:
                        conn.execute('INSERT INTO current VALUES (?,?,?)',
                                     (key, json.dumps(row, ensure_ascii=False), digest({f: row.get(f) for f in FIELDS})))
                    except sqlite3.IntegrityError:
                        raise ValueError('duplicate management number across public pages') from None
            count = conn.execute('SELECT count(*) FROM current').fetchone()[0]
            if count != total: raise ValueError('public unique row count differs from totalCount')
            queries = {
                'raw': 'SELECT body FROM current ORDER BY id',
                'new': 'SELECT c.body FROM current c LEFT JOIN old o ON c.id=o.id WHERE o.id IS NULL ORDER BY c.id',
                'upd': 'SELECT c.body FROM current c JOIN old o ON c.id=o.id WHERE c.semantic<>o.semantic ORDER BY c.id',
            }
            paths = {'raw': matching / 'localdata_raw' / f'{dataset}.jsonl',
                     'new': matching / 'localdata_raw/delta' / f'{dataset}_new.jsonl',
                     'upd': matching / 'localdata_raw/delta' / f'{dataset}_upd.jsonl'}
            counts = {}
            for kind, query in queries.items():
                counts[kind] = conn.execute(f'SELECT count(*) FROM ({query})').fetchone()[0]
                write_lines(paths[kind], (json.loads(r[0]) for r in conn.execute(query)))
            counts['unobserved'] = conn.execute('SELECT count(*) FROM old o LEFT JOIN current c ON o.id=c.id WHERE c.id IS NULL').fetchone()[0]
            # 미관측은 폐업이 아니다. new는 신규 개업과 과거 수집 누락 모두 포함해 검증 후보로 전달한다.
            return {'counts': counts, 'files': {str(p.relative_to(matching)): file_hash(p) for p in paths.values()}}
        finally:
            conn.close()


def collect(root, seed, datasets, max_pages, max_seconds, fetch=None):
    if fetch is not None:
        return collect_pages(root, seed, datasets, max_pages, max_seconds, fetch)
    with tls_session() as session:
        key = load_service_key()
        return collect_pages(root, seed, datasets, max_pages, max_seconds,
                             lambda dataset, number: call(session, dataset, key, number))


def collect_pages(root, seed, datasets, max_pages, max_seconds, fetch):
    deadline, fetched = time.monotonic() + max_seconds, 0
    report = {'status': 'INCOMPLETE', 'datasets': {}, 'started_at': utc_now(), 'pages_fetched': 0}
    matching = root / 'matching'
    root.mkdir(parents=True, exist_ok=True)
    for dataset in datasets:
        pages_dir = root / 'pages' / dataset
        pages_dir.mkdir(parents=True, exist_ok=True)
        if fetched >= max_pages or time.monotonic() >= deadline: break
        # 원천의 페이지 경계가 바뀐 것이 보이면 이어 붙이지 않고 새 cycle을 요구한다.
        try:
            body = fetch(dataset, 1)
        except QuotaExceeded:
            report['status'] = 'BLOCKED'
            break
        fetched += 1
        total = int(body['totalCount'])
        if total < 0: raise ValueError('negative public totalCount')
        first = {'dataset': dataset, 'page': 1, 'total': total, 'rows': items_of(body), 'observed_at': utc_now()}
        first['digest'] = digest(first['rows'])
        first_path = pages_dir / '1.json'
        if first_path.exists():
            previous = json.loads(first_path.read_text())
            validated_page(first_path, dataset, 1, total)
            if previous['digest'] != first['digest']: raise ValueError('public first page changed; start a new cycle')
        else:
            atomic_json(first_path, first)
            validated_page(first_path, dataset, 1, total)
        needed = max(1, (total + PAGE_SIZE - 1) // PAGE_SIZE)
        complete = True
        for number in range(2, needed + 1):
            target = pages_dir / f'{number}.json'
            if target.exists():
                validated_page(target, dataset, number, total)
                continue
            if fetched >= max_pages or time.monotonic() >= deadline:
                complete = False
                break
            try:
                body = fetch(dataset, number)
            except QuotaExceeded:
                report['status'], complete = 'BLOCKED', False
                break
            fetched += 1
            if int(body['totalCount']) != total: raise ValueError('public totalCount changed during collection')
            page = {'dataset': dataset, 'page': number, 'total': total, 'rows': items_of(body), 'observed_at': utc_now()}
            page['digest'] = digest(page['rows'])
            atomic_json(target, page)
            validated_page(target, dataset, number, total)
        if complete:
            report['datasets'][dataset] = materialize(dataset, pages_dir, total,
                                                      seed / 'matching/localdata_raw' / f'{dataset}.jsonl', matching)
        else:
            break
    report['pages_fetched'] = fetched
    if len(report['datasets']) == len(datasets): report['status'] = 'COMPLETED'
    report['finished_at'] = utc_now()
    atomic_json(root / 'public-result.json', report)
    return 0 if report['status'] == 'COMPLETED' else 3 if report['status'] == 'BLOCKED' else 4


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--seed', type=Path, required=True)
    parser.add_argument('--datasets', nargs='+', choices=tuple(DATASETS), required=True)
    parser.add_argument('--max-pages', type=int, default=100)
    parser.add_argument('--max-seconds', type=int, default=1800)
    args = parser.parse_args()
    if args.max_pages < 1 or args.max_seconds < 1: parser.error('positive page/time budget required')
    try:
        return collect(args.root, args.seed, args.datasets, args.max_pages, args.max_seconds)
    except Exception as error:
        # HTTP 예외에 인증키 포함 URL이 있을 수 있어 예외 종류만 기록한다.
        atomic_json(args.root / 'public-error.json', {'error_type': type(error).__name__, 'at': utc_now()})
        print(f'public collection failed: {type(error).__name__}', flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
