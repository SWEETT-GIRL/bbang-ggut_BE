-- MenuKind enum(65종)과 menu_category 테이블(38행) 정합 — 미시딩 27종 추가 (협의 안건 ④)
-- 테이블 CHECK 제약(menu_category_name_check)에는 65종이 이미 등록돼 있어 행 시딩만 빠진 상태.
-- 멱등: 이미 있는 이름은 건너뛴다. id 는 identity 라 name 만 INSERT.
INSERT INTO menu_category (name)
SELECT v.name
FROM (VALUES
    ('티라미수'), ('크로플'), ('아이스크림'), ('브륄레'), ('버터떡'), ('요거트'), ('빵'),
    ('크로크무슈'), ('파운드'), ('쫀득쿠키'), ('번'), ('롤'), ('버터바'), ('에끌레어'), ('마카롱'),
    ('슈'), ('프레첼'), ('몽블랑'), ('고로케'), ('프레지에'), ('비스킷'), ('티그레'), ('크림빵'),
    ('샌드베이글'), ('떠먹케'), ('와플'), ('맘모롤')
) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM menu_category mc WHERE mc.name = v.name);
