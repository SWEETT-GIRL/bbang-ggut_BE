-- 빵긋(com.bbanggut.app) FCM 토큰 비활성화
--
-- 2026-09-22 prod 에 이미 적용됨. 기록용으로 남긴다.
--
-- 적용:
--   psql -h localhost -p 15432 -U sweetgirl -d soboro -f docs/migrations/2026-09-22-deactivate-bbanggut-fcm-tokens.sql
--
-- 왜:
--   BE 하나가 앱 둘(빵긋·대동빵지도)의 토큰을 받지만 FirebaseConfig 는 서비스 계정 하나로
--   FirebaseApp 하나만 초기화한다. 같은 날 서비스 계정을 daedongbbangmap 으로 교체했으므로
--   빵긋 토큰으로 보내면 SENDER_ID_MISMATCH 가 돌아온다. 끄지 않으면 그 토큰만 가진 회원의
--   발송이 NotificationDelivery 에 FAILED 로 쌓인다.
--
-- bundle_id IS NULL 도 같이 끄는 근거 — 이들은 빵긋 토큰이다:
--   1. 마지막 갱신이 2026-05-19 ~ 06-19. 대동빵지도 앱 최초 커밋이 2026-07-20, FCM 토큰
--      파이프를 실은 빌드가 1.4.2(2026-09-11) 이므로 그 이전 토큰일 수 없다.
--   2. bundle_id 컬럼 자체가 V20260615190000 로 추가되어 그 전 등록분은 전부 NULL 이다.
--   3. NULL 보유 39명 중 대동빵지도 활성 토큰을 함께 가진 회원은 0명이었다.
--
-- 되돌리기는 필요 없다 — 두 앱 모두 실행마다 POST /alarm/fcm-token 으로 토큰을 재등록하므로
-- 그 사람이 앱을 한 번 열면 다시 활성으로 돌아온다.
--
-- 적용 결과: 71행 (com.bbanggut.app 32 + NULL 39)
--   활성 토큰 보유 회원 115명 -> 45명 (모두 com.daedongbbangmap.app)

UPDATE fcm_token
SET is_active  = false,
    updated_at = now()
WHERE is_active = true
  AND (bundle_id = 'com.bbanggut.app' OR bundle_id IS NULL);
