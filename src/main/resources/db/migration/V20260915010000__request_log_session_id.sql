-- request_log.session_id — 앱이 보내는 세션 id 헤더(X-Session-Id)를 남긴다 (#661 상권 칩 개인화, 2026-09-15 결정).
--
-- 왜: "세션에서 본 상권"을 맨 앞 칩으로 두려면 이번 사용 흐름의 가게 상세 조회(STORE_VIEW)를 알아야 하는데, JWT 는 상태가
-- 없고 로그에 세션 구분이 없었다. JWT 에 세션 id 를 넣는 건 토큰 수명(액세스 1시간·리프레시 14일)과 묶여 "이번 흐름"을
-- 뜻하지 못한다. 앱이 포그라운드마다 정하는 id(Amplitude SDK 세션 id 재사용 권장 — 서버 로그와 이벤트가 같은 id 로 이어진다)를
-- 헤더로 보내고 여기 남긴다. 헤더가 없는 옛 앱은 "최근 30분" 창으로 근사한다(운영 실측: 연속 조회의 93% 가 30분 안).
-- 조회는 "회원 + STORE_VIEW + (세션 id 또는 최근 창)"이다. 회원 인덱스(idx_rlog_member)만 타면 그 회원의 로그 전체(폴링·목록
-- 포함)를 찍고 힙에서 거른다 — 운영 실측(09-15) 회원당 최대 7,935행·상위 1% 1,637행이고 STORE_VIEW 는 전체의 6.1% 뿐이라,
-- STORE_VIEW 만 담는 부분 인덱스가 정확히 이 조회만 작게 덮는다(PR #741 리뷰). 부분 인덱스라 1.75M 행에서도 작고 빨리 만든다.
ALTER TABLE request_log ADD COLUMN session_id VARCHAR(64);
CREATE INDEX idx_rlog_member_store_view
    ON request_log (member_id, created_at DESC)
    WHERE event_type = 'STORE_VIEW' AND store_id IS NOT NULL;
COMMENT ON COLUMN request_log.session_id IS
    '앱 세션 id (요청 헤더 X-Session-Id, ASCII 64자 이하). 앱 포그라운드마다 바뀌는 값 — Amplitude SDK 세션 id 재사용 권장. '
    '없으면 NULL(헤더 배포 전 앱). 추천 칩의 "세션에서 본 상권" 판정에 쓴다(#661)';
