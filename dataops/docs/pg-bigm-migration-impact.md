# OpenSearch → pg_bigm 전환 — dataops 관점 영향 정리

작성: 2026-08-20 · 배경: 팀 결정으로 검색을 OpenSearch 에서 PostgreSQL pg_bigm(2-gram GIN)으로 이전

## 1. 소멸하는 문제들 (dataops 부담 대폭 감소)

| 사라지는 것 | 왜 |
|---|---|
| **색인 동기화 문제 전체** ([결정 문서](./search-sync-architecture-decision.md) 종결) | 검색이 운영 DB 직접 조회 — **dataops DML = 즉시 검색 반영**. push/워터마크/증분 잡 전부 불요 |
| 전체 재색인 운영 (admin API, ~10분) | 개념 소멸 — DDL 마이그레이션이 대체 |
| 폐업 가게 유령 문서 잔류 | 쿼리가 `status='ACTIVE'` 필터 — 문서 수명주기 자체가 없음 |
| 매핑 변경 시 인덱스 재생성 | 인덱스 변경 = Flyway 마이그레이션 |
| P-4 게이트 (색인 경로 확인) | 근본 소멸 — 3단계 자동 반영의 마지막 외부 부수효과 우려가 사라짐 |

**특히 3단계(자동 반영)의 안전성이 크게 올라간다** — 직접 DML 의 최대 리스크로 꼽았던
"검색 인덱스 부수효과 우회"가 구조적으로 불가능해진다.

## 2. 살아남는 자산 (전환 시 반드시 승계)

| 자산 | 승계 방법 |
|---|---|
| **`store_name_alias` 1,661행 + 지속 갱신 규율** | bigm 검색의 매치 대상에 alias 포함 필수 — 예: `store.name` 매치 UNION `store_name_alias.alias` 매치. 빠뜨리면 개명 가게 구명 검색(헤이도우→HEY DOUGH)이 다시 깨진다. **검증 시나리오 4종을 전환 후 재실행할 것** (맘모스베이커리/맘모스/헤이도우/자동완성 구명) |
| menu_category 정비 (67종·정밀 분류) | 무관하게 생존 — 카테고리 검색·빵 키워드 매치의 입력 |
| 이미지 파이프라인 (S3·람다) | 검색과 무관 — 그대로 |
| 검색 품질 지표(SearchQueryCoverage) 개념 | 재구현 대상이지만 판정 로직(조합 검색 vs 통째 대조)은 이식 가치 있음 |
| OpenSearch alias 구현 (PR #596 코드) | 폐기 예정 — 단 **어떤 필드를 어떤 가중치로 매치했는지**(SearchMatchProfile)가 bigm 쿼리 설계의 명세서 역할 |

## 3. 본 앱 설계 참고 (dataops 관찰 기반 조언)

1. **pg_bigm 은 한국어에 적합한 선택** — 2-gram 이라 2글자 한국어 단어("소금", "빵집")부터 인덱스가
   듣는다 (pg_trgm 3-gram 은 한국어 단문에 불리). `LIKE '%검색어%'` 를 GIN 이 가속.
2. **RDS 지원 확인 필요**: `SELECT * FROM pg_available_extensions WHERE name='pg_bigm'` —
   RDS PostgreSQL 은 13.4+ 승인 확장이라 PG16 이면 될 것. `CREATE EXTENSION` 만으로 충분
   (shared_preload 불요). ⚠ 2026-08-20 현재 dataops 터널 자격 만료로 실측 미완 — 재개 시 1순위 확인.
3. **인덱스 대상 후보**: `store.name` · `store_name_alias.alias` · `menu.name`(활성 ~55만 행 —
   GIN 쓰기 비용은 정상 유입량에선 무해, 대량 백필 시엔 인덱스 후생성 고려) · `store.address` ·
   지역 alias 테이블. 통합 검색이면 가게 단위 "검색 문서" 컬럼(이름+alias+메뉴명 concat)에
   GIN 하나를 두는 설계도 검토 가치 — 단 그 컬럼의 갱신 트리거가 필요해져 동기화 문제가 미니로 부활하니,
   **테이블별 인덱스 + UNION 쿼리가 dataops 관점에선 안전**.
4. **랭킹 재설계**: OpenSearch function_score(텍스트 × (1+인기도+거리감쇠))의 대체 —
   `bigm_similarity()` + popularity 컬럼 정렬 + 거리는 PostGIS `ST_DWithin`/`<->` 재사용.
5. **부하 이동**: 검색 QPS 가 RDS 로 들어온다 — 커넥션 풀 여유 확인, 훗날 리드 레플리카 분리 옵션.
6. **dataops 쓰기 프로파일 공유**: 정기 반영 시 store/menu UPDATE·INSERT 수십~수백 건/회(주 단위),
   대량 백필(수만 건)은 예외적 — GIN 인덱스가 있어도 문제없는 수준. 단 93k 급 재백필 시엔
   사전 공유하겠다 (인덱스 유지 비용).

## 4. dataops 액션

- [x] 색인 동기화 결정 문서 종결 처리 (2026-08-20)
- [ ] 터널 자격 갱신 후: 운영 `pg_available_extensions` 에서 pg_bigm 확인 (§3-2)
- [ ] 전환 배포 후: **검증 4종 재실행** (§2 — alias 승계 확인이 핵심)
- [ ] opensearch-collab-request.md 의 재색인 관련 항목(①·재색인 후 검증) 폐기 표시 — alias 데이터
  규율(이름 변경 승인 → alias INSERT)은 유지됨을 명시
