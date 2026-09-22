# feat/dessert-map — 지도에서 요즘 뜨는 디저트 파는 곳 보기

## Spec

앱 지도 탭 검색바 아래에 '요즘 뜨는 디저트' 칩 줄이 선다. 하나 고르면 그걸 파는 곳이
가까운 순으로 핀과 목록에 뜬다. FE 는 이미 올라갔고(daedongbbangmap#101) 목으로 돌고 있다.
이 브랜치는 그 목을 걷어낼 읽기 API 두 개를 만든다.

표는 이미 있다 — `V20260918200000__create_dessert_item_tables.sql`, `V20260921014450__add_instagram_to_dessert_evidence.sql`.
디저트 파이프라인(블로그·인스타 → LLM 가게명 추출 → 카카오·네이버 매칭)이 쓰고 **BE 는 읽기만 한다.**
`store_profile` 과 같은 구조다.

### 엔드포인트

```
GET /desserts
  → 칩 줄 재료. is_active = true 만, 뜨는 순서대로
  → [{ id, name, itemType, brand, trendStatus }]

GET /desserts/{dessertItemId}/stores?lat&lng&size&radiusMeters
  → 가까운 순 N 곳
  → [{ storeId, name, address, latitude, longitude, distanceMeters,
       grade, evidenceCount, lastEvidenceAt, brand }]
```

둘 다 비로그인 허용이다. 지도는 로그인 전에도 보이고, /trend/** · /stores/nearby 와 같은 자리다.

### item_type 이 조회를 가른다

| item_type | 어디서 읽나 | 왜 |
|---|---|---|
| `GENERIC` | `dessert_item_store` ⨝ `store` | 피자설기처럼 동네 가게가 각자 판다 |
| `CHAIN` | `dessert_item_store` ⨝ `store` | 파이프라인이 브랜드 지점을 store 로 매칭해 같은 표에 넣는다 |
| `CONVENIENCE` | `convenience_store` (브랜드 일치) | 빵긋 가게가 아니라 따로 둔 표다 |

> 마이그레이션 주석은 `dessert_item_store` 를 "GENERIC 전용"이라 적었지만, 실제 적재분에는
> CHAIN(요거트 찹쌀떡 8곳 · 호박인절미 5곳) 행이 들어 있다. 데이터를 따른다.

**응답 모양은 셋 다 같다.** 화면이 타입으로 갈라지지 않게 하려는 것이다.
편의점은 빵긋 가게가 아니라 `storeId` 가 null 이고, FE 는 그걸로 상세 이동·담기를 막는다.

### 등급을 그대로 내려보내는 이유

`grade`(STRONG·LIKELY·WEAK·STALE)를 서버가 거르지 않고 그대로 준다. 화면은 이 말을
"확실해요/아마도"로 옮기지 않고 사실(`lastEvidenceAt`·`evidenceCount`)만 적기로 했다.
서버가 미리 걸러 버리면 그 판단을 화면이 되돌릴 수 없다.

## Plan

### 새 파일 (dessert 패키지)

| 파일 | 하는 일 |
|---|---|
| `enums/DessertItemType.java` | CONVENIENCE · CHAIN · GENERIC |
| `enums/DessertTrendStatus.java` | HOT · RISING · COOLING · UNCERTAIN + 칩 줄 정렬 순서 |
| `enums/DessertGrade.java` | STRONG · LIKELY · WEAK · STALE |
| `enums/ConvenienceBrand.java` | CU · GS25 · SEVEN_ELEVEN · EMART24 |
| `entity/DessertItem.java` | `dessert_item`. `aliases` 는 수집 스크립트용이라 매핑하지 않는다 |
| `entity/ConvenienceStore.java` | `convenience_store`. geography(Point, 4326) |
| `repository/DessertItemRepository.java` | 활성 목록 + 판매 가게 반경 조회(네이티브) |
| `repository/ConvenienceStoreRepository.java` | 브랜드 지점 반경 조회(네이티브) |
| `dto/projection/DessertStoreProjection.java` | 두 쿼리가 같은 모양으로 답한다 |
| `dto/response/HotDessertResponse.java` | 칩 줄 |
| `dto/response/DessertStoreResponse.java` | 판매 가게 |
| `code/DessertErrorCode.java` · `exception/DessertException.java` | 없는 디저트 404 |
| `service/DessertQueryService.java` | `@Transactional(readOnly = true)` |
| `controller/DessertController.java` | 위 두 엔드포인트 |

### 고치는 파일

- `config/security/SecurityConfig.java` — `GET /desserts/**` permitAll 한 줄

### 테스트

- `DessertQueryServiceTest` (Mockito) — 타입별 분기 · 썸네일 채움 · 없는 디저트 예외
- `DessertControllerTest` (`@WebMvcTest`)
- 반경 쿼리는 PostGIS 라 H2 에서 못 돈다. 레포지토리 테스트는 이 브랜치에서 만들지 않고
  `RecommendationCandidateJdbcRepository` 와 같은 자리(통합 테스트)에 맡긴다.

### 위험 요소

- **편의점 5만 곳.** 반경 상한(`radiusMeters`)과 개수 상한(`size`)이 없으면 지도가 핀으로 덮인다.
  둘 다 기본값을 두고 `size` 는 서버가 최대치로 자른다.
- `convenience_store` 는 파이프라인이 "지도를 연 지역만 쌓는 캐시"다. 비어 있는 지역이 정상이라
  빈 목록이 오류가 아니다.
