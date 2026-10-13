#!/usr/bin/env python3
"""공식 코드와 기존 앱 지역을 연결해 로컬 런타임에 저장한다. 기존 course_area는 변경하지 않는다."""
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path

from audit_nationwide_regions import emd_reference
from load_region_boundaries import RUNTIME_SCHEMA
from region_polygon_lab import ROOT, local_guard, query, reference, sql

REFERENCE_HASH = '4333ed7bdb62ed7132e02650936877b848b7fd3168bbb00264534d0694130cb5'
HISTORICAL_HASH = 'd1062860dd739c8fe6da23f110b08b0147d909ec2430f6933586e0014a0379f3'


def require_hash(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f'Unaudited reference: {path}')


def course_area_bindings(areas, current_path, historical_path):
    require_hash(current_path, REFERENCE_HASH)
    require_hash(historical_path, HISTORICAL_HASH)
    emd = emd_reference(reference(current_path))
    with historical_path.open(encoding='cp949', newline='') as stream:
        names = [row['법정동명'].removeprefix('인천광역시 중구 ') for row in csv.DictReader(stream)
                 if row['폐지여부'] == '존재' and row['법정동코드'].startswith('28110')
                 and row['법정동코드'].endswith('00') and row['법정동코드'][5:8] != '000'
                 and row['법정동명'].startswith('인천광역시 중구 ')]
    if len(names) != 52 or len(set(names)) != 52:
        raise ValueError('Expected historical Jung-gu 52 EMDs')
    jung = []
    for name in names:
        matched = [row for row in emd.values() if row['province'] == '인천'
                   and row['city'] in {'제물포구', '영종구'} and row['emd'] == name]
        if len(matched) != 1:
            raise ValueError(f'Ambiguous historical EMD: {name}')
        jung.extend(matched)
    if Counter(row['city'] for row in jung) != {'제물포구': 44, '영종구': 8}:
        raise ValueError('Unexpected historical successors')
    result, groups, ids = [], {}, set()
    for area in sorted(areas, key=lambda row: row['id']):
        key = (area['sido'], area['passport_spot'])
        if key == ('인천', '중구'):
            codes = sorted(row['code'] for row in jung)
            policy = f'legacy-incheon-jung-52-v1;historical_sha256={HISTORICAL_HASH}'
        elif area['region_3rd']:
            if not area['region_2nd']:
                raise ValueError('Neighborhood without district')
            codes = sorted(row['code'] for row in emd.values() if row['province'] == area['sido']
                           and row['city'] == area['region_2nd'] and row['app_emd'] == area['region_3rd'])
            policy = 'legal-emd-name-v1'
        else:
            codes = sorted(row['code'] for row in emd.values()
                           if (row['province'], row['city']) == key)
            policy = 'legal-app-city-v1'
        if not codes or len(codes) != len(set(codes)) or area['id'] in ids:
            raise ValueError(f'Invalid binding: {area["id"]}')
        if key in groups and groups[key] != codes:
            raise ValueError(f'Inconsistent chip: {key}')
        groups[key] = codes
        ids.add(area['id'])
        result.append({'course_area_id': area['id'], 'legal_codes': codes, 'mapping_note': policy,
                       'area_signature': [area['sido'], area['passport_spot'], area['region_2nd'], area['region_3rd']]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--historical-reference', type=Path, required=True)
    parser.add_argument('--load-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    local_guard()
    version = json.loads(args.load_manifest.read_text())['version_key']
    if query(f'SELECT reference_sha256 FROM {RUNTIME_SCHEMA}.region_boundary_versions '
             f'WHERE version_key={sql(version)} AND ready;') != REFERENCE_HASH:
        raise ValueError('Boundary version not ready or reference mismatch')
    areas = json.loads(query('SELECT jsonb_agg(a ORDER BY id) FROM (SELECT id,sido,passport_spot,region_2nd,region_3rd '
                             'FROM public.course_area WHERE is_active) a;'))
    bindings = course_area_bindings(areas, args.reference, args.historical_reference)
    if query(f"SELECT to_regclass('{RUNTIME_SCHEMA}.course_area_region_bindings') IS NOT NULL;") != 't':
        migration = ROOT / 'src/main/resources/db/migration/V20261013200000__bind_course_areas_to_regions.sql'
        query(f'BEGIN; SET LOCAL search_path TO {RUNTIME_SCHEMA}, public; {migration.read_text()} COMMIT;')
    query(f"""BEGIN; SET LOCAL search_path TO {RUNTIME_SCHEMA}, public;
        CREATE TEMP TABLE binding_import ON COMMIT DROP AS
          SELECT * FROM jsonb_to_recordset({sql(json.dumps(bindings, ensure_ascii=False))}::jsonb)
          AS t(course_area_id bigint,legal_codes text[],mapping_note text,area_signature jsonb);
        INSERT INTO course_area_region_bindings(version_id,course_area_id,legal_codes,mapping_note,area_signature)
          SELECT v.id,m.* FROM binding_import m CROSS JOIN region_boundary_versions v
          WHERE v.version_key={sql(version)} AND NOT EXISTS (SELECT 1 FROM course_area_region_bindings b
            WHERE b.version_id=v.id AND b.course_area_id=m.course_area_id);
        DO $$ BEGIN
          IF EXISTS(SELECT 1 FROM binding_import m JOIN course_area_region_bindings b USING(course_area_id)
              JOIN region_boundary_versions v ON v.id=b.version_id WHERE v.version_key={sql(version)}
              AND (m.legal_codes<>b.legal_codes OR m.area_signature<>b.area_signature OR m.mapping_note<>b.mapping_note)) THEN
            RAISE EXCEPTION 'Existing bindings differ; create a new version';
          END IF;
        END $$; COMMIT;""")
    report = {'version_key': version, 'area_count': len(bindings),
              'chip_count': len({tuple(row['area_signature'][:2]) for row in bindings}),
              'legal_code_count': len({code for row in bindings for code in row['legal_codes']}),
              'bindings': bindings, 'production_applied': False}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'bindings'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
