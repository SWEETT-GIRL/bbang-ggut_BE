-- 빵BTI 타입 코드를 16종에서 48종으로 넓힌다 (#760 후속 · 검사 다중 갈래).
--
-- 검사 문항이 축마다 두 갈래(A/B)에서 갈래 이름으로 바뀌면서, 맛이 넷(달콤·담백·짭짤·풍미)
-- 식감이 셋(부드·바삭·쫄깃)이 됐다. 속·스타일은 그대로 둘이다.
--
--   자리  축        글자
--   1     맛        S 달콤 · P 담백 · T 짭짤 · M 풍미
--   2     식감      F 부드 · C 바삭 · Y 쫄깃
--   3     속        R 꽉 찬 · L 심플        (그대로)
--   4     스타일    O 클래식 · N 트렌디     (그대로)
--
-- 기존 글자의 뜻은 하나도 바꾸지 않았고 새 갈래만 더했다. 그래서 48종이 16종을 그대로 품고,
-- 저장된 옛 코드(SFRO 등)가 전부 유효하다. 넓히는 것이라 기존 행을 건드릴 필요가 없다.
--
-- 짭짤을 S(salty), 풍미를 R(rich) 로 두면 달콤·꽉 찬과 부딪혀서 T·M 을 골랐다. 그 결과
-- 자리마다 글자 집합이 겹치지 않아({S,P,T,M} {F,C,Y} {R,L} {O,N}) 자리를 몰라도 글자만 보고
-- 어느 축인지 안다.
--
-- 가게(store_profile.bread_type)도 같이 넓힌다. 아직 dataops 가 16종만 넣지만, 제약을 먼저
-- 풀어 두지 않으면 라벨 적재(PR2)가 CHECK 에 걸려 멈춘다. 제약을 넓히는 것은 기존 값을
-- 건드리지 않으므로 지금 해도 안전하다. 두 표가 같은 규칙을 쓴다는 약속은 그대로다.

ALTER TABLE user_taste_profile DROP CONSTRAINT ck_user_taste_profile_type_code;
ALTER TABLE user_taste_profile ADD CONSTRAINT ck_user_taste_profile_type_code
    CHECK (type_code IS NULL OR type_code ~ '^[SPTM][FCY][RL][ON]$');

ALTER TABLE store_profile DROP CONSTRAINT ck_store_profile_bread_type;
ALTER TABLE store_profile ADD CONSTRAINT ck_store_profile_bread_type
    CHECK (bread_type IS NULL OR bread_type ~ '^[SPTM][FCY][RL][ON]$');

-- 검사 판 번호. 1 = 축마다 두 갈래(A/B) 4·8문항, 2 = 갈래 이름 11개.
-- quiz_answers 의 choice 값으로도 갈래를 가릴 수 있지만, 판이 하나 더 생기면 값만으로는
-- 구분되지 않는다. 검사를 푼 회원이 0명인 지금 넣어 두는 것이 가장 싸다.
ALTER TABLE user_taste_profile ADD COLUMN quiz_version SMALLINT;

COMMENT ON COLUMN user_taste_profile.quiz_version IS
    '이 회원이 푼 빵BTI 검사의 판. 1 = 축마다 두 갈래(A/B), 2 = 갈래 이름 11개(맛 4 · 식감 3 · 속 2 · 스타일 2). '
    'quiz_answers 가 비어 있으면 NULL. 앱이 요청 본문의 quizVersion 으로 보낸 값을 그대로 적는다.';

COMMENT ON COLUMN user_taste_profile.type_code IS
    '이 사용자의 빵 취향 타입 — 4글자 코드 48종. 자리마다 한 축: 1 맛 S달콤/P담백/T짭짤/M풍미, '
    '2 식감 F부드/C바삭/Y쫄깃, 3 속 R꽉찬/L심플, 4 스타일 O클래식/N트렌디. '
    '검사를 푼 회원은 고른 갈래가 맛·식감 글자를 정하고, 신호만 있는 회원은 축 부호로 정해져 옛 16종 안에 남는다 '
    '(짭짤·풍미·쫄깃은 −1~+1 축 하나로 표현되지 않아 신호만으로는 나올 수 없다). '
    'store_profile.bread_type 과 같은 체계다. 코드는 식별자로만 쓰고, 자리별 조건이 필요하면 코드를 자르지 말고 축 컬럼을 본다.';

COMMENT ON COLUMN store_profile.bread_type IS
    '이 가게가 파는 빵의 성격 — 4글자 코드 48종. 가게의 성격이 아니라 메뉴 구성에서 나온 값이다(가게의 성격은 tags). '
    '자리마다 한 축: 1 맛 S달콤/P담백/T짭짤/M풍미, 2 식감 F부드/C바삭/Y쫄깃, 3 속 R꽉찬/L심플, 4 스타일 O클래식/N트렌디. '
    'user_taste_profile.type_code 와 같은 체계라 그대로 비교한다. '
    '부호만 본다 — 축 값이 0 보다 크면 뒤 글자다. 0 에 가깝다고 앞 글자로 접지 않는다(그 버그로 6,272곳이 틀린 타입을 받았다). '
    'T·M·Y 는 dataops 가 라벨을 적재한 뒤에야 나온다 — 그 전까지 가게는 16종 안에 있다.';
