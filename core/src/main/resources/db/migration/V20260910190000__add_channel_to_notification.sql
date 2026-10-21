-- 알림이 어디까지 나가는지를 알림 자신이 들고 있게 한다.
-- IN_APP 이면 알림함에만 쌓고, PUSH 면 알림함에 쌓은 뒤 FCM 까지 내보낸다.
--
-- notification_delivery.channel 과 이름은 같지만 뜻이 다르다. 그쪽은 "이 줄은 그 채널로 나간
-- 기록"이고, 이쪽은 "이 알림은 그 채널까지 나간다"는 의도다.
ALTER TABLE notification
	ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'PUSH';

-- 기존 알림은 전부 푸시까지 나갔던 것이라 PUSH 로 채우는 것이 사실과 맞다.
-- 백필이 끝났으면 기본값은 뗀다 -- 앞으로는 코드가 반드시 정하게 한다.
ALTER TABLE notification
	ALTER COLUMN channel DROP DEFAULT;
