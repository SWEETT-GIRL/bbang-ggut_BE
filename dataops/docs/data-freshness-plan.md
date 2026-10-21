# 데이터 최신화 시스템 — 구현 계획 (Plan)

> **후속 개선안 (2026-09-25)**: [안정화·운영화 v3 plan](./data-freshness-v3-plan.md) · [v3 spec](./data-freshness-v3-spec.md).
> 아래는 초기 도입 당시 계획이다. 기존 도메인 삭제·DB 볼륨 초기화 절차를 v3 이관에 재사용하지 않는다.

> 작성 2026-08-01 · 작업 분해 문서 (어떤 순서로 무엇을 만들 것인가)
> 규범: [스펙](./data-freshness-spec.md) · 상세 설계: [설계서 v2](./data-freshness-design.md)
> 레포 전략: [분리 레포 유지](./repo-strategy-comparison.md) — 이 계획은 `bbang-ggut_dataops_BE` 단독 작업이다. 본 앱 레포는 건드리지 않는다.

패키지 루트 `sweetgirl.dataops`, 도메인별 `code/controller/dto/entity/enums/exception/repository/service`
+ Command/Query 서비스 분리 컨벤션 유지.

---

## 선행 확인 체크리스트 (코드 밖 협의·확인)

| # | 항목 | 필요 시점 | 상태 |
|---|---|---|---|
| P-1 | 운영 DB 읽기 전용 계정으로 `.env`(`PROD_DB_*`) 설정 | 0단계 시딩 전 | ☐ |
| P-2 | MENU_ADD 승인 이력으로 사용자 기여 메뉴를 식별하는 쿼리 확정 (provenance USER 소급의 입력) | 0단계 시딩 전 | ☐ |
| P-3 | 운영 쓰기 전용 계정 `dataops_writer` 발급 협의 (store·menu INSERT/UPDATE + 전역 SELECT) | 2단계 진입 전 | ☐ |
| P-4 | 본 앱 OpenSearch 색인 경로 확인 (주기 재색인인가, 앱 레이어 색인인가) | 3단계 진입 전 (게이트) | ☐ |
| P-5 | pg_dump 원격 보관 위치 결정 (apply_log·provenance 백업) | 2단계 쓰기 시작 전 | ☐ |

---

## 0단계 — 폐기 + 골격 + 시딩

### 0-1. 기존 리소스 폐기

| 대상 | 작업 |
|---|---|
| `src/main/java/sweetgirl/dataops/{issue,ingest,remedy,crawltask}` | 삭제 |
| `src/test/java/sweetgirl/dataops/{issue,ingest,remedy,crawltask}` | 삭제 |
| `src/main/resources/db/migration/V20260719120000__create_data_issue.sql` 외 2개 (`remedy_execution`, `crawl_task`) | 삭제 |
| `scripts/crawl_worker.py` | 삭제 |
| `application-local.properties`의 `dataops.admin-api.*` · `dataops.slack.*` | 삭제 (admin API 경유 폐기, Slack 수집은 ingest 소속) |
| 로컬 DB | `docker compose down -v && docker compose up -d` (볼륨 리셋 — 남길 이력 있으면 리셋 전 dump) |
| 유지 | `global`·`common`·`config/persistence` 스캐폴딩, `docs/`, docker-compose |

검증: `./gradlew build` 통과 (남는 테스트 0개여도 컴파일 성공).

### 0-2. Flyway 마이그레이션 6개

설계서 3장 DDL 그대로. `src/main/resources/db/migration/`:

- `V20260801100000__create_store_sync_state.sql`
- `V20260801100100__create_place_snapshot.sql`
- `V20260801100200__create_store_field_provenance.sql`
- `V20260801100300__create_change_proposal.sql`
- `V20260801100400__create_apply_log.sql`
- `V20260801100500__create_new_store_candidate.sql`

검증: 부팅 시 flyway migrate 성공, `ddl-auto=validate` 통과(엔티티 작성 후).

### 0-3. serving 도메인 — 읽기 경로 먼저

| 파일 | 내용 |
|---|---|
| `serving/config/ProdDataSourceConfig` | 운영 DB 별도 DataSource + JdbcTemplate (`dataops.prod-db.*`). JPA 미사용 |
| `serving/client/ServingReader` | 운영 현재값 SELECT: store 필드, menu 목록, μ 집계용 request_log·subscribed_store 조회 |
| `serving/config/ProdSchemaGuard` | 스타트업 스키마 대조 — ServingReader/Writer가 참조하는 테이블·컬럼 상수 목록 vs `information_schema.columns`, 불일치 시 기동 실패 |
| `serving/entity/ApplyLog` + `repository/ApplyLogRepository` | apply_log 매핑 (dataops DB) |
| `serving/service/ApplyLogQueryService` + `controller/ApplyLogController` | `GET /api/apply-logs?storeId=` |

`ServingWriter`는 2단계에서 작성한다(쓰기 계정과 함께 — 그 전에 쓰기 코드가 존재하지 않게).

### 0-4. sync 도메인

| 파일 | 내용 |
|---|---|
| `sync/entity/StoreSyncState` | `recordSuccess(changed)` / `recordFailure()` / `park()` / `unlink()` — 카운터·백오프·next_due_at(주기+지터 ±10%) 캡슐화 |
| `sync/enums/SyncSource`·`SyncTier`·`SyncStateStatus` | NAVER·LOCALDATA·INSTAGRAM / T0(3.5일)·T1(7일) Duration 보유 / ACTIVE·PARKED·UNLINKED |
| `sync/repository/StoreSyncStateRepository` | `findDue(now, limit)` — next_due_at 도래 + 소프트 리스 제외, 부분 인덱스 활용 |
| `sync/service/SyncStateCommandService` | due export(last_attempt_at 스탬프), 실패 보고 처리, park/unpark/tier |
| `sync/service/SyncStateQueryService` | 상태·카운터 조회 |
| `sync/service/SyncSeedService` | 멱등 시딩: ServingReader로 매칭 가게 SELECT → sync_state 벌크 생성(naver_place_id 복사) + **사용자 기여 메뉴 provenance USER 소급 기록**(P-2 쿼리) |
| `sync/controller/SyncStateController` | `GET /due` · `POST /{id}/failure` · `GET /` · `POST /seed` · `PATCH /{id}/park·unpark·tier` |

### 0-5. snapshot 도메인

| 파일 | 내용 |
|---|---|
| `snapshot/entity/PlaceSnapshot` | 불변 매핑 (전이 메서드 없음) |
| `snapshot/repository/PlaceSnapshotRepository` | `findLatest(storeId, source)`, 기간 조회 |
| `snapshot/service/SnapshotCommandService` | 제출 수신: SHA-256 → 직전 비교 → 생략/저장 → `syncState.recordSuccess(changed)` (reconcile 트리거는 2단계에 연결) |
| `snapshot/service/SnapshotQueryService` + `controller/SnapshotController` | `POST /api/snapshots` · `GET /api/snapshots?storeId=` |
| `snapshot/parser/NaverPlaceParser` + `parser/ParsedPlace` | Apollo state → 정규화 record. **참조 해소 필수**, 인스타 핸들 추출 포함 |

### 0-6. 워커 + 백업 스크립트

| 파일 | 내용 |
|---|---|
| `scripts/sync_worker.py` | due 폴링 → requests fetch(1.5s/req) → `__APOLLO_STATE__` 추출 → snapshot 제출, 실패 보고, 차단 감지 시 exit 3. 복구 로직 없음 |
| `scripts/backup_dataops.sh` | pg_dump — `place_snapshot` 제외(`--exclude-table-data`), apply_log·provenance 필수 포함, 원격 보관(P-5) |

### 0-7. 시딩 실행 + 게이트

1. `.env`에 읽기 전용 운영 계정 설정(P-1) → `POST /api/sync-states/seed`
2. **게이트 검증**: 시딩 행 수 = 운영 `naver_place_id` 보유 ACTIVE 가게 수 · 워커로 표본 100곳 수집 성공(스냅샷 100건 + visit_count 증가 확인)

테스트: 엔티티 전이(성공/실패/백오프/unlink), 해시 dedup, due 쿼리 소프트 리스, 시딩 멱등성, 파서(실제 Apollo 페이로드 픽스처 — 참조 해소 케이스 포함).

---

## 1단계 — 관찰 모드 (판정 미가동)

| 작업 | 내용 |
|---|---|
| 표본 선정 | T1 중 1,000곳만 `next_due_at` 활성화 — **메뉴 0개 + only-naver 대량 가게 우선**(리서치 2.5: 소수 가게에 누락 집중). 나머지는 next_due_at NULL |
| 스냅샷·카운터 축적 | 워커 세션 실행(주 2회 밤새 등) — 판정 없이 스냅샷·해시·visit/change 카운터만 |
| overdue 지표 | `now - next_due_at > 티어 주기` 가게 수 집계 쿼리(상시화 전환 신호 겸용) |
| 파서 정합성 눈검사 | 표본 스냅샷 파싱 결과 vs 실제 페이지 대조 (SnapshotQueryService에 파싱 미리보기 응답 추가) |

**게이트**: 1주 무차단 완주 · 해시 변경률 상식 범위 · 파서 눈검사 통과.

## 2단계 — 판정 가동 (전 필드 PROPOSAL 모드)

| 작업 | 내용 |
|---|---|
| `reconcile/dto/FieldDiff` | target, oldValue, newValue, source, snapshotId |
| `reconcile/policy/MergePolicy` | 5장 매트릭스 + 출처 우선순위. **모드 스위치**: 2단계는 전 필드 PROPOSAL 강제 |
| `reconcile/service/ReconcileService` | ParsedPlace vs ServingReader diff → 정책 적용 → Proposal 위임. 외부 변경 감지 시 강등. SnapshotCommandService의 변경 감지 훅에 연결 |
| `reconcile/entity/StoreFieldProvenance` + repository | provenance 매핑·upsert |
| `proposal/entity/ChangeProposal` + enums + repository | `approve()`/`reject(note)`/`supersede()`, PENDING 부분 유니크 대응 |
| `proposal/service/ProposalCommandService` | 생성(기존 PENDING supersede), 승인(apply_log 선기록 → ServingWriter → provenance, source=데이터 원천), 거절 |
| `proposal/service/ProposalQueryService` + `controller/ProposalController` | `GET /api/proposals?status=` (근거 스냅샷 링크) · `PATCH approve/reject` |
| `serving/client/ServingWriter` | **운영 DML 유일 위치**: store 필드별 UPDATE, menu INSERT/UPDATE, store soft delete. 물리 DELETE 없음. `dataops_writer` 계정(P-3) 활성화 |
| 대사 스크립트 | apply_log ↔ 운영 현재값 대조 쿼리 (트랜잭션 순서 규약 NFR-8의 재대사 도구) |

**게이트**: proposal 표본 검수로 오탐률 확인 · apply_log↔운영 값 대사 일치.

## 3단계 — 자동 반영 + 전체 확대

선행: P-4(OpenSearch 색인 경로) 확인 완료 + provenance USER 소급 완료 확인.

| 작업 | 내용 |
|---|---|
| `reconcile/service/AutoApplyService` | 안전 필드 자동 반영: apply_log 선기록 → ServingWriter → provenance upsert. 실패 시 proposal 강등 |
| MergePolicy 모드 전환 | 5장 매트릭스대로 AUTO 활성화 (business_hours, phone, menu price, 신규 menu) |
| 시딩 확대 | 전 T0/T1 활성화 — **첫 완주가 곧 백필 스윕** (기대: 메뉴 0개 ~3,170곳 / ~32,000메뉴) |
| `sync/service/SyncTierService` | 주 1회 티어 재산정: request_log(4.2 필터) + subscribed_store 집계 |
| 소형 테이블 RDS 승격 | apply_log·provenance·sync_state·proposal을 RDS `dataops` 스키마로 이전 — apply_log+운영 DML 단일 트랜잭션화, 백업 스크립트 은퇴. 스냅샷은 로컬 유지 |

**게이트**: 자동 반영 오류 0 유지 · 주간 예산(13.4h) 내 완주.

## 4단계 — 공공데이터 파일럿

| 작업 | 내용 |
|---|---|
| `candidate/entity/NewStoreCandidate` + enums + repository | 상태 전이 — WAITING_NAVER가 워크리스트 |
| `candidate/client/LocaldataClient` + `config/LocaldataRestClientConfig` | 공공데이터 API (인증키 `.env`) |
| `candidate/service/CandidateSyncService` | 주기 pull → 업종·지역 필터 → mgmt_no upsert. 폐업 레코드는 ProposalCommandService로 |
| `candidate/service/CandidateQueryService` + `controller/CandidateController` | `POST /api/candidates/sync-run` · `GET /api/candidates?status=` |
| `sync_worker.py` 매칭 모드 | candidates 폴링 → 네이버 검색 매칭 → 성공 시에만 운영 store INSERT 경로 |

**게이트**: 파일럿 매칭률 실측으로 계속/중단 판단.

---

## 커밋 단위 가이드 (0단계)

1. `:fire: chore: 기존 도메인 4종 폐기 (issue·ingest·remedy·crawltask)` — 0-1
2. `:card_file_box: feat: 최신화 스키마 6종 마이그레이션` — 0-2
3. `:sparkles: feat: serving 읽기 경로 — ProdDataSource·ServingReader·스키마 가드` — 0-3
4. `:sparkles: feat: sync 도메인 — 상태·due export·시딩` — 0-4
5. `:sparkles: feat: snapshot 도메인 — 수집·해시 dedup·파서` — 0-5
6. `:sparkles: feat: sync_worker.py + 백업 스크립트` — 0-6
7. 각 도메인 테스트는 해당 feat 커밋에 동반 또는 직후 `:test_tube: test:` 커밋
