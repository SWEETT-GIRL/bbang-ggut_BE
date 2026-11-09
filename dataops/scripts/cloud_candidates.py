"""현재 실행에서 검증된 신규 후보만 DataOps에 멱등 수납한다. 운영 등록은 하지 않는다."""
import argparse
import csv
from pathlib import Path
import sys

from batch_execution import atomic_json
from cloud_runtime import matching_dir, serving_connect
import local_db
from naver_sweep_enqueue import ymd


def normalized(rows, source):
    candidates, conflicts = {}, set()
    for row in rows:
        if source == 'public':
            value = {'mgmt_no': row['mgmt_no'], 'name': row['lic_name'], 'address': row['road_address'],
                     'dataset': row['dataset'], 'date': ymd(row['licensed_ymd']), 'place_id': row['naver_place_id']}
        else:
            value = {'mgmt_no': row['mgmt_no'], 'name': row['licenseName'], 'address': row['address'],
                     'dataset': row['dataset'], 'date': ymd(row['licensed_ymd']), 'place_id': row['placeId']}
        if not value['mgmt_no'] or not value['place_id']: raise ValueError('candidate identity missing')
        previous = candidates.get(value['mgmt_no'])
        if previous and previous['place_id'] != value['place_id']: conflicts.add(value['mgmt_no'])
        candidates[value['mgmt_no']] = value
    return [value for key, value in sorted(candidates.items()) if key not in conflicts], sorted(conflicts)


def persist(connection, candidates):
    inserted, existing, conflicts = 0, 0, []
    with connection, connection.cursor() as cursor:
        for row in candidates:
            cursor.execute('''INSERT INTO new_store_candidate
                (mgmt_no,name,road_address,category_code,licensed_at,status,naver_place_id)
                VALUES (%s,%s,%s,%s,%s,'MATCHED',%s) ON CONFLICT (mgmt_no) DO NOTHING''',
                (row['mgmt_no'], row['name'][:100], row['address'][:200] or None,
                 row['dataset'][:40], row['date'], row['place_id']))
            if cursor.rowcount:
                inserted += 1
            else:
                cursor.execute('SELECT naver_place_id FROM new_store_candidate WHERE mgmt_no=%s', (row['mgmt_no'],))
                found = cursor.fetchone()
                if found and found[0] == row['place_id']: existing += 1
                else: conflicts.append(row['mgmt_no'])
    return inserted, existing, conflicts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', choices=['public', 'discovery'], required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--summary', type=Path, required=True)
    args = parser.parse_args()
    with args.input.open() as stream: source_rows = list(csv.DictReader(stream, delimiter='\t'))
    candidates, conflicts = normalized(source_rows, args.source)
    with (matching_dir() / 'registered_links.tsv').open() as stream:
        registered = {row['mgmt_no'] for row in csv.DictReader(stream, delimiter='\t')}
    skipped_registered = sum(row['mgmt_no'] in registered for row in candidates)
    candidates = [row for row in candidates if row['mgmt_no'] not in registered]
    # 로컬의 과거 등록 이력을 이관하지 않아도 운영에 이미 존재하는 place는 신규 수납하지 않는다.
    if candidates:
        reader = serving_connect()
        try:
            with reader.cursor() as cursor:
                cursor.execute('SELECT naver_place_id FROM store WHERE naver_place_id=ANY(%s)',
                               ([row['place_id'] for row in candidates],))
                known = {row[0] for row in cursor}
        finally:
            reader.close()
        skipped_registered += sum(row['place_id'] in known for row in candidates)
        candidates = [row for row in candidates if row['place_id'] not in known]
    conn = local_db.connect()
    try:
        inserted, existing, db_conflicts = persist(conn, candidates)
    finally:
        conn.close()
    atomic_json(args.summary, {'input': len(source_rows), 'inserted': inserted, 'existing': existing,
                              'registered': skipped_registered, 'held': len(conflicts) + len(db_conflicts),
                              'conflicts': sorted(set(conflicts + db_conflicts))})
    return 0


if __name__ == '__main__':
    sys.exit(main())
