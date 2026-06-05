# 영업시간 크롤링 — 구현 계획

작성일: 2026-05-27
상태: 계획 (구현 전)
의존성: `kakao-bulk-import` 완료 이후 시작

## 배경

`kakao-bulk-import` 로 적재한 store 데이터에는 영업시간이 없다. Kakao Local API 가 영업시간을 제공하지 않기 때문. 카카오맵 페이지 크롤링 + 네이버 플레이스 크롤링으로 영업시간을 보강하고 두 소스를 교차 검증해 신뢰도를 매긴다.

기존 `store_business_hour` 테이블을 그대로 사용. 휴게시간/라스트오더 컬럼만 추가.

---

## 1. 스키마 변경

### `store_business_hour` — 휴게시간/라스트오더 추가

```sql
ALTER TABLE store_business_hour ADD COLUMN break_start_time TIME;
ALTER TABLE store_business_hour ADD COLUMN break_end_time TIME;
ALTER TABLE store_business_hour ADD COLUMN last_order_time TIME;
```

- 3개 모두 nullable (대부분 가게엔 없음)
- 엔티티 `StoreBusinessHour.java` 에 LocalTime 필드 3개 + 빌더 인자 추가

### `store` — 크롤링 메타 컬럼 추가

```sql
ALTER TABLE store ADD COLUMN naver_place_id VARCHAR(50);
ALTER TABLE store ADD COLUMN hours_source VARCHAR(20);       -- KAKAO | NAVER | MERGED
ALTER TABLE store ADD COLUMN hours_confidence VARCHAR(20);   -- HIGH | MEDIUM | LOW
ALTER TABLE store ADD COLUMN hours_updated_at TIMESTAMP;
ALTER TABLE store ADD COLUMN hours_crawl_status VARCHAR(20); -- PENDING | DONE | FAILED | NO_DATA
ALTER TABLE store ADD COLUMN hours_failed_count INT DEFAULT 0;

CREATE INDEX idx_store_hours_crawl_status ON store(hours_crawl_status);
CREATE UNIQUE INDEX idx_store_naver_place_id ON store(naver_place_id) WHERE naver_place_id IS NOT NULL;
```

- `hours_crawl_status` 으로 재시도/완료 추적
- `naver_place_id` UNIQUE 인덱스로 중복 매칭 방지
- 별도 진행 상황 테이블 불필요 (`store` 에 직접 status 컬럼)

---

## 2. 패키지 구조

```
sweetgirl.croissant.admin.hoursenrich/
├── controller/
│   └── HoursEnrichController.java         # /api/admin/stores/enrich-hours/{start,stop,progress}
├── service/
│   ├── HoursEnrichOrchestrator.java       # bulk import 패턴 복제
│   ├── KakaoPlaceDetailCrawler.java       # 카카오 페이지 크롤러
│   ├── NaverPlaceMatcher.java             # 네이버 매처 (Local API + 좌표 매칭)
│   ├── NaverPlaceDetailCrawler.java       # 네이버 페이지 크롤러
│   ├── BusinessHoursMerger.java           # 두 소스 머지 + 신뢰도
│   └── BusinessHoursPersister.java        # store_business_hour 7 row 영속화
├── code/
│   ├── KakaoCrawlRateLimiter.java         # 카카오 전용 rate limiter
│   └── NaverCrawlRateLimiter.java         # 네이버 전용 rate limiter
├── dto/
│   ├── ParsedBusinessHours.java           # 파싱된 영업시간 표준 모델
│   └── NaverMatchResult.java              # 매칭 결과
└── config/
    └── HoursEnrichAsyncConfig.java        # @Async executor + rate limiter Bean
```

---

## 3. 크롤러 설계

### Phase A — 카카오 페이지 크롤러

- **Endpoint**: `https://place.map.kakao.com/main/v/{kakao_place_id}` (JSON, 비공식)
- 입력: `kakao_place_id`
- 출력: `ParsedBusinessHours` (요일별 open/close, 휴게, 라스트오더, 휴무)
- Rate limit: **1.5 req/sec** (월 300만 한도 — 안전선)
- User-Agent: 일반 mobile browser
- 실패 처리: 3회 retry → `hours_failed_count++`

### Phase B — 네이버 매처

1. **Naver Local Search API** 호출 (공식, 무료 25,000/day)
   ```
   GET https://openapi.naver.com/v1/search/local.json?query={name}+{region_3rd}&display=5
   Headers: X-Naver-Client-Id, X-Naver-Client-Secret
   ```
2. 응답에서 `mapx`/`mapy` (KATEC 좌표) → WGS84 변환
3. 카카오 좌표 ± **50m 이내** 면 매칭 성공
4. `link` URL 에서 naver place_id 추출 (`https://m.place.naver.com/restaurant/{id}` 형태)
5. `naver_place_id` 저장

### Phase C — 네이버 페이지 크롤러

- **Endpoint**: `https://map.naver.com/p/api/place/place-summary/{naver_place_id}?lang=ko` (JSON, 비공식)
- 또는 `https://m.place.naver.com/restaurant/{id}/home` HTML 파싱
- 응답 구조 PoC 에서 확인 필요
- Rate limit: **1 req/sec**

### Phase D — 머저

```
파싱된 ParsedBusinessHours 두 개 (카카오, 네이버) 입력
- 둘 다 있고 영업시간 ±30분 이내 일치 → MERGED, confidence=HIGH
- 둘 다 있지만 불일치 → MERGED, Naver 우선, confidence=MEDIUM
- 한쪽만 있음 → 그 소스 단독, confidence=MEDIUM
- 둘 다 없음 → status=NO_DATA, confidence=LOW
```

### Phase E — 영속화

`BusinessHoursPersister.save(storeId, merged)`:
- 기존 `store_business_hour` rows DELETE (`deleteByStoreId`)
- 새 7 rows INSERT (월~일)
- `store` 테이블 메타 컬럼 update (source, confidence, updated_at, status=DONE)

---

## 4. 오케스트레이터

`HoursEnrichOrchestrator` (bulk-import 패턴):

```java
public void runEnrich() {
    List<Store> targets = storeRepository.findByHoursCrawlStatusInOrderByIdAsc(
        List.of("PENDING", "FAILED"));
    for (Store store : targets) {
        if (shouldStop.get()) break;
        try {
            processOneStore(store);  // Kakao crawl → Naver match+crawl → merge → persist
        } catch (Exception e) {
            markFailed(store, e);
        }
    }
}
```

- `@Async(EXECUTOR_NAME)` 백그라운드 실행
- AtomicBoolean running/shouldStop
- `recoverStaleOnStartup`: ApplicationReadyEvent 에서 stale IN_PROGRESS → PENDING 복구
- 중단 가능, 재시작 시 PENDING/FAILED 만 처리

---

## 5. Endpoint 명세

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/admin/stores/enrich-hours` | 시작. 이미 실행 중이면 409 |
| POST | `/api/admin/stores/enrich-hours/stop` | 현재 가게 끝나고 종료 |
| GET | `/api/admin/stores/enrich-hours/progress` | running, totalStores, doneStores, failedStores, noDataStores, pendingStores |
| POST | `/api/admin/stores/{storeId}/enrich-hours/retry` | 특정 store 강제 재시도 (디버깅/스폿 보정용) |

`POST /enrich-hours` 에 `?force=true` 쿼리 옵션 → 모든 store 의 `hours_crawl_status` 를 PENDING 으로 초기화하고 처음부터 재실행.

---

## 6. Rate Limit 산정

| 소스 | 일 한도 | 페이스 | 24h 환산 |
|---|---|---|---|
| Kakao 페이지 | 월 300만 (= 일 100K) | 1.5 req/sec | 약 130K → 한도 근접 → 안전선 |
| Naver Local Search API | 일 25,000 | 0.3 req/sec | 약 25K → **보틀넥** |
| Naver Place 페이지 (크롤링) | 비공식 | 1 req/sec | 한도 없음, IP 차단 위험만 |

전체 스토어 23만개 가정:

- 카카오 크롤링: 23만 ÷ 1.5/sec ≈ **43시간**
- 네이버 매칭: 23만 ÷ 0.3/sec ≈ **8.5일** ← 보틀넥
- 네이버 페이지 크롤링: 매칭된 가게(약 65%, 15만) ÷ 1/sec ≈ **42시간**

→ 네이버 매칭 단계는 약 9-10 일 걸림.

### 보틀넥 완화 옵션

- **A. 카카오만 먼저 1차 완성** (43시간, MVP 데이터 확보)
- **B. 네이버로 보완** (백그라운드 9-10일 천천히)
- **C. 네이버 SEARCH API 한도 증가 신청** (네이버 측 승인 시 일 100K 까지 가능, 무료)

A → B 순차 진행 권장. A 끝나면 부분 데이터 노출 가능.

---

## 7. 환경 변수 / 인증

```properties
naver.search.client-id=<NAVER_DEVELOPERS_APP_CLIENT_ID>
naver.search.client-secret=<...>
kakao.crawl.user-agent=Mozilla/5.0 (...)
naver.crawl.user-agent=Mozilla/5.0 (...)
```

- 네이버 Developers (https://developers.naver.com) 앱 등록 필요 — 무료
- `application-local.properties` 에 추가 (기존 `kakao.api.key` 패턴)

---

## 8. ToS / 리스크 관리

- User-Agent 정상 브라우저 위장
- 1-2 req/sec rate limit (서버 부하 0)
- HTTP 403/429 다발 감지 시 자동 stop + 알림 로그
- Retry 3회 후 FAILED 마킹, 같은 가게 24h 재시도 안 함
- kakao_place_id, naver_place_id 로 결과 캐싱 → 중복 호출 방지
- 네이버 공식 Local Search API 는 ToS OK
- 카카오 `place.map.kakao.com` endpoint 와 네이버 페이지 크롤링은 **공식 문서 없는 회색 영역** — 자동 차단 감지 + slow rate 로 위험 최소화

---

## 9. PoC 체크리스트 (실구현 전 검증)

| 단계 | 검증 항목 | 방법 |
|---|---|---|
| 1 | 카카오 place 페이지 endpoint 응답 구조 | 임의 place_id 로 curl, JSON 받아 영업시간 필드 위치 확인 |
| 2 | 카카오 endpoint rate limit 행동 | 100회 연속 호출, 응답 코드 변화 확인 |
| 3 | 네이버 Local Search 응답 + 좌표 변환 | 빵집 1개로 API 호출 → mapx/mapy → WGS84 변환 검증 |
| 4 | 네이버 place 페이지에서 영업시간 추출 | 매칭된 place_id 로 HTML/JSON 받아 파싱 가능 여부 |
| 5 | 매칭 정확도 sample 검증 | 50개 가게로 매칭 → 수동 확인, 정확도 측정 |

PoC 만 약 **3-5시간** 예상.

---

## 10. 작업 시간 추정

| Phase | 작업 | 시간 |
|---|---|---|
| 사전 PoC | endpoint 응답 검증, 매칭 정확도 sample | 3-5h |
| 스키마 마이그레이션 | ALTER + 엔티티 수정 | 1h |
| Kakao 크롤러 + 파서 | 응답 → ParsedBusinessHours 변환 | 4h |
| Naver 매처 (Local Search + 좌표) | 검색 + 좌표 변환 + 매칭 | 4h |
| Naver 크롤러 + 파서 | HTML/JSON 파싱 | 4h |
| 머저 + 신뢰도 | 비교 로직 + persist | 3h |
| 오케스트레이터 + 엔드포인트 | bulk-import 패턴 복제 | 3h |
| Rate limiter (2개 분리) | Kakao/Naver 각각 | 1h |
| 테스트 + 디버깅 | | 6-8h |
| **합계** | | **약 28-34h** |

---

## 11. 진행 순서

1. **현재 도는 import 끝나기 기다림** (~17-22h 후)
2. **PoC**: 카카오 + 네이버 endpoint 응답 구조 확인 (3-5h)
3. **PoC 결과 검토** — 응답 구조에 따라 설계 조정
4. **스키마 마이그레이션** (1h)
5. **카카오 크롤러 + 오케스트레이터 + endpoint** → 1차 enrichment 완성 (12-15h)
6. **카카오만으로 enrichment 실행** (~43h 자동) — MVP 데이터 확보
7. **네이버 매처 + 크롤러 추가** (10h)
8. **네이버 enrichment 백그라운드 실행** (~10일 자동)

순차 분리로 부분 데이터 빠르게 확보, 나중에 보완.

---

## 12. 현재 store_business_hour 테이블 매핑

기존 구조:

```
store_business_hour
├── id (PK)
├── store_id (FK → store)
├── day_of_week (MONDAY..SUNDAY, 가게당 7 rows)
├── open_time (LocalTime, nullable)
├── close_time (LocalTime, nullable)
└── is_closed (boolean, 휴무 여부)
```

크롤링 후 매핑:

| 크롤링 데이터 | store_business_hour 매핑 |
|---|---|
| "월~금 09:00~22:00" | MONDAY~FRIDAY 5 rows, open=09:00, close=22:00, is_closed=false |
| "매주 일요일 휴무" | SUNDAY 1 row, is_closed=true |
| "15:00~17:00 브레이크" | 해당 row 의 break_start_time=15:00, break_end_time=17:00 |
| "라스트오더 21:30" | 해당 row 의 last_order_time=21:30 |
| 24시간 영업 | open=00:00, close=23:59 |
