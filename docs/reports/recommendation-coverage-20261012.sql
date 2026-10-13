-- Read-only production catalog coverage, 2026-10-12.
-- Counts structural candidate eligibility; excludes member/course/history/date filters and ranking.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object('section','metadata','measured_at_kst',now() AT TIME ZONE 'Asia/Seoul','read_only',current_setting('transaction_read_only'),'store_profile_latest',(SELECT max(computed_at) FROM store_profile),'active_stores',(SELECT count(*) FROM store WHERE status='ACTIVE'));
SELECT jsonb_build_object('section','areas','rows',jsonb_agg(a)) FROM (SELECT id,sido,name,passport_spot,center_lat,center_lng,radius_m,max_hop_m,region_2nd,region_3rd,display_order FROM course_area WHERE is_active ORDER BY sido,passport_spot,display_order,id) a;
WITH cells AS (
 SELECT sido,passport_spot,count(*) neighborhoods,min(region_2nd) region_2nd,min(region_3rd) region_3rd FROM course_area WHERE is_active GROUP BY 1,2
), catalog AS MATERIALIZED (
 SELECT s.id,s.region_1st,s.region_2nd,s.region_3rd,s.location,p.store_id IS NOT NULL has_profile,coalesce(p.bakery_score>=0.5,false) eligible
 FROM store s LEFT JOIN store_profile p ON p.store_id=s.id WHERE s.status='ACTIVE'
), membership AS (
 SELECT c.*,s.id,s.location,s.has_profile,s.eligible,
 EXISTS (SELECT 1 FROM course_area a WHERE a.is_active AND a.sido=c.sido AND a.passport_spot=c.passport_spot AND (
   ST_DWithin(s.location,ST_SetSRID(ST_MakePoint(a.center_lng,a.center_lat),4326)::geography,a.radius_m)
   OR (a.region_2nd IS NOT NULL AND s.region_1st=a.sido AND s.region_2nd=a.region_2nd AND (a.region_3rd IS NULL OR s.region_3rd=a.region_3rd))
 )) covered
 FROM cells c LEFT JOIN catalog s ON s.region_1st=c.sido AND (
   (c.sido='서울' AND s.region_2nd=c.region_2nd AND s.region_3rd=c.region_3rd)
   OR (c.sido<>'서울' AND s.region_2nd=c.passport_spot)
 )
), stats AS (
 SELECT sido,passport_spot,neighborhoods,
 count(id) active_stores,count(id) FILTER(WHERE NOT has_profile) without_profile,
 count(id) FILTER(WHERE eligible) eligible_stores,
 count(id) FILTER(WHERE eligible AND covered) covered_eligible,
 count(id) FILTER(WHERE eligible AND NOT covered) outside_eligible,
 count(id) FILTER(WHERE covered AND NOT has_profile) in_scope_without_profile,
 round(100.0*count(id) FILTER(WHERE eligible AND covered)/nullif(count(id) FILTER(WHERE eligible),0),1) coverage_pct
 FROM membership GROUP BY 1,2,3 ORDER BY coverage_pct NULLS FIRST,sido,passport_spot
)
SELECT jsonb_build_object('section','cell_coverage','rows',jsonb_agg(stats)) FROM stats;
WITH stats AS (
 SELECT a.id,a.sido,a.passport_spot,a.name,a.radius_m,a.max_hop_m,
 count(s.id) active_candidates,
 count(s.id) FILTER(WHERE p.store_id IS NULL) without_profile,
 count(s.id) FILTER(WHERE p.bakery_score>=0.5) eligible_candidates,
 count(s.id) FILTER(WHERE p.bakery_score>=0.5 AND f.store_id IS NOT NULL AND f.taste_sweet IS NOT NULL) with_labels
 FROM course_area a
 LEFT JOIN store s ON s.status='ACTIVE' AND (
 ST_DWithin(s.location,ST_SetSRID(ST_MakePoint(a.center_lng,a.center_lat),4326)::geography,a.radius_m)
 OR (a.region_2nd IS NOT NULL AND s.region_1st=a.sido AND s.region_2nd=a.region_2nd AND (a.region_3rd IS NULL OR s.region_3rd=a.region_3rd)))
 LEFT JOIN store_profile p ON p.store_id=s.id
 LEFT JOIN store_recommendation_feature f ON f.store_id=s.id
 WHERE a.is_active GROUP BY a.id ORDER BY eligible_candidates,a.id
)
SELECT jsonb_build_object('section','neighborhood_candidates','rows',jsonb_agg(stats)) FROM stats;
WITH eligible AS MATERIALIZED (SELECT s.id,s.location,s.region_1st,s.region_2nd,s.region_3rd FROM store s JOIN store_profile p ON p.store_id=s.id WHERE s.status='ACTIVE' AND p.bakery_score>=0.5), counts AS (
 SELECT count(*) eligible_nationwide,count(*) FILTER(WHERE EXISTS(SELECT 1 FROM course_area a WHERE a.is_active AND (ST_DWithin(s.location,ST_SetSRID(ST_MakePoint(a.center_lng,a.center_lat),4326)::geography,a.radius_m) OR (a.region_2nd IS NOT NULL AND s.region_1st=a.sido AND s.region_2nd=a.region_2nd AND (a.region_3rd IS NULL OR s.region_3rd=a.region_3rd))))) covered_any_area FROM eligible s
)
SELECT jsonb_build_object('section','national','rows',jsonb_agg(counts)) FROM counts;
WITH r AS (SELECT r.*,jsonb_array_length(coalesce(r.result->'stores','[]'::jsonb)) store_count FROM recommendation r WHERE r.mode='AREA' AND r.created_at>=(now() AT TIME ZONE 'Asia/Seoul')-interval '30 days'), stats AS (
 SELECT a.sido,a.passport_spot,count(*) recommendations,count(*) FILTER(WHERE r.store_count<4) fewer_than_four,count(*) FILTER(WHERE coalesce((r.result->>'lastBatch')::boolean,false)) last_batch,count(*) FILTER(WHERE r.fallback) taste_fallback
 FROM r JOIN course_area a ON a.id=r.course_area_id GROUP BY 1,2 ORDER BY recommendations DESC
)
SELECT jsonb_build_object('section','usage_30d','rows',coalesce(jsonb_agg(stats),'[]'::jsonb)) FROM stats;
WITH stats AS (
 SELECT a.sido,a.passport_spot,f.action,f.reason,count(*) events FROM recommendation_feedback f JOIN recommendation r ON r.id=f.recommendation_id JOIN course_area a ON a.id=r.course_area_id
 WHERE f.created_at>=(now() AT TIME ZONE 'Asia/Seoul')-interval '30 days' AND f.action IN ('RETRY','DISLIKE') GROUP BY 1,2,3,4 ORDER BY events DESC
)
SELECT jsonb_build_object('section','feedback_30d','rows',coalesce(jsonb_agg(stats),'[]'::jsonb)) FROM stats;
COMMIT;
