# ContributionReport / AbuseReport Phase TODO

## 커밋 규칙

- [ ] 커밋 메시지는 `emoji feat: 한글 설명` 형식으로 작성한다.
- [ ] phase 단위가 커도 한 번에 묶지 말고, phase 진행 중간중간 작은 단위로 커밋한다.
- [ ] 예시: `✨ feat: ContributionReport 기본 엔티티 추가`

## Flyway 주의사항

- [ ] 이미 dev 또는 prod에 적용된 migration 파일은 수정하지 않는다.
- [ ] 스키마 변경은 항상 새 migration 파일로 추가한다.
- [ ] dev에 push하기 전 local에서 Flyway migration이 처음부터 정상 적용되는지 확인한다.
- [ ] dev에서 prod로 올리기 전 prod에 이미 적용된 migration 버전과 충돌하지 않는지 확인한다.
- [ ] dev 전용 임시 migration을 만들지 않는다. prod까지 갈 수 있는 migration만 작성한다.
- [ ] 테이블/컬럼 삭제, rename, not null 추가는 기존 데이터 영향 확인 후 별도 phase에서 처리한다.
- [ ] backfill이 필요한 경우 `컬럼 추가 -> 데이터 채움 -> 제약조건 추가` 순서로 나눈다.
- [ ] migration 파일명은 기존 프로젝트 규칙에 맞춰 중복 없는 버전으로 만든다.

## 원칙

- [ ] 기존 API 응답/경로는 기본 유지한다. 변경이 필요하면 별도 phase에서 결정한다.
- [ ] 한 phase 안에서는 DB, 엔티티, 서비스 변경 범위를 작게 유지한다.
- [ ] 기존 `store_report`, `store_add_report`, `menu_add_report`, `UserReport` 동작을 깨지 않는다.
- [ ] 리네이밍과 기능 변경을 같은 PR/커밋에 섞지 않는다.

## Phase 0. 현재 구조 고정

- [x] 현재 제보/신고 테이블과 API 목록 정리
  - `store_report`
  - `store_add_report`
  - `menu_add_report`
  - `user_report`
  - `report_notification`
- [x] 기존 관리자 제보 목록/상세/승인/거절 동작 기준 정리
- [x] 기존 알림 생성/조회 응답 기준 정리
- [x] 기존 API 호환 테스트 최소 추가

## Phase 1. ContributionReport 뼈대 추가

- [ ] 상위 테이블 `contribution_report` 추가
- [ ] `ContributionReport` 엔티티 추가
- [ ] `ContributionTargetType` enum 추가 (`STORE`, `MENU`)
- [ ] `ContributionReportType` enum 추가 (`ADD`, `UPDATE`)
- [ ] `ContributionReportStatus` enum 추가 (`PENDING`, `ACCEPTED`, `REJECTED`)
- [ ] 기존 테이블은 그대로 두고 신규 테이블만 추가
- [ ] Flyway 마이그레이션 작성
- [ ] `ContributionReport` 생성/조회 repository 테스트 추가

## Phase 2. 가게 제보 상세 분리

- [ ] 가게 상세 테이블 `store_contribution_report` 추가
- [ ] `StoreContributionReport` 엔티티 추가
- [ ] `store_report` 생성 시 `ContributionReport + StoreContributionReport`도 같이 생성
- [ ] `store_add_report` 생성 시 `ContributionReport + StoreContributionReport`도 같이 생성
- [ ] 이미지 변경 payload 저장 방식은 우선 기존 이미지 테이블을 유지하고 참조만 연결
- [ ] 기존 가게 제보 API 응답은 유지
- [ ] 가게 추가/수정 제보 생성 테스트 추가

## Phase 3. 메뉴 제보 상세 분리

- [ ] 메뉴 상세 테이블 `menu_contribution_report` 추가
- [ ] `MenuContributionReport` 엔티티 추가
- [ ] `menu_add_report` 생성 시 `ContributionReport + MenuContributionReport`도 같이 생성
- [ ] 메뉴 수정 제보(`MOD_BREAD`) 생성 시 `ContributionReport + MenuContributionReport`도 같이 생성
- [ ] 메뉴 이미지 변경 payload는 기존 구조를 우선 유지
- [ ] 기존 메뉴 제보 API 응답은 유지
- [ ] 메뉴 추가/수정 제보 생성 테스트 추가

## Phase 4. 관리자 Contribution 조회/처리 전환

- [ ] 관리자 제보 목록을 `ContributionReport` 기준으로 조회
- [ ] 관리자 제보 상세를 공통 응답으로 정리
- [ ] 가게 추가 승인/거절 로직을 `StoreContributionReport` 기준으로 연결
- [ ] 가게 수정 승인/거절 로직을 `StoreContributionReport` 기준으로 연결
- [ ] 메뉴 추가 승인/거절 로직을 `MenuContributionReport` 기준으로 연결
- [ ] 메뉴 수정 승인/거절 로직을 `MenuContributionReport` 기준으로 연결
- [ ] 이미 처리된 제보 예외 처리 유지
- [ ] 관리자 목록/상세/승인/거절 테스트 추가

## Phase 5. AbuseReport 리네이밍

- [ ] `UserReport`를 `AbuseReport`로 리네이밍
- [ ] `UserReportStatus`를 `AbuseReportStatus`로 리네이밍
- [ ] `UserReportAction`을 `AbuseReportAction`으로 리네이밍
- [ ] `ReportTargetType`을 `AbuseReportTargetType`으로 리네이밍
- [ ] `ReportReasonType`을 `AbuseReportReasonType`으로 리네이밍
- [ ] service/controller/repository/DTO 패키지와 클래스명 정리
- [ ] DB 테이블명은 우선 `user_report` 유지, 테이블 리네임은 별도 phase에서 판단
- [ ] API path는 우선 `/reports` 유지
- [ ] AbuseReport 생성/처리 테스트 추가

## Phase 6. 알림 연결 정리

- [ ] `ReportKind` 위치 재정의
- [ ] `ReportNotification`이 `contribution_report_id` 또는 `abuse_report_id`를 참조하도록 컬럼 추가
- [ ] 기존 `report_id`, `report_kind` 응답 호환 유지
- [ ] Contribution 승인/거절 알림 유지
- [ ] Abuse 신고 처리 알림 유지
- [ ] 기존 알림 조회 응답 호환 테스트 추가

## Phase 7. 후속 정리

- [ ] 기존 테이블 유지/삭제/호환 전략 결정
- [ ] 기존 데이터 backfill 마이그레이션 작성
- [ ] 신규 구조 검증 후 legacy 쓰기 중단
- [ ] legacy repository/service 제거 범위 결정
- [ ] API path 변경 여부 결정 (`/reports` 유지 또는 `/abuse-reports` 추가)
- [ ] 운영 데이터 이관 체크리스트 작성
