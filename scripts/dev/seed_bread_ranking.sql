-- dev 전용. 빵집 순위(판)·내 빵 순위·가게 상세 빵 평가·순위 변동(▲▼NEW)을 시뮬레이터에서 보기 위한 시드.
-- 여러 번 돌려도 같은 상태가 된다 — 자기가 넣은 것(가게 kakao_place_id 'devseed-%', 기록 memo '[시드]%')을 먼저 지우고 다시 넣는다.
--
-- 실행 (dev 앱 서버의 postgres 컨테이너에 stdin 으로 흘려 넣는다, me = 내 빵 순위를 채울 회원 id):
--   ssh -i ~/Desktop/sweetgirl-new.pem \
--     -o ProxyCommand="ssh -i ~/Desktop/sweetgirl-new.pem -W %h:%p ubuntu@54.116.12.200" ubuntu@10.3.31.174 \
--     "docker exec -i staging-postgres psql -U sweetgirl -d soboro -v ON_ERROR_STOP=1 -v me=50004" \
--     < scripts/dev/seed_bread_ranking.sql
--
-- 넣는 것
--   가게 8곳(서울 7·천안 1) + 기존 성심당 본점(id 1)
--   표: 소금빵 12명 · 크루아상 11명 · 베이글 10명 · 식빵 10명 · 단팥빵 10명 (탭은 10명 이상만 뜨므로 다섯 종류가 탭·"전체" 격자에 나온다)
--   표마다 그 가게의 빵로그 1건(사진 1·태그 1·평가) — 가게 상세의 "빵 평가"는 5명 이상인 (가게, 종류)만 뜬다
--   me 의 탭 3개(소금빵·크루아상·베이글)와 등수·담기만 한 가게·후보 가게
--   어제 날짜의 빵집 판 스냅샷 — 오늘 판과 어긋나게 넣어 ▲▼NEW 가 보인다
-- 주의
--   사진은 S3 에 실제 파일이 없어 앱에서 빈 이미지로 보인다.
--   me 가 GUEST 면 USER 로 바꾼다 — 내 빵 순위는 로그인 회원만 쓸 수 있다.
--   검증 계정 50005·50009 가 남긴 데이터도 같이 지운다.

\if :{?me}
\else
\set me 50004
\endif

BEGIN;

CREATE TEMP TABLE seed_param AS SELECT :me::bigint AS me;

-- ── 0. 지난 시드·검증 찌꺼기 정리 ────────────────────────────────────────────────
DELETE FROM bread_rank_entry
WHERE member_id BETWEEN 1000 AND 1029
   OR member_id IN (50005, 50009)
   OR member_id = (SELECT me FROM seed_param);
DELETE FROM member_bread_category
WHERE member_id IN (50005, 50009)
   OR (member_id = (SELECT me FROM seed_param) AND category_id IN (1, 31, 39));

CREATE TEMP TABLE seed_old_record AS
SELECT id FROM bread_record WHERE memo LIKE '[시드]%' OR member_id IN (50005, 50009);
DELETE FROM bread_record_tag_chip WHERE tag_id IN (SELECT t.id FROM bread_record_tag t JOIN seed_old_record o ON o.id = t.record_id);
DELETE FROM bread_record_tag WHERE record_id IN (SELECT id FROM seed_old_record);
DELETE FROM bread_record_loved_bread WHERE record_id IN (SELECT id FROM seed_old_record);
DELETE FROM bread_record_photo WHERE record_id IN (SELECT id FROM seed_old_record);
DELETE FROM bread_record WHERE id IN (SELECT id FROM seed_old_record);

DELETE FROM ranking_snapshot WHERE board = 'BREAD' AND category_id IN (1, 2, 14, 31, 39);

UPDATE member SET member_type = 'USER' WHERE id = (SELECT me FROM seed_param) AND member_type = 'GUEST';

-- ── 1. 가게 ──────────────────────────────────────────────────────────────────────
INSERT INTO store (name, address, location, status, source, region_1st, region_2nd, region_3rd, kakao_place_id,
	naver_enrich_attempts, popularity_score, created_at)
SELECT v.name, v.address, ST_SetSRID(ST_MakePoint(v.lng, v.lat), 4326)::geography, 'ACTIVE', 'ADMIN_REGISTERED',
	v.r1, v.r2, v.r3, v.code, 0, 0, NOW()
FROM (VALUES
	('런던베이글뮤지엄 안국점', '서울 종로구 북촌로4길 20',    126.9846, 37.5793, '서울특별시', '종로구', '안국동', 'devseed-01'),
	('태극당 장충점',           '서울 중구 동호로 24길 7',      127.0068, 37.5607, '서울특별시', '중구',   '장충동', 'devseed-02'),
	('김영모과자점 도곡본점',   '서울 강남구 선릉로 118',       127.0442, 37.4911, '서울특별시', '강남구', '도곡동', 'devseed-03'),
	('오월의종 이태원점',       '서울 용산구 이태원로 229',     126.9986, 37.5351, '서울특별시', '용산구', '한남동', 'devseed-04'),
	('나폴레옹과자점 성북본점', '서울 성북구 성북로 7',         127.0057, 37.5926, '서울특별시', '성북구', '성북동', 'devseed-05'),
	('코끼리베이글 망원점',     '서울 마포구 포은로 95',        126.9103, 37.5566, '서울특별시', '마포구', '망원동', 'devseed-06'),
	('밀도 성수점',             '서울 성동구 왕십리로 115',     127.0556, 37.5445, '서울특별시', '성동구', '성수동', 'devseed-07'),
	('뚜쥬루과자점 거북이점',   '충남 천안시 동남구 풍세로 706', 127.1142, 36.7616, '충청남도',   '천안시', '풍세면', 'devseed-08')
) AS v(name, address, lng, lat, r1, r2, r3, code)
WHERE NOT EXISTS (SELECT 1 FROM store s WHERE s.kakao_place_id = v.code);
UPDATE store SET status = 'ACTIVE' WHERE kakao_place_id LIKE 'devseed-%';

CREATE TEMP TABLE seed_store AS
SELECT 'ssd' AS code, 1::bigint AS id
UNION ALL
SELECT replace(kakao_place_id, 'devseed-', 'd'), id FROM store WHERE kakao_place_id LIKE 'devseed-%';

-- ── 2. 표 하나 = 그 가게의 빵로그 1건(사진·태그·평가) + 순위 행 ────────────────────
CREATE FUNCTION pg_temp.seed_vote(
	p_member BIGINT, p_category BIGINT, p_store BIGINT, p_rank INTEGER, p_add_entry BOOLEAN,
	p_again BOOLEAN, p_textures TEXT[], p_taste TEXT, p_days_ago INTEGER)
RETURNS VOID LANGUAGE plpgsql AS $fn$
DECLARE
	v_store   store%ROWTYPE;
	v_kind    TEXT;
	v_record  BIGINT;
	v_photo   BIGINT;
	v_tag     BIGINT;
	v_at      TIMESTAMP;
	v_texture TEXT;
BEGIN
	SELECT * INTO v_store FROM store WHERE id = p_store;
	SELECT menu_kind INTO v_kind FROM bread_category WHERE id = p_category;
	v_at := NOW() - make_interval(days => p_days_ago, hours => (p_member % 12)::int);

	INSERT INTO bread_record (member_id, store_id, store_name, region_key, memo, eaten_at, is_public, created_at)
	VALUES (p_member, p_store, v_store.name,
		concat_ws('_', v_store.region_1st, v_store.region_2nd, v_store.region_3rd),
		'[시드] ' || v_store.name || ' ' || v_kind, v_at, TRUE, v_at)
	RETURNING id INTO v_record;

	INSERT INTO bread_record_photo (record_id, name, mime_type, aws_key, processing_status, sort_order, width, height, created_at)
	VALUES (v_record, 'seed.jpg', 'IMAGE_JPEG',
		format('bread-records/members/%s/images/seed-%s.jpg', p_member, v_record), 'PENDING', 0, 1024, 1024, v_at)
	RETURNING id INTO v_photo;

	INSERT INTO bread_record_tag (record_id, photo_id, name, bread_category, x, y, again, rating_memo, created_at)
	VALUES (v_record, v_photo, v_kind, v_kind, 0.5, 0.5, p_again,
		CASE WHEN p_again THEN '또 먹고 싶어요' ELSE '한 번이면 충분해요' END, v_at)
	RETURNING id INTO v_tag;

	FOREACH v_texture IN ARRAY p_textures LOOP
		INSERT INTO bread_record_tag_chip (tag_id, chip_type, chip) VALUES (v_tag, 'TEXTURE', v_texture) ON CONFLICT DO NOTHING;
	END LOOP;
	IF p_taste IS NOT NULL THEN
		INSERT INTO bread_record_tag_chip (tag_id, chip_type, chip) VALUES (v_tag, 'TASTE', p_taste) ON CONFLICT DO NOTHING;
	END IF;

	IF p_add_entry THEN
		INSERT INTO bread_rank_entry (member_id, category_id, store_id, rank, ranked_at, created_at)
		VALUES (p_member, p_category, p_store, p_rank, CASE WHEN p_rank IS NULL THEN NULL ELSE v_at END, v_at)
		ON CONFLICT (member_id, category_id, store_id) DO UPDATE SET rank = EXCLUDED.rank, ranked_at = EXCLUDED.ranked_at;
	END IF;
END
$fn$;

-- ── 3. 부하테스트 회원 1000~1029 의 1등 표 ────────────────────────────────────────
-- 한 종류 안에서 투표자 오프셋이 겹치지 않게 잡아 한 사람이 같은 종류에 두 표를 내지 않는다.
DO $do$
DECLARE
	v        RECORD;
	i        INT;
	n        INT := 0;
	textures TEXT[] := ARRAY['겉바속촉', '쫄깃', '폭신', '촉촉', '바삭', '부드러움', '쫀득'];
	tastes   TEXT[] := ARRAY['짭짤', '고소', '버터향', '달달'];
BEGIN
	FOR v IN
		SELECT c.category_id, s.id AS store_id, c.votes, c.voter_offset, c.again_every
		FROM (VALUES
			--  종류        가게    표  오프셋  again=false 주기
			(1,  'ssd', 5,  0, 4),  -- 소금빵: 성심당 5
			(1,  'd01', 4,  5, 4),  --          런던베이글 4
			(1,  'd07', 3,  9, 3),  --          밀도 3            → 12명
			(31, 'd03', 5, 12, 4),  -- 크루아상: 김영모 5
			(31, 'd04', 3, 17, 9),  --            오월의종 3 (전부 또 먹을래요)
			(31, 'd05', 3, 20, 2),  --            나폴레옹 3       → 11명
			(39, 'd01', 6, 23, 4),  -- 베이글: 런던베이글 6
			(39, 'd06', 4, 29, 3),  --          코끼리베이글 4     → 10명 (29,0,1,2)
			(2,  'd07', 4,  3, 4),  -- 식빵: 밀도 4
			(2,  'd02', 3,  7, 3),  --        태극당 3
			(2,  'd03', 3, 10, 5),  --        김영모 3            → 10명
			(14, 'd02', 5, 13, 4),  -- 단팥빵: 태극당 5
			(14, 'd05', 3, 18, 3),  --          나폴레옹 3
			(14, 'd08', 2, 21, 2)   --          뚜쥬루 2           → 10명
		) AS c(category_id, code, votes, voter_offset, again_every)
		JOIN seed_store s ON s.code = c.code
	LOOP
		FOR i IN 0 .. v.votes - 1 LOOP
			PERFORM pg_temp.seed_vote(
				1000 + ((v.voter_offset + i) % 30), v.category_id, v.store_id, 1, TRUE,
				((i + 1) % v.again_every) <> 0,
				ARRAY[textures[1 + (i % 7)], textures[1 + ((i + 3) % 7)]],
				tastes[1 + ((i + v.category_id::int) % 4)],
				(i * 3 + v.category_id::int) % 27);
			n := n + 1;
		END LOOP;
	END LOOP;
	RAISE NOTICE '1등 표 % 건', n;
END
$do$;

-- ── 4. me 의 내 빵 순위 ──────────────────────────────────────────────────────────
DO $do$
DECLARE
	me  BIGINT := (SELECT me FROM seed_param);
	ssd BIGINT := (SELECT id FROM seed_store WHERE code = 'ssd');
	d01 BIGINT := (SELECT id FROM seed_store WHERE code = 'd01');
	d03 BIGINT := (SELECT id FROM seed_store WHERE code = 'd03');
	d04 BIGINT := (SELECT id FROM seed_store WHERE code = 'd04');
	d06 BIGINT := (SELECT id FROM seed_store WHERE code = 'd06');
	d07 BIGINT := (SELECT id FROM seed_store WHERE code = 'd07');
BEGIN
	INSERT INTO member_bread_category (member_id, category_id, sort_order, created_at)
	VALUES (me, 1, 0, NOW()), (me, 31, 1, NOW()), (me, 39, 2, NOW())
	ON CONFLICT DO NOTHING;

	-- 소금빵: 1등 성심당 · 2등 밀도 · 담기만 한 런던베이글
	PERFORM pg_temp.seed_vote(me, 1, ssd, 1,    TRUE, TRUE,  ARRAY['겉바속촉', '촉촉'], '짭짤',   1);
	PERFORM pg_temp.seed_vote(me, 1, d07, 2,    TRUE, TRUE,  ARRAY['쫄깃'],           '고소',   3);
	PERFORM pg_temp.seed_vote(me, 1, d01, NULL, TRUE, FALSE, ARRAY['폭신'],           '버터향', 6);
	-- 크루아상: 1등 김영모 · 2등 오월의종
	PERFORM pg_temp.seed_vote(me, 31, d03, 1, TRUE, TRUE,  ARRAY['겉바속촉', '바삭'], '버터향', 2);
	PERFORM pg_temp.seed_vote(me, 31, d04, 2, TRUE, FALSE, ARRAY['부드러움'],         '달달',   8);
	-- 베이글: 1등 런던베이글. 코끼리베이글은 빵로그만 있어 "담을 후보"에 뜬다
	PERFORM pg_temp.seed_vote(me, 39, d01, 1,    TRUE,  TRUE, ARRAY['쫄깃', '쫀득'], '고소', 4);
	PERFORM pg_temp.seed_vote(me, 39, d06, NULL, FALSE, TRUE, ARRAY['쫄깃'],         '짭짤', 10);
END
$do$;

-- ── 5. 어제 스냅샷 — 오늘 판과 어긋나게 ──────────────────────────────────────────
-- 오늘 판(표 순): 소금빵 성심당·런던·밀도 / 크루아상 김영모·(오월의종 ↔ 나폴레옹) / 베이글 런던·코끼리 / 식빵 밀도·태극당·김영모 / 단팥빵 태극당·나폴레옹·뚜쥬루
INSERT INTO ranking_snapshot (snapshot_date, board, category_id, region, subject_id, rank)
SELECT (NOW() AT TIME ZONE 'Asia/Seoul')::date - 1, 'BREAD', v.category_id, '', s.id, v.rank
FROM (VALUES
	(1,  'd07', 1), (1,  'ssd', 2),                  -- 소금빵: 성심당 ▲1 · 런던 NEW · 밀도 ▼2
	(31, 'd03', 1), (31, 'd05', 2), (31, 'd04', 3),  -- 크루아상: 2·3등이 서로 바뀜
	(39, 'd06', 1), (39, 'd01', 2),                  -- 베이글: 런던 ▲1 · 코끼리 ▼1
	(2,  'd07', 1), (2,  'd02', 2), (2,  'd03', 3),  -- 식빵: 변동 없음
	(14, 'd02', 1), (14, 'd05', 2)                   -- 단팥빵: 뚜쥬루 NEW
) AS v(category_id, code, rank)
JOIN seed_store s ON s.code = v.code
ON CONFLICT (snapshot_date, board, category_id, region, subject_id) DO UPDATE SET rank = EXCLUDED.rank;

-- ── 확인 ─────────────────────────────────────────────────────────────────────────
SELECT c.name, COUNT(DISTINCT e.member_id) AS voters, COUNT(*) AS votes
FROM bread_rank_entry e JOIN bread_category c ON c.id = e.category_id
WHERE e.rank = 1
GROUP BY c.name ORDER BY voters DESC;

SELECT s.name AS store, c.name AS category, COUNT(DISTINCT r.member_id) AS raters
FROM bread_record_tag t
JOIN bread_record r ON r.id = t.record_id AND r.deleted_at IS NULL
JOIN bread_category c ON c.menu_kind = t.bread_category
JOIN store s ON s.id = r.store_id
WHERE r.memo LIKE '[시드]%'
GROUP BY s.name, c.name HAVING COUNT(DISTINCT r.member_id) >= 5
ORDER BY raters DESC;

COMMIT;
