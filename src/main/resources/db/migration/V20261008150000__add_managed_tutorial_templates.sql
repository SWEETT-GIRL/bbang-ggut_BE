CREATE TABLE tutorial_template_revision (
    code VARCHAR(60) PRIMARY KEY,
    content TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE tutorial_template_catalog (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    active_code VARCHAR(60) REFERENCES tutorial_template_revision(code)
);
INSERT INTO tutorial_template_catalog (id, active_code) VALUES (1, NULL);
-- 존재하지 않는 S3 이미지를 초기값으로 배포하지 않는다. 관리 화면에서 업로드·발행 후 노출한다.
