# ContributionReport / AbuseReport Phase TODO

## 커밋 규칙

- [ ] 커밋 메시지는 `emoji feat: 한글 설명` 형식으로 작성한다.
- [ ] 커밋 메시지에 `phase0`, `phase1`처럼 phase 번호를 넣지 않는다.
- [ ] phase 단위가 커도 한 번에 묶지 말고, 아래처럼 변경 성격별 작은 단위로 나눠 커밋한다.
- [ ] Flyway migration 추가 커밋 예시: `✨ feat: contribution_report 테이블 추가`
- [ ] 엔티티/enum 추가 커밋 예시: `✨ feat: ContributionReport 엔티티 추가`
- [ ] repository 추가 커밋 예시: `✨ feat: ContributionReport 저장소 추가`
- [ ] 테스트 추가 커밋 예시: `✅ test: ContributionReport 저장소 테스트 추가`
- [ ] TODO 체크/문서 갱신 커밋 예시: `📝 docs: ContributionReport 진행 항목 갱신`

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
- [ ] 기존 `store_report`, `store_add_report`, `menu_add_report`, `AbuseReport` 동작을 깨지 않는다.
- [ ] 리네이밍과 기능 변경을 같은 PR/커밋에 섞지 않는다.

## Phase 0. 현재 구조 고정

- [x] 현재 제보/신고 테이블과 API 목록 정리
  - `store_report`
  - `store_add_report`
  - `menu_add_report`
  - `abuse_report`
  - `report_notification`
- [x] 기존 관리자 제보 목록/상세/승인/거절 동작 기준 정리
- [x] 기존 알림 생성/조회 응답 기준 정리
- [x] 기존 API 호환 테스트 최소 추가

## Phase 1. ContributionReport 뼈대 추가

- [x] 상위 테이블 `contribution_report` 추가
- [x] `ContributionReport` 엔티티 추가
- [x] `ContributionTargetType` enum 추가 (`STORE`, `MENU`)
- [x] `ContributionReportType` enum 추가 (`ADD`, `UPDATE`)
- [x] `ContributionReportStatus` enum 추가 (`PENDING`, `ACCEPTED`, `REJECTED`)
- [x] 기존 테이블은 그대로 두고 신규 테이블만 추가
- [x] Flyway 마이그레이션 작성
- [x] `ContributionReport` 생성/조회 repository 테스트 추가

## Phase 2. 가게 제보 상세 분리

- [x] 가게 상세 테이블 `store_contribution_report` 추가
- [x] `StoreContributionReport` 엔티티 추가
- [x] `store_report` 생성 시 `ContributionReport + StoreContributionReport`도 같이 생성
- [x] `store_add_report` 생성 시 `ContributionReport + StoreContributionReport`도 같이 생성
- [x] 이미지 변경 payload 저장 방식은 우선 기존 이미지 테이블을 유지하고 참조만 연결
- [x] 기존 가게 제보 API 응답은 유지
- [x] 가게 추가/수정 제보 생성 테스트 추가

## Phase 3. 메뉴 제보 상세 분리

- [x] 메뉴 상세 테이블 `menu_contribution_report` 추가
- [x] `MenuContributionReport` 엔티티 추가
- [x] `menu_add_report` 생성 시 `ContributionReport + MenuContributionReport`도 같이 생성
- [x] 메뉴 수정 제보(`MOD_BREAD`) 생성 시 `ContributionReport + MenuContributionReport`도 같이 생성
- [x] 메뉴 이미지 변경 payload는 기존 구조를 우선 유지
- [x] 기존 메뉴 제보 API 응답은 유지
- [x] 메뉴 추가/수정 제보 생성 테스트 추가

## Phase 4. 관리자 Contribution 조회/처리 전환

- [x] 관리자 제보 목록을 `ContributionReport` 기준으로 조회
- [x] 관리자 제보 상세를 공통 응답으로 정리
- [x] 가게 추가 승인/거절 로직을 `StoreContributionReport` 기준으로 연결
- [x] 가게 수정 승인/거절 로직을 `StoreContributionReport` 기준으로 연결
- [x] 메뉴 추가 승인/거절 로직을 `MenuContributionReport` 기준으로 연결
- [x] 메뉴 수정 승인/거절 로직을 `MenuContributionReport` 기준으로 연결
- [x] 이미 처리된 제보 예외 처리 유지
- [x] 관리자 목록/상세/승인/거절 테스트 추가

## Phase 5. AbuseReport 리네이밍

- [x] `UserReport`를 `AbuseReport`로 리네이밍
- [x] `UserReportStatus`를 `AbuseReportStatus`로 리네이밍
- [x] `UserReportAction`을 `AbuseReportAction`으로 리네이밍
- [x] `ReportTargetType`을 `AbuseReportTargetType`으로 리네이밍
- [x] `ReportReasonType`을 `AbuseReportReasonType`으로 리네이밍
- [x] service/controller/repository/DTO 패키지와 클래스명 정리
- [x] DB 테이블명을 `abuse_report`로 리네이밍
- [x] API path를 `/abuse-reports`로 변경
- [x] AbuseReport 생성/처리 테스트 추가

## Phase 6. 알림 연결 정리

- [x] `ReportKind` 위치 재정의
- [x] `ReportNotification`이 `contribution_report_id` 또는 `abuse_report_id`를 참조하도록 컬럼 추가
- [x] 기존 `report_id`, `report_kind` 응답 호환 유지
- [x] Contribution 승인/거절 알림 유지
- [x] Abuse 신고 처리 알림 유지
- [x] 기존 알림 조회 응답 호환 테스트 추가

## Phase 7. 후속 정리

- [x] 기존 테이블 유지/삭제/호환 전략 결정
- [x] 기존 데이터 backfill 마이그레이션 작성
- [ ] 신규 구조 검증 후 legacy 상세 테이블 쓰기 중단
- [x] legacy repository/service 제거 범위 결정
- [x] API path 변경 여부 결정 (`/abuse-reports`)
- [x] 운영 데이터 이관 체크리스트 작성

## Phase 8. Legacy 상세 테이블 제거

- [x] `store_contribution_report`를 가게 추가/수정 상세 테이블로 확장
  - [x] `store_id`, `request_type`, `naver_place_id`, `name`, `address`, `road_address`, `phone`, `location`, `message`, `reason` 컬럼 추가
  - [x] 기존 `store_report`, `store_add_report` 데이터가 있으면 상세 컬럼으로 backfill
  - [x] `store_report_id`, `store_add_report_id` bridge 컬럼 제거 계획 수립
- [x] `menu_contribution_report`를 메뉴 추가/수정 상세 테이블로 확장
  - [x] `store_id`, `menu_id`, `menu_category_id`, `request_type`, `name`, `price`, `description`, `message` 컬럼 추가
  - [x] 기존 `menu_add_report`, 메뉴 수정용 `store_report` 데이터가 있으면 상세 컬럼으로 backfill
  - [x] `menu_add_report_id`, `store_report_id` bridge 컬럼 제거 계획 수립
- [x] 이미지 테이블을 contribution 기준으로 교체
  - [x] `store_contribution_report_image_file` 생성
  - [x] `menu_contribution_report_image_file` 생성
  - [x] 기존 이미지 데이터가 있으면 새 이미지 테이블로 backfill
- [x] 사용자 제보 생성 로직에서 legacy 상세 테이블 쓰기 중단
  - [x] `StoreReportService`가 `store_report` 없이 `store_contribution_report`에 직접 저장
  - [x] `StoreAddReportService`가 `store_add_report` 없이 `store_contribution_report`에 직접 저장
  - [x] `MenuAddReportService`가 `menu_add_report` 없이 `menu_contribution_report`에 직접 저장
- [x] 관리자 조회/상세/승인/거절 로직에서 legacy 엔티티 제거
  - [x] `AdminReportService`가 `StoreReport`, `StoreAddReport`, `MenuAddReport`를 직접 읽지 않도록 변경
  - [x] 승인/거절 시 `contribution_report.status`와 contribution 상세 상태만 갱신
  - [x] S3 이미지 이동/삭제가 신규 contribution image 테이블 기준으로 동작
- [x] legacy repository/service/entity 제거
  - [x] `StoreReportRepository`, `StoreAddReportRepository`, `MenuAddReportRepository` 제거
  - [x] `StoreReportImageFileRepository`, `MenuAddReportImageFileRepository` 제거
  - [x] `StoreReport`, `StoreAddReport`, `MenuAddReport` 엔티티 제거
  - [x] legacy 이미지 엔티티 제거
- [x] legacy DROP migration 작성
  - [x] `store_report_image_file` DROP
  - [x] `menu_add_report_image_file` DROP
  - [x] `store_report` DROP
  - [x] `store_add_report` DROP
  - [x] `menu_add_report` DROP
- [ ] 전체 테스트 통과 후 dev 배포
