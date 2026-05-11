---
name: 알림 관련 팀 결정 사항
description: notificationRadius 허용값, 마케팅 히스토리 저장 여부, FCM 실패 정책 결정 내용
type: project
---

## notificationRadius

허용값: 30, 50, 70 (단위: 미터)  
기본값: 50m (`NotificationRadius.RADIUS_50`)  
구현: `NotificationRadius` enum (`common/enums/`) — DB에 STRING으로 저장

**Why:** 팀 협의로 3단계 선택지로 확정. 연속값 아닌 이산값이라 enum으로 표현.

## 마케팅 알림 히스토리

히스토리 저장 안 함 — `MarketingNotification` 엔티티는 현재 미사용 (삭제 여부 미결).  
`NotificationSetting.isMarketingEnabled`는 수신 여부 설정이므로 유지.

**Why:** 팀 결정. 히스토리 저장 불필요.

## FCM 실패 정책

- `UNREGISTERED` / `INVALID_ARGUMENT` → FCM 토큰 비활성화 (기존)
- 그 외 오류 → `log.error` + `AlarmException(FCM_SEND_FAILED)` throw
- `AlarmService`에서 토큰별로 catch → `log.warn` 후 skip, `StoreNotification`은 정상 저장

**Why:** FCM 예상 외 오류는 인지해야 하지만, 한 토큰 실패가 전체 히스토리 저장을 막으면 안 됨.
