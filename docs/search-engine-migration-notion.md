# 검색 엔진 전환: OpenSearch → PostgreSQL(pg_bigm)

> 작성 2026-08-18 · 브랜치 `feat/search-postgres-bigm` · 상태: **구현·랩 실측 완료, 운영 전환 대기**
> 상세 아티클(차트 포함): [OpenSearch에서 pg_bigm으로](https://claude.ai/code/artifact/bb52d7ca-0cb4-4111-930a-75d4676a05f1) · 플래닝 `.claude/planning/feat-search-postgres-bigm.md`

## TL;DR

- 가게 검색을 **AWS OpenSearch(t3.medium.search × 2, 월 $130~140) → PostgreSQL pg_bigm(추가 비용 0)** 으로 옮겼다. 검색 범위는 **가게명·구명·지역·주소**로 좁혔다(메뉴·카테고리 검색 제외 — 제품 결정).
- 랩 A/B(같은 JVM, 20라운드, 실검색어 40개): **엔진 순수 시간 4.4 → 2.2ms**, 검색 e2e p50 **32.6 → 29.9ms**, 자동완성 e2e p50 **5.3 → 2.5ms**, 상위10 결과 겹침 **81%**.
- API 계약은 그대로. `search.engine` 프로퍼티 하나로 두 엔진을 전환/롤백할 수 있고, 지금 기본값은 `opensearch`(배포 후 `postgres`로 전환 예정).
- OpenSearch 노드는 운영 전환 후 1~2주 관찰 뒤 별도 PR에서 코드·도메인 제거.

---

## 1. 왜 바꿨나 — 숫자

| 항목 | 값 | 출처 |
|---|---|---|
| OpenSearch 비용 | `t3.medium.search` × 2 (2 AZ) + gp3 20GiB × 2 ≈ **월 $130~140** | AWS 콘솔 |
| 검색 트래픽 | 30일 검색 1,857 + 자동완성 5,236 = **7,093건 (하루 300~1,000)** | 운영 `request_log` |
| 운영 e2e 기준선 | 검색 p50 **125ms** / p95 392 / p99 949 · 자동완성 p50 **28ms** / p95 166 / p99 823 | 운영 `request_log` 30일 (WAS에서 측정, RDB 조립 4쿼리 포함) |
| 데이터 규모 | store **45,444**(ACTIVE 45,339) · menu 440,579 · store_name_alias 1,661 · search_region_alias 10,228 · store_search_region 225,147 | 운영 DB |
| OpenSearch 코드 | `external/opensearch` ~1,600줄 + 검색 서비스 ~900줄 + 테스트 1,770줄, 인덱스 1개(nori·icu·edge_ngram·ngram·nested·function_score) | 코드베이스 |

> 💡 처음 계획은 "EC2 셀프호스팅 vs PG, 속도 재서 결정"이었는데, 이 규모에선 둘 다 충분히 빠르다. 결정을 가른 건 **"메뉴·카테고리 검색을 뺀다"는 제품 판단** — 그러면 OpenSearch가 하던 무거운 일(nested 메뉴 44만 건, inner_hits, 빵 사전 매칭)이 사라지고 4.5만 행 이름 부분일치 + 지역 조인만 남는다.

## 2. 결정 과정 (2026-08-18 하루)

| 순서 | 질문 | 실측 | 결정 |
|---|---|---|---|
| ① | 속도가 결정 축인가? | 운영 DB에서 인덱스 없이 `ILIKE` 풀스캔 = 가게 43ms, 메뉴 105ms | 아니다. 축은 기능 동등성·재작성 비용·운영 부담·비용 |
| ② | tsvector로 되나? | 운영 가게명 45,339건 실측 (아래 표) | 안 된다 — 한국어는 n-gram 부분일치가 필요 |
| ③ | pg_bigm vs pg_trgm? | 랩 PG 인덱스 변형 벤치 (아래 표) | **pg_bigm** (2글자 검색어에서 50배 차이) |
| ④ | 어떻게 되돌리나? | — | 엔진 포트 + `search.engine` 프로퍼티 + 랩 한정 `engine=` 파라미터 |
| ⑤ | 실측을 어떻게 공정하게? | — | 둘 다 로컬 Docker(운영 덤프 복제본), 같은 JVM에서 A/B |

### ② tsvector 실측 — 우리 가게명에서 어떻게 깨지나

`to_tsvector('simple', name)`은 공백으로만 자른다 (RDS엔 한국어 형태소 분석기 없음). 가게명 **29%(13,229/45,339)가 공백 포함**.

| 실제 검색어 | tsvector 정확 | tsvector 접두 | 정규화 부분일치 | 비고 |
|---|---|---|---|---|
| 성심당본점 | 0 | 0 | 1 | 가게명 "성심당 본점" — 띄어쓰기 |
| 브릭 베이글 | 0 | 0 | 1 | 가게명 "브릭베이글" — 반대 방향 |
| 트리마제 | 0 | 0 | 1 | "양산트리마제점" 토큰 안 |
| 베이커리 | 246 | 532 | 2,714 | "OO베이커리"가 한 토큰 |
| 콜드버터 | 0 | 3 | 3 | 접두만 됨 |
| 성심당 본점 | 2 | 2 | 1 | 단어 AND는 tsvector가 나은 유일 케이스 |

### ③ 인덱스 변형별 지연 (랩 PG, 45k 가게, p50 ms, 15회)

| 쿼리 | 인덱스 없음 | **pg_bigm** | pg_trgm |
|---|---|---|---|
| 이름 포함 2글자 '몽심' | 6.66 | **0.36** | 17.87 (seq scan) |
| 이름 포함 2글자 '대구' (243건) | 6.41 | **0.47** | 18.57 (seq scan) |
| 이름 포함 '성심당' | 6.50 | 0.29 | 0.33 |
| 이름 포함 '베이커리' (2,714건) | 6.93 | 1.67 | 1.70 |
| 자동완성 접두 '성심' | 0.25 | 0.26 | 0.28 |
| 인덱스 크기(가게명) | – | **3.7MB** | 7.4MB |

> ⚠️ 랩 머신(M-시리즈)은 운영 RDS보다 훨씬 빨라 **절대값은 상대 비교로만** 본다. 같은 seq scan이 운영에선 43ms.

## 3. 무엇이 어떻게 바뀌나

### API (계약 불변)

| 항목 | 전 | 후 |
|---|---|---|
| `GET /stores/search` 파라미터/응답 | 그대로 | 그대로 (+ `engine=` 파라미터, **운영에선 무시**, 랩 A/B 전용) |
| 검색 대상 | 가게명·구명·지역·빵종류·메뉴명·메뉴카테고리 | **가게명·구명·지역·주소(폴백)** |
| `matchedFields` | STORE_NAME · REGION · BREAD_TYPE · MENU | STORE_NAME · REGION 만 |
| 자동완성 타입 | STORE · MENU · BREAD_TYPE · REGION | **STORE · REGION만 결과 생성.** 열거형은 유지 → FE가 `types=MENU`를 보내도 400 아님(빈 결과) |
| `hasNext` | `hits.size()==pageSize` 추정(마지막 페이지에서 틀림) | size+1 조회로 정확 |
| 지역 사전 밖 지역어 ("천안시 서북구") | 0건 | 주소 부분일치 폴백 → 1건 |
| 인기도 | OS 문서 `popularityScore` (주간 배치) | `store.popularity_score` 컬럼, 같은 산식(구독수+전국랭킹+조회수), 주간 배치 + `POST /admin/search/popularity/refresh` |
| 장애 시 | OS 오류 → 빈 결과 + 200 | DB 오류 → 500 (숨기지 않음) |

### 알려진 동작 차이 (의도된 것)

- 메뉴 매칭이 빠진다: "한남 에그타르트"(에그타르트 메뉴 가게들), "우드 앤 브릭"(메뉴명) → 0건. 빵 단어는 가게명 부분일치로만 취급 ("에그타르트" → 이름에 든 가게). 수요는 기존처럼 `search_misses`·`search.query{outcome}`로 계속 관찰.
- 지역만 검색("대구·수원·성수동")은 후보 집합은 같고 **순위**가 다르다 (OS BM25+nested 부스트 vs PG 지역 weight × 인기도 × 거리). 겹침@10 10~20%.
- 자동완성은 후보의 정확성이 아니라 우선순위가 다르다 (PG: 접두 우선 → 인기도).

### 랭킹 (설명 가능한 계단, OpenSearch function_score와 같은 꼴)

```
score = (max(name_score, alias_score) + region_score + address_score)
        × (1 + 0.3·ln(1 + popularity) + gauss(distance))

name  : 정확 100 · 접두 80 · 포함 60   (검색어 전체와 각 이름 토큰 중 최고)
alias : 같은 계단 × 0.75               (구명은 한 단계 낮게)
region: 40 × 매핑 weight (핫플 1.0 · 동 0.9 · 역 0.8 · 구 0.5 · 시 0.3)
gauss : scale 3km, offset 500m, decay 0.5 — OS와 동일 파라미터
sort  : relevance=점수 · distance=거리→점수 · popularity=인기도→점수
```

## 4. 랩 A/B 결과 (2026-08-18, `docs/reports/search-engine-ab-20260818-1733.md`)

**조건**: Docker OpenSearch 3.5.0(+nori/icu, 운영과 같은 `store-index.json`, 45,339건 색인) · Docker PostgreSQL 18 + PostGIS + pg_bigm(운영 덤프 복제본) · 같은 JVM(`local,lab`) · 실검색어 30개 + 자동완성 접두어 10개 × 20라운드(워밍업 3회 제외) · 라운드마다 엔진 순서 교대 · 좌표 있음(거리 가중 켜짐)

| 지표 (ms) | OpenSearch | **PostgreSQL** | 읽는 법 |
|---|---|---|---|
| 검색 엔진 mean / p50 / p95 | 4.4 / ≤5.0 / ≤7.0 | **2.2 / ≤1.4 / ≤8.4** | 엔진 자체는 2배 |
| 검색 e2e p50 / p95 / p99 | 32.6 / 43.9 / 48.3 | **29.9 / 39.6** / 50.1 | e2e는 RDB 조립(~25ms)이 지배 → 10% |
| 자동완성 엔진 mean | 4.6 | **1.9** | |
| 자동완성 e2e p50 / p95 | 5.3 / 8.0 | **2.5 / 5.9** | 조립이 없어 엔진 차이가 그대로 |
| 상위10 겹침률 (검색 29개) | 81% | | |
| 5xx | 0 | 0 | |

검색어별 (e2e p50, 겹침@10):

| 검색어 | OS | PG | 겹침 | 해석 |
|---|---|---|---|---|
| 성심당 | 37.0 | 33.3 | 80% | 동일 집합, 지점 순서 차이 |
| 브릭베이글 · 밀로밀 · 몽심 · 콜드버터 · 하츠베이커리 | 30~34 | 27~30 | 100% | 가게명 |
| 망원 브릭 | 31.5 | 27.9 | 100% | 지역 ∩ 이름 |
| 천안시 서북구 | 29.9 | 27.8 | 100% | 주소 폴백이 같은 가게를 찾음 |
| 대구 / 수원 / 성수동 | 34~37 | 32~37 | 10~20% | 지역만 검색 — 순위 차이 |
| 브릭 베이글 | 30.5 | 26.3 | 50% | OS는 베이글 메뉴까지 (범위 축소분) |
| 우드 앤 브릭 | 29.3 | 4.1 | 0% | OS 1건은 메뉴명 (범위 축소분) |
| 베이커리 | 37.3 | 48.1 | 70% | 후보 2,714건 스코어링 — PG가 유일하게 느린 검색어 |

### 실측 중 잡은 버그 (전부 랩에서, 운영 미반영)

| 문제 | 원인 | 조치 |
|---|---|---|
| 좌표 검색 500 | 망원↔대전 150km → `exp(-1700)`을 PG가 underflow **오류**로 냄 | 지수 700 클램프 |
| '베이커리' 56ms | 후보 2.7k에 `ST_Distance`(회전타원체) 18ms | geography `<->`(구면) 2.2ms, 원거리 오차 0.3% |
| 같은 쿼리 28ms | 구명 점수 LEFT JOIN 행수 추정 1 → 중첩루프×Materialize | 가게별 상관 서브쿼리 → 20ms |
| `name LIKE OR EXISTS(alias)` 14ms | 인덱스 못 탐 (seq scan) | 후보를 UNION/INTERSECT로 조립 |

> ✅ 정직한 요약: 엔진은 2배 빨라졌지만 e2e는 10%. 검색 응답의 대부분은 검색이 아니라 뒤의 RDB 조립이다. 이 전환의 실제 가치는 **비용 0 · 색인 파이프라인 제거(정합성 즉시 반영) · 설명 가능한 랭킹**.

## 5. 코드 구조 (브랜치 `feat/search-postgres-bigm`)

| 구분 | 파일 |
|---|---|
| 포트/라우터 | `store/service/search/StoreSearchEngine` · `SearchEngineType` · `SearchProperties` · `StoreSearchEngineRouter` |
| PG 엔진 | `store/service/search/postgres/PostgresStoreSearchEngine` · `PostgresStoreSearchSqlBuilder` · `PostgresSearchPlan` |
| PG 실행 | `store/repository/search/StoreSearchJdbcRepository` · `StoreSearchRow` · `StoreAutocompleteRow` |
| OS 어댑터 | `external/opensearch/client/OpenSearchStoreClient implements StoreSearchEngine` (기존 유지) |
| DTO 이동 | `OpenSearchStoreHit`→`StoreSearchHit`, `OpenSearchStoreSearchResult`→`StoreSearchResult`, `OpenSearchAutocompleteSuggestion`→`StoreAutocompleteSuggestion` |
| 인기도 | `StorePopularityRepository`(집계 UPDATE) · `StorePopularityCommandService` · `store/batch/StorePopularityScoreScheduler` · `admin/controller/AdminStorePopularityController` |
| 마이그레이션 | `V20260818170000__add_store_search_normalized_columns_and_bigm_indexes.sql` — `pg_bigm` 확장, 생성 컬럼(`name_normalized`·`address_normalized`·`alias_normalized`), `popularity_score`, 인덱스 7개, 백필 (랩 적용 2.35초) |
| 설정 | `application.properties`: `search.engine=opensearch`, `search.engine-override-enabled=false` · `application-lab.properties`(랩) |
| 테스트 | 단위 35 (라우터 6·컨트롤러 3·빌더 9·엔진 11·인기도 6) + 랩 통합 8 (`./gradlew test -PlabTests`, CI 제외) |
| 랩/측정 | `docker/`, `docker-compose.lab-postgres.yml`, `docker-compose.local-opensearch.yml`, `scripts/lab/{dump-prod,restore-lab}.sh`, `scripts/lab/SearchEngineAbRunner.java` |

`./gradlew spotlessApply && ./gradlew build` 통과. `./gradlew test` 801건 중 실패 10건은 **main에서도 동일하게 실패하는 기존 건**(Course/KakaoAuth/Trend/ReviewRepository, 이 브랜치와 무관, CI는 `-x test`).

## 6. 랩 재현 방법

```bash
# 1) 컨테이너 (Docker Desktop 필요)
docker compose -f docker-compose.local-opensearch.yml up -d --build   # :9200, Dashboards :5601
docker compose -f docker-compose.lab-postgres.yml up -d --build       # :5432 soboro / sweetgirl / soboro-lab

# 2) 운영 덤프 → 랩 PG (운영 터널 localhost:15432 필요, request_log 제외, 80MB)
scripts/lab/dump-prod.sh          # ~/bbanggut-lab/dumps/
scripts/lab/restore-lab.sh        # 복원 + pg_bigm/pg_trgm 확장

# 3) 앱 — 반드시 local,lab (local 단독은 운영 RDS 터널을 바라봄!)
./gradlew bootRun --args='--spring.profiles.active=local,lab --management.server.port=8082'
curl -XPUT localhost:9200/stores -H 'Content-Type: application/json' --data-binary @src/main/resources/opensearch/store-index.json
curl -XPOST localhost:8080/admin/search/reindex/stores      # 45k건 ≈ 2분 (랩은 지연 50ms)

# 4) 비교
curl 'localhost:8080/stores/search?query=성심당&engine=postgres'
curl 'localhost:8080/stores/search?query=성심당&engine=opensearch'
java scripts/lab/SearchEngineAbRunner.java 20               # → docs/reports/search-engine-ab-*.md
```

## 7. 운영 전환 절차

| 단계 | 내용 | 롤백 |
|---|---|---|
| 1 | PR 머지·배포. 기본 엔진 `opensearch` 그대로. 부팅 시 Flyway가 마이그레이션 적용(랩 2.35초). `CREATE EXTENSION pg_bigm`은 RDS 마스터 계정 권한(postgis 만든 계정) | — |
| 2 | (선택) `POST /admin/search/popularity/refresh` 1회 | — |
| 3 | `search.engine=postgres`로 프로퍼티 변경 후 재배포 | 프로퍼티를 `opensearch`로 되돌려 재배포 |
| 4 | 1~2주 관찰: `request_log` p50/p95(기준선 검색 125/392, 자동완성 28/166ms) · `search_misses` · `search.query{outcome}` · `search.engine.query` 타이머 | 위와 동일 |
| 5 | OpenSearch 제거 PR (`external/opensearch` 패키지·색인 이벤트·재색인 배치·주간 점수 배치·의존성) → 도메인 삭제 → **월 $130~140 → 0** | 이 단계 전까지는 언제든 되돌림 가능 |

## 8. 남은 리스크 · 후속 후보

- **품질 회귀** — 랭킹을 옮긴 게 아니라 다시 정의했다. 겹침률·실패 로그로 감시, 롤백은 프로퍼티 하나.
- **랩 ≠ 운영** — 절대값은 운영에서 다시 잰다. RDS `random_page_cost`(SSD인데 기본 4)를 1.1로 낮추면 인덱스 중첩루프가 선택되는 것 확인 → 파라미터 그룹 조정 후보.
- **메뉴 검색 수요** — `search_misses`에 쌓이는 빵 단어 검색이 많으면 "빵 토큰 → 카테고리 → 조인"으로 재도입 검토 (tsvector 아님).
- **tsvector 하이브리드는 지금 안 얹음** — 우리 검색어(아는 이름을 치는 조회)에선 추가 이득이 없음. 긴 텍스트(소개글·리뷰·코스 설명) 검색이 생기면 재검토.
- **자동완성 자음 입력** — 상위 입력에 `ㅁ·ㅇ·ㅅ·ㅎ` 각 11~17회 (IME 조합 중간 상태). FE 디바운스 150~200ms 또는 `name_chosung` 컬럼+bigm으로 한 타 먼저 반응 — 별개 소규모 후보.
- **오타 허용** — 기존 OS도 fuzzy 없었음. 필요 시 `bigm_similarity` 폴백으로 추가 가능.

## 9. FE에 알릴 것

- 검색·자동완성 URL/응답 형식 변화 없음. `engine=` 파라미터는 로컬 랩 전용(운영은 무시).
- 자동완성에서 `MENU`·`BREAD_TYPE` 타입 후보가 더 이상 오지 않는다(열거형은 유지). 메뉴명 검색이 0건이 되는 UX 문구/안내가 있으면 검토.
- `matchedFields`에 `BREAD_TYPE`·`MENU`는 오지 않는다.

## 참고

- 최종 A/B 리포트: `docs/reports/search-engine-ab-20260818-1733.md` (1729 = 최적화 전, 1717 = 2라운드 시운전)
- 아티클(차트·설계 상세): https://claude.ai/code/artifact/bb52d7ca-0cb4-4111-930a-75d4676a05f1
- 플래닝(결정 기록·재검토): `.claude/planning/feat-search-postgres-bigm.md`
