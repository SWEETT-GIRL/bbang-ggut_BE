-- 스냅샷 하루 작업의 선점 기록 — advisory lock 대신 날짜 행으로 "오늘은 내가 한다"를 잡는다(판마다 트랜잭션을 끊으려고).
CREATE TABLE IF NOT EXISTS ranking_snapshot_run (
	snapshot_date DATE      PRIMARY KEY,
	started_at    TIMESTAMP NOT NULL,
	finished_at   TIMESTAMP,
	saved_rows    INTEGER,
	failed_boards INTEGER
);
