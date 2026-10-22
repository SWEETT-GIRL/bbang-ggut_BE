-- 기본 금칙어(BLOCK) · 허용어(ALLOW) 시드 — 10/22 dev 에서 표가 비어 "씨발빵"이 통과했다.
-- 필터는 NFC · 소문자 · 공백/특수문자 제거 뒤 부분 일치라 짧은 글자는 넣지 않는다(오탐). 댓글 · 닉네임 · 공개 후기가 같이 쓰므로
-- "운영자" 같은 일반어는 넣지 않는다. 목록은 어드민 /api/admin/forbidden-words 로 늘리고 줄인다.
INSERT INTO forbidden_word (word, kind, created_at) VALUES
    ('씨발', 'BLOCK', now()), ('시발', 'BLOCK', now()), ('씨팔', 'BLOCK', now()), ('씨빨', 'BLOCK', now()),
    ('시팔', 'BLOCK', now()), ('ㅅㅂ', 'BLOCK', now()), ('병신', 'BLOCK', now()), ('븅신', 'BLOCK', now()),
    ('개새끼', 'BLOCK', now()), ('개새키', 'BLOCK', now()), ('새끼야', 'BLOCK', now()), ('지랄', 'BLOCK', now()),
    ('ㅈㄹ', 'BLOCK', now()), ('좆', 'BLOCK', now()), ('좃', 'BLOCK', now()), ('썅', 'BLOCK', now()),
    ('쌍년', 'BLOCK', now()), ('쌍놈', 'BLOCK', now()), ('미친놈', 'BLOCK', now()), ('미친년', 'BLOCK', now()),
    ('느금마', 'BLOCK', now()), ('니애미', 'BLOCK', now()), ('니미럴', 'BLOCK', now()), ('엿먹어', 'BLOCK', now()),
    ('호로새끼', 'BLOCK', now()), ('개년', 'BLOCK', now()), ('걸레년', 'BLOCK', now()), ('창녀', 'BLOCK', now()),
    ('보지', 'BLOCK', now()), ('자지', 'BLOCK', now()), ('섹스', 'BLOCK', now()), ('섹파', 'BLOCK', now()),
    ('야동', 'BLOCK', now()), ('성매매', 'BLOCK', now()), ('조건만남', 'BLOCK', now()), ('딸딸이', 'BLOCK', now()),
    ('fuck', 'BLOCK', now()), ('shit', 'BLOCK', now()), ('bitch', 'BLOCK', now()), ('porn', 'BLOCK', now()),
    ('일베', 'BLOCK', now()), ('틀딱', 'BLOCK', now()), ('한남충', 'BLOCK', now()), ('김치녀', 'BLOCK', now())
ON CONFLICT DO NOTHING;

-- 금칙어가 든 멀쩡한 말은 먼저 걷어 낸 뒤 검사한다
INSERT INTO forbidden_word (word, kind, created_at) VALUES
    ('시발점', 'ALLOW', now()), ('보지도', 'ALLOW', now()), ('보지만', 'ALLOW', now()), ('보지않', 'ALLOW', now()),
    ('자지러', 'ALLOW', now()), ('자지않', 'ALLOW', now()), ('자지도', 'ALLOW', now()), ('자지말', 'ALLOW', now())
ON CONFLICT DO NOTHING;
