# ContributionReport / AbuseReport TODO

## 목표

- [ ] 가게/메뉴 정보 제보는 `ContributionReport` 도메인으로 통합
- [ ] 리뷰/프로필/사용자 신고는 `AbuseReport` 도메인으로 분리
- [ ] 기존 기능 동작은 유지하면서 패키지/네이밍/테이블 구조를 단계적으로 정리

## ContributionReport 설계

- [ ] 상위 테이블 `contribution_report` 설계
- [ ] `ContributionReport` 엔티티 추가
- [ ] `ContributionTargetType` enum 추가 (`STORE`, `MENU`)
- [ ] `ContributionReportType` enum 추가 (`ADD`, `UPDATE`)
- [ ] `ContributionReportStatus` enum 추가 (`PENDING`, `ACCEPTED`, `REJECTED`)
- [ ] 가게 상세 테이블 `store_contribution_report` 설계
- [ ] 메뉴 상세 테이블 `menu_contribution_report` 설계
- [ ] 이미지 변경 payload 저장 방식 결정 (`json` 컬럼 또는 상세 이미지 테이블)

## 기존 제보 마이그레이션

- [ ] `store_report`를 `ContributionReport + StoreContributionReport`로 이관
- [ ] `store_add_report`를 `ContributionReport + StoreContributionReport`로 이관
- [ ] `menu_add_report`를 `ContributionReport + MenuContributionReport`로 이관
- [ ] 메뉴 수정 제보(`MOD_BREAD`)를 `ContributionReport + MenuContributionReport`로 이관
- [ ] 기존 테이블 유지/삭제/호환 전략 결정
- [ ] Flyway 마이그레이션 작성

## AbuseReport 리네이밍

- [ ] `UserReport`를 `AbuseReport`로 리네이밍
- [ ] `UserReportStatus`를 `AbuseReportStatus`로 리네이밍
- [ ] `UserReportAction`을 `AbuseReportAction`으로 리네이밍
- [ ] `ReportTargetType`을 `AbuseReportTargetType`으로 리네이밍
- [ ] `ReportReasonType`을 `AbuseReportReasonType`으로 리네이밍
- [ ] `UserReportService`, `UserReportController`, repository, DTO 패키지/이름 정리
- [ ] API path 유지 여부 결정 (`/reports` 유지 또는 `/abuse-reports` 변경)

## 관리자 API

- [ ] 관리자 제보 목록을 `ContributionReport` 기준으로 조회
- [ ] 관리자 제보 상세를 공통 응답으로 정리
- [ ] 가게 추가 승인 로직 이관
- [ ] 가게 수정 승인 로직 이관
- [ ] 메뉴 추가 승인 로직 이관
- [ ] 메뉴 수정 승인 로직 이관
- [ ] 이미 처리된 제보 예외 처리 유지
- [ ] 사용자 신고 관리자 API를 `AbuseReport` 기준으로 정리

## 알림

- [ ] `ReportKind` 위치 재정의 (`ContributionReport`/`AbuseReport` 구조에 맞게)
- [ ] `ReportNotification`이 `contribution_report_id` 또는 `abuse_report_id`를 참조하도록 정리
- [ ] Contribution 승인/거절 알림 유지
- [ ] Abuse 신고 처리 알림 유지
- [ ] 기존 알림 조회 응답 호환성 확인

## 테스트

- [ ] ContributionReport 생성 테스트
- [ ] StoreContributionReport 승인/거절 테스트
- [ ] MenuContributionReport 승인/거절 테스트
- [ ] AbuseReport 생성/처리 테스트
- [ ] 관리자 목록/상세/승인/거절 테스트
- [ ] 알림 생성/조회 테스트
- [ ] 기존 API 호환 테스트
