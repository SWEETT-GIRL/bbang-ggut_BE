-- 문의 유형. 대화방이 사람당 하나라 한 방에 여러 주제가 섞이는데, 유형이 메시지에 붙어 있어야
-- 어드민이 "이번 건이 뭐였는지"를 안다.
--
-- nullable인 이유: 유형은 말을 처음 걸 때 한 번만 고르고, 이어지는 메시지에는 앱이 같은 값을
-- 실어 보낸다. 그래도 유형 없이 들어온 옛 메시지나 어드민 답장이 있어서 NOT NULL로 못 묶는다.
ALTER TABLE letter_message ADD COLUMN category VARCHAR(20);

-- 어드민 목록이 스레드마다 "최근 사용자 메시지의 유형"을 뱃지로 띄운다.
CREATE INDEX idx_letter_message_category ON letter_message (thread_id, sender, created_at DESC)
	WHERE category IS NOT NULL;
