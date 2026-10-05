CREATE TABLE monthly_report_run (
 id uuid PRIMARY KEY, year_month varchar(7) NOT NULL UNIQUE CHECK (year_month ~ '^20[0-9]{2}-(0[1-9]|1[0-2])$'),
 asset_version varchar(60) NOT NULL,
 state varchar(20) NOT NULL CHECK (state IN ('PREPARING','REVIEW','PUBLISHING','COMPLETE','BLOCKED')),
 after_member_id bigint NOT NULL DEFAULT 0, scan_complete boolean NOT NULL DEFAULT false,
 sources_ready boolean NOT NULL DEFAULT false, push_at timestamptz, created_at timestamptz NOT NULL,
 error_code varchar(100)
);
CREATE TABLE monthly_report_draft (
 member_id bigint NOT NULL REFERENCES member(id) ON DELETE CASCADE, year_month varchar(7) NOT NULL,
 run_id uuid NOT NULL REFERENCES monthly_report_run(id),
 state varchar(20) NOT NULL CHECK (state IN ('PENDING','PREPARED','FAILED','PUBLISHED','SKIPPED','PUBLISH_FAILED')),
 payload jsonb, raw_metrics jsonb, generated_at timestamptz, attempts integer NOT NULL DEFAULT 0,
 error_code varchar(100), PRIMARY KEY (member_id, year_month),
 CHECK (state NOT IN ('PREPARED','PUBLISHED','PUBLISH_FAILED') OR (payload IS NOT NULL AND raw_metrics IS NOT NULL AND generated_at IS NOT NULL))
);
CREATE INDEX monthly_report_draft_run_state ON monthly_report_draft(run_id, state, member_id);
