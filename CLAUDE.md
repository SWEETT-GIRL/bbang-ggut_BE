# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 규칙 & 컨벤션

- **모든 API 응답은 `ApiResponse<T>` 사용** — `ApiResponse.ok(data)` / `ApiResponse.fail(errorCode)`
- **도메인 에러코드는 `BaseErrorCode` 구현 Enum으로 정의** → `CustomException`으로 throw
- **코드 스타일: Naver Java 컨벤션** — pre-commit 시 `spotlessApply` + `checkstyle` 자동 실행; 코드 작성 시부터 컨벤션 준수 필요
  - import 순서: `java` → `javax` → `org` → `com` → `io` 순, 그룹 내 알파벳순
  - 들여쓰기: 탭(tab) 사용
  - 줄바꿈: 여는 중괄호 같은 줄, 닫는 중괄호 새 줄
- **인증** — JWT 기반. `JwtAuthenticationFilter`가 `Authorization: Bearer ...` 헤더를 검증하고 `SecurityContext`에 `memberId`를 저장. 컨트롤러에서는 `@AuthenticationPrincipal Long memberId`로 꺼내 사용
- 테스트 DB는 H2 사용 — PostGIS 공간 쿼리(`Store.location`) 포함 시 Repository 테스트 불가

## 프로젝트

**빵긋(Bbang-ggut)** — 사용자 주변 빵집 오픈 알림 서비스 백엔드

**기술 스택**: Spring Boot 4.0.6 · Java 17 · PostgreSQL + Hibernate Spatial · Firebase FCM · JWT · SpringDoc OpenAPI

## 도메인 용어

| 용어 | 설명 |
|------|------|
| Store | 빵집 (위치는 PostGIS `geography(Point, 4326)`) |
| Member | 앱 사용자 |
| FcmToken | 디바이스별 FCM 푸시 토큰 (`member + deviceId`로 식별) |
| SubscribedStore | 사용자가 구독한 빵집 |
| StoreNotification | 빵집 알림 발송 히스토리 |
| NotificationSetting | 알림 수신 시간대·반경·요일 설정 |
| Inquiry | 사용자 문의 (category: STORE·ACCOUNT·BUG·ETC) |
| Terms | 약관/정책 (SERVICE·PRIVACY·MARKETING, 텍스트 하드코딩) |
| Banner | 앱 메인 배너 (썸네일 + 상세 이미지, S3 presigned URL로 직접 업로드) |

## 프로젝트 구조

```
sweetgirl.croissant
├── {domain}/    controller · service · repository · entity · dto/request · dto/response
│   (alarm · banner · member · menu · store · admin · inquiry · policy)
├── external/    외부 API 연동 (kakao — KakaoLocalClient, DTO, 에러코드 / aws — S3Service, presigned URL, MimeType)
├── global/      ApiResponse, CustomException, BaseErrorCode, GlobalExceptionHandler
├── config/      FirebaseConfig, JpaConfig, SwaggerConfig
└── common/      BaseTimeEntity, 공통 Enum
```

## 관련 프로젝트

| 폴더 | 설명 | 스택 |
|------|------|------|
| `../bbang-ggut_admin_web/` | 관리자 웹 대시보드 — 빵집·메뉴·배너·신고 관리 | React 19, Vite, TypeScript, Tailwind CSS 4 |
| `../bbang-ggut_FE/bbanggut/` | 사용자 앱 | Expo SDK 54, TypeScript, NativeWind v4 |

## 주요 명령어

```bash
./gradlew bootRun                  # 실행 (기본 프로파일: local)
./gradlew build                    # 빌드
./gradlew test                     # 전체 테스트
./gradlew test --tests "sweetgirl.croissant.<패키지>.<클래스>"  # 단일 테스트
./gradlew spotlessApply            # 포맷 적용
./gradlew checkstyleMain checkstyleTest  # 스타일 검사
```

> 첫 clone 후 `./gradlew compileJava` 실행 필요 — pre-commit 훅 자동 등록됨
