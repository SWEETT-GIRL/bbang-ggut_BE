# feat/custom-business-metrics — 비즈니스 커스텀 지표 계측

> 선행 작업: `dev`의 `6bc3284`(actuator·마이크로미터 설정), `57fa23b`(관리 포트 바인딩).
> 인프라 지표(JVM·HTTP·커넥션풀·TPS)는 이미 수집 중이며, 이 브랜치는 **애플리케이션 고유 지표**만 다룬다.

---

## 1. Spec — 요구사항

운영 중 다음 질문에 답할 수 있어야 한다.

| 질문 | 현재 | 목표 |
|------|------|------|
| 이미지 업로드가 정상 처리되고 있나? | 로그를 grep | 발급 대비 콜백 도달률 그래프 |
| Lambda 콜백이 밀리고 있나? | 알 수 없음 | 미처리 콜백 적체 수 |
| 푸시가 실제로 나가고 있나? | 알 수 없음 | 발송 성공/실패 카운터 |
| 무효 토큰이 쌓이고 있나? | 알 수 없음 | 무효 토큰 비율 |
| 검색이 결과를 못 주고 있나? | 알 수 없음 | 무결과 검색 비율 |
| 주간 색인 배치가 돌긴 했나? | 로그를 확인 | 마지막 성공 시각 gauge |

---

## 2. 지표 설계

### A. 이미지 리사이즈 파이프라인

| 지표 | 타입 | 태그 | 계측 위치 |
|------|------|------|-----------|
| `image_presigned_url_issued_total` | Counter | `folder` | `S3Service#generatePresignedUrl` |
| `image_processing_callback_total` | Counter | `status`(DONE/FAILED), `folder` | `ImageCallbackService#updateImageStatus` |
| `image_processing_orphan_callback_total` | Counter | `folder` | 이미지 row 없이 먼저 도착한 콜백 |
| `image_processing_pending_callbacks` | Gauge | — | `ImageProcessingCallbackRepository#count` |
| `image_callback_auth_failure_total` | Counter | — | `ImageCallbackController` 401 분기 |

**도달률 (PromQL)**
```promql
sum(rate(image_processing_callback_total[10m]))
  / sum(rate(image_presigned_url_issued_total[10m]))
```
1에 가까우면 정상. 지속적으로 낮으면 Lambda 중단 또는 업로드 실패.

**`folder` 태그** — `S3Folder` enum 기반(stores/menus/reviews/courses/reports/menu-add-reports)이라 카디널리티가 7 이하로 고정된다.

### B. FCM

| 지표 | 타입 | 태그 | 계측 위치 |
|------|------|------|-----------|
| `fcm_send_total` | Counter | `result`, `error_code` | `FcmService#sendPushNotification` |

`result` = `success` / `invalid_token` / `auth_error` / `error`
`error_code` = `MessagingErrorCode` enum 또는 `none`

기존 예외 분기가 이미 세 갈래로 나뉘어 있어 그대로 매핑한다.

**무효 토큰 비율**
```promql
sum(rate(fcm_send_total{result="invalid_token"}[1h])) / sum(rate(fcm_send_total[1h]))
```

### C. 검색 품질 — ✅ 구현 완료

| 지표 | 타입 | 태그 | 계측 위치 |
|------|------|------|-----------|
| `search_query_total` | Counter | `type`(search/autocomplete), `outcome` 5종 | `StoreSearchService` |

검색 지연 p95는 기존 `opensearch_query` Timer로 이미 커버된다.

**`outcome` 값 — 판정 신뢰도별로 분리**

실패를 한 값으로 뭉치면 오탐 때문에 지표 전체를 못 믿게 되므로 확실도에 따라 나눈다.

| 값 | 의미 | 확실도 |
|----|------|--------|
| `hit` | 입력한 말이 결과에 있음 | — |
| `empty` | 폴백까지 돌려도 0건 | 확실 |
| `fallback` | 1차 0건 → 빵 분해 폴백으로 건짐. 시스템이 검색어를 깎았음 | 확실 |
| `mismatch` | 전부 지역·빵으로 분류된 검색어인데 담당 필드에 없음 | 높음 |
| `mismatch_loose` | 사전에 없는 말이 섞여 통째 대조했고 실패. 오탐 섞임 | 낮음 |

**판정 규칙** (`SearchQueryCoverage`)

`remainingTokens`(사전에 없는 말)가 남았는지가 "쪼개서 볼지 / 붙여서 볼지"를 가른다.

```
remainingTokens 없음 → 조합 검색. 지역 토큰은 지역 필드, 빵 토큰은 메뉴·빵종류 필드에서 각각 대조
remainingTokens 있음 → 가게명 검색. cleanQuery 전체를 한 필드에서 통째로 대조
```

쪼개면 안 되는 이유: `"맘모스 베이커리"`를 토큰으로 나누면 **메뉴에 맘모스가 있고 이름에 베이커리가 든
무관한 가게(어글리 베이커리)가 통과**한다. 통째 대조라야 걸러진다.

- 불용어만 남은 잔여 토큰은 무시한다(`"성수동 빵집"`이 통째 대조로 새지 않도록).
- 동의어(`시오빵`→`소금빵`)는 `breadTypes`가 `MenuCategory` 대표어를 들고 있어 자연히 걸린다.
- **알려진 한계**: `"성수동 어글리베이커리"`처럼 지역어 + 가게명 조합은 mismatch_loose로 오탐된다.
  완화를 시도하면 위의 어글리 베이커리 케이스가 다시 통과해버려 엄격한 쪽을 택했다.
  지표를 실제보다 좋게 보이게 만들지 않는 방향의 오차다.

**검색어 원문** — 카디널리티가 무한해 태그로 쓸 수 없다. `outcome != hit`일 때 로그로만 남긴다.
PostHog 전송은 별도 작업으로 분리.

**Grafana**
```promql
sum by (outcome) (rate(search_query_total[5m]))                     # outcome별 추이
sum(rate(search_query_total{outcome=~"empty|fallback|mismatch"}[10m]))
  / sum(rate(search_query_total[10m]))                              # 오탐 없는 실패율
```
Counter는 최초 증가 전까지 노출되지 않으므로 `@PostConstruct`에서 시계열을 사전 등록한다.

### D. 색인 배치

| 지표 | 타입 | 계측 위치 |
|------|------|-----------|
| `opensearch_index_batch_duration_seconds` | Timer(histogram) | `StoreIndexScheduler#weeklyScoreUpdate` |
| `opensearch_index_batch_documents_total` | Counter | 위와 동일 |
| `opensearch_index_batch_last_success_timestamp_seconds` | Gauge | 위와 동일 |

**배치 미실행 감지**
```promql
time() - opensearch_index_batch_last_success_timestamp_seconds > 8 * 24 * 3600
```
주간 배치이므로 8일 넘게 갱신이 없으면 실패로 본다.

---

## 3. 범위에서 제외 (확정)

### ① 리사이즈 소요시간 — **제외 확정**

정확히 재려면 "presigned 발급 → 콜백 수신" 시각 차이가 필요한데, 현재 구조로는 불가능하다.

- 발급 시각을 저장하는 곳이 없다 (`generatePresignedUrl`은 DB에 아무것도 안 남긴다)
- `ImageFile.createdAt`은 row 생성 시각이고, **콜백보다 늦게 생성되는 경우가 실제로 있다**
  (`ImageCallbackService`에 "이미지 row 생성 전 lambda 콜백 수신" 분기가 존재)

따라서 `createdAt` 기반 계산은 음수가 나오거나 무의미한 값이 된다.

**대안:** Lambda가 콜백 body에 자신의 처리 시간을 실어주면 정확히 잴 수 있다. `ImageStatusUpdateRequest`에 필드 추가 + Lambda 코드 수정이 필요하므로 **이번 범위에서 제외**하고 별도 작업으로 분리한다.

### ② PENDING 이미지 적체 수 — **제외 확정**

`ImageFile.processingStatus = PENDING`인 row 수를 세려면 6개 테이블(store/menu/review/course/storeContribution/menuContribution)을 각각 count 해야 한다. Gauge는 스크랩마다(15초) 평가되므로 **15초마다 count 쿼리 6번**이 돌게 된다.

**대안:** `image_processing_orphan_callback_total`과 도달률 지표로 이상 징후는 충분히 감지된다. 정말 필요해지면 캐싱된 Gauge(1분 주기 갱신)로 별도 추가한다.

### ③ 기존 `@Timed` 유지

`aws.s3`, `opensearch.query`, `external.api`는 그대로 둔다.

---

## 4. 구현 계획

**쉬운 것 → 복잡한 것 순.** 앞 단계에서 Counter/Gauge 패턴이 자리잡으면 뒤가 수월하다.
각 단계마다 **구현 직후 테스트를 작성**하고 커밋한다.

### 1단계 — 색인 배치 (D)
- `StoreIndexScheduler` 수정 (MeterRegistry 주입, Timer/Counter/Gauge)
- `StoreIndexSchedulerTest` 신규 — 성공 시 지표 기록, 예외 시 last_success 미갱신

### 2단계 — 검색 품질 (C)
- `OpenSearchStoreClient` 수정 (hit/empty 분기 Counter)
- 기존 테스트 존재 여부 확인 후 케이스 추가 또는 신규

### 3단계 — FCM (B)
- `FcmService#sendPushNotification` 수정 (예외 분기별 Counter)
- `FcmServiceTest` — 성공 / UNREGISTERED / THIRD_PARTY_AUTH_ERROR / 기타 4케이스

### 4단계 — 이미지 파이프라인 (A)
- `S3Service#generatePresignedUrl` — 발급 Counter
- `ImageCallbackService#updateImageStatus` — 콜백/orphan Counter
- `ImageCallbackController` — 인증 실패 Counter
- `ImageProcessingCallbackService` 또는 신규 설정 클래스 — pending Gauge
- 각 테스트

### 5단계 — 마무리
- `./gradlew spotlessApply && ./gradlew build && ./gradlew test`
- dev PR → 스테이징에서 `/actuator/prometheus`로 실제 노출 확인
- dev → main PR

---

## 5. 위험 요소

| 위험 | 대응 |
|------|------|
| 지표 코드가 비즈니스 로직을 오염시킴 | Counter 증가만 인라인, 조건 분기 추가 금지 |
| 계측 실패가 요청을 깨뜨림 | Micrometer Counter는 예외를 던지지 않음. 단 Gauge의 람다(count 쿼리)는 예외 가능 → try-catch 대신 Repository 호출 자체를 단순 유지 |
| 태그 카디널리티 폭증 | 모든 태그를 enum/고정 문자열로 제한. 사용자 입력·awsKey·토큰은 태그로 쓰지 않음 |
| 테스트에서 MeterRegistry 누락 | `SimpleMeterRegistry`를 테스트에서 직접 주입 |
| Gauge 참조 유실 | Micrometer Gauge는 대상을 weak reference로 잡음. 람다가 참조하는 객체를 필드로 보관 |

---

## 6. 진행 현황

- [ ] 1단계 색인 배치 — 작성 중 미완성분을 stash에 보관 (`Timer.stop`/Counter/Gauge 갱신 누락, 테스트 없음)
- [x] **2단계 검색 품질** — 우선순위 상향해 먼저 구현. 테스트 7개 추가(693→700), 전부 통과
- [ ] 3단계 FCM
- [ ] 4단계 이미지 파이프라인
- [ ] 5단계 검증 및 PR

### 2단계 산출물

| 파일 | 상태 |
|------|------|
| `store/service/search/SearchOutcome.java` | 신규 |
| `store/service/search/SearchQueryCoverage.java` | 신규 — 판정 규칙 |
| `store/service/search/StoreSearchService.java` | 수정 — outcome 확정·Counter·로그 |
| `external/opensearch/client/OpenSearchStoreClient.java` | 수정 — hit별 커버 판정 |
| `external/opensearch/dto/OpenSearchStoreSearchResult.java` | 수정 — `queryCovered` 추가 |
| `store/service/search/SearchQueryCoverageTest.java` | 신규 — 9케이스 |
| `store/service/search/StoreSearchServiceTest.java` | 수정 — outcome 6케이스 추가 |

### 후속 과제 (2단계에서 발견)

- `stop-words.txt`에 `빵집`·`가게`는 있으나 **`베이커리`·`제과`·`제과점`이 없다.**
  일반 업종어라 불용어여야 맞지만, 고치면 `cleanQuery`가 바뀌어 검색 결과 자체가 달라진다.
  지표로 실태를 확인한 뒤 근거를 갖고 수정한다.
- 인덱스 `min_gram: 2` + 서브필드 OR 결합 때문에 **2글자만 겹쳐도 결과에 올라온다.**
  이번 지표는 이걸 고치는 게 아니라 빈도를 드러내는 것.
- `OpenSearchStoreClient`는 여전히 전용 테스트가 없다(`parseResponse`·`hasNext` 휴리스틱 미검증).
  판정 로직을 `SearchQueryCoverage`로 분리해 테스트 가능하게 둔 이유이기도 하다.
