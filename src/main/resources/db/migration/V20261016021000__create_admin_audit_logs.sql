-- 어드민 쓰기 감사 로그. X-Admin-Key 원문은 남기지 않는다(admin_label 은 설정한 이름 또는 키 해시 앞자리).
CREATE TABLE admin_audit_logs (
    id              BIGSERIAL    PRIMARY KEY,
    admin_label     VARCHAR(50),
    method          VARCHAR(10)  NOT NULL,
    path            VARCHAR(255) NOT NULL,
    query           VARCHAR(500),
    target_type     VARCHAR(50),
    target_id       VARCHAR(64),
    request_summary TEXT,
    status_code     INT,
    ip              VARCHAR(45),
    user_agent      VARCHAR(255),
    request_id      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_logs_created_at ON admin_audit_logs (created_at DESC);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs (target_type, target_id);
