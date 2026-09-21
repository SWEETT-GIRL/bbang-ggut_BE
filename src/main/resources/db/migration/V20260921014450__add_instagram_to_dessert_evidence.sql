-- 디저트 근거 수집처에 인스타그램을 추가한다.
-- V20260918200000 은 이미 적용된 마이그레이션이라 파일을 고치지 않고(체크섬), 제약만 다시 건다.
-- 값을 넓히기만 하므로 기존 행이 새 제약을 위반할 일은 없다.
--
-- IG_LOCATION = 인스타 게시물의 위치 태그 좌표로 가게를 붙인 경우.
-- 블로그는 본문에서 주소 문자열을 뽑아 붙이지만 인스타는 좌표가 온다 — 매칭 경로가 달라
-- 오매칭을 추적하려면 같은 값으로 뭉뚱그리면 안 된다.
--
-- 인스타 본문도 저장하지 않는다 (저작권). 링크와 제목만 남기는 기조는 블로그와 같다.

ALTER TABLE dessert_item_evidence
    DROP CONSTRAINT ck_dessert_item_evidence_source_type;

ALTER TABLE dessert_item_evidence
    ADD CONSTRAINT ck_dessert_item_evidence_source_type
        CHECK (source_type IN ('BLOG', 'INSTAGRAM', 'USER_REPORT', 'OWNER'));

ALTER TABLE dessert_item_evidence
    DROP CONSTRAINT ck_dessert_item_evidence_match_method;

ALTER TABLE dessert_item_evidence
    ADD CONSTRAINT ck_dessert_item_evidence_match_method
        CHECK (match_method IS NULL
            OR match_method IN ('ADDRESS', 'NAME_AREA', 'LANDMARK', 'NAVER', 'BLOG_ADDRESS', 'IG_LOCATION'));
