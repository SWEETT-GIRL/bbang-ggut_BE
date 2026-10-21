-- 레벨 = 기록한 빵 개수(리워드 2.0). 첫 기록에 바로 다음 레벨이 보이도록 지구의 두 단계만 앞당긴다.
--   하늘 3 → 1, 우주 7 → 5
-- 기록 1~2 개인 회원은 마을에서 하늘로 올라간다. 내려가는 회원은 없다.
-- 어드민이 이미 값을 바꿨다면 건드리지 않는다(옛 값일 때만 고친다).
UPDATE world_planet_stages SET offset_at = 1 WHERE planet_code = 'earth' AND code = 'sky' AND offset_at = 3;
UPDATE world_planet_stages SET offset_at = 5 WHERE planet_code = 'earth' AND code = 'space' AND offset_at = 7;

-- 앱과 서버가 들고 있는 카탈로그를 다시 받게 한다.
UPDATE world_catalog SET version = version + 1, updated_at = CURRENT_TIMESTAMP;
