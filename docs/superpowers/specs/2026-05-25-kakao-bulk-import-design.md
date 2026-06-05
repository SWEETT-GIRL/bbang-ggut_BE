# Kakao Bulk Import — Dev DB 전국 빵집/카페 적재

작성일: 2026-05-25
대상 환경: Dev DB (`54.79.182.81:5432/soboro`)
스코프: 본 작업은 **DB 적재**까지만 다룬다. 런타임에서 Kakao API 의존성을 제거하는 작업은 별도 후속.

## 1. 목표

Dev DB의 `store` 테이블에 전국 빵집/카페를 **90% 이상 커버리지**로 적재한다.
- Kakao Local API에서 행정안전부 동 리스트 기반으로 키워드 검색 후 수집
- 기존 `KakaoLocalClient`의 자동 필터(카테고리 키워드 + 프랜차이즈 블랙리스트) 그대로 적용
- 통과한 store를 `INSERT IF NOT EXISTS BY kakao_place_id`로 적재
- 운영 DB로의 promote는 본 작업 범위 외 (별도 작업)

## 2. 핵심 결정사항

| 항목 | 결정 | 비고 |
|------|------|------|
| 검수 방식 | 자동 필터링만 | 기존 `KakaoCategoryCode` + `FranchiseName` 룰 재사용 |
| 적재 위치 | Dev DB `store` 테이블 직접 INSERT | `insertIfNotExistsByKakaoPlaceId` |
| 동 리스트 소스 | 행정안전부 주소계 데이터 | CSV로 `resources/static/`에 포함 |
| 실행 주체 | `POST /admin/stores/bulk-import` | Async fire-and-forget |
| 진행상황 추적 | `bulk_import_progress` 테이블 | 동 단위 status |
| 45 cap 처리 | 동 × 11 키워드 조합 | 합집합으로 수집 |
| `category` 할당 | Kakao `category_name`에 `양식` 포함 시 `WESTERN`, 그 외 `null` | |
| `source` | `KAKAO_IMPORTED` | |
| Rate limit | 5 req/sec (Guava RateLimiter) | 단일 스레드 |
| 재시작 | `PENDING` + `FAILED` 동만 재처리 | `IN_PROGRESS`는 startup 시 `PENDING` 복귀 |

**작업 규모 추정:** 20,000 동 × 11 키워드 × 2 카테고리 × 평균 1.5페이지 ≈ **66만 API 호출**, 5 req/sec 기준 약 **37시간**.

## 3. 컴포넌트 구조

```
sweetgirl.croissant.admin.bulkimport/
├── controller/
│   └── BulkImportController.java          # POST/GET 엔드포인트
├── service/
│   ├── BulkImportOrchestrator.java        # @Async fire-and-forget, 동 단위 루프
│   ├── DongImporter.java                  # 한 개 동 처리 (11 키워드 × 페이지 순회)
│   ├── BulkImportRateLimiter.java         # Guava RateLimiter 래퍼 (5 req/sec)
│   ├── BulkImportStoreSaver.java          # REQUIRES_NEW + category 매핑 + insertIfNotExistsByKakaoPlaceId
│   └── BulkImportProgressService.java     # 진행상황 조회/업데이트
├── entity/
│   └── BulkImportProgress.java
├── repository/
│   └── BulkImportProgressRepository.java
└── enums/
    └── BulkSearchKeyword.java

신규 리소스:
└── src/main/resources/static/dong_list.csv   # 행정안전부 동 리스트

수정 대상:
└── store/repository/StoreRepository.java     # insertIfNotExistsByKakaoPlaceId에 category 파라미터 추가
                                              # (기존 호출처는 null 넘기도록 호환 유지)
```

**재사용:** `KakaoLocalClient.searchByKeyword`, `AddressParser`, `StoreRepository.insertIfNotExistsByKakaoPlaceId` (확장 후).

## 4. 데이터 흐름

```
BulkImportController.start()
  └─> BulkImportOrchestrator.runAsync()                    # @Async
        ├─> ApplicationReadyEvent 시 IN_PROGRESS → PENDING 복구 (별도 리스너)
        ├─> running 플래그 set (AtomicBoolean)
        ├─> dong_list.csv 로드
        ├─> PENDING/FAILED 동 조회
        └─> for each 동:
              DongImporter.importDong(dong)
                ├─> status = IN_PROGRESS 업데이트
                ├─> for each (BulkSearchKeyword × page until isEnd):
                │     rateLimiter.acquire()  (5 req/sec)
                │     places = kakaoLocalClient.searchByKeyword(
                │                   "{region1st} {region2nd} {region3rd} {keyword}", page)
                │     # 내부적으로 FD6 + CE7 양쪽 호출 + 키워드/프랜차이즈 필터링
                │     allPlaces.addAll(places)
                ├─> kakao_place_id 기준 in-memory dedup
                ├─> for each place:
                │     BulkImportStoreSaver.save(place)   # REQUIRES_NEW
                │       └─> deriveCategory(place.categoryName)
                │       └─> insertIfNotExistsByKakaoPlaceId(..., category)
                └─> status = COMPLETED, found/inserted/last_attempted_at 업데이트
```

## 5. DB 스키마

### 5-1. `bulk_import_progress` (신규)

```sql
CREATE TABLE bulk_import_progress (
    id                BIGSERIAL PRIMARY KEY,
    region_1st        VARCHAR(50)  NOT NULL,
    region_2nd        VARCHAR(50)  NOT NULL,
    region_3rd        VARCHAR(50)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    found_count       INT          NOT NULL DEFAULT 0,
    inserted_count    INT          NOT NULL DEFAULT 0,
    error_message     TEXT,
    last_attempted_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (region_1st, region_2nd, region_3rd)
);
CREATE INDEX idx_bulk_import_status ON bulk_import_progress(status);
```

**상태 흐름:** `PENDING` → `IN_PROGRESS` → `COMPLETED` / `FAILED`

### 5-2. `store` (변경 없음)

기존 스키마 그대로 사용. `source = KAKAO_IMPORTED`, `category` nullable.

### 5-3. `StoreRepository.insertIfNotExistsByKakaoPlaceId` 시그니처 확장

```java
// 기존
void insertIfNotExistsByKakaoPlaceId(
    String name, String address, String region1st, String region2nd, String region3rd,
    double x, double y, String phone, String kakaoPlaceId);

// 확장
void insertIfNotExistsByKakaoPlaceId(
    String name, String address, String region1st, String region2nd, String region3rd,
    double x, double y, String phone, String kakaoPlaceId,
    String category);  // "WESTERN" or NULL
```

기존 호출처 (`StoreService.registerKakaoStore`, `IndividualStoreSaver`)는 새 시그니처에 `null`을 넘기도록 함께 수정한다. 동작 변화는 없다 (해당 경로는 category 정보를 가지고 있지 않음).

## 6. Category 매핑

```java
public static StoreCategory deriveCategory(String kakaoCategoryName) {
    if (kakaoCategoryName == null) return null;
    if (kakaoCategoryName.contains("양식")) return StoreCategory.WESTERN;
    return null;
}
```

예: `"음식점 > 양식 > 베이커리"` → `WESTERN`, `"음식점 > 간식 > 도넛"` → `null`.

## 7. 검색 키워드

```java
public enum BulkSearchKeyword {
    브런치, 빵집, 베이커리, 디저트, 소금빵, 빵, 도넛, 케이크, 에그타르트, 휘낭시에, 구움과자;
}
```

쿼리 형식: `"{region1st} {region2nd} {region3rd} {keyword}"`
예: `"서울특별시 강남구 역삼동 베이커리"`

페이지: `KakaoSearchResult.isEnd == true`까지 진행 (보통 1-2페이지).

## 8. 에러 처리 & 재시도

| 발생 위치 | 에러 유형 | 처리 |
|----------|---------|------|
| Kakao API | 일시적 (5xx, timeout) | 즉시 3회 재시도 (exp backoff 1s/3s/9s). 실패 시 동 `FAILED` 후 다음 동으로 |
| Kakao API | 429 rate limit | 30초 대기 후 재시도 (최대 3회). 그래도 실패 시 현재 동을 `PENDING`으로 되돌리고 orchestrator의 async 스레드 내에서 `Thread.sleep(1h)` 후 다음 동부터 루프 재개 |
| Kakao API | 401/403 인증 만료 | orchestrator 즉시 종료, `log.error`. 사람이 확인 |
| place 저장 | 단일 INSERT 실패 | `REQUIRES_NEW`로 한 건만 롤백, `log.warn`, 다음 place로 |
| 동 처리 | 예상치 못한 Exception | catch → `FAILED` + error_message, 다음 동 진행 |
| CSV 파싱 | 파일 손상/없음 | startup 실패 |

### 재시작 정책

- `POST /admin/stores/bulk-import` 다시 호출 → `PENDING` + `FAILED` 동만 처리
- `ApplicationReadyEvent` 리스너에서 `IN_PROGRESS` → `PENDING` 일괄 복구 (서버 다운 대비)
- `AtomicBoolean running` 플래그로 동시 트리거 차단 (409 Conflict)

## 9. API 엔드포인트

모두 `/admin/stores/bulk-import` prefix. 관리자 인증 필요.

### 9-1. `POST /admin/stores/bulk-import` — 시작

**Response 202:**
```json
{
  "success": true,
  "code": "OK",
  "message": "Bulk import started",
  "data": {
    "totalDongs": 20142,
    "pendingDongs": 19587,
    "completedDongs": 555
  }
}
```

**409 Conflict (이미 실행 중):**
```json
{ "success": false, "code": "BULK_IMPORT_ALREADY_RUNNING", "message": "..." }
```

### 9-2. `GET /admin/stores/bulk-import/progress`

```json
{
  "success": true,
  "data": {
    "running": true,
    "totalDongs": 20142,
    "completedDongs": 8732,
    "failedDongs": 12,
    "inProgressDongs": 1,
    "pendingDongs": 11397,
    "totalInserted": 142583,
    "estimatedCompletionTime": "2026-05-26T14:30:00"
  }
}
```

### 9-3. `GET /admin/stores/bulk-import/failed`

```json
{
  "success": true,
  "data": [
    { "region1st": "서울특별시", "region2nd": "강남구", "region3rd": "역삼동",
      "errorMessage": "Kakao API timeout", "lastAttemptedAt": "..." }
  ]
}
```

### 9-4. `POST /admin/stores/bulk-import/stop` (선택)

`shouldStop = true` 셋. 현재 동 처리 마치고 종료. 재호출 시 재개 가능.

## 10. 로깅 & 관측

- 동 단위 INFO: `[BulkImport] 서울특별시 강남구 역삼동 - found=23, inserted=18, skipped=5 (4.2s)`
- 100동마다 누적: `[BulkImport] 진행률 543/20142 (2.7%) — 누적 inserted=8421`
- 진행상황 endpoint로 외부 확인

## 11. 테스트 전략

| 레이어 | 테스트 |
|--------|------|
| `deriveCategory` | 파라미터화 단위테스트 (양식 포함 / 미포함 / null) |
| `BulkImportStoreSaver` | 단위테스트 — category 매핑 + saver 호출 검증 |
| `DongImporter` | Mockito로 `KakaoLocalClient` 모킹 → 11 키워드 × 페이지 순회, dedup, status 업데이트 |
| `BulkImportOrchestrator` | Mockito — 진행상황 업데이트, 동시실행 방지, ApplicationReadyEvent 복구 |
| 컨트롤러 | MockMvc로 endpoint 응답 검증 |
| `StoreRepository.insertIfNotExistsByKakaoPlaceId` (확장) | H2가 PostGIS 미지원 → 통합테스트는 수동 dev DB 검증으로 대체 |

**E2E:** `강남구` 단일 시군구만 먼저 실행해 sanity check → 전체 실행.

## 12. 운영 절차

1. dev DB에 `bulk_import_progress` 테이블 생성 (Hibernate auto-DDL or 수동 DDL)
2. `dong_list.csv`를 `resources/static/`에 배치 후 빌드/배포
3. `POST /admin/stores/bulk-import` 호출
4. `GET /admin/stores/bulk-import/progress`로 진행 모니터링 (수십 시간 소요)
5. 완료 후 `failed` 목록 확인, 필요 시 재호출
6. (별도 작업) dev → prod promote

## 13. 비스코프

- 운영 DB로의 데이터 promote (별도 작업)
- 런타임에서 Kakao API 의존성 제거 (별도 작업)
- 영업시간(`StoreBusinessHour`), 이미지(`StoreImageFile`), 메뉴(`Menu`) 수집 (Kakao Local API가 제공 안 함)
- 사람 검수 UI (자동 필터만 사용)
- 카카오 외 다른 데이터 소스(네이버 지도 등)
