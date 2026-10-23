-- 시스템이 쓰는 이름을 사람이 가져가지 못하게 — 탈퇴 표시 · 어드민 초기화 이름. 정규화(공백 제거) 기준으로 비교된다.
INSERT INTO forbidden_word (word, kind, created_at)
VALUES ('탈퇴한회원', 'BLOCK', now()), ('빵긋사용자', 'BLOCK', now())
ON CONFLICT DO NOTHING;
