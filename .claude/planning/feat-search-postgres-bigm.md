# feat/search-postgres-bigm — 가게 검색을 OpenSearch → PostgreSQL(pg_bigm/pg_trgm)로 전환

작성일 2026-08-18 · 상태: **OpenSearch 완전 제거본으로 정리 완료(2026-08-19)** — 커밋/PR 대기. 두 엔진 병존 A/B 스냅샷은 로컬 전용 브랜치 `lab/search-engine-ab`(푸시 금지)에 보존

---

## 1. 배경 · 목표

- 운영 OpenSearch(`t3.medium.search` × 2, gp3 20GiB × 2 ≈ 월 $130~140)가 비용의 큰 몫. dev 도메인·dev RDS는 이미 삭제.
- 검색 범위를 **가게명 + 지역**으로 좁히기로 결정(메뉴·카테고리 검색 제외). 남는 데이터는 store 45k · store_name_alias 1.7k · 지역 사전 10k뿐이라 RDB로 충분.
- 목표
  1. OpenSearch 의존을 없애고 노드 2개를 내린다 (월 $130~140 → 0).
  2. 검색 지연·품질을 **실측**해 포트폴리오로 문서화한다 (랩 A/B + 운영 전후).
  3. 전환 중 언제든 되돌릴 수 있게 한다 (엔진 스위치).

## 2. 확정된 결정

| # | 결정 | 내용 |
|---|---|---|
| 1 | ~~엔진 병존 + 라우팅~~ → **완전 제거로 변경(2026-08-19)** | 랩 A/B(수치 확보)까지는 두 엔진을 병존시켰고, 확보 후 사용자 결정으로 **PR은 PostgreSQL 단독**: `external/opensearch` 패키지·색인 이벤트 5종·재색인 API·주간 OS 점수 배치·`opensearch-java` 의존성·CI/CD 프로퍼티·엔진 라우터/`engine=` 파라미터 전부 삭제. 병존 상태는 로컬 브랜치 `lab/search-engine-ab`에만 남김. 롤백 = 이전 이미지 재배포 |
| 2 | **pg_bigm 확정** (2026-08-18 랩 실측) | 2글자 검색어 bigm 0.36ms vs trgm 17.9ms(인덱스 불가·seq scan), 3글자 이상 동등, 인덱스 크기 절반(3.7MB vs 7.4MB). RDS PG18에 1.2 제공 |
| 3 | 자동완성 | STORE·REGION만 생성. `AutocompleteSuggestionType`의 MENU·BREAD_TYPE 열거형은 유지(FE `types=` 호환), 결과만 안 냄 |
| 4 | 지역 미스 폴백 | 사전(`search_region_alias`)에 없는 지역어는 `store.address` 부분일치로 폴백 (1차 0건일 때) |
| 5 | 브랜치 | `feat/search-postgres-bigm`, 이 문서 |

## 3. Spec — 기능 요구사항

### 3.1 검색 `GET /stores/search` (요청/응답 계약 불변)

- 파라미터 `query, lat, lon, page, size, sort=relevance|distance|popularity` 그대로.
- 응답 `StoreSearchResponse` 그대로. `matchedFields`는 STORE_NAME·REGION만 나온다. `topMenus`는 RDB hydration이라 그대로(검색 조건에서만 메뉴 제외).
- 파서(`StoreSearchQueryParser`)는 재사용. 토큰 분류:
  - `regionTokens` → 지역 조건
  - `breadTokens` + `remainingTokens` → **이름 토큰**(메뉴 검색이 없으므로 빵 단어도 가게명 부분일치로만 취급. "에그타르트" → 이름에 에그타르트가 든 가게)
  - 길이 1 잔여 토큰은 조건에서 제외(기존 규칙 유지)
- 정규화: 양쪽 모두 `lower` + `[^0-9a-z가-힣]` 제거 (OS의 `query_normalized_analyzer`와 동일).
- 케이스별 조건 (OS `StoreSearchQueryBuilder` 케이스 1~4를 SQL로 옮김)

| 케이스 | 예 | WHERE |
|---|---|---|
| 지역 + 이름 | "서울 테디뵈르하우스", "망원 브릭베" | 지역 IN (…) **AND** 이름토큰마다 (name_norm LIKE %t% OR alias_norm LIKE %t%) |
| 지역만 | "한남", "성수동" | 지역 IN (…) **OR** name/alias LIKE %cleanQuery_norm% |
| 이름만 / 미분류 | "브릭베이글", "성심당 본점" | 이름토큰마다 AND (name/alias 부분일치) |
| 폴백 A (기존) | "한남에그타르트" 1차 0건 | `parseWithGluedTokenFallback` 재파싱 후 재검색 (그대로) |
| 폴백 B (신규) | "천안시 서북구" 1차 0건·지역토큰 없음 | address_norm LIKE %cleanQuery_norm% |

- 랭킹(RELEVANCE): 설명 가능한 계단식 점수. `text_score × (1 + 0.3·ln(1+popularity) + gauss(distance))`
  - text_score: 가게명 정확=100 · 접두=80 · 포함=60 / 구명(alias) 동일 계단 × 0.75 / 지역 매칭 = 40 × weight(0.3~1.0) / 폴백 B = 20
  - gauss(d): OS와 동일 파라미터(scale 3km, offset 500m, decay 0.5), lat/lon 없으면 0
  - DISTANCE: `ST_Distance` 오름차순 → 점수 / POPULARITY: popularity_score 내림차순 → 점수
- `hasNext`: size+1 조회로 정확히 계산(기존 `hits.size()==pageSize` 추정 버그 해소)
- `queryCovered`(품질 지표): hit 중 하나라도 이름(clean query 통째) 또는 모든 지역 토큰이 매칭됐으면 true
- OS와 동일하게 예외 시 빈 결과 + warn 로그 (DB 예외는 GlobalExceptionHandler로 가지 않게 엔진 내부에서 처리? → **아니오.** DB 장애는 앱 전체 장애라 숨기지 않고 그대로 500. OS처럼 "빈 결과"로 감출 이유가 없음)

### 3.2 자동완성 `GET /stores/autocomplete`

- 후보: 가게명(접두 우선 → 포함), 구명(alias), 지역 alias(접두 → 포함). 각각 `size×3`(최대 50) 오버페치 후 서비스가 dedupe·우선순위(REGION < STORE) 정렬 — 기존 로직 유지.
- MENU·BREAD_TYPE 후보는 생성하지 않음.

### 3.3 인기도

- `store.popularity_score`(double, default 0) 컬럼 신설. 산식은 기존과 동일: `subscribeCount + Σ max(0, 51-rank) + storeViewCount`.
- 마이그레이션에서 1회 백필(SQL) + 주간 배치(`StorePopularityScoreScheduler`, 월 03:00) + 어드민 수동(`POST /admin/search/popularity/refresh`)이 **같은 집계 UPDATE 한 방**(`StorePopularityRepository.refreshAllPopularityScores`)으로 갱신. Java 계산기 추출은 하지 않았다 — OS 쪽 `StoreIndexService`의 산식은 OS 제거 PR에서 함께 사라진다.

### 3.4 비기능

- 랩(로컬 Docker) 기준 엔진 순수 시간 p95 < 50ms, 운영 e2e p50이 현재(125ms)보다 나쁘지 않을 것.
- 인덱스는 GIN(bigm 또는 trgm) + `text_pattern_ops` btree(접두). 45k행이라 생성 1~2초, 운영 배포 시 Flyway로 적용.

## 4. Plan — 구현

### 4.1 스키마 (Flyway `V20260818xxxxxx__*.sql`)

```sql
CREATE EXTENSION IF NOT EXISTS pg_bigm;      -- 또는 pg_trgm (랩 결과로 확정)
ALTER TABLE store
  ADD COLUMN name_normalized    varchar(100) GENERATED ALWAYS AS (regexp_replace(lower(name),    '[^0-9a-z가-힣]', '', 'g')) STORED,
  ADD COLUMN address_normalized varchar(255) GENERATED ALWAYS AS (regexp_replace(lower(address), '[^0-9a-z가-힣]', '', 'g')) STORED,
  ADD COLUMN popularity_score   double precision NOT NULL DEFAULT 0;
ALTER TABLE store_name_alias
  ADD COLUMN alias_normalized   varchar(100) GENERATED ALWAYS AS (regexp_replace(lower(alias),   '[^0-9a-z가-힣]', '', 'g')) STORED;
CREATE INDEX idx_store_name_normalized_bigm    ON store USING gin (name_normalized gin_bigm_ops);
CREATE INDEX idx_store_address_normalized_bigm ON store USING gin (address_normalized gin_bigm_ops);
CREATE INDEX idx_store_name_normalized_prefix  ON store (name_normalized text_pattern_ops);
CREATE INDEX idx_store_name_alias_normalized_bigm   ON store_name_alias USING gin (alias_normalized gin_bigm_ops);
CREATE INDEX idx_store_name_alias_normalized_prefix ON store_name_alias (alias_normalized text_pattern_ops);
CREATE INDEX idx_search_region_alias_normalized_prefix ON search_region_alias (normalized_alias text_pattern_ops);
-- popularity 백필: subscribed_store, nationwide_hot_store_ranking, request_log(STORE_VIEW) 집계 UPDATE
```
- 생성 컬럼은 엔티티에 매핑하지 않는다(`ddl-auto=validate`는 매핑된 컬럼만 검사) → 앱 코드가 정규화를 유지할 필요 없음.
- 마이그레이션은 **랩에서 확정한 뒤** 운영 배포로만 적용(로컬 부팅으로 운영에 적용하지 않음).

### 4.2 코드 — 파일 목록

| 구분 | 파일 | 내용 |
|---|---|---|
| 포트 | `store/service/search/StoreSearchEngine.java` (신규) | `engineType()`, `searchStores(intent, pageable, lat, lon, sort)`, `autocompleteStores(query, size)` |
| 라우터 | `store/service/search/StoreSearchEngineRouter.java` (신규), `SearchEngineType` enum | 기본 엔진(프로퍼티) + 요청별 오버라이드(랩 한정). 컨트롤러가 `engine` 파라미터를 받아 서비스로 넘김 |
| DTO 이동 | `OpenSearchStoreHit`→`store/service/search/StoreSearchHit`, `OpenSearchStoreSearchResult`→`StoreSearchResult`, `OpenSearchAutocompleteSuggestion`→`StoreAutocompleteSuggestion` | 엔진 중립 이름으로 이동(레코드 그대로) |
| OS 어댑터 | `external/opensearch/client/OpenSearchStoreClient.java` | `implements StoreSearchEngine`, `@ConditionalOnProperty(search.engine=opensearch, matchIfMissing=true)` |
| PG 엔진 | `store/service/search/postgres/PostgresStoreSearchEngine.java` (신규) | 케이스 분기·폴백 B·hit 조립·타이머 `@Timed("search.engine.query", tags engine=postgres)` |
| PG 쿼리 | `store/repository/search/StoreSearchJdbcRepository.java` + `StoreSearchRow`/`StoreAutocompleteRow` | NamedParameterJdbcTemplate 실행·행 매핑 |
| PG 쿼리 조립 | `store/service/search/postgres/PostgresStoreSearchSqlBuilder.java`, `PostgresSearchPlan.java` | 후보(UNION/INTERSECT) → 점수 → 정렬. 전부 바인드 파라미터 |
| 인기도 | `store/repository/StorePopularityRepository.java`(집계 UPDATE), `store/service/StorePopularityCommandService.java`, `store/batch/StorePopularityScoreScheduler.java`, `admin/controller/AdminStorePopularityController.java` | SQL 한 방 갱신 |
| 서비스 | `StoreSearchService` | `OpenSearchStoreClient` → `StoreSearchEngine` 의존, 폴백 B 호출 추가, 타이머 |
| 조립 | `StoreSearchAssembler` | DTO 타입명만 변경 |
| 설정 | `application.properties`(`search.engine=opensearch` 기본), `application-lab.properties`(신규: 랩 DB·request_log off·스케줄러 off), `OpenSearchProperties`/`StoreDocumentIndexer`에 `opensearch.reindex.batch-delay-ms` |
| 어드민 | `AdminSearchController`에 `POST /admin/search/popularity/refresh` (수동 갱신) |
| 테스트 | `PostgresStoreSearchEngineTest`(Mockito, 케이스 4종+폴백 2종+예외), `PostgresSearchSqlBuilderTest`, `StoreSearchServiceTest` 수정(엔진 인터페이스 mock), `StorePopularityScoreCalculatorTest`, `StorePopularityScoreUpdaterTest`; SQL 자체는 랩 통합 테스트(`@Tag("lab")`, CI 제외)로 실제 PG에서 검증 |

### 4.3 순서 (기능 단위, 구현 직후 테스트)

0. **랩 배선** ✅ — `application-lab.properties`, 재색인 지연 프로퍼티 → `local,lab`로 기동 → OS 재색인 45,339건(2분, 실패 0)
1. **스키마 실측** ✅ — 랩 PG에 정규화 컬럼·접두 인덱스·인기도 백필 적용, 인덱스 없음/bigm/trgm 비교(`scratchpad/index-bench.txt`) → **pg_bigm 확정**. 발견: `name LIKE OR EXISTS(alias)`는 인덱스를 못 타므로 엔진 SQL은 UNION으로; '서울'급 대형 지역은 `popularity_score desc` 인덱스 후보
2. **포트 추출** ✅ — `StoreSearchEngine`/`SearchEngineType`/`SearchProperties`/`StoreSearchEngineRouter`, DTO 이동(`StoreSearchHit`·`StoreSearchResult`·`StoreAutocompleteSuggestion`), OS 어댑터, 서비스·컨트롤러 `engine` 파라미터, 테스트(라우터 6·컨트롤러 3 추가)
3. **인기도** ✅ — 마이그레이션 `V20260818170000`(확장·생성컬럼·인덱스 7·백필, 랩 적용 2.35초), 저장소/서비스/스케줄러/어드민 + 테스트 6
4. **PG 엔진** ✅ — `PostgresStoreSearchEngine`(케이스 4종 + 주소 폴백 + 자동완성), 테스트 20. 랩에서 발견·수정: 원거리 `exp()` underflow → 지수 700 클램프, `ST_Distance` → geography `<->`(8배), alias 점수 LEFT JOIN → 상관 서브쿼리(28→20ms)
5. **A/B 러너** ✅ — `scripts/lab/SearchEngineAbRunner.java` → `docs/reports/search-engine-ab-*.md` (히스토그램 차분으로 엔진 시간, e2e p50/p95, 겹침@10)
6. **최종 점검** ✅ — `spotlessApply` + `build`(checkstyle) 통과. `test` 801건 중 실패 10건은 main 에도 동일하게 실패하는 기존 건(CourseCommandServiceTest·KakaoAuthServiceTest·TrendQueryServiceTest·ReviewRepositoryTest, 이 브랜치와 무관, CI 는 `-x test`). 랩 통합 테스트 `-PlabTests` 8/8 통과 → 커밋/PR 대기

### 4.3-1 랩 A/B 최종 수치 (`docs/reports/search-engine-ab-20260818-1733.md`, 20라운드)

| 항목 | OpenSearch | PostgreSQL(pg_bigm) |
|---|---|---|
| 검색 엔진 순수 시간 mean / p50 / p95 | 4.4ms / ≤5.0 / ≤7.0 | **2.2ms / ≤1.4 / ≤8.4** |
| 검색 e2e p50 / p95 / p99 (hydration 포함) | 32.6 / 43.9 / 48.3 | **29.9 / 39.6** / 50.1 |
| 자동완성 엔진 mean | 4.6ms | **1.9ms** |
| 자동완성 e2e p50 / p95 | 5.3 / 8.0 | **2.5 / 5.9** |
| 상위10 겹침률(검색 29개) | — | 81% (불일치는 지역만 검색의 순위 차이·메뉴 매칭 제거분) |
| 비용 | 월 $130~140 | 0 |

### 4.6 운영 전환 절차 (완전 제거본 기준)

1. PR 머지·배포 — 배포 부팅 시 Flyway 가 `V20260818170000` 적용(랩 2.35초; `CREATE EXTENSION pg_bigm` 은 RDS 마스터 계정 권한 필요). **배포 즉시 검색이 PostgreSQL 로 간다** (OS 코드가 없으므로 단계적 전환 없음)
2. (선택) 운영에서 `POST /admin/search/popularity/refresh` 1회 — 백필은 마이그레이션이 하므로 필수 아님
3. 1~2주 request_log p50/p95(기준선: 검색 125/392ms, 자동완성 28/166ms)·`search_misses`·`search.query{outcome}`·`[검색추적]` 로그 관찰. 회귀 시 롤백 = 이전 이미지 태그 재배포(마이그레이션은 추가 컬럼·인덱스뿐이라 되돌려도 무해)
4. 이상 없으면 OpenSearch 도메인 삭제 → 월 $130~140 → 0. GitHub secrets `PROD_OPENSEARCH_ENDPOINT`/`STAGING_OPENSEARCH_ENDPOINT` 정리
5. FE: 검색 응답의 `matchedFields`에 BREAD_TYPE/MENU, 자동완성에 MENU/BREAD_TYPE 타입이 더 이상 오지 않음(열거형은 유지) — 관련 UI 정리는 후속


## 5. 랩 환경 (완료)

- Docker Desktop 4.87 (arm64). `docker-compose.local-opensearch.yml`(OS 3.5.0 + nori/icu, :9200, Dashboards :5601), `docker-compose.lab-postgres.yml`(PG 18.6 + PostGIS 3.6.1 + pg_bigm 1.2 + pg_trgm 1.6, :5432, `sweetgirl`/`soboro-lab`)
- 운영 덤프 `~/bbanggut-lab/dumps/soboro-20260818-1622.dump`(80MB, request_log 제외) 복원 완료 — store 45,444 · menu 440,579 · alias 1,661 · region alias 10,228 · store_search_region 225,147
- `stores` 인덱스는 `store-index.json`으로 생성됨(문서 0 → 재색인 예정)
