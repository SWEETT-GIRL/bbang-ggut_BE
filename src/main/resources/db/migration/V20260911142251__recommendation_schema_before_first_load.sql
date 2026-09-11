-- 추천 스키마를 첫 적재·첫 사용 전에 한 번에 바로잡는다 (이슈 #659 후속, 2026-09-11)
--
-- store_profile·user_taste_profile 은 운영에서 아직 0 행이고, courses 의 추천 컬럼 둘은 값이 0 개다.
-- dataops 가 처음 적재하고 #660 이 처음 쓰기 전에 고친다. 네 부분으로 되어 있다.
--   1부 — store_profile: 리뷰 집계 셋과 소개글을 attrs 에서 컬럼으로 올리고, 쓰지 않게 된 LLM 태깅 메타를 뺀다.
--   2부 — 두 표의 타입: 숫자 1~16(V20260910120000) 을 4글자 코드로 바꾼다.
--   3부 — courses: 코스 단위 추천 출처(source·recommendation_id)를 뺀다. 출처는 가게 단위에만 둔다.
--   4부 — course_area: 도보·차량 구분(scale)을 뺀다. 지도 기능이 없어 이동 수단으로 가를 이유가 없다.
--
-- ══════════════════════════════════════════════════════════════════════════════
-- 1부. store_profile — 리뷰 집계·소개글을 컬럼으로, LLM 태깅 메타 제거
-- ══════════════════════════════════════════════════════════════════════════════
-- 왜 attrs JSON 이 아니라 컬럼인가 — 네 값 모두 거의 모든 가게에 있고(37,989~45,121곳), 타입이 고정이고,
-- 후보 SQL 이 정렬·필터에 직접 쓴다. JSON 안에 있으면 읽을 때마다 캐스팅이 붙고 CHECK 로 범위를 못 잠근다.
-- attrs 는 있다/없다가 갈리고 형태가 자주 바뀌는 것(편의시설 10종)만 남긴다.
-- 조인 표(EAV)는 더 나쁘다 — 값이 불리언·숫자·문자열로 섞여 값 컬럼이 결국 텍스트가 되고, 타입 안전성은
-- 그대로 없는 채 조인만 늘어난다.
--
-- 적재 후 attrs 에서 reviewScore·reviewCount 는 빠진다. 두 곳에 있으면 어느 쪽이 참인지 모르게 된다.
-- (category·categoryCode 는 store.category 로 나간다 — 별건)
--
-- 인덱스는 넣지 않는다. 후보 SQL 은 store_id 로 조인해 들어오고 이 넷은 조인 뒤에 쓰인다.
-- 정렬 병목이 실제로 확인되면 그때 만든다.
ALTER TABLE store_profile ADD COLUMN review_count INTEGER;
ALTER TABLE store_profile ADD COLUMN rating_count INTEGER;
ALTER TABLE store_profile ADD COLUMN review_score NUMERIC(3, 2);
ALTER TABLE store_profile ADD COLUMN description  TEXT;

ALTER TABLE store_profile ADD CONSTRAINT ck_store_profile_review_score
    CHECK (review_score IS NULL OR review_score BETWEEN 1 AND 5);
ALTER TABLE store_profile ADD CONSTRAINT ck_store_profile_review_counts
    CHECK ((review_count IS NULL OR review_count >= 0)
       AND (rating_count IS NULL OR rating_count >= 0));

COMMENT ON COLUMN store_profile.review_count IS
    '네이버 방문자 리뷰 수(visitorReviewsTotal). 45,121곳, 평균 528.5. 인기 prior 재료 — '
    'store.popularity_score 가 45,339곳 중 850곳만 0 이 아니라 사실상 유일한 보완이다. '
    '점수식에 직접 들어가지 않고 prior 동순위를 가를 때와 화면 표시에 쓴다.';

COMMENT ON COLUMN store_profile.rating_count IS
    '별점을 실제로 매긴 사람 수(ratingReviewsTotal). 45,106곳, 평균 149.9 로 리뷰 수의 28.4% 다. '
    'review_score 의 신뢰도라서 따로 둔다 — 이게 없으면 3명이 준 4.9 와 3,000명이 준 4.9 를 구분할 수 없고, '
    '별점이 있는데 10명 미만인 가게가 12,231곳이다. '
    'StoreSignalParser 가 아직 이 필드를 안 뽑아서 파서 수정 전까지는 NULL 이다(재크롤 불요, 재파싱 1회).';

COMMENT ON COLUMN store_profile.review_score IS
    '네이버 별점 평균 1.00~5.00. 소수 두 자리다 — 실측 최대 자릿수가 2 라 NUMERIC(3,2) 가 정확히 맞는다. '
    '네이버는 별점 표본이 적으면 평균을 감추는데 그때 null 이 아니라 0 을 준다(5,071곳). '
    '이 가게들도 전부 리뷰는 있다(평균 55.5개, 최대 3,154개) — 리뷰가 없는 게 아니라 별점 매긴 사람이 '
    '평균 12.7명뿐이라 감춘 것이다. 0 과 1 미만(0.5 가 45곳, 0.75 가 2곳)은 dataops 가 NULL 로 바꿔 넣으므로 '
    'CHECK 가 성립한다. 점수 계산에서는 rating_count 가 적은 가게를 전체 평균 쪽으로 당긴다.';

COMMENT ON COLUMN store_profile.description IS
    '네이버 가게 소개글 원문. 37,989곳, 중앙값 142자·평균 256자·최대 2,000자. '
    '배치는 읽지 않는다 — 요청 때 LLM 후보 선택·추천 이유 프롬프트에 그대로 넘기는 용도다. '
    '네이버가 한도를 올릴 수 있어 VARCHAR 가 아니라 TEXT 로 둔다. '
    '주의: 점주가 쓴 마케팅 문구라 검증된 사실이 아니다. 설계 §7.1 규칙 2("제공된 정보 밖의 사실은 말하지 않는다")를 '
    '이 값과 함께 어떻게 지킬지는 #662 에서 정한다.';

-- ── LLM 태깅 메타 제거 ────────────────────────────────────────────────
-- tagger_model · prompt_version 은 가게 프로필을 LLM 으로 태깅할 때 그 출처를 적으려던 자리다.
-- 사진 태깅과 텍스트 태깅 배치를 둘 다 접기로 하면서 채울 주체가 사라졌다 — 태그 12개는 전부 결정적
-- 필드(카테고리·편의시설·리뷰 투표·영업시간·개업일)에서 나오고, 소개글을 쓰던 노포 태그도 규칙으로 대체한다.
--
-- "나중에 다시 켤 때를 위해 남기자" 는 안을 접었다. 항상 NULL 인 컬럼은 읽는 사람에게 "여기 뭔가 있나 보다" 라는
-- 잘못된 신호를 주고, 정작 다시 켤 때는 그때 필요한 모양이 지금과 같으리라는 보장도 없다. 그때 다시 만든다.
--
-- 지우는 시점이 지금인 이유: store_profile 이 아직 0 행이라 데이터 손실이 없다. 적재 후에는 값이 있든 없든
-- DROP COLUMN 이 되돌리기 어려운 변경이 된다.
--
-- 주의: dataops 의 store_profile upsert 가 이 두 컬럼을 아직 보내고 있으면 적재가 깨진다. 배치에서 먼저 뺄 것.
ALTER TABLE store_profile DROP COLUMN tagger_model;
ALTER TABLE store_profile DROP COLUMN prompt_version;

-- ══════════════════════════════════════════════════════════════════════════════
-- 2부. 빵 취향 타입을 숫자 1~16 에서 4글자 코드로
-- ══════════════════════════════════════════════════════════════════════════════
--
-- 대상: store_profile.bread_type, user_taste_profile.type_code — 둘 다 같은 16타입 체계다.
-- V20260910120000 이 둘을 SMALLINT 1~16 으로 맞췄는데, 운영 배포 전에 표현을 다시 정한다. 두 표 모두 0 행이다.
--
-- 왜 코드인가. 숫자와 4글자 코드는 "네 축의 부호 조합"이라는 같은 산출 규칙에 똑같이 묶여 있다 —
-- 측정이 네 개의 이진 축을 벗어나면(예: 결정 기록 §13 의 24타입안) 둘 다 깨지고, 타입 수가 바뀌면 어떤 표현이든
-- 축 값에서 다시 계산해야 한다. 결합도가 같으니 남는 차이는 읽기뿐이고, 거기서 코드가 압도한다.
-- psql 에서 13 과 11 은 표 없이 구분되지 않지만 PCLN 은 "담백·바삭·심플·신상" 으로 바로 읽힌다.
--
-- 글자 배정 — 여덟 글자가 전부 다르다. MBTI 가 읽히는 이유가 E/I S/N T/F J/P 가 겹치지 않아서다.
-- 영어 첫 글자를 그냥 따면 S 가 달콤(Sweet)·부드(Soft)·심플(Simple)에 세 번 걸려 SSSC 같은 코드가 나온다.
--
--   자리  축          −1 극            +1 극
--   1     맛          S 달콤 Sweet     P 담백 Plain
--   2     식감        F 폭신 Fluffy    C 바삭 Crispy
--   3     속          R 꽉 찬 Rich     L 심플 Lean
--   4     빵의 신상    O 고전 Old       N 신상 New
--
-- ④ 를 Old/New 로 둔 것도 의도다. "클래식/트렌디" 는 가게 분위기로도 읽혀 혼선을 낳았다(결정 기록 §9.0).
-- O 와 N 은 빵의 신상과 고전만 뜻한다.
--
-- 부호만 본다 — 축 값이 0 보다 크면 +1 극 글자다. 0 에 가깝다고 −1 극으로 접지 않는다
-- (dataops 구현에 그 버그가 있었고 가게 6,272곳이 틀린 타입을 받았다). 0 은 −1 극이다.
-- 코드는 식별자로만 쓴다. 자리별 조건이 필요하면 코드를 자르지 말고 축 컬럼을 직접 본다.

-- 1) 옛 범위 제약을 먼저 뗀다 — 남겨 두면 타입을 바꿀 때 'PCLN' BETWEEN 1 AND 16 을 평가하다 깨진다.
ALTER TABLE store_profile DROP CONSTRAINT ck_store_profile_bread_type;
ALTER TABLE user_taste_profile DROP CONSTRAINT ck_user_taste_profile_type_code;

-- 2) 숫자 → 코드. 0 행이라 실제로 바뀔 값은 없지만, 값이 있어도 정확히 옮겨지게 변환식을 둔다.
--    숫자 규칙은 code = 1 + 8*(맛>0) + 4*(식감>0) + 2*(속>0) + 1*(④>0) 이었으므로 n-1 의 비트가 곧 네 축의 부호다.
ALTER TABLE store_profile ALTER COLUMN bread_type TYPE VARCHAR(4) USING (
    CASE WHEN bread_type IS NULL THEN NULL ELSE
        (CASE WHEN ((bread_type - 1) & 8) <> 0 THEN 'P' ELSE 'S' END) ||
        (CASE WHEN ((bread_type - 1) & 4) <> 0 THEN 'C' ELSE 'F' END) ||
        (CASE WHEN ((bread_type - 1) & 2) <> 0 THEN 'L' ELSE 'R' END) ||
        (CASE WHEN ((bread_type - 1) & 1) <> 0 THEN 'N' ELSE 'O' END)
    END);

ALTER TABLE user_taste_profile ALTER COLUMN type_code TYPE VARCHAR(4) USING (
    CASE WHEN type_code IS NULL THEN NULL ELSE
        (CASE WHEN ((type_code - 1) & 8) <> 0 THEN 'P' ELSE 'S' END) ||
        (CASE WHEN ((type_code - 1) & 4) <> 0 THEN 'C' ELSE 'F' END) ||
        (CASE WHEN ((type_code - 1) & 2) <> 0 THEN 'L' ELSE 'R' END) ||
        (CASE WHEN ((type_code - 1) & 1) <> 0 THEN 'N' ELSE 'O' END)
    END);

-- 3) 새 제약 — 정확히 16개 값만 들어간다. 숫자의 BETWEEN 1 AND 16 만큼 단단하다.
ALTER TABLE store_profile ADD CONSTRAINT ck_store_profile_bread_type
    CHECK (bread_type IS NULL OR bread_type ~ '^[SP][FC][RL][ON]$');
ALTER TABLE user_taste_profile ADD CONSTRAINT ck_user_taste_profile_type_code
    CHECK (type_code IS NULL OR type_code ~ '^[SP][FC][RL][ON]$');

COMMENT ON COLUMN store_profile.bread_type IS
    '이 가게가 파는 빵의 성격 — 4글자 코드. 가게의 성격이 아니라 메뉴 구성에서 나온 값이다(가게의 성격은 tags). '
    '자리마다 한 축: 1 맛 S달콤/P담백, 2 식감 F폭신/C바삭, 3 속 R꽉찬/L심플, 4 빵의 신상 O고전/N신상. '
    '예: PCLN = 담백·바삭·심플·신상. user_taste_profile.type_code 와 같은 체계라 그대로 비교한다. '
    '부호만 본다 — 축 값이 0 보다 크면 뒤 글자다. 0 에 가깝다고 앞 글자로 접지 않는다(그 버그로 6,272곳이 틀린 타입을 받았다). '
    '산출 규칙은 BE TasteAxis.typeCode() 한 곳에 있다. 식별자로만 쓰고, 자리별 조건은 코드를 자르지 말고 축 컬럼을 본다.';

COMMENT ON COLUMN user_taste_profile.type_code IS
    '이 사용자의 빵 취향 타입 — 4글자 코드. store_profile.bread_type 과 같은 체계라 그대로 비교한다. '
    '자리마다 한 축: 1 맛 S달콤/P담백, 2 식감 F폭신/C바삭, 3 속 R꽉찬/L심플, 4 빵의 신상 O고전/N신상. '
    '화면에 보이는 이름("고독한 철학자")은 #660 의 BbtiType 이 코드에서 찾는다.';

-- ══════════════════════════════════════════════════════════════════════════════
-- 3부. courses — 코스 단위 추천 출처를 뺀다
-- ══════════════════════════════════════════════════════════════════════════════
-- V20260908162423 이 courses.source 와 courses.recommendation_id 를 붙였는데, 둘 다 가게 단위 출처와 겹치고
-- 코스 단위라 정보를 잃는다.
--
-- recommendation_id — 한 코스가 추천을 여러 번 받는다. 일차마다 상권 추천을 한 번씩 받게 설계돼 있고(재사용 키에
-- day 가 있다), 같은 날에도 성수에서 1곳을 담고 다른 상권에서 몇 곳을 담을 수 있다. 한 칸에는 마지막 하나만 남는다.
-- 필요한 정보는 이미 두 곳에 있다 — 가게마다 어느 추천에서 왔는지는 course_stores.recommendation_id,
-- 이 코스에 요청된 추천 전부는 recommendation.course_id(NOT NULL).
--
-- source — 값이 RECOMMENDED 하나뿐이라 사실상 "추천을 쓴 적 있다" 는 불리언이다. course_stores 가 소프트 삭제라
-- 사용자가 뺀 가게도 recommendation_id 를 들고 남으므로 이것도 가게 단위에서 나온다. 상권 추천과 앵커 추천을
-- 가르지도 못한다 — 여정 퍼널이 실제로 필요한 그 구분은 course_stores.recommendation_id 에서 recommendation.mode 로 찾는다.
--
-- 운영 courses 730 행에서 두 컬럼 모두 값이 0 개다(2026-09-11 확인). 값을 쓰는 코드가 없었다.
-- recommendation_id 를 지우면 그 외래 키와 부분 인덱스 idx_courses_recommendation 도 같이 사라진다.
ALTER TABLE courses DROP COLUMN recommendation_id;
ALTER TABLE courses DROP COLUMN source;

-- ══════════════════════════════════════════════════════════════════════════════
-- 4부. course_area — 도보·차량 구분을 뺀다
-- ══════════════════════════════════════════════════════════════════════════════
-- V20260908162422 가 scale(WALK | CAR) 을 두고, 도보 상권은 취향 후보가 부족해도 반경을 넓히지 않고 차량 상권은
-- 1.5배씩 두 번 넓힌다는 규칙을 걸었다. 그런데 앱에 아직 지도·길찾기 기능이 없다 — 사용자가 걸어가는지 차로 가는지
-- 앱이 모르고, 보여 줄 수도 없다. 모르는 값으로 가르는 규칙은 근거 없는 분기다. 반경 확장 규칙과 앵커 추천의
-- 기본 반경은 이동 수단 없이 하나로 정한다(#661). 지도 기능이 생기면 그때 이동 수단을 사용자에게서 받는다.
--
-- radius_m(소속 판정 반경)과 max_hop_m(코스 안 인접 가게 거리 상한)은 남긴다 — 이동 수단과 무관하게 필요한 값이다.
-- course_area 는 시드 전이라 0 행이다. 컬럼을 지우면 그 CHECK(ck_course_area_scale)도 같이 사라진다.
ALTER TABLE course_area DROP COLUMN scale;
