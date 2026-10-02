-- 순위 변동(▲▼NEW)용 일별 스냅샷 — 매일 00:05 KST 에 "어제 마감" 상위 100 을 판마다 남기고, 조회 때 어제와 비교한다.
-- 빵순이 4판은 전국 + (WEEKLY·RECORDS 는 인증된 구마다), 빵집 판은 카테고리마다 전국. 14일 지나면 지운다.
CREATE TABLE IF NOT EXISTS ranking_snapshot (
	snapshot_date DATE        NOT NULL,            -- Asia/Seoul, 그 날의 마감 순위
	board         VARCHAR(20) NOT NULL,            -- WEEKLY | RECORDS | REGIONS | SHARE | BREAD
	category_id   BIGINT      NOT NULL DEFAULT 0,  -- 빵 판만, 사람 판은 0
	region        VARCHAR(30) NOT NULL DEFAULT '', -- '' = 전국, 예: '서울_동대문구'
	subject_id    BIGINT      NOT NULL,            -- 사람 판 member_id, 빵 판 store_id
	rank          INTEGER     NOT NULL,
	PRIMARY KEY (snapshot_date, board, category_id, region, subject_id)
);
