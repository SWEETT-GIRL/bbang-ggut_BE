CREATE TABLE image_processing_callback (
    id BIGSERIAL PRIMARY KEY,
    aws_key VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    fail_reason VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_image_processing_callback_aws_key UNIQUE (aws_key),
    CONSTRAINT ck_image_processing_callback_status CHECK (status IN ('DONE', 'FAILED'))
);

ALTER TABLE store_image_file ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE menu_image_file ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE review_image_file ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE course_image_files ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE store_contribution_report_image_file ALTER COLUMN processing_status SET DEFAULT 'PENDING';
ALTER TABLE menu_contribution_report_image_file ALTER COLUMN processing_status SET DEFAULT 'PENDING';
