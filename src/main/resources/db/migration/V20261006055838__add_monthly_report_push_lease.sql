ALTER TABLE monthly_report_push ADD COLUMN lease_token uuid;
ALTER TABLE monthly_report_push ADD COLUMN lease_expires_at timestamptz;
ALTER TABLE monthly_report_push ADD CONSTRAINT monthly_report_push_lease_pair
 CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL));
DROP INDEX monthly_report_push_due;
CREATE INDEX monthly_report_push_due ON monthly_report_push(next_attempt_at)
 WHERE NOT done AND (attempts < 5 OR lease_token IS NOT NULL);
