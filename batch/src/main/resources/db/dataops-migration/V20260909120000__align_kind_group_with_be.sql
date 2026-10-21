-- kind_group 을 본 앱 정본(menu/enums/MenuCategory, '트렌드 대분류')에 맞춘다.
-- 09-04 초안에서 임의로 붙였던 값 31종이 정본과 달랐다(단과자빵·떡한과·식빵·초콜릿 대분류 자체가 없었음).
-- kind_group 은 검수 표의 참고 열일 뿐 축·타입 계산에는 쓰이지 않는다 — 팀과 같은 언어를 쓰기 위한 정렬.
-- 출처: bbang-ggut_BE origin/dev MenuCategory (21개 대분류, MenuKind 89종). 담기지 않은 종류는 NULL.
UPDATE menu_kind_axis SET kind_group = NULL;
UPDATE menu_kind_axis SET kind_group = '구움과자' WHERE kind IN ('까눌레', '다쿠아즈', '러스크', '마들렌', '머핀', '버터바', '브라우니', '비스킷', '스콘', '오란다', '쫀득쿠키', '쿠키', '티그레', '호두과자', '휘낭시에');
UPDATE menu_kind_axis SET kind_group = '단과자빵' WHERE kind IN ('계란빵', '고구마빵', '꿀빵', '단팥빵', '땅콩빵', '밤빵', '붕어빵', '소라빵', '소보로', '앙버터', '커피콩빵', '크림빵');
UPDATE menu_kind_axis SET kind_group = '도넛' WHERE kind IN ('도넛', '와플', '츄러스');
UPDATE menu_kind_axis SET kind_group = '떡한과' WHERE kind IN ('떡', '만쥬', '모나카', '버터떡', '약과', '한과', '화과자');
UPDATE menu_kind_axis SET kind_group = '마카롱' WHERE kind IN ('마카롱');
UPDATE menu_kind_axis SET kind_group = '맘모스' WHERE kind IN ('맘모롤', '맘모스');
UPDATE menu_kind_axis SET kind_group = '베이글' WHERE kind IN ('베이글', '샌드베이글');
UPDATE menu_kind_axis SET kind_group = '빙수' WHERE kind IN ('빙수', '아이스크림', '요거트');
UPDATE menu_kind_axis SET kind_group = '샌드위치' WHERE kind IN ('샌드위치', '크로크무슈', '토스트');
UPDATE menu_kind_axis SET kind_group = '소금빵' WHERE kind IN ('소금빵');
UPDATE menu_kind_axis SET kind_group = '식빵' WHERE kind IN ('식빵');
UPDATE menu_kind_axis SET kind_group = '조리빵' WHERE kind IN ('고로케', '롤', '마늘빵', '모닝빵', '번', '소세지빵');
UPDATE menu_kind_axis SET kind_group = '쫀득빵' WHERE kind IN ('쫀득빵');
UPDATE menu_kind_axis SET kind_group = '초콜릿' WHERE kind IN ('초콜릿');
UPDATE menu_kind_axis SET kind_group = '케이크' WHERE kind IN ('떠먹케', '몽블랑', '산도', '수플레', '오믈렛', '카스테라', '케이크', '크레페', '티라미수', '파운드', '프레지에');
UPDATE menu_kind_axis SET kind_group = '크루아상' WHERE kind IN ('퀸아망', '크로플', '크루아상', '크룽지');
UPDATE menu_kind_axis SET kind_group = '타르트' WHERE kind IN ('에그타르트', '타르트');
UPDATE menu_kind_axis SET kind_group = '파이' WHERE kind IN ('파이');
UPDATE menu_kind_axis SET kind_group = '페이스트리' WHERE kind IN ('슈', '에끌레어', '페이스트리');
UPDATE menu_kind_axis SET kind_group = '푸딩' WHERE kind IN ('브륄레', '푸딩');
UPDATE menu_kind_axis SET kind_group = '하드빵' WHERE kind IN ('깜빠뉴', '바게트', '브리오슈', '사워도우', '치아바타', '통밀빵', '포카치아', '프레첼');
