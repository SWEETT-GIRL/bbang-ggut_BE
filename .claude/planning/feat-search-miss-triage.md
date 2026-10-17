# feat/search-miss-triage — 실패 검색어 자동 판정

노션 정리: https://app.notion.com/p/3fbd867c7c84815296eec9fc6d95b7ca (개인 초안)

## Spec

### 문제
`search_misses`에 실패 검색어가 쌓이지만, 사람이 하나씩 보기엔 양이 많아 결국 처리되지 않는다.

### 목표
- 대부분의 행을 사람 없이 닫는다: 쓰레기 입력은 IGNORED, 이미 있는 가게(줄임말·오타)는 alias 추가 후 ALREADY_EXISTS.
- 사람은 "신규 가게 후보"와 "애매한 것 중 발생 횟수 상위"만 본다.

### 원칙
- 있다/없다 판정은 **코드가** 한다. place ID 일치 → 좌표+이름(ST_DWithin) → 우리 검색엔진 재실행 순서.
- **AI는 한 단계에만** 쓴다: 네이버 블로그 글을 읽고 "이 검색어가 무엇을 가리키는지" 해석. 그 해석은 근거 글에 글자 그대로 있어야 통과(환각 방지).
- 가게 등록은 자동으로 하지 않는다. alias는 결과를 *추가*만 하므로 자동 + 되돌리기(`source=SEARCH_MISS_AUTO`).
- **shadow mode 기본** — `auto-apply=false`면 판정·근거만 기록하고 alias·status는 건드리지 않는다.

### 파이프라인 (행 하나)
0. 꺼내기: **매일 06:00 KST 한 번**(`search-miss.triage.cron`), `status=PENDING AND (ai_triaged_at IS NULL OR occurrence_count >= count_at_triage*2)` 발생 횟수 순 2,000건(10/18 상향 — 200건이 2분 51초라 2,000건은 30분 안팎, 미판정 약 2,900건을 이틀에 소진)
   - 실시간일 필요가 없고, 새벽에 돌리면 probe 부하가 한산한 시간에 가며 아침에 결과를 한 번에 본다. 04:00 은 interaction 적재 배치
   - 한도 초과 시 그 회차만 멈추고 남은 행은 다음 날
1. 쓰레기 필터 → JUNK (IGNORED)
2. probe(검색어) — 지표·SearchMissEvent 없는 재검색. HIT → NOW_HIT (ALREADY_EXISTS)
   - 파서가 지역·빵 사전 단어로만 이뤄졌다고 보면 → NOT_A_STORE (외부 호출 없음)
3. 네이버 지역검색 → 검색어와 관련 있는 장소만 (줄임말/포함/오타)
   - 3-a `naver_place_id` 일치
   - 3-b 반경 50m(status 무관) + 지점명 뗀 이름 유사
   - ACTIVE 매칭 → 5 / DELETED → EXISTS_BUT_DELETED / **10m** 안에 이름 다른 가게 → UNRESOLVED / 없음+빵집·카페 → NEW_STORE_CANDIDATE
   - "이름 다른 근처 가게" 반경을 50m 로 잡으면 성수처럼 가게가 붙은 곳의 신규 가게가 전부 검수로 빠져서, 같은 자리(10m, `conflict-radius-meters`)만 본다
   - 관련 장소 0건 → 4
4. 블로그 검색 + LLM 해석 → {refersTo, officialName, evidence}
   - MENU/REGION → NOT_A_STORE
   - 검증: 근거 글 ≥2개에 officialName 그대로 + 검색어↔이름 관계(줄임말/포함/오타)
   - 통과 → probe(officialName) HIT면 그 가게들로 5, 아니면 officialName으로 3 재진입
   - 실패 → UNRESOLVED
5. alias 반영: 지역·빵 단어가 섞인 검색어는 alias 금지. 대상 가게(체인 지점 포함, 상한 30) → INSERT → 캐시 무효화 → probe(검색어) HIT면 ALREADY_EXISTS, 아니면 alias 삭제 + UNRESOLVED
6. 기록: `ai_verdict`, `matched_store_id`, `ai_evidence`(jsonb), `ai_triaged_at`, `count_at_triage`

7. 리포트: 매 회차 끝나면 Slack(`SLACK_SEARCH_MISS_WEBHOOK_URL`, `OperationalNotificationEvent.Channel.SEARCH_MISS`)으로 보고
   - 0건이어도·도중에 멈춰도·실패해도 보낸다 → 06시 리포트가 없으면 안 돈 것. 꺼져 있으면 안 보낸다
   - 모드(shadow/auto-apply), 꺼낸·판정·실패·못 본 건수, 소요 시간
   - 판정별 건수 + 자동 처리된 상태(shadow 면 "예정, 미적용")
   - 별칭 추가(검색어 → 가게, 지점 수), 신규 가게 후보(네이버 이름·카테고리·주소), 삭제된 가게 일치, 판단 보류 상위(사유), 가게 아님, 실패(사유)
   - 목록은 발생 횟수 순 앞쪽만, 나머지는 "… 외 N건"

### 범위
- 1차(이번 브랜치): 위 0~7, 스케줄러, 설정, 단위 테스트
- 2차: 관리자 화면(AI 판정 필터·NEW_STORE 원클릭·alias 되돌리기), 1회성 꼬리 자동 IGNORED, 카카오 교차 확인, 영문명, 정답셋 임계값 튜닝

## Plan

### 파일
| 구분 | 파일 | 내용 |
|---|---|---|
| DB | `V20261016120000__add_search_miss_triage.sql` | search_misses AI 컬럼 5개 + CHECK, store_name_alias.source |
| enum | `searchmiss/enums/SearchMissVerdict` | JUNK, NOW_HIT, ALIAS_RESOLVED, NEW_STORE_CANDIDATE, NOT_A_STORE, EXISTS_BUT_DELETED, UNRESOLVED |
| enum | `store/enums/StoreNameAliasSource` | DATAOPS, SEARCH_MISS_AUTO |
| entity | `SearchMiss` | AI 컬럼 + `recordTriage(...)` |
| entity | `StoreNameAlias` | source + `fromSearchMiss(store, alias)` |
| repo | `SearchMissRepository.findTriageCandidates` | 0단계 |
| repo | `StoreRepository.findAllWithinRadius` | 3-b, status 무관 |
| external | `NaverBlogSearchClient` + DTO + `NaverSearchException` | 블로그 검색, 429 → RATE_LIMITED |
| search | `StoreSearchService.probe` | 부작용 없는 재검색 (searchStores와 실행부 공유) |
| service | `SearchMissTextMatcher` | 정규화·지점명 제거·줄임말·편집거리·이름 일치·쓰레기 판정 |
| service | `SearchMissKeywordInterpreter` | 블로그 → LLM → 근거 검증 |
| service | `SearchMissPlaceResolver` | 3-a / 3-b |
| service | `SearchMissTriageService` | 1~5 오케스트레이션 + 배치 |
| service | `SearchMissTriageCommandService` | alias INSERT/DELETE, 판정 기록 (트랜잭션 경계) |
| scheduler | `SearchMissTriageScheduler` | 매일 06:00 KST, 한도 초과 시 그 회차만 멈춤, 끝나면 리포트 발행 |
| report | `SearchMissTriageReport` | 배치 결과 → Slack 운영 알림(`SEARCH_MISS` 채널) |
| config | `SearchMissTriageProperties` | enabled=false, auto-apply=false, batch-size=200, 반경, 근거 최소 수, alias 상한, model(ANTHROPIC) |
| resource | `search-miss-interpret-prompt.txt` | LLM 프롬프트 |

### 위험 요소
- probe가 `searchStores`를 그대로 부르면 SearchMissEvent가 또 발행돼 occurrence_count가 오른다 → 실행부만 공유, 기록 없음.
- alias 오염: 사전 단어 차단 + 관계 검증 + 재검색 검증 + shadow mode.
- `store_name_alias`는 dataops 소유(구명 용도) → `source` 컬럼으로 구분, dataops 공유 필요.
- 외부 호출 실패한 행은 `ai_triaged_at`이 안 찍혀 다음 날 배치에서 다시 집힌다(의도).
- `@Scheduled`라 서버가 여러 대면 인스턴스마다 돈다(분산 락 없음). 운영이 한 대인 동안은 문제없고, 늘리면 락 추가 또는 한 대에서만 켠다.
- 임계값(50m, 유사도 0.7, 근거 2개)은 초기값.

### 테스트
- `SearchMissTextMatcherTest`, `SearchMissKeywordInterpreterTest`, `SearchMissPlaceResolverTest`,
  `SearchMissTriageServiceTest`, `SearchMissTriageCommandServiceTest`, `SearchMissTriageSchedulerTest`
- `StoreSearchServiceTest`: probe가 이벤트·지표를 남기지 않음
- `SearchMissRepositoryTest`: `findTriageCandidates`
