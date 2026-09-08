-- V20260909110000 시드 되돌리기 — menu_category 4축 값을 다시 비운다.
--
-- 왜: 그 시드는 매핑표 draft v2 값인데, 팀 검수에서 두 가지가 드러났다.
--   ① 속(filling) 채점 오류 — 88종 중 55종을 '풍부'로 매겼다. 기준이 "속 재료가 있느냐"였는데
--      물어야 할 것은 "한 입에 속이 주인공이냐"였다. 재채점 결과 20종 40,631건이 풍부 → 심플로 뒤집힌다.
--   ② 축 구조 자체가 검토 중 — 맛 담백 극이 짭짤(64,412건)과 진짜 담백(71,620건)을 뭉치고 있고,
--      식감 부드↔바삭에 쫄깃(89,449건)이 갈 곳이 없다. 다중 라벨안(v3)을 팀 안건으로 올린 상태다.
--      (dataops docs/course-reco/axis_v3_proposal.md · axis_v3_eyeball.md)
--
-- 틀린 값이 남아 있는 것보다 비어 있는 편이 낫다. 축을 쓰는 곳(사용자 취향 프로필·후보 SQL)은 아직 없다.
-- 파일을 지우지 않고 되돌리는 마이그레이션을 얹는 이유: 이미 적용한 환경(dev DB 등)에서 Flyway validate 가 깨지지 않게.
-- 축 설계가 확정되면 그때 새 시드를 올린다.
UPDATE menu_category
SET axis_taste = NULL, axis_texture = NULL, axis_filling = NULL, axis_vibe = NULL;
