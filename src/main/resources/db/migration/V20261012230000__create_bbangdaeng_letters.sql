-- 빵댕이 편지 — 월요일마다 한 통, 열면 부스러기를 받는다.
-- 운영팀과의 1:1 편지함(letter_thread / letter_message)과는 다른 것이다. 표도 API 도 섞지 않는다.

-- 편지 글 묶음. 회원마다 seq 순서대로 받고, 끝까지 받으면 처음으로 돌아간다. 어드민에서 더한다.
CREATE TABLE bbangdaeng_letters (
    id         BIGSERIAL PRIMARY KEY,
    seq        INTEGER   NOT NULL,
    body       TEXT      NOT NULL,
    active     BOOLEAN   NOT NULL DEFAULT TRUE,                 -- 끄면 새로 배달하지 않는다(이미 받은 편지는 그대로)
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_bbangdaeng_letters_seq UNIQUE (seq)
);

-- 회원이 받은 편지. 배치 없이, 요약을 읽을 때 그 주의 편지가 없으면 그때 만든다.
-- 안 연 편지는 4 통까지만 쌓이고 늘 가장 최근 주에 놓인다.
-- visible_from 은 보이기 시작하는 날이다 — 가입한 주의 첫 통만 가입 다음 날이고, 나머지는 그 주 월요일이다.
CREATE TABLE member_bbangdaeng_letters (
    id           BIGSERIAL PRIMARY KEY,
    member_id    BIGINT    NOT NULL REFERENCES member (id),
    letter_id    BIGINT    NOT NULL REFERENCES bbangdaeng_letters (id),
    week_start   DATE      NOT NULL,                            -- 그 주 월요일(한국 날짜)
    visible_from DATE      NOT NULL,
    opened_at    TIMESTAMP,
    created_at   TIMESTAMP NOT NULL,
    CONSTRAINT uq_member_bbangdaeng_letters_week UNIQUE (member_id, week_start)
);

INSERT INTO bbangdaeng_letters (seq, body, created_at) VALUES
    (1, E'안녕! 나 빵댕이야.\n월요일마다 이렇게 편지 쓸게.\n이번 주엔 어떤 빵을 만날까?\n맛있는 거 먹으면 꼭 알려 줘.', CURRENT_TIMESTAMP),
    (2, E'주말에 뭐 먹었어?\n나는 네가 쌓아 준 빵 위에서 낮잠 잤어.\n따끈한 빵 냄새가 나는 꿈을 꿨는데\n일어나 보니 진짜더라.', CURRENT_TIMESTAMP),
    (3, E'오늘 아침 하늘이 식빵 속살 같았어.\n이런 날엔 버터 바른 토스트가 딱인데.\n너는 어떤 빵이 생각나?', CURRENT_TIMESTAMP),
    (4, E'빵집 문 열 때 나는 종소리 좋아해?\n나는 그 소리 들으면 꼬리가 먼저 움직여.\n이번 주에 새 빵집 한 곳 가 보는 건 어때.', CURRENT_TIMESTAMP),
    (5, E'갓 나온 빵은 왜 그렇게 금방 사라질까.\n봉투가 따뜻할 때 한 입 먹는 게 제일 맛있더라.\n이번 주엔 꼭 따뜻할 때 먹어.', CURRENT_TIMESTAMP),
    (6, E'요즘 소금빵 생각이 자꾸 나.\n겉은 바삭하고 속은 촉촉한 거.\n네가 아는 소금빵 맛집 있으면 나도 데려가 줘.', CURRENT_TIMESTAMP),
    (7, E'비 오는 날엔 빵집 가기 귀찮지.\n그래도 젖은 길 끝에 빵 냄새가 나면\n발걸음이 빨라지더라. 나만 그런가.', CURRENT_TIMESTAMP),
    (8, E'지난번에 먹은 빵 기억나?\n나는 다 기억해. 탑에 쌓여 있으니까.\n가끔 올려다보면 뿌듯해.', CURRENT_TIMESTAMP),
    (9, E'단 게 당기는 날이 있고\n담백한 게 당기는 날이 있잖아.\n오늘의 너는 어느 쪽이야?', CURRENT_TIMESTAMP),
    (10, E'빵 고를 때 한참 서 있는 편이야?\n나는 쟁반 들고 3바퀴는 돌아.\n결국 처음 본 걸 고르지만.', CURRENT_TIMESTAMP),
    (11, E'친구랑 빵 나눠 먹으면 더 맛있대.\n반으로 가르면 김이 올라오는 그 순간 있잖아.\n이번 주엔 누구랑 먹을 거야?', CURRENT_TIMESTAMP),
    (12, E'이번 주도 고생 많았어.\n힘든 날엔 좋아하는 빵 하나면 좀 괜찮아지더라.\n나는 여기서 기다리고 있을게.', CURRENT_TIMESTAMP);
