-- 같은 그림의 WebP 교체만 허용한다. 원본 보고서 및 S3 객체는 보존한다.
CREATE TABLE monthly_report_asset_replacement (
    original_image_key VARCHAR(500) PRIMARY KEY,
    image_key VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT monthly_report_asset_replacement_distinct CHECK (original_image_key <> image_key)
);
CREATE INDEX idx_monthly_report_asset_replacement_target ON monthly_report_asset_replacement(image_key);
