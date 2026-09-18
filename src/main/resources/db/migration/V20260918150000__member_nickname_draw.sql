-- 닉네임 뽑기: 한국 날짜로 하루 한 번 새 닉네임 후보를 뽑고, 수락해야 닉네임이 바뀐다. "원래대로"를 눌러도 그날 기회는 쓴 것이다.
ALTER TABLE member ADD COLUMN pending_nickname VARCHAR(100);
ALTER TABLE member ADD COLUMN nickname_drawn_at TIMESTAMP;
COMMENT ON COLUMN member.pending_nickname IS '닉네임 뽑기로 받은 후보. 수락하면 nickname 으로 옮기고 비운다, "원래대로"면 그냥 비운다';
COMMENT ON COLUMN member.nickname_drawn_at IS '마지막으로 닉네임을 뽑은 시각(Asia/Seoul). 이 날짜가 오늘이면 오늘은 더 못 뽑는다';
