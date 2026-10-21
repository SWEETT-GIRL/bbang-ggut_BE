-- 축 v3 초안 — 맛 4라벨(달콤·담백·짭짤·고소) · 식감 3라벨(부드·바삭·쫄깃) 을 0/0.5/1 로. 속·분위기는 이분 축 유지.
-- 기존 menu_kind_axis 는 그대로 두고(현행 배치가 쓰는 중) 별도 표로 병행한다 — 팀 결정 전까지 되돌리기 쉽게.
-- 값은 scripts/axis_v3_labels.py 가 정본. 이 표는 그걸 로컬에 적재한 것.
CREATE TABLE IF NOT EXISTS menu_kind_label (
    kind         VARCHAR(30) PRIMARY KEY,
    taste_sweet  NUMERIC(2,1) NOT NULL DEFAULT 0,
    taste_plain  NUMERIC(2,1) NOT NULL DEFAULT 0,
    taste_salty  NUMERIC(2,1) NOT NULL DEFAULT 0,
    taste_nutty  NUMERIC(2,1) NOT NULL DEFAULT 0,
    tex_soft     NUMERIC(2,1) NOT NULL DEFAULT 0,
    tex_crisp    NUMERIC(2,1) NOT NULL DEFAULT 0,
    tex_chewy    NUMERIC(2,1) NOT NULL DEFAULT 0,
    axis_filling SMALLINT,     -- -1 풍부 / +1 심플 (정의: 속이 부피의 절반쯤이거나 그것 때문에 먹는가)
    axis_vibe    SMALLINT,     -- -1 클래식 / +1 트렌디
    sum_taste    SMALLINT,     -- 16타입용 요약 (달콤 -1 / 담백 +1 / 0 중간)
    sum_texture  SMALLINT,     -- 16타입용 요약 (부드 -1 / 바삭 +1 / 0 중간 = 쫄깃 계열)
    confidence   VARCHAR(10) NOT NULL,
    memo         VARCHAR(200),
    draft_version INTEGER NOT NULL DEFAULT 3
);
-- 풍미 라벨 추가 (2026-09-09 검수: 고소=견과·곡물, 풍미=버터·발효·치즈 향. 크루아상처럼 담백하지도 달지도 않은데 버터 향이 핵심인 것)
ALTER TABLE menu_kind_label ADD COLUMN IF NOT EXISTS taste_aroma NUMERIC(2,1) NOT NULL DEFAULT 0;
-- 고소 라벨 폐지 (2026-09-09 검수): 1.0 짜리 40,229건 중 18,136건이 휘낭시에로 풍미와 중복, 단독 가치 2만 건뿐.
-- 견과 향은 풍미(taste_aroma)로 흡수하고 통밀빵은 담백으로 옮긴다. 최종 맛 라벨 4개: 달콤·담백·짭짤·풍미.
ALTER TABLE menu_kind_label DROP COLUMN IF EXISTS taste_nutty;
