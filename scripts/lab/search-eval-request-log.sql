-- 랩 서버(local,lab)에 검색한 뒤, 랩 DB request_log 를 검색어 유형(지역/가게명/혼합/자동완성)별로 집계한다.
-- 실행: docker exec -i postgres-lab psql -U sweetgirl -d soboro < scripts/lab/search-eval-request-log.sql
-- 유형은 query 값과 지역 사전으로만 대략 나눈다(정확한 유형은 서버 로그 [검색추적] type= 참고).
-- 최근 N시간만 보려면 아래 since 를 조정. 운영 request_log 에도 그대로 쓸 수 있다(헬퍼 함수 2개 생성 권한 필요).

\set since '''6 hours'''

-- URL 디코딩 헬퍼 (한글 %XX 시퀀스 → UTF-8, '+' → 공백). 랩 전용, 있으면 덮어쓴다.
-- decode(...,'escape')는 8진수 표기만 받으므로, %XX 를 hex 로 모아 bytea 를 직접 조립한다.
CREATE OR REPLACE FUNCTION lab_url_decode(input text) RETURNS text
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    src   text := replace(coalesce(input, ''), '+', ' ');
    out   bytea := ''::bytea;
    i     int := 1;
    n     int := length(src);
    ch    text;
BEGIN
    WHILE i <= n LOOP
        ch := substr(src, i, 1);
        IF ch = '%' AND i + 2 <= n AND substr(src, i + 1, 2) ~ '^[0-9A-Fa-f]{2}$' THEN
            out := out || decode(substr(src, i + 1, 2), 'hex');
            i := i + 3;
        ELSE
            out := out || convert_to(ch, 'UTF8');
            i := i + 1;
        END IF;
    END LOOP;
    RETURN convert_from(out, 'UTF8');
END $$;

CREATE OR REPLACE FUNCTION lab_normalize(input text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT regexp_replace(lower(input), '[^0-9a-z가-힣]', '', 'g')
$$;

-- 1) 유형별 요약
WITH logs AS (
    SELECT
        endpoint, response_time_ms, response_status, created_at,
        lab_url_decode(substring(query_params FROM 'query=([^&]*)')) AS q
    FROM request_log
    WHERE endpoint IN ('/stores/search', '/stores/autocomplete')
      AND created_at >= now() - :since::interval
),
typed AS (
    SELECT l.*,
        CASE
            WHEN endpoint = '/stores/autocomplete' THEN 'AUTOCOMPLETE'
            WHEN EXISTS (SELECT 1 FROM search_region_alias a WHERE a.normalized_alias = lab_normalize(q)) THEN 'REGION_ONLY'
            WHEN EXISTS (SELECT 1 FROM search_region_alias a
                          WHERE length(a.normalized_alias) >= 2 AND position(a.normalized_alias IN lab_normalize(q)) > 0)
                THEN 'REGION_AND_NAME'
            ELSE 'NAME_ONLY'
        END AS type
    FROM logs l
)
SELECT type,
       count(*)                                                                          AS calls,
       count(DISTINCT q)                                                                 AS distinct_queries,
       round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY response_time_ms)::numeric, 1) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY response_time_ms)::numeric, 1) AS p95_ms,
       max(response_time_ms)                                                             AS max_ms,
       count(*) FILTER (WHERE response_status >= 500)                                    AS err5xx
FROM typed
GROUP BY type
ORDER BY type;

-- 2) 검색어별 상세 (최근순)
WITH logs AS (
    SELECT response_time_ms, response_status, created_at,
        lab_url_decode(substring(query_params FROM 'query=([^&]*)')) AS q
    FROM request_log
    WHERE endpoint = '/stores/search' AND created_at >= now() - :since::interval
)
SELECT q AS query,
       count(*)                                                     AS calls,
       round(avg(response_time_ms)::numeric, 1)                     AS avg_ms,
       max(response_time_ms)                                        AS max_ms,
       count(*) FILTER (WHERE response_status >= 500)               AS err5xx,
       max(created_at)                                              AS last_at
FROM logs
GROUP BY q
ORDER BY last_at DESC
LIMIT 40;
