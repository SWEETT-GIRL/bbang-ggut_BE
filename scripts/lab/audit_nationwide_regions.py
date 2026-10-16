#!/usr/bin/env python3
"""전국 로컬 가게의 법정 읍면동 소속 감사. DB 읽기 전용, 개별 근거는 Git 밖에 저장."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re

from region_polygon_lab import SCHEMA, ROOT, ancestry, local_guard, query, reference, sql

ALIASES = dict(zip(
    ['서울특별시', '부산광역시', '대구광역시', '인천광역시', '광주광역시', '대전광역시',
     '울산광역시', '세종특별자치시', '경기도', '강원특별자치도', '충청북도', '충청남도',
     '전북특별자치도', '전라남도', '경상북도', '경상남도', '제주특별자치도'],
    ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원',
     '충북', '충남', '전북', '전남', '경북', '경남', '제주']))
ALIASES.update({'강원도': '강원', '전라북도': '전북'})


def fold(name):
    return re.sub(r'\d+가$', '', name or '') or name


def normalize_province(name, city):
    if name in ('전남광주통합특별시', '전남광주'):
        return '광주' if city in {'동구', '서구', '남구', '북구', '광산구'} else '전남'
    return ALIASES.get(name, name)


def emd_reference(ref):
    result = {}
    for code, entry in ref.items():
        if not code.endswith('00') or code[5:8] == '000':
            continue
        chain = ancestry(ref, code)
        province = ' '.join(chain[-1]['locatadd_nm'].split())
        full_name = ' '.join(entry['locatadd_nm'].split())
        parent = ' '.join(ref[code[:5] + '00000']['locatadd_nm'].split())
        if not full_name.startswith(parent + ' ') or not parent.startswith(province):
            raise ValueError(f'Unexpected reference hierarchy: {code}')
        emd = full_name[len(parent) + 1:]
        district = parent[len(province):].strip()
        city = district.split()[0] if district else fold(emd)  # 세종의 기존 앱 투영
        result[code] = {'code': code, 'name': full_name, 'emd': emd, 'district': district,
                        'province': normalize_province(province, city), 'city': city,
                        'app_emd': fold(emd), 'ancestors': [r['region_cd'] for r in chain]}
    return result


def classify(row, ref):
    codes = row['codes']
    if row['point'] is None:
        return 'NO_POINT'
    if len(codes) == 0:
        return 'NO_MATCH'
    if len(codes) > 1:
        return 'AMBIGUOUS'
    mapped = ref.get(codes[0])
    if mapped is None:
        return 'CODE_NOT_IN_REFERENCE'
    r1, r2, r3 = (' '.join((row.get('region_' + level) or '').split())
                  for level in ('1st', '2nd', '3rd'))
    if normalize_province(r1, r2) != mapped['province']:
        return 'PROVINCE_DIFFERENT'
    # city+gu 문자열과 city만 저장한 경우를 같은 도시로 취급. 세종은 시군구를 생략한다.
    if mapped['province'] != '세종' and (r2 or '').split()[:1] != [mapped['city']]:
        return 'CITY_DIFFERENT'
    if r3 in (mapped['emd'], mapped['app_emd']):
        return 'CONSISTENT'
    if len(mapped['district'].split()) == 2 and r3 == mapped['district'].split()[-1]:
        return 'DISTRICT_IN_THIRD_FIELD'
    return 'EMD_DIFFERENT'


def counts(rows):
    classes = Counter(r['classification'] for r in rows)
    unique = sum(len(r['codes']) == 1 for r in rows)
    verified = sum(len(r['codes']) == 1 and r['classification'] != 'CODE_NOT_IN_REFERENCE' for r in rows)
    return {'stores': len(rows), 'unique_geometry_match': unique, 'unique_reference_match': verified,
            'unique_reference_match_pct': round(100 * verified / len(rows), 4) if rows else None,
            'unique_app_scope': sum(r['unique_app_scope'] for r in rows),
            'classification': dict(sorted(classes.items()))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-id', required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.evidence.resolve().is_relative_to(ROOT):
        raise ValueError('Store-level evidence must be outside this repository')
    local_guard()
    ref = emd_reference(reference(args.reference))
    src = sql(args.source_id)
    print('Reading nationwide local snapshot...', flush=True)
    raw = json.loads(query(f"""
      BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
      WITH stores AS (
        SELECT s.id,s.region_1st,s.region_2nd,s.region_3rd,
          ST_AsText(s.location::geometry) point,
          s.status='ACTIVE' AND EXISTS(SELECT 1 FROM public.store_profile p
            WHERE p.store_id=s.id AND p.bakery_score>=.5) eligible,
          ARRAY(SELECT DISTINCT b.code FROM {SCHEMA}.boundary b
            WHERE b.source_id={src} AND b.usable AND ST_Covers(b.geom,s.location::geometry)
            ORDER BY b.code) codes,
          ARRAY(SELECT b.id FROM {SCHEMA}.boundary b
            WHERE b.source_id={src} AND NOT b.usable AND ST_IsValid(b.geom)
              AND ST_Covers(b.geom,s.location::geometry) ORDER BY b.id) invalid_boundary_ids
        FROM public.store s
      ) SELECT jsonb_build_object(
        'measured_at',now(), 'db_system_identifier',(SELECT system_identifier::text FROM pg_control_system()),
        'store_latest_update',(SELECT max(updated_at) FROM public.store),
        'source_id',{src}, 'source_metadata',(SELECT metadata FROM {SCHEMA}.source WHERE id={src}),
        'boundaries',(SELECT jsonb_agg(t ORDER BY id) FROM (
          SELECT id,code,name,usable,invalid_reason,ST_Area(geom::geography) area_m2
          FROM {SCHEMA}.boundary WHERE source_id={src}) t),
        'stores',(SELECT jsonb_agg(stores ORDER BY id) FROM stores));
      COMMIT;
    """))
    if not raw['source_metadata'] or not raw['boundaries']:
        raise ValueError('Unknown or empty polygon source')
    rows = raw['stores']
    assert len({r['id'] for r in rows}) == len(rows)
    for row in rows:
        row['classification'] = classify(row, ref)
        scopes = {(ref[c]['province'], ref[c]['city']) for c in row['codes'] if c in ref}
        row['unique_app_scope'] = len(scopes) == 1 and all(c in ref for c in row['codes'])
        if len(row['codes']) == 1:
            row['reference'] = ref.get(row['codes'][0])
    exceptions = [{'id': r['id'], 'point': r['point'], 'codes': r['codes']}
                  for r in rows if r['classification'] in ('NO_MATCH', 'AMBIGUOUS') and r['point']]
    # 저장한 좌표로 진단한다. 최근접 도형은 0.1도 이내 검색 결과일 뿐 소속으로 채택하지 않는다.
    raw['exception_diagnostics'] = json.loads(query(f"""
      BEGIN TRANSACTION READ ONLY;
      WITH points AS (SELECT id,ST_GeomFromText(point,4326) geom,codes
        FROM jsonb_to_recordset({sql(json.dumps(exceptions))}::jsonb)
          AS t(id bigint,point text,codes text[]))
      SELECT coalesce(jsonb_agg(t ORDER BY id),'[]'::jsonb) FROM (
        SELECT p.id,coalesce((SELECT jsonb_agg(jsonb_build_object(
          'code',b.code,'strict_interior',ST_Contains(b.geom,p.geom),
          'boundary_distance_m',ST_Distance(ST_Boundary(b.geom)::geography,p.geom::geography)) ORDER BY b.code)
          FROM {SCHEMA}.boundary b WHERE b.source_id={src} AND b.usable AND b.code=ANY(p.codes)),
          '[]'::jsonb) matches,
          CASE WHEN cardinality(p.codes)=0 THEN (SELECT jsonb_agg(n) FROM (
            SELECT b.code,ST_Distance(b.geom::geography,p.geom::geography) distance_m
            FROM {SCHEMA}.boundary b WHERE b.source_id={src} AND b.usable
              AND ST_DWithin(b.geom,p.geom,.1)
            ORDER BY distance_m,b.code LIMIT 3) n) END nearest_within_point_one_degree
        FROM points p) t;
      COMMIT;
    """))
    diagnostic_by_id = {d['id']: d for d in raw['exception_diagnostics']}
    ambiguous_groups = defaultdict(list)
    for r in rows:
        if r['classification'] == 'AMBIGUOUS':
            ambiguous_groups[tuple(r['codes'])].append(r)
    pairs = [{'first': c[0], 'second': c[1]} for c in sorted(ambiguous_groups) if len(c) == 2]
    raw['overlap_diagnostics'] = json.loads(query(f"""
      BEGIN TRANSACTION READ ONLY;
      SELECT coalesce(jsonb_agg(t ORDER BY first_code,second_code),'[]'::jsonb) FROM (
        SELECT a.code first_code,b.code second_code,ST_Equals(a.geom,b.geom) same_geometry,
          ST_Area(ST_Intersection(a.geom,b.geom)::geography) intersection_area_m2,
          (SELECT bool_and(o.usable) FROM {SCHEMA}.boundary o
             WHERE o.source_id={sql(raw['source_metadata'].get('parent_source_id', args.source_id))}
               AND o.code IN (a.code,b.code)) both_usable_before_repair
        FROM jsonb_to_recordset({sql(json.dumps(pairs))}::jsonb) AS p(first text,second text)
        JOIN {SCHEMA}.boundary a ON a.source_id={src} AND a.code=p.first
        JOIN {SCHEMA}.boundary b ON b.source_id={src} AND b.code=p.second) t;
      COMMIT;
    """))
    boundaries = raw['boundaries']
    usable_codes = {b['code'] for b in boundaries if b['usable']}
    invalid = [b for b in boundaries if not b['usable']]
    invalid_groups = defaultdict(list)
    for boundary in invalid:
        # 이름 칸의 19자리 값은 진단용 접두사만 대조한다. 법정 코드로 채택하지 않는다.
        name = boundary['name'] or ''
        hint = ref.get(name[:8] + '00') if re.fullmatch(r'\d{19}', name) else None
        invalid_groups[hint['name'] if hint else 'UNKNOWN'].append(boundary)
    eligible = [r for r in rows if r['eligible']]
    by_province = defaultdict(list)
    for row in rows:
        by_province[normalize_province(row['region_1st'], row['region_2nd'])].append(row)
    summary = {k: v for k, v in raw.items() if k not in ('stores', 'boundaries', 'exception_diagnostics')}
    summary.update(reference_sha256=hashlib.sha256(args.reference.read_bytes()).hexdigest(),
                   script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   ambiguous_groups=[{'codes': list(codes), 'names': [ref[c]['name'] for c in codes],
                     'stores': len(group), 'eligible': sum(r['eligible'] for r in group),
                     'all_matches_strict_interior': all(all(m['strict_interior'] for m in diagnostic_by_id[r['id']]['matches']) for r in group),
                     'max_boundary_distance_m': max(m['boundary_distance_m'] for r in group for m in diagnostic_by_id[r['id']]['matches'])}
                     for codes, group in sorted(ambiguous_groups.items())],
                   unmatched_diagnostics=[{'stored_province': r['region_1st'], 'stored_city': r['region_2nd'],
                     'stored_emd': r['region_3rd'], 'eligible': r['eligible'],
                     'nearby': diagnostic_by_id[r['id']]['nearest_within_point_one_degree']}
                     for r in rows if r['classification'] == 'NO_MATCH'],
                   all_stores=counts(rows), eligible_stores=counts(eligible),
                   by_stored_province={k: {'all': counts(v), 'eligible': counts([r for r in v if r['eligible']])}
                                       for k, v in sorted(by_province.items())},
                   boundary_quality={'rows': len(boundaries), 'usable_rows': len(boundaries)-len(invalid),
                     'reference_emd_codes': len(ref), 'usable_distinct_codes': len(usable_codes),
                     'usable_codes_absent_reference': sorted(usable_codes-ref.keys()),
                     'reference_codes_without_usable_geometry': [ref[c] for c in sorted(ref.keys()-usable_codes)],
                     'unusable_rows': len(invalid),
                     'boundary_reference_name_differences': [
                       {'code': b['code'], 'boundary_name': b['name'], 'reference_name': ref[b['code']]['name']}
                       for b in boundaries if b['usable'] and b['code'] in ref
                       and ' '.join((b['name'] or '').split()) != ref[b['code']]['emd']],
                     'unusable_diagnostic_name_prefix_groups': [
                       {'reference_name_hint': name, 'rows': len(group),
                        'area_m2': sum(b['area_m2'] for b in group)}
                       for name, group in sorted(invalid_groups.items())],
                     'stores_intersecting_unusable_geometry': sum(bool(r['invalid_boundary_ids']) for r in rows),
                     'eligible_intersecting_unusable_geometry': sum(bool(r['invalid_boundary_ids']) for r in eligible)},
                   limitations=['Local snapshot; no production or store updates.',
                     'September 9 boundaries versus September 26 code reference; later deltas not applied.',
                     'Province breakdown uses normalized stored fields, not a spatial prefilter.',
                     'ST_Covers exact matching; edge/multiple matches not assigned, no nearest snapping.',
                     'String disagreements are review candidates, not proven address errors.',
                     'Invalid name prefixes are diagnostic hints only; never used to assign stores.'])
    for cohort in (summary['all_stores'], summary['eligible_stores']):
        assert sum(cohort['classification'].values()) == cohort['stores']
    assert sum(v['all']['stores'] for v in summary['by_stored_province'].values()) == len(rows)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(raw, ensure_ascii=False, indent=2)+'\n')
    summary['evidence_sha256'] = hashlib.sha256(args.evidence.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: summary[k] for k in ('all_stores', 'eligible_stores', 'boundary_quality')},
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
