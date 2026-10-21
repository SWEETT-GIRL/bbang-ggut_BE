-- 검수표를 운영 store_profile 의 새 모양에 맞춘다 (BE #731, 2026-09-13)
--
-- 운영 표가 첫 적재 전에 바뀌었다 — ① 타입이 숫자 1~16 에서 4글자 코드로, ② 리뷰 집계 셋과 소개글이 attrs 에서
-- 컬럼으로, ③ tagger_model·prompt_version 삭제(검수표엔 원래 없다). 검수표는 운영으로 옮기기 전에 눈으로 보는
-- 자리라 같은 모양이어야 한다.

-- ① 타입 4글자 코드. 자리마다 한 축: 1 맛 S달콤/P담백 · 2 식감 F부드/C바삭 · 3 속 R풍부/L심플 · 4 빵의 신상 O고전/N신상.
--    숫자 규칙이 code = 1 + 8·(맛>0) + 4·(식감>0) + 2·(속>0) + (신상>0) 이었으므로 n-1 의 비트가 곧 네 글자다.
--    BE V20260911142251 의 USING 식과 같다. 36,904행이 값을 잃지 않고 옮겨진다(1→SFRO, 12→PFLN, 16→PCLN).
ALTER TABLE store_profile_draft ALTER COLUMN bbti_type TYPE VARCHAR(4) USING (
    CASE WHEN bbti_type IS NULL THEN NULL ELSE
        (CASE WHEN ((bbti_type - 1) & 8) <> 0 THEN 'P' ELSE 'S' END) ||
        (CASE WHEN ((bbti_type - 1) & 4) <> 0 THEN 'C' ELSE 'F' END) ||
        (CASE WHEN ((bbti_type - 1) & 2) <> 0 THEN 'L' ELSE 'R' END) ||
        (CASE WHEN ((bbti_type - 1) & 1) <> 0 THEN 'N' ELSE 'O' END)
    END);
ALTER TABLE store_profile_draft ADD CONSTRAINT ck_store_profile_draft_bbti_type
    CHECK (bbti_type IS NULL OR bbti_type ~ '^[SP][FC][RL][ON]$');
-- type_label 은 배치가 "13 날카로운 감각 (…)" 처럼 옛 번호를 앞에 붙여 두었다. 다음 배치가 "PCRO 날카로운 감각 (…)" 로 다시 쓴다.

-- ② 리뷰 집계 셋 + 소개글. 운영과 같은 타입·같은 CHECK. 값은 배치(review_fields)가 store_signal 에서 채운다.
ALTER TABLE store_profile_draft ADD COLUMN review_count INTEGER;
ALTER TABLE store_profile_draft ADD COLUMN rating_count INTEGER;
ALTER TABLE store_profile_draft ADD COLUMN review_score NUMERIC(3, 2);
ALTER TABLE store_profile_draft ADD COLUMN description  TEXT;
ALTER TABLE store_profile_draft ADD CONSTRAINT ck_store_profile_draft_review_score
    CHECK (review_score IS NULL OR review_score BETWEEN 1 AND 5);

-- attrs 에서 컬럼으로 나간 키를 뺀다. 두 곳에 있으면 어느 쪽이 참인지 모르게 된다.
-- category·categoryCode 는 store.category(BE #730)로 나갔다.
UPDATE store_profile_draft
SET attrs = attrs - 'reviewScore' - 'reviewCount' - 'category' - 'categoryCode'
WHERE (attrs - 'reviewScore' - 'reviewCount' - 'category' - 'categoryCode') <> attrs;

COMMENT ON COLUMN store_profile_draft.bbti_type IS
    '4글자 타입 코드(SFRO~PCLN). 부호만 본다 — 0 보다 크면 뒤 글자, 0 은 앞 글자. 규칙은 store_profile_batch.type_of.';
COMMENT ON COLUMN store_profile_draft.attrs IS
    '편의시설 10종 + newOpening. 점수에 넣지 않는다. 리뷰 수·별점은 review_count·rating_count·review_score 컬럼, 소개글은 description.';
COMMENT ON COLUMN store_profile_draft.review_score IS
    '네이버 별점 평균 1.00~5.00. 0 과 1 미만은 결측 — 네이버가 별점 표본이 적으면 평균을 감추면서 0 을 준다 '
    '(0점 5,071곳 전부 리뷰는 있다, 평균 55.5개). "리뷰 없음"이 아니라 "별점 매긴 사람이 평균 12.7명뿐" 이다.';
