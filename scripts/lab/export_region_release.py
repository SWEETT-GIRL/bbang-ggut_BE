#!/usr/bin/env python3
"""로컬 검증 버전을 독립 SQL 릴리스 파일로 내보낸다. 대상 환경에는 접속하지 않는다."""
import argparse
import hashlib
import json
from pathlib import Path

from load_region_boundaries import RUNTIME_SCHEMA
from region_polygon_lab import local_guard, query, sql


def export_release(version, output):
    source = f'{RUNTIME_SCHEMA}.region_boundary_versions'
    quoted = sql(version)
    version_row = json.loads(query(f'SELECT row_to_json(v) FROM {source} v WHERE version_key={quoted} AND ready;'))
    counts = json.loads(query(f"""SELECT json_build_object(
        'boundaries',(SELECT count(*) FROM {RUNTIME_SCHEMA}.region_emd_boundaries WHERE version_id=v.id),
        'scopes',(SELECT count(*) FROM {RUNTIME_SCHEMA}.region_app_scopes WHERE version_id=v.id),
        'bindings',(SELECT count(*) FROM {RUNTIME_SCHEMA}.course_area_region_bindings WHERE version_id=v.id))
        FROM {source} v WHERE version_key={quoted};"""))
    if counts['boundaries'] != 5067 or counts['bindings'] != 59:
        raise ValueError('Only the completely audited national release may be exported')
    get_id = f'(SELECT id FROM region_boundary_versions WHERE version_key={quoted})'
    statements = ["-- #742: reviewed release; run after both Flyway migrations, in the application's schema.",
                  "BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='120s';",
                  'INSERT INTO region_boundary_versions(version_key,source_date,reference_sha256,projection_version,metadata) '
                  f'VALUES ({quoted},{sql(version_row["source_date"])},{sql(version_row["reference_sha256"])},'
                  f'{sql(version_row["projection_version"])},{sql(json.dumps(version_row["metadata"], ensure_ascii=False))}::jsonb);']
    statements.append(query(f"""SELECT format(
        'INSERT INTO region_app_scopes(version_id,scope_code,name,level) VALUES (%s,%L,%L,%L);',
        {sql(get_id)},s.scope_code,s.name,s.level) FROM {RUNTIME_SCHEMA}.region_app_scopes s
        JOIN {source} v ON v.id=s.version_id WHERE v.version_key={quoted} ORDER BY s.scope_code;"""))
    statements.append(query(f"""SELECT format(
        'INSERT INTO region_emd_boundaries(version_id,legal_code,official_name,ancestry,scope_code,geom) '
        'VALUES (%s,%L,%L,%L::jsonb,%L,ST_GeomFromEWKB(decode(%L,''hex'')));',
        {sql(get_id)},b.legal_code,b.official_name,b.ancestry,b.scope_code,encode(ST_AsEWKB(b.geom),'hex'))
        FROM {RUNTIME_SCHEMA}.region_emd_boundaries b JOIN {source} v ON v.id=b.version_id
        WHERE v.version_key={quoted} ORDER BY b.legal_code;"""))
    statements.append(query(f"""SELECT format(
        'INSERT INTO course_area_region_bindings(version_id,course_area_id,legal_codes,mapping_note,area_signature) '
        'VALUES (%s,%s,%L::text[],%L,%L::jsonb);',
        {sql(get_id)},b.course_area_id,b.legal_codes,b.mapping_note,b.area_signature)
        FROM {RUNTIME_SCHEMA}.course_area_region_bindings b JOIN {source} v ON v.id=b.version_id
        WHERE v.version_key={quoted} ORDER BY b.course_area_id;"""))
    statements.append(f"""DO $$ BEGIN
        IF (SELECT count(*) FROM region_emd_boundaries WHERE version_id={get_id}) <> {counts['boundaries']}
        OR (SELECT count(*) FROM course_area_region_bindings WHERE version_id={get_id}) <> {counts['bindings']}
        THEN RAISE EXCEPTION 'Incomplete region release'; END IF;
        END $$;
        UPDATE region_boundary_versions SET ready=true,updated_at=now() WHERE version_key={quoted};
        COMMIT;""")
    output.write_text('\n'.join(statements) + '\n')
    manifest = {'version_key': version, **counts, 'release_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
                'bytes': output.stat().st_size, 'reference_sha256': version_row['reference_sha256'],
                'source_date': version_row['source_date'], 'production_applied': False}
    output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--load-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    local_guard()
    print(json.dumps(export_release(json.loads(args.load_manifest.read_text())['version_key'], args.output)))


if __name__ == '__main__':
    main()
