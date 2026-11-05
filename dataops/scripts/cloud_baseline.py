"""로컬 이관 없이 공공데이터 최초 기준과 운영 가게 연결 근거를 클라우드에서 만든다."""
import csv
import json
from pathlib import Path
import unicodedata

from batch_execution import atomic_json, utc_now
from cloud_public_pull import collect, file_hash, rows
from cloud_runtime import serving_csv
from cloud_seed import allowed_files, validate
from localdata_match import building_key


def identity_key(name, address):
    building = building_key(address)
    name = ' '.join(unicodedata.normalize('NFKC', name or '').casefold().split())
    if not name or not building or building.sido is None or building.city is None:
        return None
    return name, building


def match_identities(stores, records):
    """시군구·도로명·본번·부번·이름이 일치하고 양쪽 모두 유일할 때만 연결한다."""
    stores_by_key = {}
    for store in stores:
        key = identity_key(store['name'], store['address'])
        if key: stores_by_key.setdefault(key, []).append(store)
    licenses_by_key = {}
    for record in records:
        key = identity_key(record.get('BPLC_NM'), record.get('ROAD_NM_ADDR'))
        if key in stores_by_key:
            licenses_by_key.setdefault(key, set()).add(str(record['MNG_NO']))
    links, ambiguous = [], 0
    for key, licenses in licenses_by_key.items():
        matches = stores_by_key[key]
        if len(matches) != 1 or len(licenses) != 1:
            ambiguous += len(matches)
            continue
        store = matches[0]
        links.append({'store_id': store['id'], 'store_name': store['name'], 'mgmt_no': next(iter(licenses)),
                      'judgment': 'AUTO', 'naver_place_id': store.get('naver_place_id') or ''})
    # 한 관리번호가 여러 운영 가게를 가리키는 근거도 자동 채택하지 않는다.
    counts = {}
    for link in links: counts[link['mgmt_no']] = counts.get(link['mgmt_no'], 0) + 1
    unique = [link for link in links if counts[link['mgmt_no']] == 1]
    return unique, {'stores': len(stores), 'mapped': len(unique), 'unmapped': len(stores) - len(unique),
                    'ambiguous': ambiguous + len(links) - len(unique)}


def publish_manifest(root, datasets):
    entries = {name: {'bytes': (root / name).stat().st_size, 'sha256': file_hash(root / name)}
               for name in allowed_files(datasets)}
    atomic_json(root / 'manifest.json', {'schema_version': 1, 'created_at': utc_now(),
                'datasets': sorted(set(datasets)), 'files': entries,
                'purpose': 'cloud-collected baseline; not evidence of a newly opened or closed store'})
    return validate(root, datasets)


def bootstrap(root, datasets, max_pages, max_seconds, fetch=None, read_stores=None):
    root = Path(root)
    scope = {'datasets': sorted(set(datasets)), 'identity_policy': 'exact-name-full-building-unique-v1'}
    root.mkdir(parents=True, exist_ok=True)
    scope_path = root / 'baseline-scope.json'
    if scope_path.exists() and json.loads(scope_path.read_text()) != scope:
        raise ValueError('baseline scope changed; choose a new baseline version')
    atomic_json(scope_path, scope)
    if (root / 'manifest.json').exists():
        validate(root, datasets)
        return 0
    # 최초 수집의 신규/변경 delta는 비교 이력이 없으므로 업무 후보로 사용하지 않는다.
    empty = root / 'empty-reference/matching/localdata_raw'
    empty.mkdir(parents=True, exist_ok=True)
    for dataset in datasets: (empty / f'{dataset}.jsonl').touch(exist_ok=True)
    result = root / 'public-result.json'
    if result.exists() and json.loads(result.read_text())['status'] == 'COMPLETED':
        report = json.loads(result.read_text())
        for output in report['datasets'].values():
            for name, expected in output['files'].items():
                if file_hash(root / 'matching' / name) != expected: raise ValueError('baseline artifact changed')
    else:
        code = collect(root, root / 'empty-reference', datasets, max_pages, max_seconds, fetch)
        if code: return code
    if read_stores is None:
        read_stores = lambda: serving_csv("SELECT id,name,address,naver_place_id FROM store WHERE status='ACTIVE'")
    current = read_stores()
    records = (record for dataset in datasets for record in rows(root / f'matching/localdata_raw/{dataset}.jsonl'))
    links, counts = match_identities(current, records)
    matching = root / 'matching'
    with (matching / 'localdata_mapping.tsv').open('w', encoding='utf-8', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=['store_id', 'store_name', 'mgmt_no', 'judgment', 'naver_place_id'], delimiter='\t')
        writer.writeheader()
        writer.writerows(links)
    with (matching / 'registered_links.tsv').open('w', encoding='utf-8', newline='') as output:
        writer = csv.writer(output, delimiter='\t')
        writer.writerow(['mgmt_no', 'registered_store_id', 'naver_place_id'])
        writer.writerows((r['mgmt_no'], r['store_id'], r['naver_place_id']) for r in links)
    (matching / 'new_review_judgments.jsonl').write_text('', encoding='utf-8')
    atomic_json(root / 'identity-summary.json', counts)
    publish_manifest(root, datasets)
    return 0
