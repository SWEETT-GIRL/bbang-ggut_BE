#!/usr/bin/env python3
"""Local-only legal EMD polygon import and point matching, standard library only.

Writes only region_polygon_lab_742 in the named local Docker container.
Never reads application-local.properties or connects through a tunnel.
Official SHP data stays outside Git. Requires the container's shp2pgsql.
"""
import argparse
import csv
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = 'region_polygon_lab_742'
PILOT_SCOPES = {'경기도 수원시': '수원시', '경기도 성남시': '성남시',
                '경기도 고양시': '고양시', '세종특별자치시': '세종'}
PSQL = ['docker', 'exec', '-i', 'postgres-lab', 'psql', '-X', '-q', '-A', '-t',
        '-v', 'ON_ERROR_STOP=1', '-h', '/var/run/postgresql', '-U', 'sweetgirl', '-d', 'soboro']


def sql(value):
    return "'" + str(value).replace("'", "''") + "'"


def query(statement):
    result = subprocess.run(PSQL, input="SET statement_timeout='60s';\n" + statement,
                            text=True, capture_output=True, check=True, timeout=90)
    return result.stdout.strip()


def local_guard():
    # Explicit Unix socket inside the lab container, independent of host env/DB URLs.
    identity = json.loads(query("SELECT json_build_object('db',current_database(),"
                                "'socket',inet_server_addr() IS NULL,'postgis',postgis_version());"))
    if identity['db'] != 'soboro' or not identity['socket']:
        raise RuntimeError('Expected local postgres-lab Unix socket')


def init():
    query(f"""
    CREATE SCHEMA IF NOT EXISTS {SCHEMA};
    CREATE TABLE IF NOT EXISTS {SCHEMA}.source (
      id text PRIMARY KEY, metadata jsonb NOT NULL, created_at timestamptz DEFAULT now());
    CREATE TABLE IF NOT EXISTS {SCHEMA}.boundary (
      id bigserial PRIMARY KEY, source_id text NOT NULL REFERENCES {SCHEMA}.source(id),
      code text NOT NULL, name text, geom geometry(MultiPolygon,4326),
      usable boolean NOT NULL, invalid_reason text);
    CREATE INDEX IF NOT EXISTS boundary_geom_idx ON {SCHEMA}.boundary USING gist(geom);
    CREATE INDEX IF NOT EXISTS boundary_source_code_idx ON {SCHEMA}.boundary(source_id,code);
    """)


def reference(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    required = {'region_cd', 'locatadd_nm', 'locathigh_cd'}
    if not rows or not required.issubset(rows[0]):
        raise ValueError('Expected official code CSV with region_cd/locatadd_nm/locathigh_cd')
    by_code = {r['region_cd']: r for r in rows}
    if len(by_code) != len(rows):
        raise ValueError('Duplicate reference codes')
    for code in by_code:
        if not re.fullmatch(r'\d{10}', code):
            raise ValueError('Invalid reference code')
    return by_code


def ancestry(ref, code):
    chain, seen = [], set()
    while code != '0000000000':
        if code in seen or code not in ref:
            raise ValueError(f'Broken/cyclic reference hierarchy: {code}')
        seen.add(code)
        chain.append(ref[code])
        code = ref[code]['locathigh_cd']
    return chain


def reference_check(path):
    ref = reference(path)
    names = ['경기도 성남시 분당구 정자동', '세종특별자치시 나성동',
             '서울특별시 성동구 성수동2가']
    result = []
    for name in names:
        matching = [r for r in ref.values() if r['locatadd_nm'] == name]
        if len(matching) != 1:
            raise ValueError(f'Expected one reference entry: {name}')
        chain = ancestry(ref, matching[0]['region_cd'])
        result.append({'name': name, 'ancestors': [r['locatadd_nm'] for r in chain]})
    mapping = scope_mapping(ref)
    by_code = {row['code']:row['scope'] for row in mapping}
    for name,scope in [('경기도 성남시 분당구 정자동','성남시'),('세종특별자치시 나성동','세종')]:
        code = next(r['region_cd'] for r in ref.values() if r['locatadd_nm']==name)
        if by_code.get(code)!=scope:
            raise ValueError(f'Incorrect app scope: {name}')
    result.append({'pilot_scope_emd_counts': {scope:sum(r['scope']==scope for r in mapping)
                                             for scope in PILOT_SCOPES.values()}})
    return result


def scope_mapping(ref):
    # The reference's parent for e.g. Bundang-gu is Gyeonggi, not Seongnam.
    # Preserve that official relation; separately map verified full-name paths to app cities.
    for name in PILOT_SCOPES:
        if sum(r['locatadd_nm']==name for r in ref.values()) != 1:
            raise ValueError(f'Missing or ambiguous app scope reference: {name}')
    mapping=[]
    for code,row in ref.items():
        if not code.endswith('00') or code[5:8]=='000': continue
        ancestry(ref,code)  # Validate the actual official chain, without inventing city ancestors.
        scopes=[scope for name,scope in PILOT_SCOPES.items() if row['locatadd_nm'].startswith(name+' ')]
        if len(scopes)>1: raise ValueError(f'Ambiguous app scope: {code}')
        if scopes: mapping.append({'code':code,'scope':scopes[0],'name':row['locatadd_nm']})
    if any(not any(r['scope']==scope for r in mapping) for scope in PILOT_SCOPES.values()):
        raise ValueError('A pilot scope has no mapped EMDs')
    return mapping


def import_shp(args):
    date.fromisoformat(args.source_date)
    path = args.shp.resolve()
    if path.suffix.lower() != '.shp':
        raise ValueError('Select the EMD .shp, not the ZIP or SIG/LIG layer')
    if not path.with_suffix('.prj').exists():
        raise ValueError('Missing .prj; verify source CRS before importing')
    components = sorted(p for p in path.parent.iterdir() if p.stem == path.stem)
    if not {'.shp', '.shx', '.dbf', '.prj'}.issubset({p.suffix.lower() for p in components}):
        raise ValueError('SHP/SHX/DBF/PRJ components required')
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in components}
    source_id = uuid.uuid4().hex
    table = 'raw_' + source_id
    folder = '/tmp/polygon_742_' + source_id
    subprocess.run(['docker', 'exec', 'postgres-lab', 'mkdir', '-p', folder], check=True)
    for component in components:
        subprocess.run(['docker', 'cp', str(component), f'postgres-lab:{folder}/{component.name}'], check=True)
    metadata = {'source_url': args.source_url, 'source_date': args.source_date,
                'source_kind': 'SYNTHETIC_TEST' if args.synthetic else 'EXTERNAL_BOUNDARY',
                'source_srid': args.source_srid, 'encoding': args.encoding, 'hashes': hashes,
                'layer': 'LEGAL_EMD', 'column_mapping': {'code': 'A1', 'name': 'A2'},
                'prj': path.with_suffix('.prj').read_text(errors='replace')}
    init()
    # Converter SQL is generated by the installed PostGIS tool, with a fixed schema/table.
    with tempfile.TemporaryFile() as converted:
        subprocess.run(['docker', 'exec', 'postgres-lab', 'shp2pgsql', '-D', '-I',
                        '-s', f'{args.source_srid}:4326', '-W', args.encoding,
                        '-g', 'geom', f'{folder}/{path.name}', f'{SCHEMA}.{table}'],
                       stdout=converted, check=True)
        converted.seek(0)
        subprocess.run(PSQL, stdin=converted, stdout=subprocess.DEVNULL, check=True, timeout=180)
    # Keep invalid geometries for inspection; do not auto-repair or snap points.
    bad = int(query(f"SELECT count(*) FROM {SCHEMA}.{table} WHERE a1 IS NULL OR a1::text !~ '^[0-9]{{8}}$';"))
    total = int(query(f"SELECT count(*) FROM {SCHEMA}.{table};"))
    if bad == total:
        raise ValueError('Not a legal EMD layer with eight-digit A1 codes; raw import retained for inspection')
    metadata.update(raw_table=f'{SCHEMA}.{table}', raw_rows=total, invalid_code_rows=bad)
    query(f"""BEGIN;
      INSERT INTO {SCHEMA}.source VALUES ({sql(source_id)},{sql(json.dumps(metadata,ensure_ascii=False))}::jsonb,now());
      INSERT INTO {SCHEMA}.boundary(source_id,code,name,geom,usable,invalid_reason)
      SELECT {sql(source_id)},CASE WHEN a1::text ~ '^[0-9]{{8}}$' THEN a1::text||'00' ELSE coalesce(a1::text,'') END,a2,ST_Multi(geom),
        coalesce(a1::text ~ '^[0-9]{{8}}$',false) AND geom IS NOT NULL AND NOT ST_IsEmpty(geom) AND ST_IsValid(geom)
          AND ST_CoveredBy(ST_Envelope(geom),ST_MakeEnvelope(120,30,140,40,4326)),
        CASE WHEN a1 IS NULL OR a1::text !~ '^[0-9]{{8}}$' THEN 'INVALID_CODE'
          WHEN geom IS NULL THEN 'NULL' WHEN ST_IsEmpty(geom) THEN 'EMPTY'
          WHEN NOT ST_IsValid(geom) THEN ST_IsValidReason(geom)
          WHEN NOT ST_CoveredBy(ST_Envelope(geom),ST_MakeEnvelope(120,30,140,40,4326)) THEN 'OUT_OF_SANITY_EXTENT'
          ELSE NULL END
      FROM {SCHEMA}.{table};
      ANALYZE {SCHEMA}.boundary; COMMIT;""")
    print(json.dumps({'source_id': source_id, 'metadata': metadata}, ensure_ascii=False, indent=2))


def compare(args):
    ref = reference(args.reference)
    mapping = scope_mapping(ref)
    source_id = sql(args.source_id)
    exists = int(query(f'SELECT count(*) FROM {SCHEMA}.source WHERE id={source_id};'))
    if exists != 1:
        raise ValueError('Unknown source ID')
    result = query(f"""
    BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
    WITH scope AS (
      SELECT * FROM jsonb_to_recordset({sql(json.dumps(mapping,ensure_ascii=False))}::jsonb)
      AS t(code text,scope text,name text)
    ), candidate AS MATERIALIZED (
      SELECT s.id,s.region_1st,s.region_2nd,s.region_3rd,s.location,
        CASE WHEN s.region_1st='세종' THEN '세종' ELSE s.region_2nd END AS stored_scope
      FROM public.store s JOIN public.store_profile p ON p.store_id=s.id
      WHERE s.status='ACTIVE' AND p.bakery_score>=.5 AND
        ((s.region_1st='경기' AND s.region_2nd IN ('수원시','성남시','고양시')) OR s.region_1st='세종')
    ), matched AS (
      SELECT c.*,m.codes,m.scopes,coalesce(cardinality(m.codes),0) match_count
      FROM candidate c LEFT JOIN LATERAL (
        SELECT array_agg(DISTINCT b.code) codes,array_agg(DISTINCT scope.scope) scopes
        FROM {SCHEMA}.boundary b JOIN scope ON scope.code=b.code
        WHERE b.source_id={source_id} AND b.usable AND ST_Covers(b.geom,c.location::geometry)
      ) m ON true
    ), stats AS (
      SELECT stored_scope,count(*) candidates,
        count(*) FILTER(WHERE match_count=1) unique_match,
        count(*) FILTER(WHERE match_count=0) no_match,
        count(*) FILTER(WHERE match_count>1) ambiguous,
        count(*) FILTER(WHERE match_count=1 AND scopes[1]<>stored_scope) different_scope
      FROM matched GROUP BY 1 ORDER BY 1
    ) SELECT jsonb_build_object('source_id',{source_id},'measured_at',now(),
      'source_metadata',(SELECT metadata FROM {SCHEMA}.source WHERE id={source_id}),
      'local_store_count',(SELECT count(*) FROM public.store),
      'local_store_latest_update',(SELECT max(updated_at) FROM public.store),
      'boundary_rows',(SELECT count(*) FROM {SCHEMA}.boundary WHERE source_id={source_id}),
      'unusable_boundary_rows',(SELECT count(*) FROM {SCHEMA}.boundary WHERE source_id={source_id} AND NOT usable),
      'pilot_reference_codes',(SELECT count(*) FROM scope),
      'pilot_codes_without_usable_geometry',(SELECT count(*) FROM scope WHERE NOT EXISTS
        (SELECT 1 FROM {SCHEMA}.boundary b WHERE b.source_id={source_id} AND b.code=scope.code AND b.usable)),
      'rows',(SELECT jsonb_agg(stats) FROM stats));
    COMMIT;
    """)
    report = json.loads(result)
    report['reference_sha256'] = hashlib.sha256(args.reference.read_bytes()).hexdigest()
    report['limitations'] = ['Local lab snapshot, not current production.',
      'Cohort uses stored pilot-region values; this is not an audit of all mislabelled stores.',
      'No match can mean stale geometry/code, point error or outside scope; not automatically a store error.',
      'No store fields updated. Duplicate/edge matches remain ambiguous.']
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))


def repair_copy(args):
    """Create a separately versioned repair candidate; never overwrite the official import."""
    parent=sql(args.source_id)
    if int(query(f'SELECT count(*) FROM {SCHEMA}.source WHERE id={parent};')) != 1:
        raise ValueError('Unknown source ID')
    new_id=uuid.uuid4().hex
    output=query(f"""BEGIN;
    CREATE TEMP TABLE repair_preview ON COMMIT DROP AS
      SELECT *, CASE WHEN invalid_reason LIKE '%Self-intersection%' AND code ~ '^[0-9]{{10}}$'
        THEN ST_Multi(ST_CollectionExtract(ST_MakeValid(geom),3)) ELSE geom END fixed,
        invalid_reason LIKE '%Self-intersection%' AND code ~ '^[0-9]{{10}}$' changed
      FROM {SCHEMA}.boundary WHERE source_id={parent};
    INSERT INTO {SCHEMA}.source(id,metadata)
      SELECT {sql(new_id)}, metadata || jsonb_build_object(
        'source_kind','DERIVED_REPAIR_CANDIDATE','parent_source_id',{parent},
        'repair_method','ST_MakeValid default linework; polygon extraction; validity recheck',
        'postgis_full_version',postgis_full_version(),
        'repaired_rows',(SELECT count(*) FROM repair_preview WHERE changed))
      FROM {SCHEMA}.source WHERE id={parent};
    INSERT INTO {SCHEMA}.boundary(source_id,code,name,geom,usable,invalid_reason)
      SELECT {sql(new_id)},code,name,fixed,
        CASE WHEN changed THEN ST_IsValid(fixed) AND NOT ST_IsEmpty(fixed)
          AND ST_CoveredBy(ST_Envelope(fixed),ST_MakeEnvelope(120,30,140,40,4326)) ELSE usable END,
        CASE WHEN changed THEN CASE WHEN ST_IsEmpty(fixed) THEN 'REPAIR_EMPTY'
          WHEN NOT ST_IsValid(fixed) THEN ST_IsValidReason(fixed)
          WHEN NOT ST_CoveredBy(ST_Envelope(fixed),ST_MakeEnvelope(120,30,140,40,4326)) THEN 'OUT_OF_SANITY_EXTENT'
          ELSE NULL END ELSE invalid_reason END FROM repair_preview;
    ANALYZE {SCHEMA}.boundary;
    SELECT jsonb_build_object('source_id',{sql(new_id)},'parent_source_id',{parent},
      'changes',(SELECT jsonb_agg(r) FROM (
        SELECT code,name,invalid_reason,
          ST_IsValid(fixed) AND NOT ST_IsEmpty(fixed) valid_after,
          ST_NumGeometries(geom) parts_before,ST_NumGeometries(fixed) parts_after,
          ST_Area(ST_Transform(fixed,5186))-ST_Area(ST_Transform(geom,5186)) area_delta_m2,
          ST_HausdorffDistance(ST_Transform(ST_Boundary(geom),5186),ST_Transform(ST_Boundary(fixed),5186)) boundary_hausdorff_m
        FROM repair_preview WHERE changed ORDER BY code) r));
    COMMIT;""")
    report=json.loads(output)
    if not report['changes']:
        raise ValueError('No repair candidates; check the source ID')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'source_id':new_id,'changed_rows':len(report['changes']),
                      'diagnostics':str(args.output)},ensure_ascii=False))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    check=sub.add_parser('check')
    check.add_argument('--reference',type=Path,required=True)
    imp=sub.add_parser('import-shp')
    imp.add_argument('--shp',type=Path,required=True)
    imp.add_argument('--source-url',required=True)
    imp.add_argument('--source-date',required=True)
    imp.add_argument('--source-srid',type=int,required=True,help='Verify .prj; do not guess')
    imp.add_argument('--encoding',required=True,help='Verify .cpg/source metadata')
    imp.add_argument('--synthetic',action='store_true',help='Mark generated test fixtures explicitly')
    cmp=sub.add_parser('compare')
    cmp.add_argument('--source-id',required=True)
    cmp.add_argument('--reference',type=Path,required=True)
    cmp.add_argument('--output',type=Path,required=True)
    repair=sub.add_parser('repair-copy',help='Create an explicit local repair candidate, preserve source')
    repair.add_argument('--source-id',required=True)
    repair.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    local_guard()
    if args.command=='check':
        print(query((ROOT/'scripts/lab/region-polygon-check.sql').read_text()))
        print(json.dumps(reference_check(args.reference),ensure_ascii=False,indent=2))
    elif args.command=='import-shp': import_shp(args)
    elif args.command=='compare': compare(args)
    else: repair_copy(args)


if __name__=='__main__':
    main()
