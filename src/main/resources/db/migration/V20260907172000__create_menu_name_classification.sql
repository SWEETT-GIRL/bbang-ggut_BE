-- 메뉴 AI 분류 — 이름 단위 캐시 테이블 + menu 판정 컬럼
--
-- 흐름: dataops/회원 등록이 카테고리 없이(NULL) 넣은 메뉴를 BE 스케줄러가 주워
--   1) normalized_name 으로 이 캐시를 먼저 본다 — 같은 이름은 AI 를 다시 부르지 않고 같은 답을 받는다
--      (운영 실측: 9월 신규 126,065건 중 고유 이름 75,258개. 캐시가 없으면 "사각 카스테라" 가
--       가게마다 다른 kind 를 받을 수 있다)
--   2) 없는 이름만 Gemini 에 3회 물어 세 번 다 같은 답일 때만 확정한다
--      (200건 실측: 3회 일치 92%, 그중 명백한 오류 1.1% / 갈린 8% 는 검수 대상)
--
-- menu_name_classification
--   kind          NULL = 3회가 갈렸거나 '빵'·'기타'로만 답함 → 사람이 볼 것. 같은 이름은 다시 안 묻는다
--   is_bakery_item 음료·식사·굿즈면 false — 표본의 56% 가 애초에 빵이 아니었다
--   confidence    모델 자기보고. 판단엔 안 쓴다 (0.99 를 달고도 답이 갈리는 걸 봤다). 재분류 표적용
--   agreed        3회 일치 여부
--
-- menu
--   is_bakery_item 앱 쿼리가 조인 없이 거르도록 메뉴에도 둔다. NULL = 미판정 (기존 68만 행)
--   classified_at  AI 가 시도한 시각. 갈려서 kind 를 못 받아도 찍는다 — 스케줄러가 같은 행을 무한 재시도하지 않게

CREATE TABLE menu_name_classification (
    normalized_name VARCHAR(100) PRIMARY KEY,
    kind            VARCHAR(50),
    is_bakery_item  BOOLEAN      NOT NULL,
    confidence      REAL,
    agreed          BOOLEAN      NOT NULL,
    model           VARCHAR(50)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP
);

ALTER TABLE menu
    ADD COLUMN is_bakery_item BOOLEAN,
    ADD COLUMN classified_at  TIMESTAMP;

-- 스케줄러의 "다음 배치" 조회 — 미분류·미시도·활성 행만. 조건이 좁아 부분 인덱스가 작다.
CREATE INDEX idx_menu_awaiting_classification
    ON menu (id)
    WHERE menu_category_id IS NULL AND classified_at IS NULL AND deleted_at IS NULL;
