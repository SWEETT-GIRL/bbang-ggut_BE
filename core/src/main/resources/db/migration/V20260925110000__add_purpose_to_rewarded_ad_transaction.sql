-- 보상형 광고 이력에 "무엇에 쓴 광고인가"를 남긴다.
--
-- 닉네임 뽑기 광고도 같은 SSV 콜백으로 온다. 지금까지는 character_code 가 NOT NULL 이라 이 표에 못 넣어,
-- 구글이 재시도하면 "오늘 이미 뽑았나"로만 막고 있었고 정산 대조도 로그로 했다.
-- 이제 닉네임 광고도 transaction_id PK 로 중복을 막고, 정산 대조를 한 표에서 한다.
--
--   CHARACTER — 친구 깨우기. character_code 가 반드시 있다(아래 CHECK).
--   NICKNAME  — 닉네임 뽑기. 친구가 없으니 character_code 는 비운다.
--
-- 기존 행은 모두 친구 광고라 DEFAULT 'CHARACTER' 로 채워진다. 외래키(world_characters)는 그대로 둔다.
ALTER TABLE rewarded_ad_transaction
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'CHARACTER';

ALTER TABLE rewarded_ad_transaction
    ALTER COLUMN character_code DROP NOT NULL;

ALTER TABLE rewarded_ad_transaction
    ADD CONSTRAINT chk_rewarded_ad_transaction_character
        CHECK (purpose <> 'CHARACTER' OR character_code IS NOT NULL);
