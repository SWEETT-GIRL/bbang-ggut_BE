-- 가게 프로필 초안 — 설계 §4 store_profile(앱 DB, 배치 산출)의 dataops 쪽 초안. 축·태그·대표 메뉴를 가게 신호(store_signal)와
-- 운영 분류 메뉴 × menu_kind_axis 로 계산해 여기 쌓고, 검수가 끝나면 ServingWriter 로 앱 DB store_profile 에 반영한다.
-- axis: A극 -1 / B극 +1 (맛 달콤/담백 · 식감 부드/바삭 · 속 풍부/심플 · 분위기 클래식/트렌디), 분류 메뉴 3개 미만이고 리뷰 언급 메뉴도 없으면 NULL.
-- conversion_prior 는 앱 로그(상세→담기)에서 BE 가 채운다 — 여기선 NULL.
CREATE TABLE store_profile_draft (
    store_id           BIGINT        PRIMARY KEY,
    axis_taste         NUMERIC(4,3),
    axis_texture       NUMERIC(4,3),
    axis_filling       NUMERIC(4,3),
    axis_vibe          NUMERIC(4,3),
    bbti_type          SMALLINT,                      -- 축 부호 조합 1~16 (|축| < 0.15 는 중간 — type_label 에 표시)
    type_label         VARCHAR(60),
    axis_menu_count    INTEGER       NOT NULL DEFAULT 0, -- 축 계산에 쓴 운영 분류 메뉴 수
    axis_review_count  INTEGER       NOT NULL DEFAULT 0, -- 축 계산에 쓴 리뷰 언급 메뉴 라벨 수
    tags               JSONB         NOT NULL DEFAULT '{}', -- {"카페형":0.9, ...} 12개 전부 (0 포함), 근거는 tag_evidence
    tag_evidence       JSONB         NOT NULL DEFAULT '{}',
    signature_menus    TEXT[]        NOT NULL DEFAULT '{}', -- 대표 메뉴 + 리뷰 언급 상위 (음료 제외, 최대 5)
    conversion_prior   NUMERIC(5,4),
    signal_snapshot_id BIGINT,
    profile_version    INTEGER       NOT NULL DEFAULT 1,
    computed_at        TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_profile_draft_type ON store_profile_draft (bbti_type);
