# feat/search-keyword-correction — 검색어 교정과 실패 검색어 판정 개선

선행: `feat-search-miss-triage.md` (#948), 하루 2,000건 상향(#950, 이 브랜치의 부모)

## 배경 — 운영 1회차(10/17, 200건 shadow) 검토 결과

| 판정 | 건수 | 검토 |
|---|---|---|
| ALIAS_RESOLVED | 25 | 24 정답. `케익부티크 → 잍아부티크` 오매칭(글자 2개 차이를 오타로 봄) |
| NOW_HIT / JUNK | 23 / 11 | 전부 정답 |
| NEW_STORE_CANDIDATE | 20 | `떰즈비 → 떰즈업(청주)` 오매칭(오타 규칙), 실제는 블로그상 "카페떰비" |
| EXISTS_BUT_DELETED | 3 | 성북구 3곳, 9/8 등록 53분 뒤 1초 안에 일괄 삭제 — 별도 확인 |
| UNRESOLVED | 116 | 아래 |

보류에서 찾은 문제
- **뚜쥬르(8회)**: DB 엔 "뚜쥬루" 6곳. 네이버 지역검색 관련 장소가 "뚜쥬르 빵돌가마마을 전기차충전소"뿐 → "빵집 아님"으로 블로그도 안 보고 끝남. 이름 전체("뚜쥬루빵돌가마마을")와 비교해 오타로도 못 잡음
- **관계 검증이 엄격(22건)**: LLM 은 맞혔는데 줄임말·포함·오타가 아니라 거부 — brick bagel→브릭베이글, milomil→밀로밀, 베콥→베이크오프, 떰즈비→카페떰비
- **여러 가게와 일치(6건)**: `서심당 → [성심당, 성심당샌드위치정거장, …]` 처럼 브랜드 오타도 막힘
- **랜덤 문자열·개인정보(12건)**: hhkthk 등이 보류, 검색창에 입력된 이메일 1건
- 리포트 "… 외 N건"으로 검토 불가, shadow 인데 "별칭 추가"로 보임, 대표 지점명 표시
- 처리된 행 90일 삭제 쿼리는 있으나 호출하는 스케줄러가 없음
- auto-apply 를 켜도 shadow 로 판정된 행은 다시 안 집혀 적용 안 됨

## Spec

### 핵심 결정: alias 대신 검색어 교정
실패 검색어 자동 처리는 가게별 별칭이 아니라 **검색어 → 검색어 교정 한 줄**로 남긴다.
- 체인(파리바게트, 뚜쥬르)도 한 줄. 새 지점 자동 포함. 지점 상한 불필요
- 검색·자동완성 앞단에서 교정: 검색어 전체 일치 → 교체, 아니면 단어 단위 교체(`콜버 탄방` → `콜드버터베이크샵 탄방`). 한 번만 적용(연쇄 없음)
- 교정 목록이 비면 지금과 같은 동작. API 응답 변화 없음
- dataops 구명 alias(`store_name_alias`)는 그대로 둔다. 앱의 자동 alias 쓰기 경로는 제거

### ERD (신규 테이블, 승인 10/18)
```
search_keyword_correction
  id                  BIGSERIAL PK
  normalized_keyword  VARCHAR(100) NOT NULL UNIQUE  -- 검색과 같은 정규화
  keyword             VARCHAR(100) NOT NULL         -- 원문(표시용)
  corrected_query     VARCHAR(255) NOT NULL         -- 바꿔 돌릴 검색어
  source              VARCHAR(30)  NOT NULL         -- SEARCH_MISS_AUTO | ADMIN
  search_miss_id      BIGINT                        -- 근거 추적용, FK 없음(search_misses 90일 삭제와 무관하게 남는다)
  created_at          TIMESTAMP NOT NULL
```
판정명 `ALIAS_RESOLVED` → `CORRECTED` (기존 shadow 25행 UPDATE).

### 판정 변경
1. 오타를 **자모 단위**로 판정 — 자모 6개 이하 1개, 그 이상 2개까지. 퉁퉁이↔통통이 통과, 케익↔잍아·떰즈비↔떰즈업 거부
2. auto-apply 를 **판정별로**: `auto-apply-verdicts`(기본 비움 = shadow). 운영은 `CORRECTED` 만
3. auto-apply 대상 판정인데 PENDING 인 행(= shadow 로 판정만 된 행)도 다시 집어 정상 경로로 적용
4. Slack 리포트: 모든 섹션 전부 표시(생략 없음), 넘치면 여러 메시지(n/N)로 순서대로 발송. shadow 면 "예정(미적용)", 교정 대상은 지점명 뗀 이름
5. 이메일·전화번호는 1단계 JUNK + 리포트 마스킹. 네이버 장소·블로그 모두 0건인 한글 없는 문자열(hhkthk)은 JUNK
6. 처리 완료 후 90일 지난 `search_misses` 행을 매일 삭제(ShedLock)
7. 검색어를 **이름 앞부분**(같은 글자 수)과도 비교(뚜쥬르↔뚜쥬루). 관련 장소가 빵집이 아닌 것뿐이면 결론 내지 않고 블로그 단계로
8. **별명 인정**: LLM 이 준 이름이 검색어와 같은 블로그 글에 함께 나오고 그런 글이 2개 이상이면 관계 검증 통과
9. 여러 가게(이름이 여럿)와 일치해도 **검색어가 대상 이름(LLM 이름 또는 가게들의 공통 앞부분)의 오타·줄임말·포함**이면 그 이름으로 교정(서심당→성심당, 뚜쥬르→뚜쥬루). 아니면 보류(샌드위치 정류장→성심당)

### 범위 밖(다음)
- 가게 이름을 정확히 입력해도 실패하는 검색(어니언 성수, 성심당 DCC점)
- 관리자 화면(교정 목록·되돌리기, NEW_STORE 원클릭)
- 신규 후보 블로그 교차 확인

## Plan

| 구분 | 파일 | 내용 |
|---|---|---|
| DB | `V20261018100000__create_search_keyword_correction.sql` | 테이블 + 판정명 변경 |
| store | `entity/SearchKeywordCorrection`, `enums/SearchKeywordCorrectionSource`, `repository/SearchKeywordCorrectionRepository` | |
| search | `service/search/SearchKeywordCorrector` | 메모리 캐시(5분 지나면 접근 시 재적재, `invalidate()`), 전체·단어 교정 |
| search | `StoreSearchService` | `execute`(검색·probe)와 자동완성 앞단에 교정. 실패 적재는 원문으로 |
| searchmiss | `SearchMissTextMatcher` | 자모 분해 편집거리, 이름 앞부분 비교, 이메일·전화번호, 공통 앞부분 |
| searchmiss | `SearchMissKeywordInterpreter` | 별명 인정(동시 등장) |
| searchmiss | `SearchMissPlaceResolver` / `SearchMissTriageService` | 빵집 아님 → 블로그, 여러 가게 규칙, 교정 쓰기·검증·되돌리기, 판정별 적용 |
| searchmiss | `SearchMissTriageCommandService` | alias 쓰기 제거 → 교정 추가·삭제, 자동완성 캐시 무효화 |
| searchmiss | `SearchMissRepository` | 재적용 대상 포함 조회 |
| searchmiss | `SearchMissTriageReport` + `SearchMissTriageReportSender` | 전체 표시·분할, Slack 순서 발송(웹훅 직접, 메시지 간 1초) |
| searchmiss | `SearchMissRetentionScheduler` | 90일 삭제, ShedLock |
| config | `SearchMissTriageProperties` | `autoApplyVerdicts`, 보관 기간 |
| deploy | `cd-prod.yml` / `cd-staging.yml` | `PROD_/STAGING_SEARCH_MISS_TRIAGE_AUTO_APPLY_VERDICTS`(기본 `CORRECTED` / 비움) |

### 위험
- 검색 경로에 교정 조회가 붙음 → 메모리 캐시, 비면 그대로
- 교정이 틀리면 그 검색어 결과가 바뀜(검색어 하나 범위). `source=SEARCH_MISS_AUTO` 일괄 삭제로 되돌림
- 다른 컨테이너(blue-green)는 캐시가 최대 5분 늦게 반영
- 자동완성 Redis 캐시는 교정된 검색어를 키로 조회하므로 원문 키의 옛 결과에 막히지 않음

### 테스트
- `SearchKeywordCorrectorTest`, `StoreSearchServiceTest`(교정 적용·원문 적재)
- `SearchMissTextMatcherTest`(자모·앞부분·PII·공통 앞부분), `SearchMissKeywordInterpreterTest`(별명)
- `SearchMissTriageServiceTest`(교정·되돌리기·여러 가게 규칙·빵집 아님 폴백·판정별 적용)
- `SearchMissTriageReportTest`(전체 표시·분할), `SearchMissTriageReportSenderTest`, `SearchMissRetentionSchedulerTest`
- `SearchMissRepositoryTest`(재적용 대상), `SearchKeywordCorrectionRepositoryTest`
