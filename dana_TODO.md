# Dana (임지빈) 구현 TODO

> 레포: `bbang-ggut_BE` | 기준: 1차 MVP | 총 API: 8개
> 구현 기준: 엔티티 설계가 반영된 `origin/dev`

---

## 구현 순서

### 1. 기존 엔티티/레포 구조 확인

- [ ] `StoreNotification`, `SubscribedStore`, `FcmToken`, `NotificationSetting`, `Store` 실제 필드 확인
- [ ] 이미 존재하는 Repository / Controller / Service 중복 확인
- [ ] 인증에서 `Member`를 어떻게 꺼내는지 확인
    - `@AuthenticationPrincipal`
    - `SecurityContext`
    - 커스텀 인증 유틸 여부

확인 포인트:

- `StoreNotification`은 `isRead` 필드가 없고 `readAt == null` 여부로 읽음 상태 판단
- `Store.location`은 `Point` 타입이므로 PostGIS 쿼리 사용 가능
- `SubscribedStoreRepository`, `StoreController`, `MemberController`는 팀원 작업과 충돌 가능성 있음
---

### 5. 알림받는 가게 전체 조회

API:

- [ ] `GET /stores/subscribed`

구현 TODO:

- [ ] `StoreService.getSubscribedStores(Member member)`
- [ ] `StoreController`에 `GET /stores/subscribed` 등록
- [ ] 테스트: 구독 가게 목록 정상 조회 확인

---

### 6. FCM 토큰 저장/갱신 흐름 확인

구현 TODO:

- [ ] 기존 FCM 토큰 저장 API 존재 여부 확인
- [ ] 기존 구현이 있으면 재사용
- [ ] 없으면 `FcmTokenRepository`, `FcmService` 정리
- [ ] 활성 FCM 토큰 조회 방식 확인

위치 기반 알림에서 필요한 조건:

- 로그인 유저의 활성 토큰 조회 가능
- 같은 `member` + `deviceId` 재등록 시 token 갱신 가능
- `isActive`가 false인 토큰은 발송 대상에서 제외

---

### 7. 알림 중복 방지 로직을 내부 메서드로 먼저 구현

우선순위:

- 컨트롤러 API로 바로 공개하지 않음
- `location-check` 내부에서 먼저 사용

구현 TODO:

- [ ] `StoreNotificationRepository.existsByMemberAndStoreAndSentAtAfter(...)`
- [ ] `AlarmService.isDuplicateAlarm(Member member, Store store)`
- [ ] 기준 시간 `N분` 임시 상수 지정
- [ ] 팀 협의 후 기준 시간 확정

예상 메서드:

```java
boolean existsByMemberAndStoreAndSentAtAfter(
    Member member,
    Store store,
    LocalDateTime after
);
```

주의:

- 짧은 시간 안에 같은 가게 알림이 반복 발송되지 않도록 처리
- 외부 API 공개 여부는 프론트 요구 확인 후 결정

---

### 8. 위치 기반 근접 체크

API:

- [ ] `POST /alarm/location-check`

구현 TODO:

- [ ] `LocationCheckRequest`
    - `@NotNull Double latitude`
    - `@NotNull Double longitude`
- [ ] `LocationCheckResponse`
    - `boolean triggered`
    - `List<String> storeNames`
- [ ] `NotificationSettingRepository.findByMember(Member member)`
- [ ] `SubscribedStoreRepository.findNearbySubscribedStores(...)`
- [ ] `AlarmService.checkProximity(Member member, LocationCheckRequest request)`
- [ ] `FcmService.sendPushNotification(String fcmToken, String title, String body)`
- [ ] 조건 충족 시 `StoreNotification` 저장
- [ ] `AlarmController`에 `POST /alarm/location-check` 등록
- [ ] 테스트: 가게 좌표 근처 위경도 요청 시 알림 발송 및 DB 저장 확인

처리 흐름:

1. `NotificationSetting` 조회
2. 알림 ON/OFF, DND, 시간대, 요일 조건 확인
3. PostGIS `ST_DWithin`으로 반경 내 구독 가게 조회
4. 가게별 중복 알림 여부 확인
5. 조건 충족 가게에 대해 FCM 발송
6. `StoreNotification` 저장
7. `LocationCheckResponse` 반환

주의:

- `notificationRadius` 단위는 팀과 확인 필요
- `Store.location`이 `Point` 타입이므로 Haversine 직접 구현보다 PostGIS 쿼리 우선
- FCM 발송 실패 시 전체 요청 실패로 볼지, 해당 토큰만 실패 처리할지 정책 필요

---

### 9. 중복 체크 API 외부 공개 여부 결정

API 후보:

- [ ] `GET /alarm/duplicate-check`

결정 기준:

- 프론트가 직접 호출해야 하면 Controller에 공개
- 서버의 `location-check` 내부 로직으로 충분하면 공개하지 않음

주의:

- 이 API는 우선순위 낮음
- 위치 기반 알림 구현 후 프론트 요구에 따라 추가

---

## 최종 구현 순서 요약

```text
1. 구조 확인
2. GET /alarm/history
3. POST /alarm/read
4. GET /users/me/store-count, GET /users/me/alarm-count
5. GET /stores/subscribed
6. FCM 토큰/발송 준비
7. 중복 방지 내부 로직
8. POST /alarm/location-check
9. GET /alarm/duplicate-check 필요 시 추가
```

---

## 팀원과 맞춰야 할 것

| 항목                          | 대상   | 내용                                |
|-----------------------------|------|-----------------------------------|
| 인증 방식                       | 김나영  | JWT에서 `Member` 꺼내는 방식             |
| `MemberController` 수정       | 김나영  | `/users/me/*` API 파일 충돌 방지        |
| `StoreController` 수정        | 이현정  | `/stores/subscribed` API 파일 충돌 방지 |
| `SubscribedStoreRepository` | 이현정  | 중복 생성 방지                          |
| `notificationRadius` 단위     | 팀 전체 | m / km / 기본값 확인                   |
| 중복 알림 기준 시간                 | 팀 전체 | 최근 N분 기준 확정                       |
| 마케팅 알림 히스토리                 | 프론트  | Dana 담당 여부 확인                     |
| FCM 실패 정책                   | 팀 전체 | 발송 실패 시 응답/DB 저장 정책               |
| PostGIS 의존성                 | 이현정  | `ST_DWithin` 사용 가능 여부 확인          |
