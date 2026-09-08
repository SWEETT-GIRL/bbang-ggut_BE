-- 큐레이션 빵투어의 분류. 지금까지 탐색 탭의 유일한 축은 가게 주소에서 파생한 시·도였고,
-- 대학가처럼 주소로는 못 뽑는 묶음(홍대·건대)을 어드민이 손으로 지정하려고 받는다.
--
-- category_group : 탐색 탭 토글에 그대로 뜨는 축 이름("대학가"). 앱에 축 이름을 박지 않으려고 값으로 갖는다 —
--                  다음 특집을 앱 배포 없이 데이터만으로 늘리기 위해서다.
-- category       : 그 축 안의 값. 필터 pill 이름이 된다("홍대").
--
-- 둘 다 nullable — 기존 코스와 유저 코스는 분류가 없다. 한쪽만 채운 값은 서버가 막는다(둘 다 있거나 둘 다 없거나).
-- 코스 수가 작고 목록 API 가 공개 코스를 통째로 내려주므로 인덱스는 걸지 않는다.
ALTER TABLE courses ADD COLUMN category_group VARCHAR(20);
ALTER TABLE courses ADD COLUMN category       VARCHAR(20);
