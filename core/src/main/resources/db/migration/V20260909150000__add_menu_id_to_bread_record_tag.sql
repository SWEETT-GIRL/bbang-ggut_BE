-- 빵로그 태그가 "무슨 빵인지"를 적어 두는 대신 "어느 메뉴인지"를 가리키게 한다.
--
-- 왜 — bread_category 는 태그를 저장하는 순간의 룰 판정을 문자열로 굳힌다. 그래서 그 뒤에
-- 메뉴가 AI 로 분류되거나 어드민이 카테고리를 고쳐도 태그는 옛 값 그대로다.
-- 실측(2026-09-09): 캐시에 매칭된 태그 678건 중 668건(98.5%)이 태그보다 나중에 분류됐다.
-- 앱이 메뉴를 먼저 만들지만 AI 분류는 스케줄러가 1분 뒤에 하기 때문 — 처음 보는 이름은
-- 영영 '기타'로 남는다. 메뉴를 가리키면 분류가 갱신될 때 태그도 같이 따라온다.
--
-- bread_category 는 지운 게 아니라 폴백으로 남는다. 메뉴를 못 찾은 태그(가게를 안 고른 기록 등)는
-- 그 값이 유일한 답이고, 룰이 구체 kind 를 낸 태그도 그 값을 그대로 쓴다.
-- 메뉴를 보는 건 룰이 '빵'·'기타'로 물러섰을 때뿐이다.

ALTER TABLE bread_record_tag
    ADD COLUMN menu_id BIGINT REFERENCES menu (id);

-- 기존 태그 백필 — 같은 가게에서 정규화 이름이 같은 활성 메뉴.
-- 정규화 식은 uq_menu_store_normalized_name 인덱스·MenuNameNormalizer 와 같은 식이어야 한다.
-- 실측: 1,006건 중 944건(93.8%) 매칭, 한 태그에 두 개 이상 걸리는 경우 0건.
UPDATE bread_record_tag tag
SET menu_id = menu.id
FROM bread_record record, menu
WHERE record.id = tag.record_id
  AND record.store_id IS NOT NULL
  AND menu.store_id = record.store_id
  AND menu.deleted_at IS NULL
  AND menu.normalized_name = lower(regexp_replace(tag.name, '[^[:alnum:]]+', '', 'g'));

-- 태그에서 메뉴로 가는 조회(목록 응답의 LEFT JOIN FETCH)만 쓰므로 menu_id 단독으로 충분하다.
CREATE INDEX idx_bread_record_tag_menu ON bread_record_tag (menu_id) WHERE menu_id IS NOT NULL;
