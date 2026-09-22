# feat/dessert-chain-store — 체인 지점 표를 따로 둔다

## 왜

초코츄러스(파리바게뜨)를 지도에 올리려다 막혔다.

빵긋 `store` 는 동네 빵집을 모은 표라 프랜차이즈가 거의 없다. **파리바게뜨는 전국 3천 곳이 넘는데
`store` 에는 여덟 곳뿐이다**(2026-09-22 `/stores/search` 로 확인). 이름으로 찾는 길이 성립하지 않는다.

편의점 때 이미 같은 문제를 풀어 뒀다 — `convenience_store` 를 따로 두고 브랜드 이름으로 지점을 찾는다.
체인도 같은 모양으로 간다.

## 그래서 표가 셋이 된다

| item_type | 어디서 읽나 | 예 |
|---|---|---|
| `GENERIC` | `dessert_item_store` ⨝ `store` | 피자설기 — 가게마다 파는지가 다르다 |
| `CHAIN` | **`chain_store`** (신규) | 초코츄러스 · 요거트 찹쌀떡 · 호박인절미 |
| `CONVENIENCE` | `convenience_store` | GS25 한정선 |

**덤:** CHAIN 이 빠지면서 `dessert_item_store` 가 `V20260918200000` 주석이 적은 대로 **GENERIC 전용**이 된다.
"주석과 적재분이 어긋난다"고 #788 에 적었던 것이 여기서 정리된다. 운영 `dessert_item_store` 는 아직
비어 있어 옮길 행이 없다.

## 갈림길은 "편의점이냐"가 아니다

CONVENIENCE 와 CHAIN 은 표만 다를 뿐 하는 일이 같다 — 브랜드 이름으로 가까운 지점을 찾는다.
진짜 갈림길은 **브랜드 지점을 보느냐, 가게마다 근거를 보느냐**다. 그래서 `isConvenience()` 를 지우고
enum 을 다 훑는 switch 로 갈랐다. 네 번째 성격이 생기면 컴파일이 깨져 조용히 빠지지 않는다.

응답 모양은 셋 다 같다(`DessertStoreResponse`). 앱은 타입을 모르고, 브랜드 지점은 `storeId`·`grade`·
`evidenceCount` 가 비어 오는 것으로만 구분된다 — 그 빈 자리를 보고 "파리바게뜨 매장"이라고 적는다.
**"여기서 판다"고 단정하지 않는다.** 초코츄러스는 품절 대란이라 이 구분이 특히 중요하다.

## 파일

### 새로 만든 것
- `V20260922140000__create_chain_store.sql` — GIST(location) + brand 인덱스. brand 에 CHECK 는 안 건다(체인은 계속 늘어난다)
- `dessert/entity/ChainStore.java`
- `dessert/repository/ChainStoreRepository.java`

### 고친 것
- `dessert/enums/DessertItemType.java` — `isConvenience()` 제거
- `dessert/service/DessertQueryService.java` — 3갈래 switch + 브랜드 지점 조회를 한 메서드로(`BrandBranchQuery`)
- `DessertQueryServiceTest.java` — CHAIN 케이스 추가 (7건)

## 남은 일 (이 브랜치 밖)

1. `dessert-pipeline` 에 파리바게뜨 지점 수집기 + `load_chain_stores.py`
2. 한정선·창억떡 지점도 `chain_store` 로
3. `dessert_item` 에 초코츄러스(CHAIN·파리바게뜨) 추가 — **적재 다음이다.** 순서를 거꾸로 하면 칩만 뜨고 빈 화면이 된다
4. `dessert_item_store` 로더(GENERIC·피자설기 204곳) — 아직 스크립트가 없다
