-- 빵투어 보상(리워드 2.0)이 판정에 쓰는 두 칸.
--
-- first_visited_on — 이 회원이 이 빵투어에서 처음 "다녀왔어요"를 누른 한국 날짜.
--   날짜를 안 정한 빵투어와 담은(남이 만든) 빵투어는 이 날과 다음 날에만 다녀옴 보상을 준다.
--   started_at 으로는 대신할 수 없다 — 그건 "시작하기"를 누른 때라, 시작만 해 두고 며칠 뒤에 처음 다녀올 수 있다.
--   이 값이 있는 행만 사진 완주를 판정한다 — 리워드가 생기기 전에 끝낸 빵투어에 뒤늦게 보상이 나가지 않는다.
-- photo_completed_at — 모든 가게에 사진 붙은 기록이 달린 때. 보상을 받았든 그 주에 이미 받아 못 받았든 한 번 찍힌다.
--   다시 판정하지 않기 위한 표시다(못 받은 사진 완주 보상이 다음 주에 뒤늦게 나가지 않게).
ALTER TABLE course_progress ADD COLUMN first_visited_on DATE;
ALTER TABLE course_progress ADD COLUMN photo_completed_at TIMESTAMP;
