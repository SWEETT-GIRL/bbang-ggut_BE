CREATE TABLE member (id bigint PRIMARY KEY);
CREATE TABLE bread_record (
    id bigint PRIMARY KEY, member_id bigint, store_id bigint, store_name varchar(100),
    region_key varchar(100), memo varchar(500), loved_bread varchar(100), tower_bread varchar(20),
    eaten_at timestamp NOT NULL, deleted_at timestamp, is_public boolean DEFAULT false,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_at timestamp
);
CREATE TABLE bread_record_photo (
    id bigint PRIMARY KEY, record_id bigint, sort_order int, name varchar(255) DEFAULT 'photo',
    mime_type varchar(50) DEFAULT 'IMAGE_JPEG', aws_key varchar(255), processing_status varchar(20) DEFAULT 'PENDING',
    resized_key_320 varchar(255), resized_key_640 varchar(255), resized_key_1024 varchar(255),
    width int, height int, created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_at timestamp
);
CREATE TABLE bread_record_loved_bread (record_id bigint, sort_order int, name varchar(100));
CREATE TABLE menu_category (
    id bigint PRIMARY KEY, name varchar(50), axis_taste smallint, axis_texture smallint,
    axis_filling smallint, axis_vibe smallint
);
CREATE TABLE menu (
    id bigint PRIMARY KEY, store_id bigint, menu_category_id bigint, name varchar(100), normalized_name varchar(100),
    created_by_member_id bigint, price int, like_count int DEFAULT 0, deleted_at timestamp,
    description varchar(255), display_order int, is_bakery_item boolean, classified_at timestamp,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_at timestamp
);
CREATE TABLE bread_record_tag (
    id bigint PRIMARY KEY, record_id bigint, photo_id bigint, name varchar(100), bread_category varchar(50),
    menu_id bigint, suggested_menu_id bigint, x double precision DEFAULT 0, y double precision DEFAULT 0,
    style varchar(50), created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_at timestamp
);

INSERT INTO member VALUES (123), (456);
INSERT INTO menu_category (id, name) VALUES (1, '소금빵'), (2, '기타');
INSERT INTO menu (id, menu_category_id, name, normalized_name) VALUES (1, 1, '버터소금빵', '버터소금빵'), (2, 2, '빵', '빵');
INSERT INTO bread_record (id, member_id, eaten_at, is_public, deleted_at) VALUES
    (1, 123, '2025-12-01 00:00:00', false, NULL),
    (2, 123, '2025-12-31 23:59:59', false, NULL),
    (3, 123, '2025-12-31 23:59:59', true, NULL),
    (4, 123, '2026-01-01 00:00:00', true, NULL),
    (5, 123, '2025-11-30 23:59:59', true, NULL),
    (6, 456, '2025-12-31 23:59:59', true, NULL),
    (7, 123, '2025-12-31 23:59:59', true, TIMESTAMP '2025-12-31 23:59:59');
INSERT INTO bread_record_photo (id, record_id, sort_order, aws_key, resized_key_640) VALUES
    (1, 1, 0, 'old-salt', NULL),
    (2, 2, 0, 'newer-salt', NULL),
    (3, 3, 1, 'second-photo', NULL),
    (4, 3, 0, 'first-photo', 'first-photo-640'),
    (5, 3, 0, 'tied-photo', NULL),
    (6, 4, 0, 'next-month', NULL),
    (7, 5, 0, 'previous-month', NULL),
    (8, 6, 0, 'foreign', NULL),
    (9, 7, 0, 'deleted', NULL);
INSERT INTO bread_record_tag (id, record_id, photo_id, name, bread_category, menu_id) VALUES
    (1, 1, 1, '소금빵', '소금빵', NULL),
    (2, 2, 2, '버터소금빵', '빵', 1),
    (3, 3, 3, '소금빵', '소금빵', NULL),
    (4, 3, 4, '소금빵', '소금빵', NULL),
    (5, 3, 5, '소금빵', '소금빵', NULL),
    (6, 1, 1, '식빵', '식빵', 1),
    (7, 1, 1, '베이글', '베이글', NULL),
    (8, 1, 1, '알 수 없는 빵', '기타', NULL),
    (9, 1, 1, '알 수 없는 빵', '빵', 2),
    (10, 4, 6, '소금빵', '소금빵', NULL),
    (11, 5, 7, '소금빵', '소금빵', NULL),
    (12, 6, 8, '소금빵', '소금빵', NULL),
    (13, 7, 9, '소금빵', '소금빵', NULL),
    (14, 3, 8, '잘못 연결된 사진', '소금빵', NULL);

UPDATE bread_record SET created_at = eaten_at, store_name = '같은 이름';

CREATE TABLE courses (
    id bigint PRIMARY KEY, member_id bigint, created_at timestamp NOT NULL, deleted_at timestamp
);
CREATE TABLE course_stores (
    id bigint PRIMARY KEY, course_id bigint, store_id bigint, created_at timestamp NOT NULL, deleted_at timestamp
);

CREATE TABLE saved_stores (
    id bigint PRIMARY KEY, member_id bigint, store_id bigint, created_at timestamp NOT NULL, updated_at timestamp
);
CREATE TABLE store_plan (
    member_id bigint, store_id bigint, created_at timestamp NOT NULL, updated_at timestamp,
    memo varchar(200), PRIMARY KEY (member_id, store_id)
);
