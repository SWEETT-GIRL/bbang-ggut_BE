-- 플랫폼별 앱 버전 정책. 어드민이 숫자만 바꾸면 앱이 스스로 막히거나(min) 안내한다(latest).
-- 두 값 다 NULL이면 아무 것도 안 한다 — 배포 직후 기본 상태. 스토어 출시 확인 후 값을 채운다.
CREATE TABLE app_version_policy (
    id                    BIGSERIAL PRIMARY KEY,
    platform              VARCHAR(20)  NOT NULL UNIQUE,
    min_supported_version VARCHAR(20),
    latest_version        VARCHAR(20),
    store_url             VARCHAR(512),
    message               VARCHAR(500),
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP
);

INSERT INTO app_version_policy (platform, store_url) VALUES
    ('IOS', 'https://apps.apple.com/kr/app/id6792633374'),
    ('ANDROID', NULL);
