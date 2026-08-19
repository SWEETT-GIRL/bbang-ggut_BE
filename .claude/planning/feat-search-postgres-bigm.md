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
3. **인기도** ✅ — 마이그레이션 `V20260818170000`(확장·생성컬럼·인덱스 4·백필, 랩 적용 2.35초; 안 타는 접두 인덱스 3개는 리뷰 반영으로 제거), 저장소/서비스/스케줄러/어드민 + 테스트 6
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

### 4.5-1 사고 기록 — 마이그레이션이 운영에 선적용됨 (2026-08-19)

- 08-19 11:36 `V20260818170000`(초안: varchar(100)·접두 인덱스 포함)이 운영 RDS 에 적용됨(installed_by=sweetgirl, 7.3초). 운영 배포는 없었고, **IntelliJ 에서 `local` 프로파일(=운영 터널 15432)로 앱을 켠 것**이 원인으로 추정. 12:56 `flyway repair` 로 이력만 삭제(type=DELETE)되고 다른 마이그레이션(`20260819120000`)이 적용됨.
- 결과: 운영(그리고 운영 덤프로 만든 개발 DB)은 "스키마는 있고 이력은 없는" 상태 → PR 배포 시 Flyway 가 재실행한다.
- 대응: 마이그레이션을 **재실행-안전**하게 보정 — `ALTER COLUMN alias_normalized TYPE varchar(255)`(초안이 100 으로 만든 환경 교정), `DROP INDEX IF EXISTS` 접두 3개, 백필에 `IS DISTINCT FROM` 가드. 랩에서 재실행 → 전부 no-op/보정만 확인(UPDATE 0).
- 재발 방지: `local` 프로파일로 부팅하면 운영에 마이그레이션이 적용된다. 로컬 개발은 반드시 `local,lab`(랩 DB) — 5.항 참고. (근본 대책 후보: local 의 flyway.enabled=false 또는 datasource 를 랩으로 기본화 — 별도 논의)

### 4.6 운영 전환 절차 (완전 제거본 기준)

1. PR 머지·배포 — 배포 부팅 시 Flyway 가 `V20260818170000` 적용(랩 2.35초; `CREATE EXTENSION pg_bigm` 은 RDS 마스터 계정 권한 필요). **배포 즉시 검색이 PostgreSQL 로 간다** (OS 코드가 없으므로 단계적 전환 없음). **롤백 = 이전 이미지 태그 재배포. 이전 이미지는 `opensearch.endpoint` 가 없으면 검색 빈이 빠져 검색만 빈 결과가 되므로(부팅은 됨), 관찰 기간(1~2주) 동안 GitHub secrets·`.env` 의 OpenSearch 값과 도메인은 지우지 않는다.**
2. (선택) 운영에서 `POST /admin/search/popularity/refresh` 1회 — 백필은 마이그레이션이 하므로 필수 아님
3. 1~2주 request_log p50/p95(기준선: 검색 125/392ms, 자동완성 28/166ms)·`search_misses`·`search.query{outcome}`·`[검색추적]` 로그 관찰. 회귀 시 롤백 = 이전 이미지 태그 재배포(마이그레이션은 추가 컬럼·인덱스뿐이라 되돌려도 무해)
4. 이상 없으면 OpenSearch 도메인 삭제 → 월 $130~140 → 0. GitHub secrets `PROD_OPENSEARCH_ENDPOINT`/`STAGING_OPENSEARCH_ENDPOINT` 정리
5. FE: 검색 응답의 `matchedFields`에 BREAD_TYPE/MENU, 자동완성에 MENU/BREAD_TYPE 타입이 더 이상 오지 않음(열거형은 유지) — 관련 UI 정리는 후속


## 5. 랩 환경 (완료)

- Docker Desktop 4.87 (arm64). `docker-compose.local-opensearch.yml`(OS 3.5.0 + nori/icu, :9200, Dashboards :5601), `docker-compose.lab-postgres.yml`(PG 18.6 + PostGIS 3.6.1 + pg_bigm 1.2 + pg_trgm 1.6, :5432, `sweetgirl`/`soboro-lab`)
- 운영 덤프 `~/bbanggut-lab/dumps/soboro-20260818-1622.dump`(80MB, request_log 제외) 복원 완료 — store 45,444 · menu 440,579 · alias 1,661 · region alias 10,228 · store_search_region 225,147
- `stores` 인덱스는 `store-index.json`으로 생성됨(문서 0 → 재색인 예정)


## 6. 코드 리뷰 결과 (2026-08-19, `/code-review`, 정적 분석)

기준 `origin/dev...HEAD` 커밋 6개 / 97개 파일. **판정: 경고** — CRITICAL 0 · WARNING 6 · SUGGESTION 8. 반영 상태는 각 항목 앞 체크박스로 관리한다.

확인해서 문제 없음: 사용자 입력은 전부 바인드 파라미터 + `SearchTextNormalizer`가 `%`·`_`·`\` 제거 → SQL/LIKE 인젝션 없음 · 검색 경로에 외부 API 호출 없음 · `Store` 엔티티에 `popularity_score` 미매핑이라 더티체킹이 배치 값을 덮어쓰지 않음 · `location` NOT NULL geography라 거리 NULL 없음 · 어드민 웹에 삭제된 reindex 엔드포인트 호출부 없음 · FE 타입 호환.

### 6.1 WARNING (머지 전 반영 권장 — 특히 ①②③)

- [x] **① 주소 폴백이 0페이지에서만 동작** — `StoreSearchEngine.java:53` `if (!result.hits().isEmpty() || pageable.getPageNumber() > 0) return result;` → **반영**: 페이지 가드 제거, 테스트 `searchStores_fallsBackToAddressOnLaterPagesToo`로 반전
  1차(지역·이름) 후보가 0건이라 주소 폴백으로만 결과가 나오는 검색어("천안시 서북구")는 0페이지에서 `hasNext=true`를 주고도 1페이지에서 1차가 비어 있고 page>0 가드에 걸려 **빈 페이지**를 반환한다. 서비스의 붙어쓴 토큰 폴백도 같은 엔진을 타서 구제되지 않는다. `StoreSearchEngineTest.searchStores_doesNotFallBackOnLaterPages`가 이 동작을 고정 중.
  → 가드 제거(`if (!result.hits().isEmpty()) return result;`). `hasNext`가 +1 페치로 정확하므로 클라이언트가 N페이지를 요청했다면 1차가 N페이지에 결과를 갖거나(그럼 폴백 안 탐) 처음부터 폴백 결과였거나 둘 중 하나라 결과셋이 섞이지 않는다. 테스트를 "2페이지에서도 1차가 비면 주소 폴백으로 이어진다"로 뒤집기.

- [x] **② 후보 규칙 ≠ 판정 규칙(주소로 잡힌 지역 후보)** — `StoreSearchSqlBuilder.java:71-72, 129-138`, `StoreSearchEngine.java:128-140` → **반영**: `region_score = greatest(매핑 weight×40, 주소 매칭 20)`(`REGION_ADDRESS`), 빌더·랩 테스트로 `matchedByRegion()` 확인
  후보는 지역 토큰을 `store_search_region` 매핑 ∪ `address_normalized LIKE`로 잡지만 `region_score`는 매핑(`rg.region_weight`)에서만 나온다. 주소로만 잡힌 가게는 `region_score=0` → `matchedByRegion()=false` → REGION_AND_NAME 검색에서 `covers()` false. 랩 케이스 "양산 트리마제"(정답 1건)가 `MISMATCH_LOOSE`로 집계돼 `search.query{outcome}`·`search_misses`·"입력한 말이 결과에 없음" 로그에 오탐으로 쌓이고 `matchedFields`에 REGION이 빠진다.
  → 내부 SELECT에 `CASE WHEN s.address_normalized LIKE '%'||:region0||'%' [OR …] THEN 1 ELSE 0 END AS address_region_hit`을 뽑아 `region_score`에 낮은 계단(예: 20)으로 합치거나 `StoreSearchRow.matchedByRegion()`이 함께 보게 한다.

- [x] **③ `alias_normalized varchar(100)` vs 원본 `alias VARCHAR(255)`** — `V20260818170000__…sql:20` → **반영**: 운영 미적용이라 마이그레이션 파일을 `varchar(255)`로 수정(랩 DB는 ALTER)
  정규화 후 100자 초과 alias가 들어오면 생성 컬럼에서 `value too long`으로 INSERT/UPDATE 실패(앱에서 못 잡음).
  → 운영 미적용이면 `varchar(255)`(또는 `text`)로 수정. 이미 적용된 환경은 새 마이그레이션 `ALTER TABLE store_name_alias ALTER COLUMN alias_normalized TYPE varchar(255)`.

- [x] **④ 인기도 갱신 엔드포인트가 비인증 + 무거움 + 동시성 가드 없음** — `AdminStorePopularityController.java:23`, `SecurityConfig.java:86`(`/admin/**` permitAll, 기존 정책) → **부분 반영**: `pg_try_advisory_xact_lock`으로 동시 실행 1개 제한 + 409(`STORE409_5`), 배치·다중 인스턴스도 같은 락. **인증은 사용자 결정으로 유지하지 않음(2026-08-19)** — `/admin/**` permitAll 은 기존 전역 정책이며 어드민 인증은 이 PR 에서 다루지 않기로 확정. 동시성 가드(advisory lock + 409)로만 보호
  `POST /admin/search/popularity/refresh`가 인증 없이 45k행 전량 UPDATE(+`request_log` 400MB GROUP BY)를 동기 실행. 반복 호출 시 `store` 행 락 수 초·DB CPU 점유. 삭제된 `AdminSearchController`는 단일 스레드 executor로 동시 실행을 막았지만 새 것은 없음.
  → 어드민 인증이 네트워크 계층에서 보장되지 않으면 인증/키 검사. 최소한 `pg_try_advisory_xact_lock`(또는 `AtomicBoolean`/`tryLock`)으로 동시 실행 1개 제한 + 실행 중 409. advisory lock을 SQL 앞에 두면 다중 인스턴스 `@Scheduled` 중복도 같이 해결.

- [x] **⑤ `/stores/search` 입력 상한/범위 검증 없음(부하가 이제 운영 RDS 프라이머리에 직접 실림)** — `StoreController.java:92` → **반영**: `size` 50 클램프(`MAX_SEARCH_PAGE_SIZE`), `lat/lon` 범위·짝 검증 → 400(`IllegalArgumentException` 기존 핸들러), 테스트 3
  비로그인 허용 엔드포인트에서 `size=100000`이면 "서울" 후보 1.5만 건 전부 정렬·반환 + `StoreSearchAssembler`가 1.5만 가게 메뉴·이미지 하이드레이션. `lat=1000`이면 `ST_MakePoint(...)::geography`가 "Coordinate values are out of range"로 500.
  → `size` 상한(예: 50) 클램프, `lat∈[-90,90]`·`lon∈[-180,180]` 검증 → 400. 자동완성은 이미 `MAX_AUTOCOMPLETE_SIZE=20`.

- [x] **⑥ 운영 덤프 도구가 개인정보 테이블을 마스킹 없이 로컬로 복제** — `scripts/lab/dump-prod.sh:33` → **반영**: 회원·인증·알림·신고·리뷰·기록 등 34개 테이블 데이터 기본 제외(스키마만), `FULL_DUMP=1`로만 전체, 보관·삭제 원칙 머리말에 명시
  검색 실험에는 `store`·`store_name_alias`·`search_region*`·`menu`면 충분한데 `member`·알림 토큰·리뷰 등이 로컬 Docker(`soboro-lab`, 5432 노출)에 그대로 올라감.
  → `pg_dump --table` 화이트리스트(또는 개인정보 테이블 `--exclude-table-data`)를 기본으로, 전체 덤프는 명시 플래그로만. 스크립트 머리말에 보관·삭제 규칙.

### 6.2 SUGGESTION

- [x] 접두 인덱스 3개(`idx_*_normalized_prefix`, 마이그레이션 :28-30)는 어떤 쿼리도 안 탄다 — 자동완성 WHERE도 `'%'||:q||'%'`(bigm)이고 `:q||'%'`는 CASE(tier)에만 있음. 자동완성 WHERE를 접두로 바꾸든지 인덱스를 빼든지 하나로. → **반영**: 마이그레이션에서 제거(자동완성은 bigm 인덱스로 0.14ms 확인)
- [x] `StoreSearchJdbcRepository.java:12`가 `service.search.StoreSearchSqlBuilder.SqlQuery`를 import — 의존 역전. `SqlQuery`를 `store.repository.search`로 옮기거나 빌더를 repository 패키지로. → **반영**: `SearchSqlQuery`를 `store.repository.search`로 이동
- [x] `SearchQueryCoverageTest` 삭제로 `isFullyClassified`의 "불용어만 잔여('성수동 빵집')" 분기 테스트가 사라졌고 새 `classifyIntent`(4분기) 테스트 없음 — `StoreSearchService.java:119, 173` package-private static이라 단위 테스트 4~5개면 됨. → **반영**: `classifyIntent` 4분기·`isFullyClassified` 3케이스 테스트 추가
- [x] `StorePopularityRepository.java:19` — 값이 같아도 45k행 전부 UPDATE. `AND s.popularity_score IS DISTINCT FROM (계산식)`으로 실제 변경분만. → **반영**: `IS DISTINCT FROM`으로 변경분만 UPDATE
- [x] 롤백 경로: 새 CD 워크플로우가 `.env`에 `opensearch.endpoint`를 안 쓰는데 이전 이미지 `OpenSearchConfig`는 `@ConditionalOnProperty("opensearch.endpoint")`라 그 값 없이는 **이전 이미지가 부팅 실패**. 관찰 기간(1~2주) 동안 시크릿·env 삭제를 미루고 롤백 시 복원 절차를 4.6에 추가. → **반영**: 4.6 에 "관찰 기간 동안 secrets/env·도메인 유지" 명시
- [x] `docker/postgres-lab.Dockerfile:10` — `git clone --depth 1` master 빌드라 재현성 없음. RDS와 같은 1.2 태그 고정. → **반영**: `v1.2-20250903` 태그 고정
- [x] `docs/search-engine-migration-notion.md:147,170` — `scripts/lab/SearchEngineAbRunner.java`는 미커밋, `docker-compose.local-opensearch.yml`은 이 브랜치에서 삭제됨. 러너를 커밋하거나 문서에서 빼고 리포트 재현 절차 맞추기. → **반영**: 러너·삭제된 compose 참조 제거, 완전 제거·롤백 절차로 갱신
- [x] 미커밋 변경(`.gitignore`, `docs/s3-resized/`, `reprocess-done-prod-notdone.txt.bak` S3 키 26,809줄)은 이미지 리사이즈 작업 산출물 — 이 PR에 섞이지 않게 정리. `.gitignore`가 `.bak`은 안 막음. → **부분 반영**: `.gitignore`에 `reprocess-done-*.txt.bak` 추가. `docs/s3-resized/`·`.bak` 파일은 이 PR과 무관해 그대로 미추적

### 6.3 배포 시 유의 (이슈 아님)

- `CREATE EXTENSION pg_bigm`은 rds_superuser 권한 필요(4.6 기록됨).
- `ALTER TABLE store ADD COLUMN … GENERATED STORED`는 테이블 재작성 → Flyway 동안 `store`에 ACCESS EXCLUSIVE 락(랩 2.35초). 롤링 배포 중 구 인스턴스 store 조회가 그만큼 대기.
- 테스트 체크리스트: 새 Service·Controller·Scheduler·Engine·SqlBuilder 모두 테스트 파일 있음, 성공/실패 케이스 각각 존재. SQL 실행은 `@Tag("lab")` 통합 테스트로 분리(CI 제외) — 적절.
