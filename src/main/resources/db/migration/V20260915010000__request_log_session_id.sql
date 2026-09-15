-- request_log.session_id — 앱이 보내는 세션 id 헤더(X-Session-Id)를 남긴다 (#661 상권 칩 개인화, 2026-09-15 결정).
--
-- 왜: "세션에서 본 상권"을 맨 앞 칩으로 두려면 이번 사용 흐름의 가게 상세 조회(STORE_VIEW)를 알아야 하는데, JWT 는 상태가
-- 없고 로그에 세션 구분이 없었다. JWT 에 세션 id 를 넣는 건 토큰 수명(액세스 1시간·리프레시 14일)과 묶여 "이번 흐름"을
-- 뜻하지 못한다. 앱이 포그라운드마다 정하는 id(Amplitude SDK 세션 id 재사용 권장 — 서버 로그와 이벤트가 같은 id 로 이어진다)를
-- 헤더로 보내고 여기 남긴다. 헤더가 없는 옛 앱은 "최근 30분" 창으로 근사한다(운영 실측: 연속 조회의 93% 가 30분 안).
-- 인덱스는 두지 않는다 — 조회는 회원 인덱스(idx_rlog_member)로 들어와 세션·이벤트로 거른다(회원당 행이 적다).
ALTER TABLE request_log ADD COLUMN session_id VARCHAR(64);
COMMENT ON COLUMN request_log.session_id IS
    '앱 세션 id (요청 헤더 X-Session-Id, ASCII 64자 이하). 앱 포그라운드마다 바뀌는 값 — Amplitude SDK 세션 id 재사용 권장. '
    '없으면 NULL(헤더 배포 전 앱). 추천 칩의 "세션에서 본 상권" 판정에 쓴다(#661)';
