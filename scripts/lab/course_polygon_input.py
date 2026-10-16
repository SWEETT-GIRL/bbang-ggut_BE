#!/usr/bin/env python3
"""Export local polygon membership for the Java comparison; no database writes."""
import argparse
import hashlib
import json

from region_polygon_lab import local_guard, query, reference, scope_mapping, sql


def main():
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--source-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    local_guard()
    mapping = scope_mapping(reference(args.reference))
    result = json.loads(query(f"""
    BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
    WITH scope AS (
      SELECT * FROM jsonb_to_recordset({sql(json.dumps(mapping, ensure_ascii=False))}::jsonb)
      AS t(code text, scope text, name text)
    ), matched AS (
      SELECT s.id, min(scope.scope) scope, count(DISTINCT b.code) matches
      FROM public.store s JOIN public.store_profile p ON p.store_id=s.id AND p.bakery_score>=.5
      JOIN region_polygon_lab_742.boundary b ON b.source_id={sql(args.source_id)}
        AND b.usable AND ST_Covers(b.geom,s.location::geometry)
      JOIN scope ON scope.code=b.code
      WHERE s.status='ACTIVE' GROUP BY s.id
    ) SELECT jsonb_build_object(
      'sourceId',{sql(args.source_id)},
      'sourceMetadata',(SELECT metadata FROM region_polygon_lab_742.source WHERE id={sql(args.source_id)}),
      'generatedAt',now(), 'databaseSystemId',(SELECT system_identifier::text FROM pg_control_system()),
      'storeCount',(SELECT count(*) FROM public.store),
      'storeLatestUpdate',(SELECT max(updated_at)::text FROM public.store),
      'profileLatestUpdate',(SELECT max(computed_at)::text FROM public.store_profile),
      'ambiguous',(SELECT count(*) FROM matched WHERE matches<>1),
      'scopes',(SELECT jsonb_object_agg(scope,ids) FROM (
        SELECT scope,jsonb_agg(id ORDER BY id) ids FROM matched WHERE matches=1 GROUP BY scope) x),
      'areas',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM course_area a
        WHERE a.is_active AND a.sido='경기' AND a.passport_spot IN ('수원시','성남시','고양시'))
    ); COMMIT;
    """))
    if not result['sourceMetadata'] or result['ambiguous']:
        raise ValueError('Missing source or ambiguous pilot membership; review before comparison')
    result['referenceSha256'] = hashlib.sha256(args.reference.read_bytes()).hexdigest()
    result['note'] = 'City membership uses coordinates across all eligible stores, not existing region strings. Sejong excluded from route comparison: current chips select individual dong/eup.'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'output': str(args.output), 'scopes': {k:len(v) for k,v in result['scopes'].items()}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
