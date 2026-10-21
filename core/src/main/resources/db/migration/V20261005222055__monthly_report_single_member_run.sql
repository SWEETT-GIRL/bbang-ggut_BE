-- 전체 발행과 회원 1명 테스트 발행은 각자 초안과 진행 상태를 갖는다.
ALTER TABLE monthly_report_run DROP CONSTRAINT monthly_report_run_year_month_key;
ALTER TABLE monthly_report_run ADD COLUMN target_member_id bigint CHECK (target_member_id > 0);
CREATE UNIQUE INDEX monthly_report_run_all_month ON monthly_report_run(year_month) WHERE target_member_id IS NULL;
CREATE UNIQUE INDEX monthly_report_run_member_month ON monthly_report_run(year_month, target_member_id) WHERE target_member_id IS NOT NULL;
ALTER TABLE monthly_report_draft DROP CONSTRAINT monthly_report_draft_pkey;
ALTER TABLE monthly_report_draft ADD PRIMARY KEY (run_id, member_id, year_month);
