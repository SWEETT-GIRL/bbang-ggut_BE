-- 어드민이 여러 사람에게 한 번에 보낸 편지가 어느 발송에서 나왔는지.
-- 발송 한 번은 notification 한 행(이름·푸시 문구·받은 사람)이고, 사람마다 꽂힌 편지가 그 행을 가리킨다.
-- 이 연결이 있어야 "A 설문에 누가 몇 번으로 답했나"를 서버가 셀 수 있다. 1:1 답장·사용자 편지는 비어 있다.
ALTER TABLE letter_message
	ADD COLUMN notification_id BIGINT REFERENCES notification (id);

CREATE INDEX idx_letter_message_notification
	ON letter_message (notification_id)
	WHERE notification_id IS NOT NULL;
