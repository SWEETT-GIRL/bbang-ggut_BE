---
name: 알림 관련 팀 결정 사항
description: notificationRadius 허용값, 마케팅 히스토리 저장 여부, FCM 실패 정책 결정 내용
type: project
---

## notificationRadius

허용값: 100, 300, 500, 700, 1000 (단위: 미터)  
기본값: 500m (`NotificationRadius.RADIUS_500`)  
구현: `NotificationRadius` enum (`common/enums/`) — DB에 STRING으로 저장

**Why:** Geofence 전환으로 30~70m는 GPS 오차 범위 내라 불안정. 100m 이상으로 상향. 5단계로 확장.  
**How to apply:** 기존 DB 데이터(RADIUS_30/50/70)가 있으면 RADIUS_100으로 migration 필요.

## 마케팅 알림 히스토리

히스토리 저장 안 함 — `MarketingNotification` 엔티티는 현재 미사용 (삭제 여부 미결).  
`NotificationSetting.isMarketingEnabled`는 수신 여부 설정이므로 유지.

**Why:** 팀 결정. 히스토리 저장 불필요.

## FCM 실패 정책

- `UNREGISTERED` / `INVALID_ARGUMENT` → FCM 토큰 비활성화 (기존)
- 그 외 오류 → `log.error` + `AlarmException(FCM_SEND_FAILED)` throw
- `AlarmService`에서 토큰별로 catch → `log.warn` 후 skip
- **하나라도 성공한 경우에만** `StoreNotification` 저장 (전부 실패 시 미저장 → 당일 재시도 가능)

**Why:** 전부 실패 시 저장하면 중복 방지 로직이 "오늘 발송됨"으로 판단해 당일 재시도 기회를 차단함.
