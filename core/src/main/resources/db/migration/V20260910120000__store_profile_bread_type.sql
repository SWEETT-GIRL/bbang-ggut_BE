-- 가게 프로필 보강 — 빵 성격 타입 컬럼과 축 의미 명시 (이슈 #659 후속, 2026-09-10)
--
-- store_profile 은 아직 0 행이다. 처음 적재하기 전에 두 가지를 바로잡는다.

-- ① 빵 성격 타입.
-- 지금까지 운영에 타입이 없어서, DB 를 여는 사람은 축 4개 숫자를 머리로 부호 계산해야 했고
-- BE 는 LLM 프롬프트에 넣을 typeLabel 을 요청마다 다시 만들어야 했다. 파생값이지만 같은 배치가
-- 같은 행에 함께 쓰므로 축과 어긋날 위험이 없다.
--
-- 이름을 bbti_type 이 아니라 bread_type 으로 둔 이유: 이 값은 '가게의 성격'이 아니라
-- '이 가게가 파는 빵의 성격'이다. 네 축이 전부 메뉴 구성에서 나오고, 가게의 성격(카페형·노포·
-- 동네빵집·데이트 …)은 tags 가 따로 맡는다. 약어는 처음 보는 사람이 못 읽는다.
ALTER TABLE store_profile ADD COLUMN bread_type SMALLINT;
ALTER TABLE store_profile ADD CONSTRAINT ck_store_profile_bread_type
    CHECK (bread_type IS NULL OR bread_type BETWEEN 1 AND 16);

COMMENT ON COLUMN store_profile.bread_type IS
    '이 가게가 파는 빵의 성격 1~16. 가게의 성격이 아니라 메뉴 구성에서 나온 값이다. '
    'code = 1 + 8*(axis_taste>0) + 4*(axis_texture>0) + 2*(axis_filling>0) + 1*(axis_vibe>0). '
    '부호만 본다 — 0 에 가깝다고 음극으로 접지 않는다. 그렇게 하면 애매한 가게가 전부 1번으로 몰린다 '
    '(dataops 구현에 그 버그가 있었고 6,272곳이 잘못된 타입을 받았다). 약한 축을 표시하려면 '
    'ABS(axis) < 0.15 를 각자 판단한다. 사용자 쪽 user_taste_profile.type_code(VARCHAR(4))와 '
    '같은 16타입이나 표현이 다르다 — 4글자 체계가 #660 에서 정해지면 맞춘다.';

-- ② 축 ④의 뜻을 컬럼에 못 박는다.
-- 이름이 '분위기'라 가게 인테리어로 읽기 쉽다. 실제로 2026-09-09 에 그렇게 읽고 리뷰 votedKeyword
-- 로 갈아끼웠다가 되돌렸다(dataops 307d5d1). 설명이 코드에도 문서에도 없어서 생긴 일이라 여기 남긴다.
COMMENT ON COLUMN store_profile.axis_vibe IS
    '신상빵(+1) ↔ 고전 빵(-1). 가게 분위기가 아니라 빵의 성질이다 — 크로플·크룽지·앙버터·소금빵·퀸아망이 +1, '
    '단팥빵·소보로·꿀빵·고구마빵이 -1. 네 축 중 이것만 성질이 다르면 자가 안 맞으므로 빵 기준을 유지한다. '
    '가게 인테리어·분위기는 tags 의 카페형·노포·신상·동네빵집·데이트가 맡는다 — 축에 또 넣으면 '
    '점수식(코사인 + 0.5*태그 내적)이 같은 신호를 두 번 센다.';

COMMENT ON COLUMN store_profile.axis_source IS
    'MENU | REVIEW | BOTH. 네 축이 종류 매핑을 어디서 얻었는지. 리뷰 쪽도 "리뷰에 언급된 메뉴 이름"을 '
    '같은 매핑표로 환산한 것이라, 둘 다 메뉴에서 나온다.';

-- ③ 사용자 쪽 타입도 같은 표현으로 맞춘다.
-- user_taste_profile.type_code 는 VARCHAR(4) 로 잡혀 있었다. 같은 16타입인데 가게는 숫자, 사용자는
-- 글자면 비교할 때마다 변환이 붙고 언젠가 어긋난다. "4글자"는 MBTI 식 코드를 염두에 둔 자리였는데
-- 그 형식이 #660 에서 아직 정해지지 않았고, FE 가 화면에 쓰는 값은 typeLabel("고독한 철학자")이지
-- typeCode 가 아니다 — 코드는 내부용이라 글자가 주는 이득이 없다. 숫자면 CHECK 로 범위를 잠글 수 있다.
--
-- 값이 있으면 변환 규칙 없이 밀어붙이지 않는다. 조용히 망가지는 것보다 배포가 멈추는 편이 낫다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_taste_profile WHERE type_code IS NOT NULL) THEN
        RAISE EXCEPTION 'user_taste_profile.type_code 에 값이 있다. 글자 → 숫자 변환 규칙을 정하고 다시 시도할 것';
    END IF;
END $$;

ALTER TABLE user_taste_profile ALTER COLUMN type_code TYPE SMALLINT USING NULL;
ALTER TABLE user_taste_profile ADD CONSTRAINT ck_user_taste_profile_type_code
    CHECK (type_code IS NULL OR type_code BETWEEN 1 AND 16);

COMMENT ON COLUMN user_taste_profile.type_code IS
    '이 사용자의 빵 취향 타입 1~16. store_profile.bread_type 과 같은 체계라 그대로 비교할 수 있다. '
    'code = 1 + 8*(axis_taste>0) + 4*(axis_texture>0) + 2*(axis_filling>0) + 1*(axis_vibe>0). '
    '화면에 보이는 이름("고독한 철학자")은 #660 BbtiType 이 코드에서 찾는다.';
