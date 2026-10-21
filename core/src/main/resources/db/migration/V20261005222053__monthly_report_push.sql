CREATE TABLE monthly_report_push (
 notification_id bigint PRIMARY KEY REFERENCES notification(id) ON DELETE CASCADE,
 member_id bigint NOT NULL REFERENCES member(id) ON DELETE CASCADE,
 send_at timestamptz NOT NULL, next_attempt_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0, done boolean NOT NULL DEFAULT false, error_code varchar(100)
);
CREATE INDEX monthly_report_push_due ON monthly_report_push(next_attempt_at) WHERE NOT done AND attempts < 5;
