-- menu.normalized_name 을 운영 유니크 인덱스 uq_menu_store_normalized_name 의 식과 맞춘다.
--
-- 배경: 2026-05-29 에 docs/migrations/2026-05-29-dedup-unique-constraints.sql 로 운영에 직접 건
-- 유니크 인덱스는 name 의 식 lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g')) — 공백·기호 제거 —
-- 을 키로 쓴다. 그런데 2026-08-04 V20260804121558 이 만든 normalized_name 컬럼은 공백만 지워서
-- (LOWER(REGEXP_REPLACE(name, '[[:space:]]+', '', 'g'))) 같은 가게의 "소금 빵!" 과 "소금빵" 을
-- 컬럼으로는 다른 메뉴로, 인덱스로는 같은 메뉴로 본다. 회원 메뉴 등록(MenuCommandService)이
-- 컬럼으로 중복을 조회한 뒤 INSERT 하므로, 조회는 통과하고 INSERT 는 인덱스에 막혀 500 이 났다.
--
-- 1) 컬럼을 인덱스 식으로 재계산 — 값이 달라지는 행만 (전체 68만 행 중 기호·괄호가 든 이름만 해당)
-- 2) 유니크 인덱스를 Flyway 에 박제 — 운영엔 이미 있어 no-op, dev·로컬엔 새로 생긴다.
--    partial 조건은 운영과 동일하게 둔다: soft-delete 제외, 2026-05-27 이전 수기 데이터 제외
--    (그 구간엔 정리하지 않은 수기 중복이 남아 있어 전 구간 유니크는 생성이 실패한다).
--
-- Java 쪽은 MenuNameNormalizer 가 같은 규칙([^\p{IsAlphabetic}\p{IsDigit}]+ 제거 + lowercase)으로 맞춘다.

UPDATE menu
SET normalized_name = lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g'))
WHERE normalized_name IS DISTINCT FROM lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_store_normalized_name
    ON menu (store_id, lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g')))
    WHERE deleted_at IS NULL AND created_at >= '2026-05-27';
