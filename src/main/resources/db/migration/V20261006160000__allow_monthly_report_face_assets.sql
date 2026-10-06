ALTER TABLE monthly_report_asset DROP CONSTRAINT monthly_report_asset_kind_check;
ALTER TABLE monthly_report_asset ADD CONSTRAINT monthly_report_asset_kind_check
    CHECK (kind IN ('COVER', 'TYPE', 'FACE'));
