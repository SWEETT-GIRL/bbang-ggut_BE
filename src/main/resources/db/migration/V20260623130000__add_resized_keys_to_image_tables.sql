ALTER TABLE store_image_file
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);

ALTER TABLE menu_image_file
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);

ALTER TABLE review_image_file
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);

ALTER TABLE course_image_files
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);

ALTER TABLE store_contribution_report_image_file
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);

ALTER TABLE menu_contribution_report_image_file
    ADD COLUMN resized_key_320  VARCHAR(255),
    ADD COLUMN resized_key_640  VARCHAR(255),
    ADD COLUMN resized_key_1024 VARCHAR(255);
