-- 회원의 취향 라벨 아홉 값 — 가게(store_recommendation_feature)와 맞대는 쪽.
--
-- 가게 표와 컬럼 이름·범위·방향을 그대로 맞춘다. 두 벡터를 맞대는 코드가 이름만 보고
-- 짝을 지을 수 있어야 하고, 한쪽만 다른 이름이면 그 자리에서 뒤집힌다.
--
--   taste_sweet · taste_plain · taste_salty · taste_aroma      맛 네 갈래
--   tex_soft · tex_crisp · tex_chewy                           식감 세 갈래
--   filling_rich · vibe_trendy                                 꽉 참 · 트렌디의 정도
--
-- 전부 0~1 이고 이름이 어느 쪽이 1 인지 말한다. 기존 axis_filling(꽉 참 −1 · 심플 +1)과
-- 부호가 반대이니 그 값을 여기에 옮기지 않는다.
--
--   ■ 왜 저장하나 — 검사분은 파생인데 신호분은 계산 결과다
--     검사를 푼 회원의 라벨은 quiz_answers 에서 언제든 다시 만들 수 있다(TasteLabels).
--     그런데 검사를 푼 회원이 사실상 0명이라, 그것만으로는 이 기능이 아무에게도 안 간다.
--     신호만 있는 회원은 좋아한 가게들의 라벨을 가중 평균해 만드는데, 그건 창 안 신호를
--     전부 훑어야 나오는 값이라 추천 요청마다 다시 계산할 수 없다. 축(axis_*)을 저장하는
--     것과 같은 이유다.
--
--   ■ nullable 인 이유
--     신호도 검사도 없는 회원은 라벨이 없다. 0 으로 채우면 "모든 성질이 0 인 사람" 이 되어
--     코사인이 정의되지 않거나 엉뚱한 가게와 맞는다. "모른다" 를 표현할 자리를 남긴다.
--     아홉 값은 함께 채워지거나 함께 비어 있다 — 일부만 채우지 않는다.

ALTER TABLE user_taste_profile
    ADD COLUMN taste_sweet  NUMERIC(5, 4),
    ADD COLUMN taste_plain  NUMERIC(5, 4),
    ADD COLUMN taste_salty  NUMERIC(5, 4),
    ADD COLUMN taste_aroma  NUMERIC(5, 4),
    ADD COLUMN tex_soft     NUMERIC(5, 4),
    ADD COLUMN tex_crisp    NUMERIC(5, 4),
    ADD COLUMN tex_chewy    NUMERIC(5, 4),
    ADD COLUMN filling_rich NUMERIC(5, 4),
    ADD COLUMN vibe_trendy  NUMERIC(5, 4);

-- 가게 표와 같은 범위다. 하나라도 벗어나면 그 차원만 점수를 더 먹는다.
ALTER TABLE user_taste_profile ADD CONSTRAINT ck_user_taste_profile_label_range CHECK (
    (taste_sweet IS NULL OR taste_sweet BETWEEN 0 AND 1)
    AND (taste_plain IS NULL OR taste_plain BETWEEN 0 AND 1)
    AND (taste_salty IS NULL OR taste_salty BETWEEN 0 AND 1)
    AND (taste_aroma IS NULL OR taste_aroma BETWEEN 0 AND 1)
    AND (tex_soft IS NULL OR tex_soft BETWEEN 0 AND 1)
    AND (tex_crisp IS NULL OR tex_crisp BETWEEN 0 AND 1)
    AND (tex_chewy IS NULL OR tex_chewy BETWEEN 0 AND 1)
    AND (filling_rich IS NULL OR filling_rich BETWEEN 0 AND 1)
    AND (vibe_trendy IS NULL OR vibe_trendy BETWEEN 0 AND 1));

-- 아홉 값은 함께 채워지거나 함께 비어 있다. 일부만 채운 행은 코사인을 반쪽으로 만들고,
-- 그건 예외가 아니라 "조금 덜 맞는 가게" 로 나와서 틀린 걸 알 방법이 없다.
ALTER TABLE user_taste_profile ADD CONSTRAINT ck_user_taste_profile_label_all_or_none CHECK (
    num_nonnulls(taste_sweet, taste_plain, taste_salty, taste_aroma,
                 tex_soft, tex_crisp, tex_chewy, filling_rich, vibe_trendy) IN (0, 9));

COMMENT ON COLUMN user_taste_profile.taste_aroma IS
    '풍미 — rich 가 아니라 aroma 다. 리뷰 태그의 rich 는 초코·치즈의 "진한 맛" 을 가리켜 다른 것이다.';

COMMENT ON COLUMN user_taste_profile.filling_rich IS
    '속이 꽉 찬 정도 0~1 — 심플할수록 0 이다. store_recommendation_feature.filling_rich 와 같은 방향이라 그대로 맞댄다. '
    'axis_filling 은 꽉 참이 −1 인 반대 부호이니 그 값을 옮기지 않는다.';

COMMENT ON COLUMN user_taste_profile.vibe_trendy IS
    '빵이 트렌디한 정도 0~1 — 클래식할수록 0 이다. axis_vibe 는 클래식이 −1 인 반대 부호다.';
