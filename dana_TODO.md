# Store Add Report TODO

## 사용자 가게 추가 제보

- [x] 네이버 장소 검색 클라이언트 추가
- [x] 네이버 장소 검색 설정 추가 (`client-id`, `client-secret`, base URL)
- [x] `NaverPlaceSearchResult` 응답 DTO 추가
- [x] `GET /stores/add-reports/naver/search` 사용자 가게 추가용 네이버 장소 검색 API 추가
- [x] `store_add_report` 테이블 마이그레이션 추가
- [x] `StoreAddReport` 엔티티 추가
- [x] `StoreAddReportRepository` 추가
- [x] `StoreAddReportRequest` 요청 DTO 추가
- [x] `StoreAddReportResponse` 응답 DTO 추가
- [x] `StoreAddReportService` 추가
- [x] `POST /stores/add-reports` 사용자 가게 추가 제보 API 추가
- [x] 네이버 검색 결과의 `naverPlaceId`, 가게명, 주소, 좌표를 기반으로 제보 저장
- [x] `naverPlaceId`, 가게명, 주소, 좌표 기반 중복 제보/기존 가게 검증
- [x] Swagger 문서 추가

## 관리자 승인/거절

- [x] `ReportKind.STORE_ADD_REPORT` 추가
- [x] 관리자 제보 목록에 `STORE_ADD_REPORT` 포함
- [x] 관리자 제보 상세 응답에 가게 추가 제보 정보 포함
- [x] `PATCH /api/admin/reports/{id}`에서 `STORE_ADD_REPORT` 승인/거절 처리
- [x] 승인 시 `naverPlaceId` 포함해서 `store` 생성 또는 기존 가게 재사용
- [x] 거절 시 제보 상태만 `REJECTED`로 변경
- [x] 중복 승인 방지 및 이미 처리된 제보 예외 처리

## 알림

- [x] 승인/거절 후 기존 `ReportNotificationService.notifyReportResult(...)` 호출
- [x] 승인 알림에는 생성/재사용된 `Store` 전달
- [x] 거절 알림 문구가 제보한 가게명 기준으로 자연스럽게 표시되도록 처리

## 테스트

- [x] 사용자 가게 추가 제보 생성 테스트
- [x] 기존 가게/중복 제보 검증 테스트
- [x] 관리자 승인 시 가게 생성 테스트
- [x] 관리자 거절 시 상태 변경 테스트
- [x] 승인/거절 알림 호출 테스트
