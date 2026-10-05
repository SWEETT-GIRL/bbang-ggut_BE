-- 기존 행의 누락 시각은 이 마이그레이션 적용 시각부터 기록한다. 과거 작업 시각을 추정하지 않는다.
CREATE FUNCTION monthly_report_update_audit_timestamp() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.updated_at := clock_timestamp();
 RETURN NEW;
END;
$$;
ALTER TABLE monthly_report_asset ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
ALTER TABLE monthly_report_asset ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TRIGGER monthly_report_asset_audit BEFORE UPDATE ON monthly_report_asset
 FOR EACH ROW EXECUTE FUNCTION monthly_report_update_audit_timestamp();
ALTER TABLE monthly_report_run ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TRIGGER monthly_report_run_audit BEFORE UPDATE ON monthly_report_run
 FOR EACH ROW EXECUTE FUNCTION monthly_report_update_audit_timestamp();
ALTER TABLE monthly_report_draft ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
ALTER TABLE monthly_report_draft ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TRIGGER monthly_report_draft_audit BEFORE UPDATE ON monthly_report_draft
 FOR EACH ROW EXECUTE FUNCTION monthly_report_update_audit_timestamp();
ALTER TABLE monthly_report_push ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
ALTER TABLE monthly_report_push ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE TRIGGER monthly_report_push_audit BEFORE UPDATE ON monthly_report_push
 FOR EACH ROW EXECUTE FUNCTION monthly_report_update_audit_timestamp();
