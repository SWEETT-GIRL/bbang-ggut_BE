-- MenuKind 머핀·다쿠아즈 추가 (dataops 백필 실측: 머핀 이름 1,222건 · 다쿠아즈 657건이 '기타'로 수납 중)
-- 주의: 롤링 배포 중 구버전 서버가 새 kind 행을 읽으면 enum 역직렬화가 깨지므로 저트래픽 시간대 배포.

-- 1) name CHECK 제약 갱신 — 기존 65종 + 머핀 + 다쿠아즈
ALTER TABLE menu_category DROP CONSTRAINT menu_category_name_check;
ALTER TABLE menu_category ADD CONSTRAINT menu_category_name_check CHECK (name::text = ANY (ARRAY[
    '소금빵', '식빵', '바게트', '크루아상', '퀸아망', '치아바타', '포카치아', '사워도우', '깜빠뉴',
    '베이글', '샌드위치', '토스트', '쫀득빵', '맘모스', '앙버터', '소보로', '브리오슈', '페이스트리',
    '스콘', '에그타르트', '타르트', '휘낭시에', '마들렌', '까눌레', '쿠키', '브라우니', '케이크',
    '푸딩', '러스크', '파이', '도넛', '떡', '소세지빵', '마늘빵', '고구마빵', '밤빵', '빙수', '기타',
    '티라미수', '크로플', '아이스크림', '브륄레', '버터떡', '요거트', '빵', '크로크무슈', '파운드',
    '쫀득쿠키', '번', '롤', '버터바', '에끌레어', '마카롱', '슈', '프레첼', '몽블랑', '고로케',
    '프레지에', '비스킷', '티그레', '크림빵', '샌드베이글', '떠먹케', '와플', '맘모롤',
    '머핀', '다쿠아즈'
]::text[]));

-- 2) 행 시딩 (멱등)
INSERT INTO menu_category (name)
SELECT v.name FROM (VALUES ('머핀'), ('다쿠아즈')) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM menu_category mc WHERE mc.name = v.name);
