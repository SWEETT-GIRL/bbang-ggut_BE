# LocalData 제과점 Import — 정부 인허가 데이터로 빵집 보강

작성일: 2026-05-29
대상 환경: Dev DB (`54.79.182.81:5432/soboro`)
선행 조건: **현재 진행 중인 Kakao dong import가 완료된 뒤 실행**한다. 둘 다 Kakao API를 쓰므로(dong=키워드 검색, 본 작업=지오코딩) 쿼터/레이트리밋 충돌 방지를 위해 동시 실행하지 않는다. 코드 구현은 미리 해두고 실행만 미룬다.

## 1. 목표

행정안전부 **제과점영업 인허가 데이터(LocalData, data.go.kr 데이터셋 15155252)**를 사용해:

1. **누락 빵집 추가** — Kakao 키워드 검색이 놓친 정상영업 제과점을 신규 Store로 적재 (정부 전수 데이터로 커버리지 보강)
2. **기존 Store enrich** — 이미 있는 가게(주로 Kakao 출신)에 LocalData를 매칭해 `localDataId` 연결 + 빈 필드만 보충

**최우선 불변식: 기존 Store와 중복을 절대 만들지 않는다.** 신규 삽입은 3중 중복 가드를 모두 통과한 경우에만 수행한다. 애매하면 "신규 삽입 안 함(enrich로 전환)" 쪽으로 보수적으로 동작한다.

## 2. 핵심 결정사항

| 항목 | 결정 | 비고 |
|------|------|------|
| 목적 | 신규 추가 + 기존 enrich 둘 다 | |
| 데이터 소스 | 행정안전부 제과점영업 인허가 (LocalData) | data.go.kr 15155252 |
| 작업 단위 | **지자체코드(localCode) 단위로 전국 순회** | dong의 동 단위 대응 |
| 영업상태 필터 | **정상영업만** | 폐업·휴업 제외 |
| 멱등성 키 | `Store.localDataId` = LocalData **관리번호** (UNIQUE) | 신규 컬럼 |
| 중복 가드 | **3중** (localDataId / 이름+주소 정규화 / 좌표 근접+이름유사) | 하나라도 걸리면 신규삽입 금지 |
| 신규 가게 좌표 | **Kakao 지오코딩**(주소→WGS84) | LocalData TM 좌표 신뢰 낮음 + 좌표계 이슈 회피 |
| enrich 범위 | `localDataId` 연결 + **빈 필드만** 보충 | 기존 값은 유지 |
| `source` | `LOCALDATA_IMPORTED` (신규 enum 값) | 신규 삽입분 |
| 실행 주체 | `POST /api/admin/localdata/import/start` | Async fire-and-forget |
| 진행상황 추적 | `localdata_import_progress` 테이블 | 지역(localCode) 단위 status |
| Rate limit | `BulkImportRateLimiter` 재사용 | 지오코딩 Kakao 호출 제어 |
| 재시작 | `PENDING` + `FAILED` 지역만 재처리 | `IN_PROGRESS`는 startup 시 `PENDING` 복귀 |

**미확정 1건:** LocalData 정확한 엔드포인트 URL·파라미터명·응답 필드명. data.go.kr 페이지가 JS 렌더링이라 사전 확인 불가, 샌드박스에서 `localdata.go.kr` 호스트 차단됨. → **구현 Task 1에서 실서버 라이브 프로브로 확정** 후 진행 (활용가이드/Swagger 기준). 본 문서의 응답 필드명은 LocalData 계열 표준 추정값.

## 3. 컴포넌트 구조

```
sweetgirl.croissant.admin.localdata/
├── controller/
│   └── LocalDataImportController.java       # start/progress/stop 엔드포인트
├── service/
│   ├── LocalDataImportOrchestrator.java     # @Async fire-and-forget, 지역(localCode) 단위 루프
│   ├── RegionImporter.java                  # 한 개 지역 처리 (페이지 순회 + 레코드별 dedup)
│   ├── StoreMatcher.java                    # 중복 가드 핵심: 정규화 매칭 + 좌표 근접 (독립 테스트)
│   ├── LocalDataStoreSaver.java             # REQUIRES_NEW + 신규삽입 vs enrich 분기
│   └── LocalDataImportProgressService.java  # 진행상황 조회/업데이트
├── entity/
│   └── LocalDataImportProgress.java
├── repository/
│   └── LocalDataImportProgressRepository.java
└── (enum) — 필요 시 영업상태/업종 코드 상수

external/localdata/
├── client/   LocalDataClient.java           # RestClient, 페이지 순회 수집
├── config/   LocalDataProperties.java       # @ConfigurationProperties("localdata.api")
└── dto/      LocalDataPlace.java 등 응답 매핑

신규 리소스:
└── src/main/resources/static/region_code.csv  # 지자체코드 리스트 (개방자치단체코드)

수정 대상:
├── store/entity/Store.java                  # localDataId 컬럼 + enrich용 세터/도메인 메서드
├── store/repository/StoreRepository.java    # 매칭 조회 + localDataId 멱등 삽입 + enrich 업데이트
└── common/.../StoreSource.java              # LOCALDATA_IMPORTED 추가
```

**재사용:** `KakaoLocalClient`(지오코딩 — 주소 검색), `BulkImportRateLimiter`, `AddressParser`, Async Executor 설정 패턴.

## 4. 데이터 흐름

```
LocalDataImportController.start()
  └─> LocalDataImportOrchestrator.runAsync()                # @Async
        ├─> ApplicationReadyEvent 시 IN_PROGRESS → PENDING 복구 (별도 리스너)
        ├─> running 플래그 set (AtomicBoolean) — 동시 트리거 409 차단
        ├─> region_code.csv 로드
        ├─> PENDING/FAILED 지역 조회
        └─> for each 지역(localCode):
              RegionImporter.importRegion(localCode)
                ├─> status = IN_PROGRESS
                ├─> for each page (LocalData, 정상영업만 필터):
                │     rateLimiter.acquire()
                │     places = localDataClient.fetch(localCode, page)   # 페이지 끝까지
                ├─> for each place:
                │     LocalDataStoreSaver.save(place)   # REQUIRES_NEW
                │       ├─ 가드(a): localDataId 존재? → skip
                │       ├─ 가드(b): 이름+주소 정규화 매칭(StoreMatcher)? → enrich
                │       ├─ Kakao 지오코딩(주소→WGS84)  [rateLimiter.acquire()]
                │       │     실패/좌표없음 → 보류 skip (FAILED 아님, found만 집계)
                │       ├─ 가드(c): 반경 50m + 이름유사 기존 Store? → enrich
                │       └─ 전부 통과 → 신규 INSERT (좌표=지오코딩 결과)
                └─> status = COMPLETED, found/inserted/enriched/last_attempted_at 업데이트
```

## 5. 중복 가드 상세 (StoreMatcher)

신규 삽입은 아래 3개를 **모두 통과**해야 한다. 하나라도 걸리면 신규삽입 금지.

### (a) localDataId 멱등성
- `Store.localDataId == 관리번호`인 행이 이미 있으면 skip (같은 LocalData 재실행 대비).

### (b) 이름 + 주소 정규화 매칭
- **이름 정규화:** 공백 제거, 괄호/지점표기 정리, 대소문자·전각/반각 통일 (기존 Naver 매칭 정규화 로직 참고).
- **주소 정규화:** 도로명/지번 주소에서 시도·시군구·읍면동 + 본번-부번 또는 도로명+건물번호 핵심 토큰 추출.
- 정규화 이름 일치 **AND** 주소 핵심 토큰 일치 → 동일 가게로 판정 → enrich.

### (c) 좌표 근접 + 이름 유사도
- (b)에서 못 잡은 경우, Kakao 지오코딩으로 얻은 WGS84 좌표 기준 **반경 50m 이내** + **이름 유사도 임계 이상**인 기존 Store가 있으면 동일 가게 → enrich.
- 둘 다(근접 AND 이름유사) 만족할 때만 동일 판정 (상가 밀집지역 옆가게 오매칭 방지).
- 공간 조회는 PostGIS `ST_DWithin` 사용. 이름 유사도/정규화는 순수 함수로 분리해 단위 테스트.

**enrich 동작:** 매칭된 기존 Store에 `localDataId` 연결. 그 외 필드는 **비어있을 때만** LocalData 값으로 채움(`address`, `region1~3`, `phone` 등). 기존 값이 있으면 유지. 신규 삽입분은 LocalData(+지오코딩 좌표)로 전부 채움.

## 6. DB 스키마

### 6-1. `localdata_import_progress` (신규)

```sql
CREATE TABLE localdata_import_progress (
    id                BIGSERIAL PRIMARY KEY,
    local_code        VARCHAR(20)  NOT NULL,
    region_name       VARCHAR(100),
    status            VARCHAR(20)  NOT NULL,
    found_count       INT          NOT NULL DEFAULT 0,
    inserted_count    INT          NOT NULL DEFAULT 0,
    enriched_count    INT          NOT NULL DEFAULT 0,
    error_message     TEXT,
    last_attempted_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (local_code)
);
CREATE INDEX idx_localdata_import_status ON localdata_import_progress(status);
```

**상태 흐름:** `PENDING` → `IN_PROGRESS` → `COMPLETED` / `FAILED`

### 6-2. `store` 변경

```sql
ALTER TABLE store ADD COLUMN local_data_id VARCHAR(50);
ALTER TABLE store ADD CONSTRAINT uq_store_local_data_id UNIQUE (local_data_id);
```

- local 프로파일 `ddl-auto=validate`이므로 위 SQL을 dev DB에 수동 실행하거나 default 프로파일로 1회 부팅 필요 (dong 작업과 동일 이슈).
- `StoreSource`에 `LOCALDATA_IMPORTED` 값 추가.

## 7. LocalData API 연동 (추정 — Task 1에서 확정)

- **계열:** LocalData(지방행정 인허가데이터) 제과점영업.
- **업종:** 제과점영업 (opnSvcId 추정 `07_24_04_P` — 확정 필요).
- **지역 파라미터:** `localCode`(개방자치단체코드). `region_code.csv`로 전국 코드 적재 후 순회.
- **페이징:** pageIndex/pageSize (또는 pageNo/numOfRows) — 응답 totalCount까지 순회.
- **인증:** `localdata.api.key` 프로퍼티 (data.go.kr 발급 서비스키).
- **응답(JSON) 추정 필드:** 관리번호(고유키), 사업장명(bplcNm), 도로명주소(rdnWhlAddr), 지번주소(siteWhlAddr), 영업상태명(trdStateNm), 인허가일자(apvPermYmd), 좌표 x/y(중부원점 TM — 본 작업에선 미사용, 좌표는 Kakao 지오코딩으로 대체).
- 실제 호스트/경로/파라미터명/필드명은 **활용가이드/Swagger + 라이브 프로브로 확정**.

## 8. 에러 처리 & 재시도

| 발생 위치 | 에러 유형 | 처리 |
|----------|---------|------|
| LocalData API | 일시적(5xx/timeout) | 즉시 3회 재시도(exp backoff). 실패 시 지역 `FAILED` 후 다음 지역 |
| LocalData API | 인증 실패(키 오류) | orchestrator 즉시 종료 + `log.error`. 사람 확인 |
| Kakao 지오코딩 | 좌표 못 구함/일시 실패 | 해당 레코드 **신규삽입 보류 skip** (found만 집계, 지역은 FAILED 아님). 다음 실행 때 재시도 가능 |
| Kakao 지오코딩 | 429 rate limit | 대기 후 재시도. dong과 동시 실행 금지로 충돌 최소화 |
| store 저장 | 단일 INSERT/UPDATE 실패 | `REQUIRES_NEW`로 한 건만 롤백, `log.warn`, 다음 레코드 |
| 지역 처리 | 예상치 못한 Exception | catch → `FAILED` + error_message, 다음 지역 |
| CSV 파싱 | 파일 손상/없음 | startup 실패 |

### 재시작 정책
- `start` 재호출 → `PENDING` + `FAILED` 지역만 처리
- `ApplicationReadyEvent` 리스너에서 `IN_PROGRESS` → `PENDING` 복구
- `AtomicBoolean running` 동시 트리거 차단(409)

## 9. API 엔드포인트

`/api/admin/localdata/import` prefix. 관리자 인증 필요. 응답은 `ApiResponse<T>`.

### 9-1. `POST /api/admin/localdata/import/start`
**202:**
```json
{ "success": true, "code": "OK", "message": "LocalData import started",
  "data": { "totalRegions": 250, "pendingRegions": 250, "completedRegions": 0 } }
```
**409 (이미 실행 중):** `{ "success": false, "code": "LOCALDATA_IMPORT_ALREADY_RUNNING" }`

### 9-2. `GET /api/admin/localdata/import/progress`
```json
{ "success": true, "data": {
    "running": true, "totalRegions": 250, "completedRegions": 120, "failedRegions": 2,
    "inProgressRegions": 1, "pendingRegions": 127,
    "totalInserted": 5821, "totalEnriched": 9043 } }
```

### 9-3. `POST /api/admin/localdata/import/stop`
`shouldStop = true` 셋. 현재 지역 마치고 종료. 재호출 시 재개.

## 10. 로깅 & 관측
- 지역 단위 INFO: `[LocalData] 11110 종로구 - found=312, inserted=41, enriched=205, skipped=66 (12.3s)`
- N개 지역마다 누적 진행률 로그
- progress 엔드포인트로 외부 확인

## 11. 테스트 전략

| 레이어 | 테스트 |
|--------|------|
| `StoreMatcher` (이름/주소 정규화, 이름 유사도) | 순수 함수 단위테스트 — 일치/불일치/엣지케이스 |
| `LocalDataStoreSaver` | Mockito — 3중 가드 분기(skip/enrich/insert), enrich 시 빈 필드만 채움 검증 |
| `RegionImporter` | Mockito로 `LocalDataClient`+지오코딩 모킹 → 페이지 순회, 정상영업 필터, dedup, status |
| `LocalDataImportOrchestrator` | Mockito — 진행상황, 동시실행 방지, ApplicationReadyEvent 복구 |
| `LocalDataClient` | 응답 JSON 파싱 단위테스트 |
| 컨트롤러 | MockMvc로 엔드포인트 응답 검증 |
| 공간 조회(`ST_DWithin`) | H2 PostGIS 미지원 → 좌표 근접 판정 로직을 순수 함수로 분리 테스트 + dev DB 수동 검증 |

**E2E:** 단일 지역(예: 종로구 11110)만 먼저 실행해 sanity check(특히 중복 0건 확인) → 전국 실행.

## 12. 운영 절차
1. dev DB에 `localdata_import_progress` 생성 + `store.local_data_id` 컬럼/유니크 추가
2. `region_code.csv` 배치 후 빌드/배포, `localdata.api.key` 설정
3. **dong import 완료 확인** 후 `POST .../localdata/import/start`
4. progress 모니터링, 단일 지역 sanity check에서 중복 0 확인
5. 완료 후 `failed`/`skipped` 확인, 필요 시 재호출

## 13. 비스코프
- 운영 DB promote (별도)
- LocalData 좌표(TM) 직접 사용 — Kakao 지오코딩으로 대체하므로 좌표 변환 라이브러리 미도입
- 폐업/휴업 가게의 status 역반영 (정상영업만 다룸)
- 영업시간·전화번호 강제 수집 (LocalData 미제공 — phone은 있으면 빈 필드 보충에만 사용)
- 사람 검수 UI
