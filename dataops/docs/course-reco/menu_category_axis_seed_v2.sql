-- 본 앱 menu_category 빵BTI 4축 시드 (값: dataops, 마이그레이션: BE) — 설계 v2 §4
-- 규약: -1 = A극(달콤·부드·풍부·클래식) / +1 = B극(담백·바삭·심플·트렌디) / NULL = 축 없음(빵·기타·요거트)
-- 대상 88종 (매핑표 draft v2, 2026-09-09). 컬럼은 V20260908162421 에서 이미 추가됨 — UPDATE 만 한다.
-- 확신도 '낮음' 행은 팀 검수 대상: 같은 SQL 을 다시 돌리면 되므로 값 수정 후 재배포 가능.

UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '고구마빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '까눌레';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '꿀빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '땅콩빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '러스크';  -- 달콤·바삭·심플·클래식 → 7 우아한 고집 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '마늘빵';  -- 담백·바삭·풍부·클래식 → 13 날카로운 감각 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '만쥬';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '맘모롤';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '모나카';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '모닝빵';  -- 담백·부드·심플·클래식 → 11 수수한 일상 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '밤빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '버터떡';  -- 담백·부드·풍부·트렌디 → 10 경계를 허무는 자 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '베이글';  -- 담백·바삭·심플·트렌디 → 16 뉴웨이브 미니멀 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '붕어빵';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '브라우니';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '브리오슈';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '산도';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '샌드베이글';  -- 담백·바삭·풍부·트렌디 → 14 세련된 반전 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '소금빵';  -- 담백·바삭·심플·트렌디 → 16 뉴웨이브 미니멀 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '소라빵';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '수플레';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '슈';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '약과';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '에끌레어';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '와플';  -- 달콤·바삭·심플·트렌디 → 8 실험적 예술가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '츄러스';  -- 달콤·바삭·심플·트렌디 → 8 실험적 예술가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '쿠키';  -- 달콤·바삭·심플·클래식 → 7 우아한 고집 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '퀸아망';  -- 달콤·바삭·심플·트렌디 → 8 실험적 예술가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '크레페';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '크로플';  -- 달콤·바삭·심플·트렌디 → 8 실험적 예술가 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '크루아상';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '크룽지';  -- 달콤·바삭·심플·트렌디 → 8 실험적 예술가 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '토스트';  -- 담백·부드·풍부·클래식 → 9 든든한 현실주의자 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '파운드';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '파이';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '푸딩';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '프레지에';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '프레첼';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 중간
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '호두과자';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 중간
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '고로케';  -- 담백·바삭·풍부·클래식 → 13 날카로운 감각 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '깜빠뉴';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '단팥빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '마들렌';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '맘모스';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '바게트';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '샌드위치';  -- 담백·부드·풍부·클래식 → 9 든든한 현실주의자 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '소세지빵';  -- 담백·부드·풍부·클래식 → 9 든든한 현실주의자 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '식빵';  -- 담백·부드·심플·클래식 → 11 수수한 일상 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '에그타르트';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '쫀득쿠키';  -- 달콤·바삭·풍부·트렌디 → 6 트렌드세터 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '치아바타';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '카스테라';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '케이크';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '크림빵';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '타르트';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '통밀빵';  -- 담백·부드·심플·클래식 → 11 수수한 일상 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '티그레';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '티라미수';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '페이스트리';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '포카치아';  -- 담백·부드·풍부·클래식 → 9 든든한 현실주의자 · 높음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '휘낭시에';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 높음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '계란빵';  -- 담백·부드·풍부·클래식 → 9 든든한 현실주의자 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '다쿠아즈';  -- 달콤·바삭·풍부·트렌디 → 6 트렌드세터 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '도넛';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '떠먹케';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음
UPDATE menu_category SET axis_taste = +1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '떡';  -- 담백·부드·심플·클래식 → 11 수수한 일상 · 낮음 · 빵 아님
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '롤';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '마카롱';  -- 달콤·바삭·풍부·트렌디 → 6 트렌드세터 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = -1 WHERE name = '머핀';  -- 달콤·부드·심플·클래식 → 3 조용한 감성파 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '몽블랑';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '버터바';  -- 달콤·바삭·풍부·트렌디 → 6 트렌드세터 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '번';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '브륄레';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 낮음
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = +1 WHERE name = '비스킷';  -- 담백·바삭·심플·트렌디 → 16 뉴웨이브 미니멀 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '빙수';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음 · 빵 아님
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '사워도우';  -- 담백·바삭·심플·클래식 → 15 고독한 철학자 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '소보로';  -- 달콤·바삭·심플·클래식 → 7 우아한 고집 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '스콘';  -- 달콤·바삭·심플·클래식 → 7 우아한 고집 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '아이스크림';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음 · 빵 아님
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = +1 WHERE name = '앙버터';  -- 달콤·바삭·풍부·트렌디 → 6 트렌드세터 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = +1, axis_vibe = -1 WHERE name = '오란다';  -- 달콤·바삭·심플·클래식 → 7 우아한 고집 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '오믈렛';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = +1, axis_vibe = +1 WHERE name = '쫀득빵';  -- 달콤·부드·심플·트렌디 → 4 가벼운 구름 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = +1 WHERE name = '초콜릿';  -- 달콤·부드·풍부·트렌디 → 2 화려한 몽상가 · 낮음 · 빵 아님
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '커피콩빵';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 낮음
UPDATE menu_category SET axis_taste = +1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '크로크무슈';  -- 담백·바삭·풍부·클래식 → 13 날카로운 감각 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = +1, axis_filling = -1, axis_vibe = -1 WHERE name = '한과';  -- 달콤·바삭·풍부·클래식 → 5 완벽주의 미식가 · 낮음
UPDATE menu_category SET axis_taste = -1, axis_texture = -1, axis_filling = -1, axis_vibe = -1 WHERE name = '화과자';  -- 달콤·부드·풍부·클래식 → 1 따뜻한 포옹 · 낮음

