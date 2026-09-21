-- 가게의 취향 라벨 — 추천이 회원 검사 답과 맞댈 9개 값 (코스 추천 v2 Spec 3.1 · 3.2).
--
-- 기존 store_profile.axis_* 는 축 넷(−1~+1)이라 두 갈래까지만 담는다. 맛이 넷(달콤·담백·
-- 짭짤·풍미)이고 식감이 셋(부드·바삭·쫄깃)이 되면 짭짤과 담백이 같은 +1 이 되고 풍미·쫄깃은
-- 어느 극도 아니라 0 이 된다. 그 표현으로는 회원이 검사에서 고른 갈래를 가게와 맞댈 수 없다.
--
-- store_profile 을 고치지 않고 새 표에 둔다. 기존 추천 경로가 계속 돌아야 하고, 라벨 기반
-- 점수가 이길지 아직 재지 않았기 때문이다. 두 표가 같은 가게를 다른 각도로 들고 있다.
--
-- 적재는 dataops 다(scripts/recommendation_feature_load.py). 표 소유는 BE 다 — 운영 스키마는
-- BE 마이그레이션으로만 바뀐다.
--
--   ■ release_id 를 두지 않는 이유
--     Spec 은 "완성된 release 만 서빙" 을 위해 release 를 제안했는데, 한 트랜잭션 안에서
--     upsert 하면 MVCC 가 그걸 그냥 보장한다 — 커밋 전에는 읽는 쪽에 안 보이고 커밋 순간에
--     전부 보인다. 대신 같은 쿼리로 두 판을 A/B 하는 것을 포기한다. 그 비교가 필요해지면
--     dataops 가 별도 표로 낸다(scratch.draft_rule_* / draft_norule_*). 쓰지도 않을
--     (release_id, store_id) 복합 PK 를 지고 가지 않는다.
--
--     ⚠ 적재는 반드시 upsert 로 한다. DROP; CREATE 는 ACCESS EXCLUSIVE 락이라 MVCC 밖이고,
--       적재 내내 읽기가 막혀 추천 API 가 그만큼 멈춘다.
--
--   ■ 아홉 값이 모두 0~1 이고, 이름이 어느 쪽이 1 인지 말한다
--     속·스타일은 store_profile 에서 두 극 사이의 위치(꽉 참 −1 · 심플 +1)였다. 여기서는 라벨
--     일곱 개와 같이 "그 성질의 세기" 로 두어 filling_rich · vibe_trendy 로 부른다.
--
--     부호를 맞추려는 게 아니라 틀렸을 때의 무게가 달라서다. store_profile 부호를 물려받으면
--     회원 선언(labels.filling 은 꽉 참이 1)과 반대라 점수 경로에서 매번 뒤집어야 하는데,
--     한 곳만 빼먹어도 내적이 조용히 반대 가게를 올린다. 예외도 안 나고 코스는 그럴듯하게
--     나오므로 틀린 걸 알 방법이 없다. 이름을 axis_filling 으로 두면 주석을 안 읽은 사람이
--     반반 확률로 틀린다. 이름이 방향을 말하면 그 실수가 안 난다.
--
--     범위를 0~1 로 맞춘 것도 같은 이유다. 이 둘만 −1~+1 이면 폭이 두 배라, 아홉 차원을 같은
--     무게로 놓는 순간 점수의 59% 를 이 둘이 정한다(dataops 실측) — 신호가 세서가 아니라 자가
--     달라서다. 표에서 맞춰 두면 읽는 쪽이 정규화를 기억할 필요가 없다.
--
--   ■ observed_at 이 nullable 인 이유
--     지금 랩 적재에는 null 이 0건이지만(가장 오래된 것 08-11), 스냅샷이 없는 가게가 앞으로
--     생길 수 있다. NOT NULL 로 두면 그때 가짜 시각을 넣게 되고, 그것이 문장 freshness 판정을
--     조용히 망친다. "모른다" 를 표현할 자리를 남긴다.

CREATE TABLE store_recommendation_feature
(
    store_id           BIGINT        PRIMARY KEY REFERENCES store (id),

    -- 맛 4 · 식감 3 — 각 성질의 세기(0~1). 서로 배타적이지 않다(소금빵은 짭짤·풍미가 동시에 선다)
    taste_sweet        NUMERIC(5, 4) NOT NULL,
    taste_plain        NUMERIC(5, 4) NOT NULL,
    taste_salty        NUMERIC(5, 4) NOT NULL,
    taste_aroma        NUMERIC(5, 4) NOT NULL,
    tex_soft           NUMERIC(5, 4) NOT NULL,
    tex_crisp          NUMERIC(5, 4) NOT NULL,
    tex_chewy          NUMERIC(5, 4) NOT NULL,

    -- 속·스타일도 같은 자다 — 0~1 의 세기이고 이름이 어느 쪽이 1 인지 말한다
    filling_rich       NUMERIC(5, 4) NOT NULL,
    vibe_trendy        NUMERIC(5, 4) NOT NULL,

    -- 무엇을 보고 만들었나. 리뷰만으로 만든 값을 메뉴에서 확인된 사실과 같은 강도로 쓰지 않는다
    label_source       VARCHAR(10)   NOT NULL,
    menu_label_count   INTEGER       NOT NULL,
    review_label_count INTEGER       NOT NULL,

    -- 가중치 큰 종류부터 5개. 이유 문장이 "무엇 때문에 이 가게인가" 를 말할 때 쓴다
    --   [{"kind":"소금빵","menuWeight":17,"reviewMentions":235}, ...]
    evidence           JSONB         NOT NULL,

    -- sha256 앞 32자 = 사전 판본 + 신호 스냅샷 id + 쓰인 메뉴 + 리뷰 라벨. 같은 값이면 다시
    -- 계산해도 같은 벡터다 — 재적재가 진짜 바뀐 것인지 가린다(시각은 넣지 않는다)
    source_fingerprint CHAR(32)      NOT NULL,
    dictionary_version VARCHAR(20)   NOT NULL,

    -- 원천을 마지막으로 읽은 때. computed_at 은 배치가 돈 시각이라 신선도가 아니다
    observed_at        TIMESTAMP,
    computed_at        TIMESTAMP     NOT NULL,

    -- 아홉 값이 모두 같은 자다. 하나라도 범위를 벗어나면 그 차원만 점수를 더 먹는다
    CONSTRAINT ck_store_feature_label_range CHECK (
        taste_sweet BETWEEN 0 AND 1 AND taste_plain BETWEEN 0 AND 1
        AND taste_salty BETWEEN 0 AND 1 AND taste_aroma BETWEEN 0 AND 1
        AND tex_soft BETWEEN 0 AND 1 AND tex_crisp BETWEEN 0 AND 1
        AND tex_chewy BETWEEN 0 AND 1
        AND filling_rich BETWEEN 0 AND 1 AND vibe_trendy BETWEEN 0 AND 1),
    CONSTRAINT ck_store_feature_source CHECK (label_source IN ('MENU', 'REVIEW', 'BOTH')),
    CONSTRAINT ck_store_feature_counts CHECK (menu_label_count >= 0 AND review_label_count >= 0)
);

COMMENT ON TABLE store_recommendation_feature IS
    '가게의 취향 라벨 9개 — 회원이 검사에서 고른 갈래와 맞대는 값. store_profile.axis_* 를 대체하지 않고 나란히 둔다. '
    '적재는 dataops, 스키마 소유는 BE. 반드시 한 트랜잭션 안에서 upsert 한다(DROP; CREATE 는 읽기를 막는다). '
    '아홉 값이 모두 0~1 이고 이름이 어느 쪽이 1 인지 말한다 — store_profile.axis_* 와 부호가 다르니 그대로 옮기지 않는다.';

COMMENT ON COLUMN store_recommendation_feature.taste_aroma IS
    '풍미 — rich 가 아니라 aroma 다. 리뷰 태그의 rich 가 초코·치즈의 "진한 맛" 을 가리켜 혼동을 본 적이 있다.';

COMMENT ON COLUMN store_recommendation_feature.filling_rich IS
    '속이 꽉 찬 정도 0~1 — 심플할수록 0 이다. 회원 응답의 labels.filling 과 같은 방향·같은 범위라 그대로 맞댄다. '
    'store_profile.axis_filling 은 꽉 참이 −1 인 반대 부호이니 그 값을 여기에 그대로 옮기지 않는다.';

COMMENT ON COLUMN store_recommendation_feature.vibe_trendy IS
    '빵이 트렌디한 정도 0~1 — 클래식할수록 0 이다. 가게 분위기가 아니라 빵의 성질이다(분위기는 store_profile.tags). '
    'store_profile.axis_vibe 는 클래식이 −1 인 반대 부호다.';

COMMENT ON COLUMN store_recommendation_feature.source_fingerprint IS
    'sha256 앞 32자 — 사전 판본 + 신호 스냅샷 id + 쓰인 메뉴(종류·가중치) + 리뷰 라벨(종류·언급수). '
    '시각은 넣지 않는다. 같은 값이면 다시 계산해도 같은 벡터가 나온다.';

COMMENT ON COLUMN store_recommendation_feature.observed_at IS
    '원천(네이버 스냅샷)을 마지막으로 읽은 때. 모르면 NULL — 가짜 시각을 넣지 않는다. '
    'computed_at 은 배치가 돈 시각이라 신선도 판정에 쓸 수 없다.';
