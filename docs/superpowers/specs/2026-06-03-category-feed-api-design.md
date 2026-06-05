# 카테고리별 순위 피드 — 점수 서빙 경로 (BE) 설계

> 상태: **설계 확정 대기** · 작성일 2026-06-03 · 브랜치 작업 예정
> 범위: 블로그 점수(`scripts/blog_score/` 산출물)를 앱 홈 피드로 내보내는 **BE 서빙 경로**.
> 상위 맥락: 메인 홈을 "위치 헤더 + 배너 + 카테고리별 순위 피드"로 개편. 본 문서는 그중 **[A] 점수 → 앱 전달 API**만 다룬다.

---

## 0. 배경 — 점수는 이미 있고, 전달 경로만 없다

- 점수 엔진(`scripts/blog_score/`)은 동작 중. 산출물은 **`docs/reports/{slug}_blog_score_{run}_v3.json`** 파일이다. (`bbang_score` PostgreSQL DB는 문서 계획만 있고 미구축)
- v3 JSON은 이미 피드에 바로 쓸 형태다:
  - `categories`: 10그룹 각각의 **점수 내림차순 랭킹 배열** — 가게당 `store_id · name · address · normalized(0~100) · mention_count · recent_count · review_count · rating`
  - 현재 데이터: **망원동 · 공덕동** 2개 동 (PoC)
- 카테고리 10그룹은 확정(`blog-score-feed-architecture.md` §2): 소금빵 · 크루아상·페이스트리 · 식사빵·하드빵 · 베이글 · 조리빵·샌드위치 · 케이크·생크림 · 타르트·파이 · 구움과자 · 도넛·디저트빵 · 앙금·전통·기타
- 가게 썸네일은 점수 JSON에 없고 **운영DB**에 있다. 기존 `StoreImageFileRepository.findThumbnailsByStoreIdIn(storeIds)` → `(storeId, awsKey)` 프로젝션을 **재사용**한다. (FE는 `buildCdnUrl(awsKey)`)

## 0.1 확정된 결정 (브레인스토밍)

| # | 결정 | 값 |
|---|------|----|
| 1 | 서빙 경로 | **신규 BE API + `bbang_score` 저장소** |
| 2 | 지역 커버리지 | 전국 노출 + 데이터 없는 지역은 **빈 상태** |
| 3 | 점수 표시 | 점수 배지 표시, **빈(0개) 카테고리는 숨김** |
| 4 | API 소스 | **JSON → DB 적재(로더) 후 DB 조회** (정적 JSON 직접 읽기 아님) |
| 5 | 저장소 형태 | 운영DB와 **같은 PG 인스턴스 + 별도 `bbang_score` 스키마**, Spring **단일 datasource** |

> 홈 레이아웃 개편(FE)·관심지역 설정 모달(3단 광역시도/시군구/읍면동 캐스케이드)은 **별도 sub-project**. 본 문서 범위 밖.

---

## 1. 데이터 흐름

```
score_v3 (기존)
   └─> docs/reports/{slug}_blog_score_{run}_v3.json
            │
            │  [신규] load_to_db.py  — JSON 파싱 → bbang_score 스키마 upsert → 해당 region run을 active 마킹
            ▼
   PostgreSQL (운영과 동일 인스턴스)
     └─ schema bbang_score: blog_crawl_run · store_category_score
            │
            │  Spring JPA (단일 datasource, @Table(schema="bbang_score"))
            │   - region + is_active run 조회
            │   - 카테고리별 normalized desc, 상위 10
            │   - store_id 배치 → findThumbnailsByStoreIdIn (운영 스키마 조인)
            ▼
   GET /feed/categories?region=공덕동   →   FE 홈 피드
```

---

## 2. 저장소 — `bbang_score` 스키마

운영DB와 같은 PostgreSQL 인스턴스에 **스키마만 분리**(`bbang_score`). 앱은 이 스키마에만 write 하며 운영 테이블(`store`, `store_image_file` 등)은 **read-only 조인**만 한다. PostGIS·Hibernate Spatial 충돌 없음(단일 datasource 유지).

### 2.1 DDL

```sql
CREATE SCHEMA IF NOT EXISTS bbang_score;

-- 크롤/점수 산출 1회 = run. region별로 active run 1개.
CREATE TABLE bbang_score.blog_crawl_run (
    id          BIGSERIAL PRIMARY KEY,
    region_3rd  VARCHAR(50)  NOT NULL,        -- 예: '공덕동'
    run_key     VARCHAR(64)  NOT NULL,        -- JSON run 식별자 (예: '20260603T112728-v3')
    is_active   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (region_3rd, run_key)
);
-- region별 active run은 부분 유니크로 1개 보장
CREATE UNIQUE INDEX uq_active_run_per_region
    ON bbang_score.blog_crawl_run (region_3rd) WHERE is_active;

-- 가게 × 카테고리 점수
CREATE TABLE bbang_score.store_category_score (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT       NOT NULL REFERENCES bbang_score.blog_crawl_run(id),
    store_id       BIGINT       NOT NULL,           -- 운영 store.id 참조값 (FK 아님)
    bread_category VARCHAR(32)  NOT NULL,           -- 그룹 코드: SALT_BREAD, CROISSANT ...
    normalized     NUMERIC(5,1) NOT NULL,           -- 0~100
    mention_count  INT          NOT NULL DEFAULT 0,
    recent_count   INT          NOT NULL DEFAULT 0,
    review_count   INT,                              -- 없으면 null
    rating         NUMERIC(3,2),                     -- 없으면 null
    name           VARCHAR(200) NOT NULL,            -- 스냅샷 가게명
    address        VARCHAR(300),
    UNIQUE (run_id, store_id, bread_category)
);
CREATE INDEX idx_scs_run_cat_norm
    ON bbang_score.store_category_score (run_id, bread_category, normalized DESC);
```

> `store_id`는 운영 `store.id`를 참조하는 **단순 값**(물리 FK 아님 — 운영 무손상 원칙). 썸네일 조인 시에만 운영 스키마 사용.

### 2.2 카테고리 코드 ↔ 라벨 (BE enum `BreadCategoryGroup`)

JSON 키(한글 그룹명) → 코드 매핑. `order`로 피드 표준 정렬.

| order | code | label (JSON 키) |
|---|------|------|
| 1 | `SALT_BREAD` | 소금빵 |
| 2 | `CROISSANT` | 크루아상·페이스트리 |
| 3 | `HARD_BREAD` | 식사빵·하드빵 |
| 4 | `BAGEL` | 베이글 |
| 5 | `SAVORY` | 조리빵·샌드위치 |
| 6 | `CAKE` | 케이크 |
| 7 | `TART` | 타르트·파이 |
| 8 | `BAKED_SWEET` | 구움과자 |
| 9 | `DONUT` | 도넛·디저트 |
| 10 | `ETC` | 단과자·크림빵 |

> 라벨은 v3 JSON의 `categories` 키와 **정확히 일치**해야 한다(로더 매핑 기준). 코드 작성 시 최신 JSON 키로 재확인하여 enum 확정.

---

## 3. 로더 — `scripts/blog_score/load_to_db.py` (신규)

파이프라인 §6의 5단계 "publish"에 해당. 기존 파이썬 자산(`regions.slug_for`, psycopg2) 재사용.

- **사용**: `.venv/bin/python -m blog_score.load_to_db --region 공덕동`
- **동작**:
  1. `docs/reports/{slug}_blog_score_*_v3.json` 중 **최신** 파일 로드
  2. `bbang_score.blog_crawl_run` 에 새 run insert (`run_key` = JSON `run`)
  3. JSON `categories[label][]` 순회 → label→code 매핑 → `store_category_score` upsert (이미 있으면 갱신, 멱등)
  4. 동일 region의 **기존 active run을 false**로, 새 run을 **active=true** (트랜잭션)
- **멱등성**: 같은 `(region, run_key)` 재실행은 안전(UNIQUE upsert).
- **DB 접속**: 운영과 같은 인스턴스, 단 **`bbang_score` 스키마에만 write**. (DDL은 최초 1회 마이그레이션 또는 로더의 `CREATE SCHEMA/TABLE IF NOT EXISTS`로 보장)

> 갱신 주기는 수동(로더 실행)으로 시작. 일/주 배치 자동화는 후속.

---

## 4. API 계약

### 4.1 엔드포인트

```
GET /feed/categories?region={region_3rd}
```
- **공개 엔드포인트** (인증 불필요). `SecurityConfig`에 `/feed/**` permitAll 추가.
- `region`: region_3rd 동명 (예: `공덕동`). 필수.

### 4.2 응답 (`ApiResponse<CategoryFeedResponse>`)

```jsonc
{
  "success": true, "code": "...", "message": "...",
  "data": {
    "region": "공덕동",
    "run": "20260603T112728-v3",
    "categories": [                 // 빈(0개) 카테고리 제외 · BreadCategoryGroup.order 순
      {
        "code": "BAGEL",
        "label": "베이글",
        "storeCount": 3,
        "stores": [                 // normalized desc · 최대 10
          {
            "storeId": 3304,
            "name": "아이엠베이글 공덕점",
            "thumbnailUrl": "stores/3304/xxx.jpg",  // awsKey (FE가 buildCdnUrl)
            "score": 87,            // Math.round(normalized)
            "mentionCount": 17,
            "reviewCount": 3712,
            "rating": 4.57
          }
        ]
      }
    ]
  }
}
```

### 4.3 규칙

- **카테고리 순서**: `BreadCategoryGroup.order` 표준 순서. (붙여진 참조 목록과 일치)
- **카테고리당 가게 수**: 최대 **10** (`normalized` 내림차순).
- **빈 카테고리**: 가게 0개면 `categories`에서 **제외**.
- **점수**: `Math.round(normalized)` → 정수 "점".
- **썸네일 없는 가게**: `thumbnailUrl=null` (FE가 로고 플레이스홀더 — 기존 동작 재사용).
- **데이터 없는 지역**: active run 없음 → `categories: []`, `run: null`. (200 OK, FE 빈 상태)
- **`region` 누락**: 400 + 도메인 에러코드.

---

## 5. BE 구조 (신규 `feed` 도메인)

```
sweetgirl.croissant.feed
├── controller/  FeedController            GET /feed/categories
├── service/     CategoryFeedService       run 조회 → 점수 조회 → 썸네일 조인 → 조립
├── repository/  BlogCrawlRunRepository    findByRegion3rdAndIsActiveTrue
│                StoreCategoryScoreRepository  findTop10 ... (run, category, order by normalized desc)
├── entity/      BlogCrawlRun, StoreCategoryScore   (@Table(schema="bbang_score"))
├── dto/response/ CategoryFeedResponse, CategoryBlock, FeedStore
└── BreadCategoryGroup (enum: code, label, order)
```

- 썸네일: 모든 카테고리의 store_id를 모아 **1회 배치** `findThumbnailsByStoreIdIn` → Map<storeId, awsKey> 조립(N+1 방지).
- 점수 조회: 카테고리별 top10을 한 번에 가져오려면 윈도우 쿼리 또는 (active run의 전체 row 조회 후 메모리 그룹핑·정렬·자르기). 데이터 규모 작아 **후자(메모리 처리)로 단순화**.

---

## 6. 테스트 전략

- **로더(Python)**: 샘플 v3 JSON → 임시 스키마 적재 → run active 토글·멱등성 검증.
- **Service 단위**: 점수 row 목록 + 썸네일 Map mock → 카테고리 순서·10개 컷·빈 카테고리 제외·점수 반올림 검증.
- **Repository**: `store_category_score`/`blog_crawl_run`은 PostGIS 의존이 없어 H2 테스트 가능 (H2 test 프로파일에서 `bbang_score` 스키마 생성 또는 엔티티 스키마 미지정 오버라이드). 운영 썸네일 조인 쿼리는 Service 단위에서 mock.
- **Controller(MockMvc)**: 공개 접근(인증 없이 200), region 누락 400, 빈 지역 `categories:[]`.

---

## 7. 범위 밖 / 후속

- 거리 블렌딩·개인화(`blog-score-feed-architecture.md` §3.4) — 지금은 순수 점수 랭킹.
- 동명 충돌 처리(여러 시·도의 동일 동명) — MVP는 망원/공덕뿐이라 region_3rd 단일 키. 후속에 region_2nd 보조 파라미터.
- 배치 자동화(점수 갱신 주기).
- "이 동네 유명 카테고리 순" 정렬(JSON `category_ranking` 활용) — 옵션.
- FE 홈 피드 UI / 관심지역 모달 — 별도 sub-project.

---

## 8. 구현 로드맵

| 단계 | 산출물 |
|------|--------|
| 1 | `bbang_score` 스키마 DDL (마이그레이션 또는 로더 부트스트랩) |
| 2 | `load_to_db.py` 로더 + 망원동·공덕동 적재·검증 |
| 3 | BE `feed` 도메인 — entity/repository/service/controller |
| 4 | 썸네일 배치 조인 + 응답 조립 |
| 5 | 단위/통합 테스트, SecurityConfig permitAll |
| 6 | 실데이터로 `GET /feed/categories?region=공덕동` 응답 확인 (참조 목록과 카테고리·개수 일치 검증) |
