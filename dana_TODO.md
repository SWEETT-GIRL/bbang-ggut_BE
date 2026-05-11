# Geofence 전환 TODO

## 목표

- iOS/Android OS Geofence 진입 이벤트를 기반으로 FCM 알림을 발송한다.
- FE는 위치 "항상 허용" 권한 유도와 Geofence 등록/해제를 담당한다.
- BE는 Geofence 후보 제공, 진입 이벤트 검증, 중복 방지, FCM 발송, 알림 이력 저장을 담당한다.

## 구현 방향

```text
앱 실행/로그인/알림 설정 변경/구독 가게 변경
 -> GET /alarm/geofence-candidates 호출
 -> 앱이 가까운 구독 가게를 OS Geofence로 등록

사용자가 등록된 빵집 반경에 진입
 -> OS가 앱에 Geofence enter 이벤트 전달
 -> 앱이 POST /alarm/geofence/enter 호출
 -> 서버가 구독 여부, 거리, 알림 설정, 중복 발송 검증
 -> FCM 발송
 -> StoreNotification 저장
```

## 파일 변경 구조

```text
src/main/java/sweetgirl/croissant/alarm
├── controller
│   └── AlarmController.java
│       ├── GET /alarm/geofence-candidates 추가
│       ├── POST /alarm/geofence/enter 추가
└── service
    └── AlarmService.java
        ├── getGeofenceCandidates(...) 추가
        ├── handleGeofenceEnter(...) 추가
        ├── isNotificationBlocked(...) 공통화
        ├── alreadyNotified(...) 추가
        └── sendStoreNotification(...) 추가

```
  4. AlarmService 내부 공통 로직부터
      - isNotificationBlocked(...)
      - alreadyNotified(...)
      - sendStoreNotification(...)
  5. AlarmService 공개 메서드 구현
      - getGeofenceCandidates(...)
      - handleGeofenceEnter(...)
  6. AlarmController 엔드포인트 추가
      - GET /alarm/geofence-candidates
      - POST /alarm/geofence/enter
  7. 테스트 / 컴파일 확인
      - repository query 검증
      - service 조건 검증
      - controller 요청/응답 검증

## 신규 API 1: Geofence 후보 조회

### Endpoint

```http
GET /alarm/geofence-candidates?lat=37.5&lng=127.0
```

### 용도

- 앱이 OS Geofence에 등록할 구독 가게 후보를 조회한다.
- 현재 위치 기준 가까운 구독 가게를 최대 20개 반환한다.
- `LIMIT 20`은 iOS `CLLocationManager` region 등록 제한을 고려한 값이다.

### Response

```json
{
  "stores": [
    {
      "storeId": 12,
      "name": "성수빵집",
      "latitude": 37.544,
      "longitude": 127.055,
      "radiusMeters": 300,
      "distanceMeters": 1520.4
    }
  ]
}
```

### 작업

- [ ] `GeofenceCandidatesResponse` DTO 추가
- [ ] `GeofenceCandidateResponse` DTO 추가
- [ ] `GeofenceCandidateProjection` 추가
- [ ] `SubscribedStoreRepository.findGeofenceCandidates(...)` native query 추가
- [ ] `AlarmService.getGeofenceCandidates(memberId, lat, lng)` 추가
- [ ] `AlarmController.getGeofenceCandidates(...)` 추가

### 후보 조회 쿼리

```sql
SELECT
  s.id AS storeId,
  s.name AS name,
  ST_Y(s.location::geometry) AS latitude,
  ST_X(s.location::geometry) AS longitude,
  ST_Distance(
    s.location,
    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
  ) AS distanceMeters
FROM subscribed_store ss
JOIN store s ON ss.store_id = s.id
WHERE ss.member_id = :memberId
  AND s.status = 'ACTIVE'
  AND ST_DWithin(
    s.location,
    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
    :candidateRadius
  )
ORDER BY distanceMeters ASC
LIMIT 20
```

### 구현 메모

- `candidateRadius`는 후보 탐색 범위다. 사용자 알림 반경과 다르다.
- 예: `candidateRadius = 3000m` 또는 `5000m`.
- `radiusMeters`는 앱이 실제 Geofence 등록에 사용할 반경이다.
- 알림 설정이 OFF인 경우 후보를 빈 리스트로 내려주는 방향을 우선 검토한다.
- `ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography`를 사용해 미터 단위 거리 계산을 보장한다.

## 신규 API 2: Geofence 진입 이벤트

### Endpoint

```http
POST /alarm/geofence/enter
```

### Request

```json
{
  "storeId": 12,
  "latitude": 37.5441,
  "longitude": 127.0552,
  "occurredAt": "2026-05-11T10:30:00"
}
```

### 작업

- [ ] `GeofenceEnterRequest` DTO 추가
- [ ] `AlarmController.handleGeofenceEnter(...)` 추가
- [ ] `AlarmService.handleGeofenceEnter(memberId, request)` 추가
- [ ] `SubscribedStoreRepository.existsSubscribedStoreWithinRadius(...)` 추가
- [ ] 단건 중복 체크 repository 메서드 추가
- [ ] 단건 FCM 발송 + 이력 저장 메서드 분리

### 서버 검증 순서

1. [ ] `memberId`로 `Member` 조회
2. [ ] `NotificationSetting` 조회
3. [ ] 알림 OFF / 시간 제한 / 요일 제한 체크
4. [ ] `storeId`가 사용자의 구독 가게인지 확인
5. [ ] 요청 좌표가 해당 가게 반경 안인지 PostGIS로 재검증
6. [ ] 오늘 또는 최근 정책 시간 내 같은 가게 알림 발송 여부 확인
7. [ ] 활성 FCM 토큰 조회
8. [ ] FCM 발송
9. [ ] `StoreNotification` 저장

### 구독 + 거리 검증 쿼리

```sql
SELECT EXISTS (
  SELECT 1
  FROM subscribed_store ss
  JOIN store s ON ss.store_id = s.id
  WHERE ss.member_id = :memberId
    AND s.id = :storeId
    AND s.status = 'ACTIVE'
    AND ST_DWithin(
      s.location,
      ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
      :radius
    )
)
```

### 구현 메모

- 클라이언트 Geofence 이벤트는 조작 가능하므로 서버에서 반드시 재검증한다.
- `occurredAt`은 클라이언트 이벤트 시각이므로 로그/디버깅용으로만 사용한다.
- 중복 체크 기준은 서버 시간을 사용한다.
- 네트워크 실패로 앱이 재시도할 수 있으므로 API는 idempotent하게 동작해야 한다.

## AlarmService 리팩터링

```text
AlarmService
├── getGeofenceCandidates(...)
├── handleGeofenceEnter(...)
├── isNotificationBlocked(...)
├── alreadyNotified(...)
├── sendStoreNotification(...)
└── getMember(...)
```

### 작업

- [ ] `isNotificationBlocked(NotificationSetting setting)` 공통 메서드 유지/정리
- [ ] `alreadyNotified(Member member, Store store)` 단건 중복 체크 메서드 추가
- [ ] `sendStoreNotification(Member member, Store store, List<FcmToken> tokens)` 단건 발송 메서드 추가
- [ ] FCM 토큰별 발송 실패는 전체 실패로 처리하지 않고 로그 후 진행
- [ ] 모든 발송이 실패했을 때도 `StoreNotification`을 저장할지 정책 결정

## 중복 알림 정책

우선 정책:

```text
같은 사용자 + 같은 가게는 하루 1회만 발송
```

작업:

- [ ] `StoreNotificationRepository.existsByMemberAndStoreAndSentAtAfter(...)` 추가 검토
- [ ] 기존 `findNotifiedStoreIdsSince(...)`를 유지할지 단건 exists로 바꿀지 결정
- [ ] 일 단위 기준은 서버 로컬 날짜 기준으로 처리

## 반경 정책

현재 49 브랜치 기준:

```java
RADIUS_30(30),
RADIUS_50(50),
RADIUS_70(70)
```

Geofence에서는 30~70m가 불안정할 수 있으므로 A안을 우선 적용한다.

```java
RADIUS_100(100),
RADIUS_300(300),
RADIUS_500(500)
```

작업:

- [ ] `NotificationRadius` enum 값 조정
- [ ] 기존 저장 데이터가 있다면 migration 필요 여부 확인
- [ ] FE 설정 화면 옵션과 동기화
- [ ] Swagger 설명 업데이트

## FE 연동 요구사항

- [ ] 앱 실행 시 후보 조회 후 OS Geofence 등록
- [ ] 로그인 시 후보 재조회
- [ ] 알림 설정 변경 시 기존 Geofence 제거 후 재등록
- [ ] 구독 가게 변경 시 재등록
- [ ] 위치 권한 변경 시 재등록 또는 제거
- [ ] 일정 거리 이상 이동 시 후보 재조회
- [ ] Geofence enter 이벤트 발생 시 `POST /alarm/geofence/enter` 호출
- [ ] 네트워크 실패 시 로컬 큐에 저장 후 재시도
- [ ] 위치 항상 허용/알림 권한 없을 때 권한 유도 UX 처리

## QA 시나리오

- [ ] 알림 ON + 구독 가게 반경 진입 시 FCM 발송
- [ ] 알림 OFF면 후보 조회 또는 enter 처리에서 알림 미발송
- [ ] 허용 시간대 밖이면 알림 미발송
- [ ] 허용 요일 밖이면 알림 미발송
- [ ] 구독하지 않은 `storeId`로 enter 요청 시 알림 미발송
- [ ] 반경 밖 좌표로 enter 요청 시 알림 미발송
- [ ] 같은 가게에 하루 두 번 enter 요청해도 한 번만 발송
- [ ] 활성 FCM 토큰이 없으면 알림 이력 저장 여부 정책대로 동작
- [ ] PostGIS 좌표가 `lng`, `lat` 순서로 처리되는지 확인

## 주의사항

- Geofence는 OS 이벤트 기반이므로 폴링보다 배터리 효율이 좋지만 100% 전달 보장은 아니다.
- 위치 항상 허용, 알림 허용, Geofence 등록 완료 상태가 필요하다.
- Android의 설정 앱 강제 중지, iOS/Android 권한 변경, 배터리 제한, 위치 정확도 저하 상황에서는 알림이 지연되거나 누락될 수 있다.
- 서버는 클라이언트 이벤트를 신뢰하지 않고 PostGIS로 거리와 구독 여부를 재검증한다.
