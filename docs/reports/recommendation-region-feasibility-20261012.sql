-- Region membership feasibility. Aggregate-only, no member data, no writes.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object('section','metadata','measured_at_kst',now() AT TIME ZONE 'Asia/Seoul','read_only',current_setting('transaction_read_only'));
WITH eligible AS (
 SELECT s.* FROM store s JOIN store_profile p ON p.store_id=s.id
 WHERE s.status='ACTIVE' AND p.bakery_score>=0.5
)
SELECT jsonb_build_object('section','eligible_quality','total',count(*),
 'missing_region1',count(*) FILTER(WHERE nullif(trim(region_1st),'') IS NULL),
 'missing_region2',count(*) FILTER(WHERE nullif(trim(region_2nd),'') IS NULL),
 'missing_region3',count(*) FILTER(WHERE nullif(trim(region_3rd),'') IS NULL),
 'missing_or_empty_point',count(*) FILTER(WHERE location IS NULL OR ST_IsEmpty(location::geometry))) FROM eligible;
WITH rows AS (
 SELECT s.region_1st,s.region_2nd,count(*) eligible,
 count(DISTINCT s.region_3rd) third_names,
 count(*) FILTER(WHERE nullif(trim(s.region_3rd),'') IS NULL) missing_third,
 count(*) FILTER(WHERE s.location IS NULL OR ST_IsEmpty(s.location::geometry)) missing_point
 FROM store s JOIN store_profile p ON p.store_id=s.id
 WHERE s.status='ACTIVE' AND p.bakery_score>=0.5
 AND s.region_1st='경기' AND s.region_2nd IN ('수원시','성남시','고양시')
 GROUP BY 1,2 ORDER BY 1,2
) SELECT jsonb_build_object('section','pilot','rows',jsonb_agg(rows)) FROM rows;
WITH rows AS (
 SELECT s.region_1st,s.region_2nd,s.region_3rd,count(*) eligible
 FROM store s JOIN store_profile p ON p.store_id=s.id
 WHERE s.status='ACTIVE' AND p.bakery_score>=0.5
 AND ((s.region_1st='서울' AND s.region_2nd='성동구' AND s.region_3rd LIKE '성수%')
 OR (s.region_1st='세종') OR (s.region_1st='경기' AND s.region_2nd='성남시'))
 GROUP BY 1,2,3 ORDER BY 1,2,3
) SELECT jsonb_build_object('section','projection_examples','rows',jsonb_agg(rows)) FROM rows;
COMMIT;
