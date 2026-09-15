-- 추천 시트 재사용 규칙(#661, 09-15 결정): 같은 앱 세션에서 시트를 닫았다 열면 보던 묶음 그대로(자리 기억), 다른 세션(다음 날)에
-- 열면 안 본 가게로 새 묶음(새로 오면 새 추천), 다 봤으면 첫 묶음을 다시 계산한다. FE dev 검증에서 어제 다시 받기로 소진한
-- 1곳짜리 마지막 묶음이 오늘 새로 열 때 그대로 재사용되는 문제로 발견. "같은 세션" 판정 재료로 요청 헤더 X-Session-Id 를
-- 추천 행에 남긴다(request_log.session_id 와 같은 규칙, 헤더 없는 옛 앱은 30분 창만 본다). 옛 행은 NULL — 창으로만 판정된다.
ALTER TABLE recommendation ADD COLUMN session_id VARCHAR(64);
COMMENT ON COLUMN recommendation.session_id IS
    '이 묶음을 만든 요청의 앱 세션 id (X-Session-Id, ASCII 64자 이하, 없으면 NULL). 새로 열 때 같은 세션이면 이 묶음을 '
    '그대로 주고, 다른 세션이면 안 본 가게로 새 묶음을 만든다(#661)';
