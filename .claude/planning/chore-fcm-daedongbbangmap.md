# FCM 발송 프로젝트를 대동빵지도로 교체

## 문제

관리자에서 푸시를 보냈더니 대동빵지도가 아니라 **빵긋**에서 울렸다.

BE(`bbang-ggut.cloud`) 하나가 앱 **둘**의 서버다.

| | 빵긋 | 대동빵지도 |
|---|---|---|
| bundle id | `com.bbanggut.app` | `com.daedongbbangmap.app` |
| Firebase 프로젝트 | `bbang-ggut` | `daedongbbangmap` |
| 토큰 등록 경로 | `POST /alarm/fcm-token` | 같음 |

둘 다 같은 `fcm_token` 테이블에 토큰을 쌓는데, `FirebaseConfig` 는 서비스 계정 하나로
`FirebaseApp` 하나만 초기화한다(`FirebaseConfig#initialize`). 그 계정이 `bbang-ggut` 이었으므로
서버는 **구조적으로 빵긋에만** 보낼 수 있었다. `FcmService#sendPushNotification` 은
`Message.setToken(...)` 만 하므로 "어느 앱으로 보낼지" 를 고르는 지점이 애초에 없다 —
목적지는 토큰이 정한다.

## 결정

빵긋 앱은 사실상 이탈했다고 보고(토큰 마지막 갱신 `com.bbanggut.app` 2026-08-21,
`bundle_id IS NULL` 2026-06-19 — 앱은 실행마다 토큰을 재등록하므로 갱신이 멈춘 것은
앱을 열지 않는다는 뜻이다) **서비스 계정을 `daedongbbangmap` 하나로 교체**한다.
두 앱 모두에 보내는 길(FirebaseApp 2개 + `bundle_id` 라우팅)은 지금 필요가 없어 택하지 않았다.

## 조치 (2026-09-22)

1. `src/main/resources/firebase/firebase-service-account.json` 을 `daedongbbangmap` 키로 교체.
   구 키는 같은 폴더 `firebase-service-account.bbang-ggut.bak.json` 로 보관.
   **이 폴더는 `.gitignore` 대상이라 저장소에 코드 변경이 없다.**
2. GH secret `FIREBASE_SERVICE_ACCOUNT_JSON` 교체. `cd-staging.yml`·`cd-prod.yml` 이
   **같은 시크릿 하나를 공유**하므로 다음 배포부터 양쪽이 동시에 전환된다.
3. prod `fcm_token` 에서 빵긋 토큰 71행 비활성화 —
   `docs/migrations/2026-09-22-deactivate-bbanggut-fcm-tokens.sql`
4. 교체 검증: 새 키로 액세스 토큰 발급 성공 + FCM v1 `validate_only` 호출이
   `INVALID_ARGUMENT`(더미 토큰) 로 응답 → 인증 통과. 401/403 이 아니므로 권한 정상.

코드 변경 없음. 반영은 **재배포만** 필요하고 앱 빌드·OTA·스토어 심사는 필요 없다.

## 남은 일

- [ ] `daedongbbangmap` 프로젝트에 **APNs 인증 키(.p8)** 업로드 확인.
      없으면 iOS 20명이 전부 `THIRD_PARTY_AUTH_ERROR` 로 실패한다.
      빵긋 프로젝트의 `.p8` 을 그대로 재사용할 수 있다(APNs 키는 Apple 팀 단위).
- [ ] 배포 후 실기기로 iOS·Android 각 1건 도달 확인
- [ ] 구 `bbang-ggut` 서비스 계정 키(`70a70c46...`) 폐기

## 롤백

1. `firebase-service-account.bbang-ggut.bak.json` 을 `firebase-service-account.json` 로 되돌린다
2. `gh secret set FIREBASE_SERVICE_ACCOUNT_JSON < src/main/resources/firebase/firebase-service-account.json`
3. 재배포

끈 토큰은 되돌릴 필요가 없다 — 앱을 한 번 열면 재등록된다.

## 영향

```
교체 전: 활성 토큰 보유 115명 → 빵긋 70명에게 도달 (대동빵지도 45명은 발송 불가)
교체 후: 활성 토큰 보유  45명 → 대동빵지도 45명에게 도달 (빵긋 70명은 도달 불가)
```
