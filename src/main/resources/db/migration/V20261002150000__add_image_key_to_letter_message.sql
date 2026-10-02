-- 편지에 사진 한 장을 붙일 수 있다. 사용자가 문의하며 화면 캡처 등을 보낸다.
-- 사진 없는 편지가 대부분이라 nullable.
ALTER TABLE letter_message ADD COLUMN image_key VARCHAR(512);
