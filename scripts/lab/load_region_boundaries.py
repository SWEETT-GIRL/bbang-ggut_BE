#!/usr/bin/env python3
"""검증한 전국 경계를 로컬 전용 런타임 스키마에 적재한다. public 테이블은 수정하지 않는다."""
import argparse
import hashlib
import json
from pathlib import Path

from audit_nationwide_regions import emd_reference
from region_polygon_lab import ROOT, SCHEMA, ancestry, local_guard, query, reference, sql

RUNTIME_SCHEMA = 'region_polygon_runtime_742'
PROJECTION = 'legal-emd-app-scope-v1'


def scope_records(ref):
    names = {}
    for code, entry in ref.items():
        name = ' '.join(entry['locatadd_nm'].split())
        if name in names:
            raise ValueError(f'Ambiguous canonical name: {name}')
        names[name] = code
    records = []
    for code, entry in emd_reference(ref).items():
        chain = ancestry(ref, code)
        root = ' '.join(chain[-1]['locatadd_nm'].split())
        scope_name = entry['name'] if entry['province'] == '세종' else root + ' ' + entry['city']
        records.append({'code': code, 'name': entry['name'], 'scope_code': names[scope_name],
                        'scope_name': scope_name, 'scope_level': 'EMD' if entry['province'] == '세종' else 'CITY',
                        'ancestry': [{'code': row['region_cd'], 'name': row['locatadd_nm'],
                                      'parent_code': row['locathigh_cd']} for row in chain]})
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-id', required=True)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    local_guard()
    mapping = scope_records(reference(args.reference))
    ref_hash = hashlib.sha256(args.reference.read_bytes()).hexdigest()
    source = json.loads(query(f'SELECT metadata FROM {SCHEMA}.source WHERE id={sql(args.source_id)};'))
    if source.get('source_kind') != 'DERIVED_REPAIR_CANDIDATE':
        raise ValueError('Use the separately audited repair candidate')
    codes = json.loads(query(f'SELECT jsonb_agg(code ORDER BY code) FROM {SCHEMA}.boundary '
                             f'WHERE source_id={sql(args.source_id)} AND usable;'))
    if sorted(codes) != sorted(row['code'] for row in mapping):
        raise ValueError('Usable boundaries must match every reference EMD exactly once')
    version = f"vworld-{source['source_date']}-{args.source_id}-{ref_hash}-{PROJECTION}"
    migration = ROOT / 'core/src/main/resources/db/migration/V20261013190000__create_store_region_boundaries.sql'
    exists = query(f"SELECT to_regclass('{RUNTIME_SCHEMA}.region_boundary_versions') IS NOT NULL;") == 't'
    if not exists:
        query(f'BEGIN; CREATE SCHEMA IF NOT EXISTS {RUNTIME_SCHEMA}; '
              f'SET LOCAL search_path TO {RUNTIME_SCHEMA}, public;\n{migration.read_text()}\nCOMMIT;')
    metadata = {'lab_source_id': args.source_id, 'source_metadata': source, 'emd_count': len(mapping),
                'scope_count': len({row['scope_code'] for row in mapping}),
                'loader_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    already_ready = query(f'SELECT EXISTS(SELECT 1 FROM {RUNTIME_SCHEMA}.region_boundary_versions '
                          f'WHERE version_key={sql(version)} AND ready);') == 't'
    if not already_ready:
        query(f"""BEGIN;
          SET LOCAL search_path TO {RUNTIME_SCHEMA}, public;
          CREATE TEMP TABLE region_import_mapping ON COMMIT DROP AS
            SELECT * FROM jsonb_to_recordset({sql(json.dumps(mapping, ensure_ascii=False))}::jsonb)
            AS t(code text,name text,scope_code text,scope_name text,scope_level text,ancestry jsonb);
          INSERT INTO region_boundary_versions(version_key,source_date,reference_sha256,projection_version,metadata)
            VALUES ({sql(version)},{sql(source['source_date'])}::date,{sql(ref_hash)},
                    {sql(PROJECTION)},{sql(json.dumps(metadata, ensure_ascii=False))}::jsonb);
          INSERT INTO region_app_scopes(version_id,scope_code,name,level)
            SELECT DISTINCT v.id,m.scope_code,m.scope_name,m.scope_level
            FROM region_import_mapping m CROSS JOIN region_boundary_versions v WHERE v.version_key={sql(version)};
          INSERT INTO region_emd_boundaries(version_id,legal_code,official_name,ancestry,scope_code,geom)
            SELECT v.id,m.code,m.name,m.ancestry,m.scope_code,b.geom
            FROM region_import_mapping m JOIN {SCHEMA}.boundary b ON b.code=m.code
              AND b.source_id={sql(args.source_id)} AND b.usable
            CROSS JOIN region_boundary_versions v WHERE v.version_key={sql(version)};
          DO $$ BEGIN
            IF (SELECT count(*) FROM region_emd_boundaries b JOIN region_boundary_versions v ON v.id=b.version_id
                WHERE v.version_key={sql(version)}) <> {len(mapping)} THEN
              RAISE EXCEPTION 'Incomplete boundary load';
            END IF;
          END $$;
          UPDATE region_boundary_versions SET ready=true,updated_at=now() WHERE version_key={sql(version)};
          COMMIT;""")
    report = {'version_key': version, 'runtime_schema': RUNTIME_SCHEMA, 'source_id': args.source_id,
              'reference_sha256': ref_hash, 'emd_count': len(mapping), 'scope_count': metadata['scope_count'],
              'already_ready': already_ready, 'production_applied': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
