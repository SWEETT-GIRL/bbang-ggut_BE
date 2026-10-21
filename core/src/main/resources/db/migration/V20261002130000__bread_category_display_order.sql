-- 공용 빵 종류 목록의 기본 8종 고정 자리 (#843). 앱은 목록 앞 8개 기본 빵을 타일로 보여 준다.
-- 투표가 0 이면 id(MenuKind 선언) 순이라 소금빵·식빵·바게트·… 가 뜨던 것을, 지정한 8종이 이 순서로 먼저 오고
-- 나머지는 1등 매긴 사람 수 순으로 뒤따르게 한다. name_key 는 한글 이름 그대로다(MenuNameNormalizer).
ALTER TABLE bread_category ADD COLUMN IF NOT EXISTS display_order INTEGER;

UPDATE bread_category c
SET display_order = v.ord
FROM (VALUES
	('소금빵', 1), ('케이크', 2), ('사워도우', 3), ('휘낭시에', 4),
	('샌드베이글', 5), ('에그타르트', 6), ('크루아상', 7), ('도넛', 8)
) AS v(name_key, ord)
WHERE c.name_key = v.name_key AND c.menu_kind IS NOT NULL;
