-- 빵 랭킹 — 빵로그 태그별 평가, 공용 빵 종류, 내 빵 순위(1등 = 한 표), 가격 수정 이력. ERD 승인 10/1(이슈 #826).
-- 순위 변동용 ranking_snapshot 은 전체 순위·스냅샷 PR 에서 만든다.

-- 1. 빵로그 태그 = 평가 1개. 하트(lovedBreads)는 또 먹을래요로 합친다.
ALTER TABLE bread_record_tag
	ADD COLUMN IF NOT EXISTS again BOOLEAN;                 -- 또 먹을래요, NULL = 안 고름
ALTER TABLE bread_record_tag
	ADD COLUMN IF NOT EXISTS rating_memo VARCHAR(100);      -- 빵별 한 줄 (기록 전체 한 줄은 bread_record.memo)

CREATE TABLE IF NOT EXISTS bread_record_tag_chip (
	tag_id     BIGINT      NOT NULL REFERENCES bread_record_tag (id) ON DELETE CASCADE,
	chip_type  VARCHAR(10) NOT NULL,                        -- TEXTURE | TASTE
	chip       VARCHAR(20) NOT NULL,                        -- TextureChip·TasteChip enum 이름
	PRIMARY KEY (tag_id, chip_type, chip)
);

-- 2. 공용 빵 종류 — 기본 89종(MenuKind 에서 빵·기타 제외) + 사용자 생성
CREATE TABLE IF NOT EXISTS bread_category (
	id           BIGSERIAL   PRIMARY KEY,
	name         VARCHAR(30) NOT NULL,                      -- 보이는 이름
	name_key     VARCHAR(30) NOT NULL UNIQUE,               -- MenuNameNormalizer 규칙(영숫자·한글만, 소문자) — 같은 이름은 하나로
	menu_kind    VARCHAR(50) UNIQUE,                        -- 기본 목록이면 MenuKind 이름, 사용자 생성이면 NULL
	bread_group  VARCHAR(20),                               -- 대분류 8개(BreadGroup) — 칩 순서·썸네일용, 못 맞추면 NULL
	created_by   BIGINT      REFERENCES member (id),        -- 만든 회원, 기본 목록은 NULL
	created_at   TIMESTAMP   NOT NULL,
	updated_at   TIMESTAMP
);

-- 3. 가격 공동 수정 이력 — 빵 평가 화면의 가격 수정만 남긴다(어드민·제보 승인은 기존대로)
CREATE TABLE IF NOT EXISTS menu_price_history (
	id         BIGSERIAL PRIMARY KEY,
	menu_id    BIGINT    NOT NULL REFERENCES menu (id),
	member_id  BIGINT    NOT NULL REFERENCES member (id),
	old_price  INTEGER,
	new_price  INTEGER   NOT NULL,
	created_at TIMESTAMP NOT NULL,
	updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_menu_price_history_menu ON menu_price_history (menu_id, created_at DESC);

-- 4. 내 빵 순위 탭(순서)
CREATE TABLE IF NOT EXISTS member_bread_category (
	member_id   BIGINT    NOT NULL REFERENCES member (id),
	category_id BIGINT    NOT NULL REFERENCES bread_category (id),
	sort_order  INTEGER   NOT NULL,
	created_at  TIMESTAMP NOT NULL,
	updated_at  TIMESTAMP,
	PRIMARY KEY (member_id, category_id)
);

-- 5. 내 빵 순위 줄 — rank = 1 이 곧 그 카테고리의 한 표(표 테이블 없음). 공동 허용, NULL = 담기만 하고 아직 안 정함
CREATE TABLE IF NOT EXISTS bread_rank_entry (
	id          BIGSERIAL PRIMARY KEY,
	member_id   BIGINT    NOT NULL REFERENCES member (id),
	category_id BIGINT    NOT NULL REFERENCES bread_category (id),
	store_id    BIGINT    NOT NULL REFERENCES store (id),
	rank        INTEGER,
	ranked_at   TIMESTAMP,                                  -- 등수를 마지막으로 정한 시각 — 전체 순위 동점 정렬("가장 최근 표")
	created_at  TIMESTAMP NOT NULL,
	updated_at  TIMESTAMP,
	CONSTRAINT uq_bread_rank_entry UNIQUE (member_id, category_id, store_id)
);
CREATE INDEX IF NOT EXISTS idx_bread_rank_entry_vote ON bread_rank_entry (category_id, store_id) WHERE rank = 1;
CREATE INDEX IF NOT EXISTS idx_bread_rank_entry_member ON bread_rank_entry (member_id, category_id);

-- 6. 이관 — 하트로 고른 빵 → 같은 기록의 같은 이름 태그에 또 먹을래요 on. 하트는 태그 중에서만 고를 수 있었다
UPDATE bread_record_tag t
SET again = TRUE
FROM bread_record_loved_bread l
WHERE l.record_id = t.record_id AND l.name = t.name AND t.again IS NULL;

-- 7. 기본 빵 종류 시딩 — BreadGroup.java 의 표와 같아야 한다(BreadCategorySeedTest 가 지킨다)
INSERT INTO bread_category (name, name_key, menu_kind, bread_group, created_at) VALUES
	('소금빵', '소금빵', '소금빵', '빵', NOW()),
	('식빵', '식빵', '식빵', '빵', NOW()),
	('바게트', '바게트', '바게트', '빵', NOW()),
	('치아바타', '치아바타', '치아바타', '빵', NOW()),
	('포카치아', '포카치아', '포카치아', '빵', NOW()),
	('사워도우', '사워도우', '사워도우', '빵', NOW()),
	('깜빠뉴', '깜빠뉴', '깜빠뉴', '빵', NOW()),
	('통밀빵', '통밀빵', '통밀빵', '빵', NOW()),
	('브리오슈', '브리오슈', '브리오슈', '빵', NOW()),
	('모닝빵', '모닝빵', '모닝빵', '빵', NOW()),
	('번', '번', '번', '빵', NOW()),
	('롤', '롤', '롤', '빵', NOW()),
	('크림빵', '크림빵', '크림빵', '빵', NOW()),
	('단팥빵', '단팥빵', '단팥빵', '빵', NOW()),
	('소보로', '소보로', '소보로', '빵', NOW()),
	('앙버터', '앙버터', '앙버터', '빵', NOW()),
	('고구마빵', '고구마빵', '고구마빵', '빵', NOW()),
	('밤빵', '밤빵', '밤빵', '빵', NOW()),
	('소라빵', '소라빵', '소라빵', '빵', NOW()),
	('땅콩빵', '땅콩빵', '땅콩빵', '빵', NOW()),
	('꿀빵', '꿀빵', '꿀빵', '빵', NOW()),
	('커피콩빵', '커피콩빵', '커피콩빵', '빵', NOW()),
	('맘모스', '맘모스', '맘모스', '빵', NOW()),
	('맘모롤', '맘모롤', '맘모롤', '빵', NOW()),
	('쫀득빵', '쫀득빵', '쫀득빵', '빵', NOW()),
	('소세지빵', '소세지빵', '소세지빵', '빵', NOW()),
	('마늘빵', '마늘빵', '마늘빵', '빵', NOW()),
	('고로케', '고로케', '고로케', '빵', NOW()),
	('크로크무슈', '크로크무슈', '크로크무슈', '빵', NOW()),
	('오믈렛', '오믈렛', '오믈렛', '빵', NOW()),
	('크루아상', '크루아상', '크루아상', '크루아상_페이스트리', NOW()),
	('퀸아망', '퀸아망', '퀸아망', '크루아상_페이스트리', NOW()),
	('크로플', '크로플', '크로플', '크루아상_페이스트리', NOW()),
	('크룽지', '크룽지', '크룽지', '크루아상_페이스트리', NOW()),
	('페이스트리', '페이스트리', '페이스트리', '크루아상_페이스트리', NOW()),
	('파이', '파이', '파이', '크루아상_페이스트리', NOW()),
	('에끌레어', '에끌레어', '에끌레어', '크루아상_페이스트리', NOW()),
	('슈', '슈', '슈', '크루아상_페이스트리', NOW()),
	('베이글', '베이글', '베이글', '베이글_도넛', NOW()),
	('샌드베이글', '샌드베이글', '샌드베이글', '베이글_도넛', NOW()),
	('도넛', '도넛', '도넛', '베이글_도넛', NOW()),
	('츄러스', '츄러스', '츄러스', '베이글_도넛', NOW()),
	('프레첼', '프레첼', '프레첼', '베이글_도넛', NOW()),
	('와플', '와플', '와플', '베이글_도넛', NOW()),
	('샌드위치', '샌드위치', '샌드위치', '샌드위치_토스트', NOW()),
	('토스트', '토스트', '토스트', '샌드위치_토스트', NOW()),
	('산도', '산도', '산도', '샌드위치_토스트', NOW()),
	('케이크', '케이크', '케이크', '케이크_디저트', NOW()),
	('티라미수', '티라미수', '티라미수', '케이크_디저트', NOW()),
	('파운드', '파운드', '파운드', '케이크_디저트', NOW()),
	('프레지에', '프레지에', '프레지에', '케이크_디저트', NOW()),
	('떠먹케', '떠먹케', '떠먹케', '케이크_디저트', NOW()),
	('몽블랑', '몽블랑', '몽블랑', '케이크_디저트', NOW()),
	('카스테라', '카스테라', '카스테라', '케이크_디저트', NOW()),
	('수플레', '수플레', '수플레', '케이크_디저트', NOW()),
	('크레페', '크레페', '크레페', '케이크_디저트', NOW()),
	('타르트', '타르트', '타르트', '케이크_디저트', NOW()),
	('에그타르트', '에그타르트', '에그타르트', '케이크_디저트', NOW()),
	('푸딩', '푸딩', '푸딩', '케이크_디저트', NOW()),
	('브륄레', '브륄레', '브륄레', '케이크_디저트', NOW()),
	('초콜릿', '초콜릿', '초콜릿', '케이크_디저트', NOW()),
	('마카롱', '마카롱', '마카롱', '케이크_디저트', NOW()),
	('휘낭시에', '휘낭시에', '휘낭시에', '쿠키_구움과자', NOW()),
	('마들렌', '마들렌', '마들렌', '쿠키_구움과자', NOW()),
	('까눌레', '까눌레', '까눌레', '쿠키_구움과자', NOW()),
	('스콘', '스콘', '스콘', '쿠키_구움과자', NOW()),
	('쿠키', '쿠키', '쿠키', '쿠키_구움과자', NOW()),
	('쫀득쿠키', '쫀득쿠키', '쫀득쿠키', '쿠키_구움과자', NOW()),
	('브라우니', '브라우니', '브라우니', '쿠키_구움과자', NOW()),
	('러스크', '러스크', '러스크', '쿠키_구움과자', NOW()),
	('버터바', '버터바', '버터바', '쿠키_구움과자', NOW()),
	('비스킷', '비스킷', '비스킷', '쿠키_구움과자', NOW()),
	('티그레', '티그레', '티그레', '쿠키_구움과자', NOW()),
	('머핀', '머핀', '머핀', '쿠키_구움과자', NOW()),
	('다쿠아즈', '다쿠아즈', '다쿠아즈', '쿠키_구움과자', NOW()),
	('호두과자', '호두과자', '호두과자', '쿠키_구움과자', NOW()),
	('오란다', '오란다', '오란다', '쿠키_구움과자', NOW()),
	('떡', '떡', '떡', '떡_한과', NOW()),
	('버터떡', '버터떡', '버터떡', '떡_한과', NOW()),
	('약과', '약과', '약과', '떡_한과', NOW()),
	('화과자', '화과자', '화과자', '떡_한과', NOW()),
	('모나카', '모나카', '모나카', '떡_한과', NOW()),
	('만쥬', '만쥬', '만쥬', '떡_한과', NOW()),
	('한과', '한과', '한과', '떡_한과', NOW()),
	('붕어빵', '붕어빵', '붕어빵', '떡_한과', NOW()),
	('계란빵', '계란빵', '계란빵', '떡_한과', NOW()),
	('빙수', '빙수', '빙수', '빙수_아이스', NOW()),
	('아이스크림', '아이스크림', '아이스크림', '빙수_아이스', NOW()),
	('요거트', '요거트', '요거트', '빙수_아이스', NOW())
ON CONFLICT (name_key) DO NOTHING;
