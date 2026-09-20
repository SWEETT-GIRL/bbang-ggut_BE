-- user_taste_profile.inferred_kinds — 좋아한 가게들의 대표 종류로 추정한 회원 종류 (#762).
--
-- top_kinds 는 빵 기록(RECORD_BREAD)에서만 나와 기록을 쓰는 회원만 갖는다(랩 09-20: 강한 신호 가게 4곳 이상 회원 32%).
-- 찜 · 코스 담기 · 기록한 가게들의 store_profile.signature_kinds 를 세어, 전체 빵집 비율보다 유난히 많은 종류(lift)를 남긴다.
-- 근처 추천 배지의 "{종류} 취향" 을 기록이 없는 회원에게도 붙이는 재료다. 기록 종류와는 따로 둔다 — 기록이 더 강한 근거다.
--
-- NOT NULL DEFAULT '{}' — 추정할 근거가 없으면 빈 배열. 기존 행은 다음 재계산에서 채워진다(그 전까지 배지는 지금과 같다).
ALTER TABLE user_taste_profile ADD COLUMN inferred_kinds TEXT[] NOT NULL DEFAULT '{}';
