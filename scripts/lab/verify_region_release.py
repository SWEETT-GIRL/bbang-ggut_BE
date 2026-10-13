#!/usr/bin/env python3
"""내보낸 SQL을 로컬 임시 스키마에 재적재·대조한 뒤 DDL까지 롤백한다."""
import argparse
import hashlib
import json
import uuid
from pathlib import Path

from load_region_boundaries import RUNTIME_SCHEMA
from region_polygon_lab import ROOT, local_guard, query


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    local_guard()
    manifest = json.loads(args.release.with_suffix('.manifest.json').read_text())
    if hashlib.sha256(args.release.read_bytes()).hexdigest() != manifest['release_sha256']:
        raise ValueError('Release checksum differs')
    release = args.release.read_text()
    if release.count('BEGIN; SET LOCAL lock_timeout=') != 1 or not release.rstrip().endswith('COMMIT;'):
        raise ValueError('Unexpected release transaction shape')
    release = release.replace('BEGIN; SET LOCAL lock_timeout=', 'SET LOCAL lock_timeout=', 1).rstrip()[:-len('COMMIT;')]
    schema = 'region_release_test_' + uuid.uuid4().hex
    migrations = '\n'.join((ROOT / 'src/main/resources/db/migration' / name).read_text() for name in (
        'V20261013190000__create_store_region_boundaries.sql', 'V20261013200000__bind_course_areas_to_regions.sql'))
    statement = f'BEGIN; CREATE SCHEMA {schema}; SET LOCAL search_path TO {schema},public;\n{migrations}\n{release}\n'
    statement += f"""SELECT json_build_object(
        'boundaries',(SELECT count(*) FROM region_emd_boundaries),
        'bindings',(SELECT count(*) FROM course_area_region_bindings),
        'ready',(SELECT bool_and(ready) FROM region_boundary_versions),
        'geometry_differences',(SELECT count(*) FROM region_emd_boundaries imported
            FULL JOIN {RUNTIME_SCHEMA}.region_emd_boundaries source USING(legal_code)
            WHERE ST_AsEWKB(imported.geom) IS DISTINCT FROM ST_AsEWKB(source.geom)
                OR imported.ancestry IS DISTINCT FROM source.ancestry),
        'binding_differences',(SELECT count(*) FROM course_area_region_bindings imported
            FULL JOIN {RUNTIME_SCHEMA}.course_area_region_bindings source USING(course_area_id)
            WHERE imported.legal_codes IS DISTINCT FROM source.legal_codes
                OR imported.area_signature IS DISTINCT FROM source.area_signature));
        ROLLBACK;"""
    report = json.loads(query(statement))
    if report != {'boundaries': 5067, 'bindings': 59, 'ready': True, 'geometry_differences': 0, 'binding_differences': 0}:
        raise ValueError(f'Release verification failed: {report}')
    report.update({'release_sha256': manifest['release_sha256'], 'production_applied': False, 'rolled_back': True})
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
