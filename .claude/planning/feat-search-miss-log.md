# feat/search-miss-log — 결과 없는 검색어 적재 및 관리자 조회

> 선행 작업: `feat/custom-business-metrics`(PR #581). `SearchOutcome` 판정이 이미 있고, 그 결과를 재사용한다.
> 지표(Prometheus)는 "얼마나 자주"에 답하고, 이 작업은 **"무슨 검색어를"** 에 답한다.

---

## 1. Spec — 요구사항

### 배경

검색이 결과를 못 냈을 때 **어떤 검색어였는지 알아야 가게를 추가할 수 있다.** 현재는 로그로만 남아
서버에 SSH로 붙어 `grep`을 돌려야 하고, 서버가 2대라 합산도 안 되며 컨테이너 재시작 시 유실된다.

지표에는 검색어를 담을 수 없다 — 검색어는 카디널리티가 무한해 Prometheus 태그로 쓰면 시계열이
무한히 늘어난다. 별도 저장소가 필요하다.

### 이건 랭킹이 아니라 작업 큐다

"top 20 조회"가 아니라 **"빠짐없이 보고 하나씩 처리해 소진하는 목록"** 이다. 그래서 두 가지가 필수다.

- **중복 제거** — 같은 검색어 100번 실패 = 100줄이 아니라 1줄 + 횟수 100
- **처리 상태** — 어제 판단한 게 오늘 또 뜨면 소진이 안 된다

### 실패했다고 다 "가게 추가" 대상이 아니다

| 종류 | 예 | 할 일 | 주로 어느 outcome |
|------|-----|-------|------------------|
| 진짜 없는 가게 | `연남 크로플집` | **가게 추가** | `empty` |
| 있는데 검색이 못 찾음 | `맘모스 베이커리` | 검색 개선 (추가하면 중복 등록) | `mismatch`, `mismatch_loose` |
| 오타·장난 | `ㅁㄴㅇㄹ` | 무시 | `empty` |

그냥 다 추가하면 **중복 가게가 쌓인다.** 그래서 `outcome`을 함께 저장해 화면에서 걸러낼 수 있게 한다.
`ALREADY_EXISTS`로 분류된 것들은 그대로 **검색 개선 작업의 근거 데이터**가 된다.

### 범위

**포함**
- `SearchOutcome != HIT`인 검색어 전량 적재 (중복 제거 + 횟수 누적)
- 관리자 조회 API — 상태·outcome 필터, 페이징, 정렬
- 관리자 상태 변경 API — 단건 + 벌크
- 처리 완료 건 90일 후 삭제

**제외 (별도 작업)**
- 관리자 웹 화면 (`../bbang-ggut_admin_web/`)
- Slack 일간 다이제스트
- 자동완성 실패는 적재하지 않는다 — 타이핑 중간 상태라 노이즈가 크다

---

## 2. 설계

### Entity — `SearchMiss`

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | PK |
| `normalizedKeyword` | String | **unique.** 공백·특수문자 제거 + lowercase. 중복 제거 키 |
| `keyword` | String | 마지막으로 들어온 사용자 입력 원문 (화면 표시용) |
| `outcome` | SearchOutcome | 마지막 발생 시 outcome (`@Enumerated(STRING)`) |
| `occurrenceCount` | long | 발생 횟수 |
| `firstOccurredAt` | LocalDateTime | 최초 발생 |
| `lastOccurredAt` | LocalDateTime | 최근 발생 |
| `status` | SearchMissStatus | `PENDING` / `STORE_ADDED` / `ALREADY_EXISTS` / `IGNORED` |
| `handledBy` | Long (nullable) | 처리한 관리자 memberId |
| `handledAt` | LocalDateTime (nullable) | 처리 시각 |

`outcome`은 발생마다 달라질 수 있다(같은 검색어가 `empty`였다가 가게 추가 후 `hit`이 되는 식).
**마지막 값으로 갱신**한다 — 최빈값 계산은 복잡도 대비 이득이 없다.

**인덱스**
- `normalizedKeyword` UNIQUE — 중복 제거 + upsert 충돌 판정
- `(status, occurrence_count DESC)` — 기본 목록 조회
- `(status, last_occurred_at DESC)` — 최신순 조회

### 적재 경로

```
StoreSearchService.searchStores()
  → outcome != HIT
  → ApplicationEventPublisher (비동기)
  → SearchMissCommandService.record()
  → upsert
```

**비동기여야 하는 이유:** `searchStores`는 사용자 요청 경로다. INSERT 지연이 검색 응답에 얹히면 안 된다.
또 `StoreSearchService`는 `@Transactional(readOnly = true)`라 같은 트랜잭션에서 쓸 수 없다.

**upsert 동시성:** 같은 검색어가 동시에 들어오면 unique 제약에 걸린다.
PostgreSQL `INSERT ... ON CONFLICT (normalized_keyword) DO UPDATE`를 native query로 쓴다.
애플리케이션 레벨 조회-후-분기는 경합에서 깨진다.

**적재 실패가 검색을 깨뜨리면 안 된다** — 비동기 경계 안에서 예외를 잡아 로깅만 한다.

### API — 관리자

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/admin/search-misses` | 목록. `status`(기본 PENDING), `outcome`, 페이징, 정렬 |
| PATCH | `/admin/search-misses/{id}/status` | 단건 상태 변경 |
| PATCH | `/admin/search-misses/status` | 벌크 상태 변경 (`ids[]` + `status`) |

벌크가 필요한 이유: 오타·장난 검색어를 하나씩 `IGNORED` 처리하면 실사용이 안 된다.

### 정리 스케줄러

매일 새벽, `status != PENDING AND handled_at < now() - 90일` 삭제.

검색어에는 사람 이름·주소가 섞여 들어올 수 있어 무기한 보관하지 않는다.

---

## 3. 위험 요소

| 위험 | 대응 |
|------|------|
| 검색 응답 지연 | 적재를 비동기로 분리. 검색 경로에서 DB 쓰기 금지 |
| 동시 요청 시 unique 충돌 | `ON CONFLICT DO UPDATE` native upsert |
| 적재 실패가 검색을 깨뜨림 | 비동기 경계에서 예외 포획 후 로깅만 |
| **봇 어뷰징으로 테이블 폭증** | 검색어 길이 상한(예: 100자) 적용. 그래도 랜덤 문자열 공격엔 취약 — 지표로 급증을 감지하고 대응 |
| PENDING이 무한 적체 | 90일 삭제는 처리 완료 건만 대상. PENDING 적체는 운영하며 관찰 후 정책 추가 |
| 개인정보 | 90일 보관 정책. 로그에도 남으므로 로그 보존기간도 함께 봐야 함 |

---

## 4. 구현 계획

각 단계마다 **구현 직후 테스트를 작성**하고 커밋한다.

### 1단계 — Entity·Repository·마이그레이션
- `SearchMiss`, `SearchMissStatus` 신규
- `SearchMissRepository` — upsert native query, 목록 조회
- Flyway 마이그레이션 (테이블 + 인덱스 3종)
- `@DataJpaTest` — upsert가 중복 시 카운트를 올리는지, 신규 시 삽입하는지

### 2단계 — 적재
- `SearchMissCommandService.record()` + 단위 테스트
- 이벤트 발행/수신 배선, 비동기 설정
- `StoreSearchService` 연결 + 기존 테스트 수정 (`outcome != HIT`일 때만 발행되는지)

### 3단계 — 조회
- `SearchMissQueryService` + 단위 테스트 (필터·정렬·페이징)

### 4단계 — 관리자 API
- `AdminSearchMissController` + `@WebMvcTest`
- 상태 변경 시 `handledBy`/`handledAt` 기록 검증

### 5단계 — 정리 스케줄러
- 90일 경과 건 삭제 + 테스트

### 6단계 — 검증
- `./gradlew spotlessApply && ./gradlew build && ./gradlew test`
- dev PR

---

## 5. 진행 현황

- [ ] 1단계 Entity·Repository·마이그레이션
- [ ] 2단계 적재
- [ ] 3단계 조회
- [ ] 4단계 관리자 API
- [ ] 5단계 정리 스케줄러
- [ ] 6단계 검증 및 PR

---

## 6. 미결 사항

- **관리자 인증 방식** — 기존 `/admin/**` 컨트롤러의 인증 패턴을 따를 것. 구현 전 `AdminStoreController` 등 확인 필요
- **`handledBy`에 넣을 값** — `@AuthenticationPrincipal Long memberId`인지 별도 관리자 식별자인지 확인 필요
