# 데이터 최신화 시스템 — 스펙 (Specification)

> **후속 개선안 (2026-09-25)**: [안정화·운영화 v3 spec](./data-freshness-v3-spec.md) · [v3 plan](./data-freshness-v3-plan.md).
> 이 문서는 초기 도입 기준을 보존한다. v3가 변경하는 요구사항과 구현 여부는 후속 문서에서 구분한다.

> 작성 2026-08-01 · 규범 문서 (무엇을 만들어야 하는가)
> 상세 설계·근거는 [설계서 v2](./data-freshness-design.md), 이론·실측 근거는 [리서치 보고서](./data-freshness-research.md) 참조.
> 레포 전략 결정은 [repo-strategy-comparison.md](./repo-strategy-comparison.md) — **분리 레포 유지**.
> 구현 순서는 [구현 계획](./data-freshness-plan.md) 참조.

---

## 1. 목적

점주 입력 채널·유저 제보 없이, 운영 DB에 적재된 가게 45,444개(네이버 매칭 ~30,900개)의
**가게 정보·메뉴를 주체적으로 최신 상태로 유지**하는 파이프라인을 만든다.
첫 전체 스윕은 동시에 **최초 수집 누락 백필**이다(메뉴 0개 가게 ~3,170곳 / ~32,000메뉴 —
리서치 2.5 실측).

## 2. 범위

### 포함

- 네이버 플레이스 주기 재수집(스냅샷) → 변경 감지 → 필드 단위 diff → 병합 판정 → 운영 반영
- 자동 반영이 불가한 변경의 어드민 승인 워크플로(change_proposal)
- 운영 DB 직접 쓰기의 감사·롤백 체계(apply_log, provenance)
- 티어 기반 크롤 스케줄링(store_sync_state, due 폴링)
- 공공데이터(LOCALDATA) 기반 신규 가게 후보 적재 — *4단계 파일럿 이후 활성화*

### 제외 (범위 밖)

- 미매칭 14,500개(32%)의 place_id 매칭 트랙 — 별도 작업
- 인스타그램 게시물 수집(유료·법률 검토 선행 필요 — 핸들 저장까지만 범위)
- 폐업 자동 판정(신뢰 신호 미확보 — proposal 수납까지만)
- 기존 dataops 도메인 4개(issue·ingest·remedy·crawltask) — **전부 폐기, 참조 금지**
- 운영 DB 스키마 변경 일체

## 3. 시스템 구성

| 구성요소 | 실체 | 책임 |
|---|---|---|
| dataops 서버 | 이 레포, Spring Boot :8090 | 상태 관리·판정·운영 반영의 전부 |
| dataops 로컬 DB | 도커 postgres :15433 | 최신화 상태 전체(스냅샷·sync_state·proposal·apply_log·provenance) |
| 크롤 워커 | `scripts/sync_worker.py` (Python, requests) | due 폴링 → fetch → 원본 제출. **판정하지 않는다** |
| 운영 DB | RDS `soboro` | 스키마 불가침. 판정 통과분의 DML + 현재값 SELECT + μ 집계 조회만 |

운영 모델은 **세션형**(상시 호스트 없음): 노트북에서 서버+DB+워커를 켤 때 동작한다.
due 기반이라 cron이 없고, 중단 시 정리 작업이 없어야 한다.

## 4. 기능 요구사항

### FR-1 동기화 상태 관리 (sync)

1. 가게×소스 단위 동기화 상태를 `store_sync_state`로 관리한다. **이 테이블이 곧 워크리스트다**
   — 별도 작업 큐를 만들지 않는다.
2. `GET /api/sync-states/due`는 `next_due_at <= now`인 ACTIVE 상태를 배부하고
   `last_attempt_at`을 스탬프한다(소프트 리스 — 최근 배부분은 due에서 제외).
3. 성공 처리 시 `next_due_at = 완료 시각 + 티어 주기 + 지터(±10%)`로 재계산한다.
   무변경 방문도 `visit_count`(n)를, 변경 방문은 `change_count`(X)도 증가시킨다(Δ 추정 입력).
4. 실패 보고 시 지수 백오프(1일→2→4…), 10회 연속 실패 시 `UNLINKED` 전환.
5. 티어: T0(구독+정제 μ 상위 ~1,200곳, 주 2회) / T1(나머지 매칭 가게, 주 1회).
   μ 집계 시 어드민 경로·팀 계정 7개 제외 필터(설계서 4.2)를 반드시 적용한다.
6. 시딩은 멱등이어야 하며, 시딩 시 **사용자 기여 메뉴에 provenance `USER`를 소급 기록**한다
   (MENU_ADD 승인 이력 기반 — 이것 없이 재수집 금지).

### FR-2 스냅샷 수집 (snapshot)

1. 워커가 제출한 원본 페이로드를 `place_snapshot`에 append-only로 저장한다.
2. SHA-256 해시가 직전과 동일하면 insert를 생략하고 방문 카운터만 갱신한다.
3. 변경 감지 시에만 파싱·판정을 트리거한다.
4. 보존: 가게당 최신 1개 + apply_log·proposal·provenance가 참조하는 스냅샷만, 나머지 주기 프루닝.
5. `NaverPlaceParser`는 Apollo state의 **참조(reference)를 해소**해 파싱한다
   (has_hours 버그 재발 방지). 인스타 핸들 추출을 포함한다.
6. 배민 연동 가게의 배달 메뉴(`PlaceDetail_BaeminMenu`)도 **그룹명을 붙여** 추출한다 —
   신규 메뉴 추가의 보조 소스이며, 그룹명은 음료 제외 필터(FR-3)의 판별 입력이다.

### FR-3 판정·병합 (reconcile)

1. ParsedPlace와 운영 현재값(`ServingReader`)을 필드 단위로 diff한다.
2. `MergePolicy`가 필드별로 AUTO / PROPOSAL / IGNORE를 정적으로 결정한다(§6 매트릭스).
3. 출처 우선순위 **ADMIN > USER > NAVER > LOCALDATA > 미기록** — 상위 출처가 기록된 필드에
   하위 출처 diff가 오면 AUTO 대상이라도 proposal로 강등한다.
4. 외부 변경 감지(우리가 기록한 마지막 값 ≠ 운영 현재값) 시 해당 필드는 proposal로 강등한다.
5. 자동 반영 실패 시 proposal로 강등한다(유실 금지).

### FR-4 승인 워크플로 (proposal)

1. 자동 반영 불가 diff는 `change_proposal(PENDING)`으로 수납한다.
2. 같은 store×target의 PENDING은 1건만 — 새 diff 도착 시 기존 건 SUPERSEDED 후 insert.
3. approve 시 운영 반영(apply_log 선기록 → ServingWriter → provenance), reject 시 사유 기록.
4. 승인된 값의 provenance source는 **데이터 원천(NAVER 등)** 으로 기록한다 — ADMIN이 아니다.

### FR-5 운영 DB 게이트웨이 (serving)

1. 운영 DML은 `ServingWriter` **단 한 곳**에만 존재한다. 물리 DELETE 메서드를 만들지 않는다.
2. 모든 운영 쓰기는 예외 없이 `apply_log`(변경 전/후 값 포함) **선기록 후** 실행한다.
3. 운영 스키마의 JPA 엔티티를 만들지 않는다 — JdbcTemplate 기반 SELECT/DML.
4. 기동 시 참조 테이블·컬럼을 `information_schema`와 대조하고 불일치 시 fail-fast한다
   (스키마 드리프트 가드 — 분리 레포 보완책).

### FR-6 신규 가게 후보 (candidate) — *4단계*

1. LOCALDATA 주기 pull → 업종·지역 필터 → `new_store_candidate(WAITING_NAVER)` upsert(mgmt_no 키).
2. `candidate.status` 자체가 워크리스트다 — 워커 매칭 모드가 폴링한다.
3. 매칭+크롤 성공 시에만 운영 store INSERT. 폐업 레코드는 `change_proposal(target=store.status)`로.

### FR-7 크롤 워커 (sync_worker.py)

1. due 폴링 → `m.place.naver.com/restaurant/{id}/home` fetch(requests, 브라우저 없음)
   → `__APOLLO_STATE__` 원본을 `POST /api/snapshots` 제출.
2. 요청 간격 1.5초/req 고정(실측 하한 0.67 req/s 준수).
3. fetch 실패는 `POST /api/sync-states/{id}/failure` 보고. 차단 감지 시 exit 3로 즉시 종료 —
   **복구 로직을 만들지 않는다**(안 긁힌 가게는 due로 남는다).
4. 판정·파싱을 하지 않는다.

## 5. API 표면

| 메서드/경로 | 도메인 | 용도 |
|---|---|---|
| `GET /api/sync-states/due?limit=` | sync | 워커 워크리스트 export (소프트 리스 스탬프) |
| `POST /api/sync-states/{id}/failure` | sync | 워커 fetch 실패 보고 → 백오프 |
| `GET /api/sync-states?tier=&state=` | sync | 상태·카운터 조회 |
| `POST /api/sync-states/seed` | sync | 최초 시딩 (멱등) |
| `PATCH /api/sync-states/{id}/park` / `unpark` / `tier` | sync | 수동 조정 (즉시 재크롤 = next_due_at을 now로) |
| `POST /api/snapshots` | snapshot | 워커 원본 제출 (storeId, source, payload) |
| `GET /api/snapshots?storeId=` | snapshot | 스냅샷 이력·페이로드 조회 |
| `GET /api/proposals?status=` | proposal | 승인 대기 목록 (근거 스냅샷 링크 포함) |
| `PATCH /api/proposals/{id}/approve` / `reject` | proposal | 승인(→운영 반영) / 거절 |
| `GET /api/apply-logs?storeId=` | serving | 운영 쓰기 감사 조회 |
| `POST /api/candidates/sync-run` | candidate | 공공데이터 pull *(4단계)* |
| `GET /api/candidates?status=` | candidate | 워커 매칭 모드 워크리스트 *(4단계)* |

reconcile 도메인은 컨트롤러가 없다(내부 도메인).

## 6. 병합 정책 매트릭스 (요약)

| 필드(target) | 판정 |
|---|---|
| `store.business_hours` · `store.phone` · `menu.{id}.price` · 신규 menu 추가(백필·배민 단독 메뉴 포함) | **AUTO** — 신규 메뉴는 **음료 제외 필터** 통과분만 (서비스는 베이커리·디저트만 등록) |
| `store.name` · menu 소멸 · `store.status`(폐업·휴업) · `store.address`·좌표 | **PROPOSAL** |
| 기존 메뉴 정책 재판정 — 제외 필터에 걸림(`menu.{id}.removed` + `old_value.reason`) · 종류 '빵'·'기타'·NULL 재분류(`menu.{id}.category`) | **PROPOSAL** — 사용자 등록 메뉴 제외, 분류기가 믿기 어려운 판정(doubt → null)은 제안 안 함 |
| 인스타 핸들 발견 | AUTO — 단 `store_sync_state(source=INSTAGRAM).external_id`에만 저장 |
| 상위 출처 기록 필드 · 외부 변경 감지 필드 | PROPOSAL 강등 |

근거·상세는 설계서 5장.

## 7. 데이터 모델

dataops 로컬 DB에 Flyway로 6개 테이블 생성(전체 DDL은 설계서 3장이 정본):

| 테이블 | 역할 | 마이그레이션 |
|---|---|---|
| `store_sync_state` | 가게×소스 동기화 상태 = 워크리스트 | `V20260801100000` |
| `place_snapshot` | 원본층 (append-only, 해시 dedup) | `V20260801100100` |
| `store_field_provenance` | 필드 단위 출처 | `V20260801100200` |
| `change_proposal` | 승인 대기 diff | `V20260801100300` |
| `apply_log` | 운영 쓰기 감사 로그 (선기록) | `V20260801100400` |
| `new_store_candidate` | 공공데이터 신규 인허가 후보 | `V20260801100500` |

운영 DB에는 **테이블·컬럼을 하나도 만들지 않는다.**
(3단계에 소형 테이블 4개를 RDS `dataops` 스키마로 승격 — 설계서 4.2, 이때도 운영 스키마
`public`은 불가침.)

## 8. 비기능 요구사항

| # | 요구사항 |
|---|---|
| NFR-1 | 운영 DB에 DDL 금지, DML(INSERT/UPDATE)만, 물리 DELETE 금지(soft delete) |
| NFR-2 | 운영 쓰기 계정은 전용 계정(`dataops_writer`) — store·menu INSERT/UPDATE + 전역 SELECT로 권한 제한 |
| NFR-3 | 크롤 멱등성 — 중복 배부·재크롤이 데이터를 오염시키지 않는다(해시 dedup) |
| NFR-4 | 세션형 운영 — 임의 시점 시작·중단 가능, 중단 시 복구 절차 불요 |
| NFR-5 | 크롤 예산 — 주간 (1,200×2 + 29,700)×1.5초 ≈ 13.4시간 이내 완주 |
| NFR-6 | RDS에 대량 데이터·풀스캔 금지 — 나가는 것은 소량 DML + 현재값 SELECT + 주 1회 μ 집계뿐 |
| NFR-7 | apply_log·provenance 유실 방지 — 0~2단계 주기 pg_dump(스냅샷 제외) 원격 보관, 3단계 RDS 승격 |
| NFR-8 | 트랜잭션 순서(0~2단계): apply_log 선기록 → 운영 커밋 → provenance/상태 갱신, 중간 실패 시 재대사 |

## 9. 수용 기준 (단계 게이트)

| 단계 | 완료 조건 |
|---|---|
| 0 | 시딩 결과 수 = 운영 매칭 가게 수 · 워커가 표본 100곳 스냅샷 수집 성공 |
| 1 | 표본 1,000곳 1주 무차단 완주 · 해시 변경률 상식 범위 · 파서 정합성 눈검사 통과 |
| 2 | proposal 표본 검수로 오탐(오매칭발 diff) 비율 확인 · apply_log↔운영 값 대사 일치 |
| 3 | OpenSearch 색인 경로 확인 완료 · provenance USER 소급 완료 · 자동 반영 오류 0 · 주간 예산 내 완주 |
| 4 | 공공데이터 파일럿 매칭률 실측으로 계속/중단 판단 |

## 10. 의도적 보류 (스펙에 포함하지 않음)

배분 공식(√μ) 적용, 크롤 예산 R 상한, 폐업 자동 판정, 인스타 게시물 수집,
menu diff 세부 매칭(이름변경 vs 삭제+추가), 이슈·제보 수집 채널 — 재검토 시점은 설계서 10장.
