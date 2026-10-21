-- 편지에 붙인 사진. 한 통에 여러 장(최대 5장), 고른 순서를 지킨다.
CREATE TABLE letter_message_image (
	message_id BIGINT       NOT NULL REFERENCES letter_message (id) ON DELETE CASCADE,
	sort_order INT          NOT NULL,
	image_key  VARCHAR(512) NOT NULL,
	PRIMARY KEY (message_id, sort_order)
);
