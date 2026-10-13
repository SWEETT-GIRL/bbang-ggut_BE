# 자동완성 급부상·스테디 노출 (PR2)

## 목표
빵·디저트는 시즌과 SNS 흐름에 따라 유행하는 가게가 빠르게 바뀐다. '한'을 치면 지금 뜨는 가게(예: 한정선)가 먼저 보여야 한다.

지금은 타입 순서가 지역 → 가게로 고정이라 '한'을 치면 앞자리를 전부 지역(한양대역·한남역…)이 차지한다(staging 재현, 10-05).

## 규칙 (STORE 이면서 검색어로 이름이 시작하는 가게만 대상)
1. **급부상 가게가 있으면** 최대 2개를 지역보다 위에 둔다. 목록에 없던 가게도 끼워 넣는다.
2. **없으면** 평소 수준 1위(스테디) 1개를 지역보다 위에 둔다. 평소 수준이 0.2 미만이면 올리지 않는다.
3. 나머지는 기존 순서를 따른다. 가게끼리는 `접두 일치 → 평소 수준 → 누적 인기도` 순이다.

스위치 `store.trending.enabled`(`TRENDING_ENABLED`, 기본 false)가 꺼져 있으면 신호도 안 쌓고 순서도 그대로다.

## 급부상 판정
- **신호**: 로그인 회원의 조회 1 · 찜 2 · 가고싶은 가게 2 · 코스담기 2 · 빵로그 3
  - 같은 회원·가게·행동은 하루 한 번만 센다.
- **recent (Redis, 실시간)**: 최근 7일 시간 버킷을 반감기 36시간으로 감쇠해 합친다.
- **baseline (DB, 매일 05:00)**: `user_store_interaction`에서 최근 7일 바로 앞 28일 동안의 같은 신호를 일평균한다.
  - 저장 위치는 `store.trending_baseline`이다. 스테디 정렬에도 이 값을 쓴다.
- **자격**: `(recent + 5) / (baseline × W + 5) ≥ 1.5`이고, 7일 고유 회원이 3명 이상이어야 한다.
  - W는 7일 감쇠 가중치의 합 ÷ 24다. 평소 속도로 꾸준했을 때 recent 가 갖는 값이다.
- **순위**: 자격을 통과한 후보끼리 `ln(1 + recent) × 배율`로 세운다.
  - 하나의 점수로 섞으면 평소대로 유명한 가게(배율 1)가 조금 뜬 작은 가게를 이긴다. 그래서 자격과 순위를 나눴다.
- **갱신 주기**: 순위표는 5분마다 다시 만든다. 자동완성은 30초짜리 메모리 스냅샷으로 읽는다.

## 왜 7일 + 감쇠인가
- 운영 실측: 가게 하나의 하루 고유 방문자는 최대 1~4명이다. 24시간 창으로는 급부상 후보가 하루 0~1개라 판정할 신호가 없다.
- 실시간성(얼마나 빨리 반영하나)과 집계 기간(얼마나 과거까지 세나)은 별개다. 그래서 5분 갱신은 그대로 두고 기간만 넓혔다.

## Redis 키
| 키 | 타입 | TTL |
|---|---|---|
| `trend:h:{yyyyMMddHH}` | Sorted Set 가게 → 신호 가중합 | 8일 |
| `trend:uv:{가게}:{yyyyMMdd}` | HyperLogLog 회원 | 8일 |
| `seen:{신호}:{가게}:{회원}:{yyyyMMdd}` | String | 26시간 |
| `trend:top` / `trend:names` | Sorted Set / Hash — 임시 키에 쓴 뒤 RENAME | 5분마다 교체 |
| `lock:trend:refresh` | String — blue/green 중 한 인스턴스만 계산 | 4분 |

## 구현
| 단계 | 내용 |
|---|---|
| 신호 | `TrendingSignalRecorder`/`Listener`(전용 실행기 `trendingExecutor`, 넘치면 버림). 발행 지점: `StoreQueryService.getStoreDetail`(로그인), `StorePlanService.upsert`(새로 담을 때), `BreadRecordCommandService.create`(가게 있을 때), 기존 `StoreSavedEvent`·`CourseStoreAddedEvent` |
| 평소 수준 | `V20261005120000` 마이그레이션, `StoreTrendingRepository`, `StoreTrendingBaselineCommandService`(advisory lock, 끝나면 자동완성 캐시 비움) |
| 순위표 | `TrendingRankingCalculator`(순수 계산), `TrendingRankingService`(ZUNIONSTORE·PFCOUNT·RENAME), `TrendingRankingReader` |
| 노출 | `StoreSearchSqlBuilder`(steady 정렬), `StoreSearchService.promoteTrendingOrSteady` |
| 운영 | `StoreTrendingScheduler`(05:00 / 5분), 관리자 `POST /admin/search/trending/{baseline,ranking}/refresh` |

## 검증
- 단위 테스트를 추가했다(계산·읽기·기록·승격 규칙·배치·관리자 API). 전체 2,058개가 통과했다.
- lab 테스트
  - `StoreTrendingRepositoryLabTest`: 평소 수준 SQL 을 lab PG 에서 실행한 뒤 롤백한다.
  - `TrendingRankingRedisLabTest`: 기록 → 계산 → 읽기를 진짜 Redis 로 끝까지 돈다.

## 남은 것
- [ ] staging 에서 켜고(`STAGING_TRENDING_ENABLED=true`) 여러 계정으로 신호를 넣어 '한' 검색 결과를 확인한다
- [ ] 운영 데이터로 7일 기준 급부상 후보 수를 재고 기준값(배율 1.5·회원 3명)을 조정한다
- [ ] 운영 인프라(was-1 Redis, `docker-compose.prod.yml`·`cd-prod.yml`)
