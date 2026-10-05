# 자동완성 Redis 캐싱 + 급부상 가게 실시간 노출

## Context
빵·디저트는 시즌과 SNS 흐름에 따라 유행하는 가게가 빠르게 바뀐다. 예를 들어 한정선 찹쌀떡처럼 갑자기 검색이 몰리는 가게가 생긴다. 자동완성이 이런 가게를 먼저 띄워줘야 한다.

지금 자동완성(`GET /stores/autocomplete`)의 문제는 세 가지다.
- 정렬이 `tier → 누적 popularity_score(주 1회 갱신)`라서 요즘 뜨는 가게가 반영되지 않는다.
- 타입 우선순위가 REGION → STORE로 고정돼 있다. '한'을 치면 '한남동'이 항상 가게보다 위에 온다.
- 요청마다 DB에서 bigm `LIKE '%q%'` 쿼리를 실행한다(0.5초 디바운스만 있음). 트래픽이 늘면 DB 부하가 그대로 늘어난다.

설계 원칙은 **신선도를 TTL에 맡기지 않는 것**이다.
- **새 가게 노출**: 가게 등록 이벤트가 오면 관련 검색어 키만 정밀하게 무효화한다.
- **뜨는 가게 상단 노출**: 캐시와 분리된 실시간 순위표를 요청 시점에 적용한다.
- **TTL**: 길게 두어 적중률을 지킨다. TTL은 무효화가 누락됐을 때를 위한 안전장치다.

Redis를 쓰는 근거 (운영은 WAS 1대 안에서 blue/green 전환 — `chore-prod-single-server.md`):
- **배포마다 JVM이 바뀐다.** 최근 30일 main 배포 약 40회(9일에 걸쳐). 메모리 캐시는 TTL(7일)을 채우지 못하고 배포 때마다 빈다.
- **전환 순간 두 JVM이 함께 떠 있다.** 무효화가 한쪽에만 닿으면 다른 쪽이 옛 결과를 들고 있다. 공유 저장소면 이 문제가 없다.
- **PR2 급부상 데이터(시간 버킷·HLL)는 배포를 넘어 7일 동안 남아야 한다.**
- 기존 자동완성 Caffeine 30초 캐시(`c3456d00`, 10-01)는 Redis 캐시로 교체한다. 빵집 판 캐시는 그대로 둔다.

## 0단계 실측 결과 (2026-10-02, 운영 `request_log`, 읽기 전용)
대상 기간은 08-28 ~ 10-02이다. 테스트 계정 1명이 이메일을 반복 입력한 요청 약 2,200건은 제외했다.
- **자동완성**
  - 하루 약 120건, p50 10ms · p95 27ms · p99 63ms. 1글자 검색이 가장 느리다(p95 42ms).
  - 고유 검색어의 72%가 1회성이다. 1글자 검색어는 평균 3.1회 반복된다.
- **캐시 적중률 시뮬레이션**
  - TTL별: 1시간 16% · 1일 21% · 7일 31% · 무한 40%
  - 2글자 이하만 캐싱하면 대상은 전체 요청의 58%다.
- **가게 조회**
  - 가게·일 단위 고유 방문자는 최대 1~4명이다. 28일 일평균 1위 가게는 0.71명/일이다.
  - 24시간 단위로 판정하면 급부상 후보가 하루 0~1개다.
- **결론**
  - 캐시 효과는 운영에서 거의 드러나지 않는다. → 부하 테스트로 증명한다.
  - 급부상 판정은 24시간 단위로는 신호가 부족하다. → **실시간 갱신(5분)은 유지**하되, 집계 기간을 7일로 넓히고 시간 감쇠를 적용한다. 신호도 조회 외에 찜·빵로그·코스담기·가고싶은 가게를 더한다.

## 핵심 설계: 목록과 순위를 분리한다
```
'폼' 검색
 → ① Redis 검색어 목록 ac:폼            [폼베이커리, 폼제과, 폼포미]   ← TTL 길게, DB 부하 감소
 → ② Redis 순위표 trend:top/trend:names  폼포미 1등                     ← 5분마다 갱신, 실시간성
 → ③ 응답 직전에 합침                    [폼포미, 폼베이커리, 폼제과]
```
| 하는 일 | 언제 | 담당 |
|---|---|---|
| 새 가게가 목록에 **나오게** | 가게 등록·변경 순간 | 관련 검색어 키 정밀 삭제 |
| 뜨는 가게가 **위로 올라가게** | 검색할 때마다(순위표는 5분 주기) | 순위표 오버레이 |

## 결정 사항 (사용자 확인 완료)
- **매칭 범위**: 기존 매칭(가게명·별칭·지역)은 그대로 둔다. 메뉴 키워드 매칭은 범위 밖이다.
- **상단 노출 규칙** (STORE 타입이면서 검색어로 이름이 시작하는 가게만 대상)
  1. 급부상 자격이 있는 가게가 있으면 최대 N개(기본 2)를 REGION 위에 둔다.
  2. 없으면 **28일 스테디 점수**가 가장 높은 가게 1개를 REGION 위에 둔다. 최소 기준 미달이면 올리지 않는다.
  3. 나머지는 기존 타입 순서(REGION → STORE)를 따른다.
  - 예: '한' 검색
    - 급부상 있음 → `[한정선(급부상), 한남동, 한스베이커리, …]`
    - 급부상 없음 → `[한스베이커리(스테디 1위), 한남동, 한정선, …]`
- **자동완성의 가게 정렬 기준**: `tier → 28일 스테디 점수 → 누적 popularity_score(동점 처리)`
  - 전체 검색(`/stores/search`)과 인기 빵집은 기존 누적 인기도를 그대로 쓴다.
- **신호**: 회원×가게×종류×일 1회로 센 가중합. 가중치는 `store.trending.weights`로 설정한다.
  - 기본 가중치: 조회 1 · 찜 2 · 가고싶은 가게 2 · 코스담기 2 · 빵로그 3
  - 로그인 회원만 센다. 음수 신호(찜 해제 등)는 넣지 않는다.
- **recent(실시간, Redis)**
  - 최근 7일 시간 버킷에 감쇠 가중치를 곱해 합산한다(반감기 기본 36시간).
  - 5분마다 다시 계산한다. 오늘 몰린 활동이 일주일 전 활동보다 크게 반영된다.
- **baseline(평소, DB)**
  - `user_store_interaction`에서 같은 종류·같은 가중치로, recent 기간 이전 28일의 일평균을 구한다. 매일 배치로 계산한다.
  - recent와 단위를 맞추기 위해 비교할 때는 `baseline × W`를 쓴다.
  - `W` = 7일 감쇠 가중치의 합(일 단위)이다. 일정한 활동이 있을 때 recent가 갖는 값이다.
  - 같은 값(baseline 일평균)을 스테디 점수로도 쓴다.
- **급부상 판정은 자격과 순위를 분리한다.**
  - 자격(모두 만족)
    - 증가 배율 `growth = (recent + k) / (baseline×W + k)` ≥ G
    - 최근 7일 고유 회원 수 ≥ M
  - 순위: 자격을 통과한 후보끼리만 `ln(1+recent) × growth`로 정렬한다.
  - G·M·k의 초기값은 운영 데이터로 7일 기준 후보 수를 측정해 정한다. 이 측정은 아직 하지 않았다.
- **Redis는 EC2 내 컨테이너로 띄운다.** 캐시 전용이라 영속화는 끈다.
- **캐시 정책 (0단계 결과 기반)**
  - TTL은 7일이다. 무효화가 신선도를 담당하므로 길게 둔다.
  - ±10% 지터를 더한다.
  - 캐싱 대상 길이 상한은 부하 테스트 결과를 보고 확정한다. 우선 전체를 대상으로 한다.

## Redis 키 구조
| 키 | 타입 | 값 | TTL | 쓰기 | 읽기 |
|---|---|---|---|---|---|
| `ac:{검색어}` | Hash | field `{types}:{size}` → 응답 JSON | 7일 ± 지터 | 캐시 미스 시 | 자동완성마다 |
| `lock:ac:{검색어}` | String | 1 | 3초 | 캐시 미스 시 1명만 획득 | 캐시 미스 시 |
| `trend:h:{yyyyMMddHH}` | Sorted Set | storeId → 그 시간 신호 가중합 | 8일 | 신호 발생 시 `ZINCRBY` | 5분 주기 계산 |
| `trend:uv:{storeId}:{yyyyMMdd}` | HyperLogLog | 그날 신호를 남긴 회원 | 8일 | 신호 발생 시 `PFADD` | 5분 주기 계산(최소 회원 수 판정) |
| `seen:{type}:{storeId}:{memberId}:{yyyyMMdd}` | String | 1 | 24시간 | 신호 발생 시 `SET NX` | 신호 발생 시(하루 1회 판정) |
| `trend:top` | Sorted Set | storeId → 급부상 점수 | 없음(주기 교체) | 5분 주기 계산 | 자동완성마다 |
| `trend:names` | Hash | storeId → 정규화 이름(+별칭) | 없음(주기 교체) | 5분 주기 계산 | 자동완성마다 |

- 검색어는 `SearchTextNormalizer`로 정규화한 값을 쓴다.
- `ac:{검색어}`를 Hash로 묶으면 `DEL ac:폼` 한 번에 그 검색어의 모든 types/size 변형이 지워진다.
- `trend:uv`를 HyperLogLog로 두는 이유: 7일 고유 회원 수를 `PFCOUNT`(여러 키를 합쳐 셈) 한 번으로 구한다. 회원 수와 상관없이 키당 최대 12KB만 쓴다.

## 작업 방식
- 브랜치: `dev`에서 `feat/autocomplete-cache`(PR1), `feat/autocomplete-trending`(PR2)
- 플래닝 문서: `.claude/planning/{브랜치명}.md`
- PR1에서 캐시와 무효화를 먼저 넣고, PR2에서 급부상을 얹는다.

---

## PR1 — Redis 자동완성 캐시 + 정밀 무효화

1. **인프라**
   - `docker-compose.redis.yml`에 `redis:7-alpine`을 정의하고 별도 compose 프로젝트 `bbang-redis`로 띄운다.
   - blue/green 두 스택이 함께 붙을 external 네트워크를 만든다.
   - 설정: `requirepass`, `maxmemory 128mb`, `allkeys-lru`, 영속화 off
   - 로컬 `docker-compose.yml`과 staging에도 추가한다.
   - `cd-prod.yml` / `cd-staging.yml`에서 Redis 기동을 보장하고 `REDIS_HOST/PORT/PASSWORD`를 주입한다.
   - EC2 메모리 여유를 확인한다.
2. **앱 설정**
   - `spring-boot-starter-data-redis`(Lettuce)를 추가하고 명령 타임아웃은 약 100ms로 둔다.
   - 테스트 프로파일에서는 Redis를 비활성화하거나 목으로 대체한다.
3. **`StoreAutocompleteCache`** (cache-aside, `StringRedisTemplate`)
   - 적중하면 Hash field를 그대로 반환한다.
   - 미스하면 `lock:ac:{q}`를 `SET NX`로 잡은 요청 1개만 DB를 조회한다. 결과를 `HSET`한 뒤 `EXPIRE`(7일 ± 지터)를 건다.
     - 락을 못 잡은 요청은 잠깐 대기 후 다시 읽는다. 그래도 없으면 DB를 직접 조회한다.
   - Redis 장애나 타임아웃이 나면 경고 로그와 메트릭만 남기고 DB 결과를 반환한다. 자동완성 자체는 절대 실패하지 않는다.
   - `StoreSearchService.autocompleteStores`가 이 캐시를 감싼다. 기존 `autocompleteCounter`는 그대로 집계한다.
4. **정밀 무효화 — `AutocompleteCacheInvalidator`**
   - 정규화한 이름과 별칭의 **모든 부분 문자열**에 해당하는 `ac:{부분문자열}` 키를 파이프라인으로 `DEL`한다.
     - 자동완성은 `'%q%'`로 매칭하므로, 이름의 부분 문자열이 곧 영향받는 검색어다.
     - 예: '폼포미베이커리' 7글자 → 28개
   - 트리거: 가게 생성, 이름 변경, 상태 변경(ACTIVE ↔ 비활성), 별칭 추가·삭제. 지역 변경도 같은 방식으로 처리한다.
   - 커밋 이후에 실행되도록 `@TransactionalEventListener(AFTER_COMMIT)`로 건다.
   - **구현 전 확인**: 가게가 생성·변경되는 경로(관리자, 크롤러, 카카오 연동 등)를 전수 조사한다.
5. **메트릭**: `autocomplete.cache{result=hit|miss|skip|error}`, 적중·미스 지연 타이머, 무효화 키 수
6. **부하 테스트 (효과 증명)**
   - k6 스크립트로 0단계에서 뽑은 실제 검색어 분포를 재현해 요청을 보낸다.
     - 분포는 정규화 검색어와 빈도만 쓴다. 회원 정보는 포함하지 않는다.
   - staging 또는 로컬(lab DB)에서 캐시 on/off를 비교한다. 트래픽은 1배, 10배, 100배로 측정한다.
     - 측정 항목: p95, DB 쿼리 수, DB CPU, 적중률
   - 결과는 플래닝 문서에 기록한다.
7. **테스트**
   - `StoreAutocompleteCacheTest`(Mockito): 적중, 미스 후 저장, 지터 범위, 락 경합, Redis 예외 시 DB 폴백
   - `AutocompleteCacheInvalidatorTest`: 부분 문자열 생성(별칭 포함, 중복 제거), 트리거 이벤트별 DEL 호출
   - 서비스 테스트에 캐시 연동 케이스를 추가한다.

## PR2 — 급부상 순위표 + 노출 규칙

1. **실시간 신호 수집 — `TrendingSignalRecorder`**
   - 신호 발생 지점: 가게 상세 조회(`GET /stores/{id}` 성공), 찜, 가고싶은 가게, 코스담기, 빵로그 작성
     - 각 도메인 서비스에서 이벤트를 발행하고, 커밋 이후 비동기로 기록한다.
   - **구현 전 확인**: 각 지점에 기존 이벤트가 있는지 조사한다(`user_store_interaction` 관련 #663 등).
   - 기록 방식
     - `SET seen:{type}:{store}:{member}:{날짜} NX EX 86400`이 성공할 때만 아래 두 가지를 실행한다.
       - `ZINCRBY trend:h:{시간} {가중치} {storeId}`
       - `PFADD trend:uv:{storeId}:{날짜} {memberId}`
     - 비로그인 요청은 제외한다.
     - Redis가 실패해도 원래 요청(조회·찜 등)에는 영향을 주지 않는다.
2. **평소 수준 (DB 일 배치)**
   - `store`에 `trending_baseline double precision NOT NULL DEFAULT 0`을 추가하고 인덱스를 건다(Flyway).
   - 값: `user_store_interaction`에서 (오늘−35일 ~ 오늘−7일) 28일 동안의 신호 가중합(회원×가게×종류×일 1회) ÷ 28
   - 적재 배치(`UserInteractionIngestScheduler`, 04:00)가 끝난 직후에 실행한다.
   - `pg_try_advisory_xact_lock`으로 중복 실행을 막는다.
   - 관리자 엔드포인트 `POST /admin/search/trending/refresh`를 둔다.
   - 참고 패턴: `StorePopularityRepository` · `StorePopularityCommandService` · `AdminStorePopularityController`
3. **순위표 계산 (5분 주기 `@Scheduled`)**
   - 최근 168개 시간 버킷을 `ZUNIONSTORE ... WEIGHTS`로 합산해 recent를 구한다.
     - 버킷 가중치: `0.5^(경과시간/반감기)`
   - recent > 0인 가게만 대상으로 다음을 수행한다.
     - `PFCOUNT trend:uv:{store}:{최근 7일}`로 고유 회원 수를 구한다.
     - DB에서 baseline을 조회한다(대상 가게만 일괄 조회).
     - `growth = (recent + k) / (baseline×W + k)`
   - 자격(`growth ≥ G`, `회원 수 ≥ M`)을 통과한 가게를 `ln(1+recent) × growth`로 정렬한다.
     - 이 결과로 `trend:top`과 `trend:names`(정규화 이름과 별칭)를 만들고, 임시 키에 쓴 뒤 `RENAME`해서 원자적으로 교체한다.
   - blue/green 중 한 인스턴스만 실행하도록 Redis `SET NX` 락을 건다.
   - 설정은 `StoreTrendingProperties`(`store.trending.*`)로 뺀다: 주기, 가중치, 반감기, k, G, M, 최대 노출 개수, 스테디 최소 기준
4. **스테디 정렬** — 캐시에 저장되는 목록에 반영한다
   - `StoreSearchSqlBuilder.buildAutocomplete`의 STORE·별칭 서브쿼리를 `ORDER BY tier, trending_baseline DESC, popularity_score DESC`로 바꾼다.
   - `StoreAutocompleteRow`·`StoreAutocompleteSuggestion`에 필드를 추가하고, `StoreSearchEngine`의 Java 재정렬도 같은 기준으로 맞춘다.
   - baseline은 하루 1회 바뀐다. 캐시에는 최대 TTL 동안 이전 정렬이 남지만 허용한다.
     - 배치가 끝난 뒤 스테디 순위가 바뀐 가게만 무효화하는 방안은 필요해지면 추가한다.
5. **상단 노출** — `StoreSearchService.autocompleteStores`에서 캐시 결과를 받은 뒤 적용한다
   - `trend:names`에서 검색어로 시작하는 가게를 찾아 `trend:top` 점수순으로 최대 N개를 맨 앞에 둔다.
     - 목록에 이미 있으면 위치만 옮기고, 없으면 끼워 넣는다.
   - 급부상 가게가 없으면 tier 0 STORE 중 맨 앞 가게(스테디 1위)를 REGION 위로 1개 올린다. 단, 스테디 최소 기준을 넘을 때만 올린다.
   - 응답 DTO는 바꾸지 않는다(FE 영향 없음). 순위표를 읽지 못하면 스테디 규칙만 적용한다.
6. **테스트**
   - 상단 노출
     - 급부상 승격
     - 자격 미달(배율 미달, 회원 수 미달)
     - 평소대로 유명한 가게 제외
     - 접두어 불일치
     - N개 상한
     - 목록에 없는 가게 삽입
     - 급부상 없을 때 스테디 1개 승격
     - 스테디 최소 기준 미달
     - 타입 필터
     - 순위표 장애
   - 신호 기록: 하루 1회만 반영, 비로그인 제외, 종류별 가중치, Redis 장애 시 원래 요청 정상
   - 순위표 계산: 감쇠 가중치, W 계산, 자격 판정, 락 실패
   - 정렬: `StoreSearchSqlBuilderTest`, `StoreSearchEngineTest`의 tier → baseline → popularity 순서
   - baseline 배치는 `@Tag("lab")`로, 관리자 컨트롤러는 `@WebMvcTest`로 검증한다.

## 재사용할 기존 코드
| 대상 | 용도 |
|---|---|
| `store/service/search/SearchTextNormalizer.java` | 캐시 키, 부분 문자열 정규화 |
| `store/service/search/StoreSearchService.java` (`autocompleteStores`) | 캐시와 오버레이를 붙일 지점 |
| `store/repository/StorePopularityRepository.java` | 점수 UPDATE SQL 패턴 |
| `store/service/StorePopularityCommandService.java` | advisory lock 패턴 |
| `admin/controller/AdminStorePopularityController.java` | 관리자 갱신 엔드포인트 패턴 |
| `personalization/batch/UserInteractionIngestScheduler.java` | baseline 배치 시점 |
| `personalization/enums/InteractionType.java` | 신호 종류 |
| `global/logging/filter/RequestLoggingFilter.java` | STORE_VIEW 판정 기준 |

## 위험 요소
- **신호 희소**: 7일로 넓혀도 지금 트래픽에서는 급부상 후보가 적을 수 있다. 그 경우 스테디 규칙이 동작한다. 기준값은 운영 데이터 측정 후 정한다.
- **무효화 누락**: 가게 생성·변경 경로가 하나라도 빠지면 그 가게는 TTL(7일) 동안 안 보인다. 경로를 전수 조사하고 TTL을 안전장치로 둔다.
- **자기강화 루프**: 위에 노출되면 더 클릭돼 계속 급부상으로 남는다. baseline이 따라 올라오며 완화되고, N개 상한도 둔다.
- **Redis 재시작**: 7일 버킷이 사라져 한동안 급부상 노출이 없다. 스테디 규칙이 대신 동작하고 캐시는 다시 채워진다.
- **우리 DB에 아직 없는 가게**: 캐시가 아니라 수집 문제다. `search_misses`로 수요를 감지해 가게를 추가하면 무효화 흐름으로 이어진다.
- **EC2 메모리**: `maxmemory` 상한으로 제한한다. HLL은 가게×일당 최대 12KB이고, 활동이 있는 가게에만 생긴다.

## 검증
- `./gradlew spotlessApply && ./gradlew build && ./gradlew test`, `./gradlew test -PlabTests`
- 로컬 Redis 기동 후 시나리오로 확인한다.
  1. '폼' 검색 → 미스 후 저장 → 재검색 → 적중 (`redis-cli MONITOR`, 메트릭)
  2. 신규 가게 '폼포미베이커리' 등록 → 부분 문자열 키 삭제 → '폼' 재검색 → 목록에 포함
  3. 여러 계정으로 폼포미 조회·찜 → 5분 주기 계산 후 '폼' 검색 → 맨 위로 승격
  4. Redis를 중지한 상태에서 자동완성·상세 조회·찜이 정상 응답하는지 확인
- 부하 테스트 결과(캐시 on/off, 1배·10배·100배)를 플래닝 문서에 기록한다.


---

## 구현 현황 (PR1, 2026-10-05)

### 한 것
- `spring-boot-starter-data-redis` 추가. 명령 타임아웃 100ms, `management.health.redis.enabled=false`(Redis 장애가 배포 readiness를 막지 않게)
- 스위치 `store.search.autocomplete-cache.enabled=${AUTOCOMPLETE_CACHE_ENABLED:false}` — 인프라가 뜨기 전에 머지해도 동작은 기존과 같다(매번 DB)
- `StoreAutocompleteCache`: 엔진 결과를 `ac:{정규화 검색어}` Hash의 size field로 캐시. 락으로 동시 미스 합치기(`COALESCED`), 장애 시 DB 폴백, TTL 7일 ±10%
  - 지표 `autocomplete.cache{result=hit|miss|coalesced|error}`. `search.engine.query`는 캐시 적중 포함 응답시간으로 바뀐다(전후 비교용)
- `AutocompleteCacheInvalidator`: 이름의 모든 부분 문자열 키 `UNLINK` / 전체 비우기는 `SCAN ac:*`
- 무효화 이벤트 발행 지점 (`StoreSearchNameChangedEvent`, 커밋 뒤 처리)
  - `AdminStoreCommandService` createStore · updateStore(이름·상태가 바뀔 때만, 옛 이름+새 이름) · deleteStore(노출 중이던 가게만)
  - `ContributionReportProcessor.createStoreFromAddReport`
  - `StoreCommandService` registerKakaoStore · registerNaverStore(새로 넣는 분기)
  - `BulkImportStoreSaver.save`(가게마다 REQUIRES_NEW 커밋)
  - `StorePopularityCommandService` → `StorePopularityRefreshedEvent` → 전체 비우기(주간 인기도 갱신이 순서를 바꾼다)
- 앱 밖에서 바뀌는 데이터 — 데이터 파이프라인의 `store_name_alias` 적재, Flyway의 지역 변경 — 는 이벤트로 못 잡는다
  - 관리자 API `DELETE /admin/search/autocomplete-cache`(전체 비우기) + TTL 안전장치로 대응
- 테스트: 단위 22개 추가·기존 6개 클래스 보강, 진짜 Redis로 도는 `AutocompleteCacheRedisLabTest`(`-PlabTests`) 4개 — 동시 미스 20개 → DB 1회 확인

### staging 측정 준비 (2026-10-05)
- 측정은 로컬이 아니라 **k6 서버(c7g.xlarge) → staging 앱(10.3.31.174, t3.small)** 으로 한다. 맥에서 앱과 k6를 같이 돌리면 k6가 CPU 81%를 먹는다(`feat-coupon-concurrency.md` §14).
- staging DB(앱 서버 안 `staging-postgres` 컨테이너)는 가게 9건뿐이라 자동완성 비용을 잴 수 없었다
  - 자동완성이 읽는 4개 테이블만 lab DB(운영 덤프, 09-19)에서 옮겼다. 개인정보 테이블은 옮기지 않았다.
    - `store` 53,103 · `store_name_alias` 6,417 · `search_region` 6,536 · `search_region_alias` 10,232
  - staging 기존 가게 id 1~9는 그대로 두고, lab의 같은 id는 건너뛰었다(`--on-conflict-do-nothing`).
  - 한 트랜잭션으로 넣었다. FK 트리거를 끄고 넣었기 때문에 FK 3종을 직접 검사한 뒤 커밋했다. `ANALYZE` 후 bigm 인덱스 사용을 확인했다(4.4ms).
- staging Redis: `docker-compose.yml`에 `redis` 서비스 추가. 포트 미노출, requirepass, 128mb, allkeys-lru, 영속화 off
  - `cd-staging.yml`이 `.env`에 `REDIS_PASSWORD`(시크릿 `STAGING_REDIS_PASSWORD`)와 `AUTOCOMPLETE_CACHE_ENABLED`(저장소 변수 `STAGING_AUTOCOMPLETE_CACHE_ENABLED`, 기본 false)를 쓴다
- 측정 전에 `scouter-agent`를 끈다 — 쿠폰 측정 때 CPU를 19~24% 먹었다

### 남은 것
- [ ] k6 부하 테스트(캐시 on/off × 1·10·100배) — 0단계 실제 검색어 분포 재현
- [ ] 운영 인프라(was-1 Redis, `docker-compose.prod.yml`·`cd-prod.yml`) — 측정 결과를 보고 진행
- [ ] 커밋 전 무효화와 동시 미스가 겹치면 옛 결과가 다시 채워질 수 있다(cache-aside의 알려진 경쟁). 창이 수 ms라 TTL에 맡긴다 — 필요하면 지연 이중 삭제
