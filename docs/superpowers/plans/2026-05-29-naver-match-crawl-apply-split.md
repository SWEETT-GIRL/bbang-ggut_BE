# Naver Enrich → Match / Crawl / Apply 분리 리팩터링 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `scripts/naver_menu_scraper.py` 의 단일 enrich 흐름을 **Match → Crawl → Apply** 세 단계로 분리해, 사고를 단계별로 격리하고 같은 snapshot 을 여러 번 적용해도 메뉴/사진 중복이 생기지 않게 만든다.

**Architecture:**
- Match Job: store → `naver_place_id` 확정 또는 후보/실패 기록. 메뉴/사진 미접촉.
- Crawl Job: `naver_place_id` → raw 메뉴/사진 snapshot (`naver_crawl_snapshot` 테이블) 저장. 메뉴/사진 DB·S3 미접촉.
- Apply Job: snapshot → 메뉴 DB / 사진 DB 반영. `MenuNameNormalizer` 와 `source_url` 기반 idempotent 처리. 같은 snapshot 을 N번 apply 해도 중복 없음.
- Spring 측은 entity·서비스·controller·migration 만 추가; 기존 `/naver-enrich-queue` / `naver-enrich-now` UX 는 유지하고 내부에서 pipeline 으로 위임.
- Python 측은 함수 단위(`match_store` / `crawl_store` / `apply_store_snapshot` / `run_pipeline`)로 분리하고 CLI 플래그 추가.

**Tech Stack:** Spring Boot 4.0.6, Java 17, PostgreSQL (raw SQL migration), Hibernate JPA, Playwright + requests (Python), pytest (Python 단위), JUnit5 + Mockito (Java 단위).

**Branch:** `feat-kakao-bulk-import` (현재). 새 브랜치 만들지 않음.

**Migrations 위치:** `docs/migrations/YYYY-MM-DD-*.sql` — 기존 패턴(수동 psql 적용 + Hibernate `ddl-auto=validate`).

---

## 결정 사항 (사용자 승인 필요)

**A. status 값 (요청 그대로 채택)**
- `Store.naverMatchStatus`: `PENDING` (NULL 호환) | `MATCHED` | `NEEDS_REVIEW` | `FAILED`
- `NaverCrawlSnapshot.status`: `SUCCESS` | `FAILED`
- `NaverCrawlSnapshot.applyStatus`: `PENDING` | `APPLIED` | `PARTIAL` | `FAILED`

**B. naver_match_status 마이그레이션 backfill**
- `naverPlaceId IS NOT NULL` → `MATCHED`
- `naverPlaceId IS NULL AND naverMatchedAt IS NOT NULL` → `FAILED`
- `naverPlaceId IS NULL AND naverMatchedAt IS NULL` → `PENDING` (NULL 채움)

**C. Snapshot 단일성**
- 한 store 에 대한 snapshot 은 **여러 row** 허용. apply-queue / crawl-retry 는 항상 `(store_id, status='SUCCESS')` 의 최신 row 를 본다. 과거 snapshot 은 audit / debug 용으로 보존.

**D. apply 멱등성 키**
- 메뉴: `MenuNameNormalizer.normalize(name)` + `store_id` (활성 메뉴 한정). 기존 `AdminMenuService.createMenu` 가 이미 채택한 로직 재사용.
- 사진: `source_url` (이미 `store_image_file.source_url` / `menu_image_file.source_url` 존재). `AdminImageService.uploadImagesFromUrls` 가 이미 SKIPPED 반환하므로 그대로 재사용.

**E. Claim/Lock 방식 (Phase 7)**
- 새 job 테이블 만들지 않고 **claim 컬럼**(`locked_by` / `locked_until` / `attempt_count` / `next_retry_at`)을 다음 테이블에 추가:
  - `store` (match·crawl 클레임): match 큐는 `naverPlaceId IS NULL`, crawl 큐는 `naverPlaceId IS NOT NULL` 인 row 를 같은 컬럼으로 구분 사용.
  - `naver_crawl_snapshot` (apply 클레임): row 자체가 작업 단위.
- atomic claim 은 `UPDATE ... WHERE locked_until IS NULL OR locked_until < now() ... RETURNING id` (PostgreSQL) — JPQL 로는 어렵고 native query 로 구현.

**F. 기존 endpoint alias**
- `/api/admin/stores/naver-enrich-queue` 유지. 내부 service 호출이 `getNaverMatchQueue` 와 같은 쿼리/페이지/DTO 를 반환하도록 위임만 시킨다 (DTO 동일, 메소드 alias).

**G. Admin UX (Phase 8)**
- BE 만 변경. FE (`bbang-ggut_admin_web/`) 는 이 plan 범위 밖. 기존 [Enrich] 버튼은 `naver-enrich-now` 가 내부적으로 `match → crawl → apply` 를 순차 실행하므로 호환 유지.

---

## 파일 구조

| 신규/수정 | 파일 | 책임 |
|---|---|---|
| 수정 | `scripts/naver_menu_scraper.py` | 함수 분리 (match/crawl/apply/pipeline), CLI 플래그 추가, `_enrich_with_place_id` 는 apply_store_snapshot 로 흡수 |
| 신규 | `scripts/tests/test_pipeline_split.py` | match-only / crawl-only / apply-only 분기 단위 테스트 (mocked HTTP) |
| 신규 | `docs/migrations/2026-05-30-naver-match-status.sql` | `store` 에 `naver_match_status` / `naver_match_error` 추가 + backfill |
| 신규 | `docs/migrations/2026-05-30-naver-crawl-snapshot.sql` | `naver_crawl_snapshot` 테이블 + 인덱스 |
| 신규 | `docs/migrations/2026-05-31-naver-claim-columns.sql` | claim 컬럼들 추가 (Phase 7) |
| 수정 | `Store.java` | 새 enum 필드 + `markNaverMatched` / `markNaverEnrichFailed` 가 status 도 갱신 |
| 신규 | `store/enums/NaverMatchStatus.java` | enum 정의 |
| 신규 | `store/entity/NaverCrawlSnapshot.java` | snapshot entity |
| 신규 | `store/repository/NaverCrawlSnapshotRepository.java` | snapshot CRUD + 최신 SUCCESS lookup |
| 수정 | `StoreRepository.java` | match-queue / crawl-queue 메소드 추가 (기존 enrich-queue 메소드는 유지) |
| 신규 | `admin/dto/response/AdminNaverCrawlSnapshotResponse.java` | snapshot 노출 DTO |
| 신규 | `admin/dto/request/AdminNaverCrawlSnapshotRequest.java` | Python 이 snapshot 저장 시 보내는 페이로드 |
| 신규 | `admin/dto/request/AdminClaimRequest.java` | claim 페이로드 (Phase 7) |
| 신규 | `admin/dto/response/AdminClaimResponse.java` | claim 응답 (Phase 7) |
| 수정 | `AdminStoreService.java` | `getNaverMatchQueue` / `getNaverCrawlQueue` 추가, `getNaverEnrichQueue` 는 alias |
| 신규 | `admin/service/NaverCrawlSnapshotService.java` | snapshot save/lookup |
| 신규 | `admin/service/NaverApplyService.java` | snapshot → 메뉴/사진 idempotent 반영 |
| 신규 | `admin/service/NaverJobClaimService.java` | atomic claim/release/heartbeat (Phase 7) |
| 수정 | `AdminStoreController.java` | 신규 endpoint 3개 (match/crawl/apply queue) + 3개 claim |
| 수정 | `NaverEnrichRunner.java` | subprocess 가 `--pipeline` 사용. 종료 후 stats 계산 동일 |
| 신규 | `src/test/java/.../service/NaverApplyServiceTest.java` | idempotent 검증 |
| 신규 | `src/test/java/.../service/NaverJobClaimServiceTest.java` | 동시 claim 검증 (`@DataJpaTest` + 트랜잭션 격리) |

---

## Phase 0 — 진입 전 검증

기존 환경/명령이 작동하는지 확인. 이후 모든 phase 시작 전 baseline.

- [ ] **Step 0.1**: 현재 상태 commit 가능한지 확인.
  ```bash
  cd /Users/danalim/bbanggut/bbang-ggut_BE
  git status -s
  ```
  현재 다수 modified 파일 (`AdminImageController`, `AdminImageService`, ...) 존재. 사용자에게 "이대로 작업 시작할지 / 먼저 staged 변경을 정리할지" 확인. **Plan 시작 시 사용자에게 별도 confirm 후 진행.**

- [ ] **Step 0.2**: 테스트 baseline 통과 확인.
  ```bash
  ./gradlew test --tests "sweetgirl.croissant.admin.service.*"
  ```
  PASS 여야 함. 실패 테스트가 있으면 그것부터 정리.

- [ ] **Step 0.3**: Python scraper 의존성 설치 확인.
  ```bash
  cd scripts && pip install -r requirements-dev.txt
  python -m pytest tests/ -v
  ```
  PASS 여야 함.

---

## Phase 2 — Python 함수 분리 + CLI 옵션

**목표:** 기존 `process_store` 의 흐름을 깨지 않으면서 내부적으로 `match_store / crawl_store / apply_store_snapshot / run_pipeline` 로 분리하고 CLI 플래그 추가. 이 phase 종료 시점에 동작 자체는 기존과 100% 동일해야 한다 (Snapshot 테이블이 아직 없으므로 crawl-only/apply-only 는 NotImplemented 시그널만 출력).

### Task 2.1: pipeline 헬퍼 함수 추가

**Files:**
- Modify: `scripts/naver_menu_scraper.py:516-684`
- Modify: `scripts/naver_menu_scraper.py:687-770` (argparse)
- Test: `scripts/tests/test_pipeline_split.py` (신규)

- [ ] **Step 2.1.1**: 실패 테스트 추가 — `match_store` 가 매칭 성공 시 `(naver_place_id, candidates)` 반환, 실패 시 `(None, candidates)` 반환.

  Create `scripts/tests/test_pipeline_split.py`:
  ```python
  from unittest.mock import MagicMock, patch
  from naver_menu_scraper import match_store, crawl_store, apply_store_snapshot

  def test_match_store_returns_place_id_on_success():
      page = MagicMock()
      store = {"id": 1, "name": "테스트빵집", "address": "서울 강남구 테헤란로 1"}
      cand = [{"id": "12345", "name": "테스트빵집", "address": "서울 강남구 테헤란로 1",
               "x": "127.0", "y": "37.5"}]
      with patch("naver_menu_scraper.search_naver_candidates", return_value=cand), \
           patch("naver_menu_scraper.select_match",
                 return_value=(cand[0], [(cand[0], {"total": 80, "name_kind": "exact",
                                                    "distance_m": 10, "breakdown": {"road": 15}})],
                               "score=80")):
          place_id, scored, reason = match_store(page, store)
      assert place_id == "12345"
      assert reason.startswith("score=80")

  def test_match_store_returns_none_on_no_candidates():
      page = MagicMock()
      store = {"id": 2, "name": "X", "address": "x"}
      with patch("naver_menu_scraper.search_naver_candidates", return_value=[]):
          place_id, scored, reason = match_store(page, store)
      assert place_id is None
      assert reason == "no_candidates"

  def test_match_store_returns_none_on_gate_fail():
      page = MagicMock()
      store = {"id": 3, "name": "X", "address": "x"}
      cand = [{"id": "9", "name": "Y"}]
      with patch("naver_menu_scraper.search_naver_candidates", return_value=cand), \
           patch("naver_menu_scraper.select_match",
                 return_value=(None, [(cand[0], {"total": 20, "name_kind": "exact",
                                                  "distance_m": 200, "breakdown": {"road": 0}})],
                               "distance>60m")):
          place_id, scored, reason = match_store(page, store)
      assert place_id is None
      assert reason == "distance>60m"
  ```

- [ ] **Step 2.1.2**: 테스트 실행 — FAIL 확인.
  ```bash
  cd scripts && python -m pytest tests/test_pipeline_split.py -v
  ```
  Expected: `ImportError: cannot import name 'match_store'`.

- [ ] **Step 2.1.3**: `match_store` 함수 추가. 기존 `process_store` 의 매칭 부분만 분리.

  Edit `scripts/naver_menu_scraper.py` — `process_store` 위에 다음 추가:
  ```python
  def match_store(page, store: dict) -> tuple[Optional[str], list, str]:
      """매칭만 수행. naver_place_id 저장은 안 함.
      Return: (place_id|None, scored_candidates, reason)
        - place_id 있음: 매칭 성공
        - place_id None: 실패. reason 에 'no_candidates' 또는 select_match 의 reason
      """
      candidates = search_naver_candidates(page, store["name"], store.get("address"))
      if not candidates:
          return None, [], "no_candidates"
      matched, scored, reason = select_match(store, candidates)
      if not matched:
          return None, scored, reason
      return str(matched.get("id", "")), scored, reason
  ```

- [ ] **Step 2.1.4**: 테스트 실행 — PASS 확인.
  ```bash
  cd scripts && python -m pytest tests/test_pipeline_split.py::test_match_store_returns_place_id_on_success -v
  cd scripts && python -m pytest tests/test_pipeline_split.py::test_match_store_returns_none_on_no_candidates -v
  cd scripts && python -m pytest tests/test_pipeline_split.py::test_match_store_returns_none_on_gate_fail -v
  ```
  Expected: 3 PASS.

- [ ] **Step 2.1.5**: `crawl_store` 와 `apply_store_snapshot` 스텁 추가. Phase 5/6 까지 본격 구현은 보류, 지금은 기존 `_enrich_with_place_id` 를 wrap.

  Edit `scripts/naver_menu_scraper.py`:
  ```python
  def crawl_store(page, store: dict, server: str) -> dict:
      """raw 메뉴/사진 snapshot 만 생성. Phase 5 까지 미구현 — _enrich_with_place_id 를 직접 호출.

      TODO(phase 5): 메뉴 DB / 사진 DB 를 건드리지 않고 snapshot row 만 저장.
      """
      stats = {"matched": 0, "menus_created": 0, "images_uploaded": 0,
               "skipped_menu_crawl": 0, "photos_uploaded": 0, "skipped_photo_crawl": 0,
               "failed": 0}
      naver_id = store.get("naverPlaceId")
      if not naver_id:
          print("  [crawl_store] naverPlaceId 없음 — 건너뜀")
          stats["failed"] = 1
          return stats
      stats["matched"] = 1
      return _enrich_with_place_id(
          page, store["id"], naver_id,
          store.get("existingMenuCount", 0) or 0,
          store.get("existingPhotoCount", 0) or 0,
          "", server, stats)


  def apply_store_snapshot(snapshot_id: int, server: str) -> dict:
      """Snapshot row → 메뉴/사진 DB 반영. Phase 6 까지 미구현."""
      raise NotImplementedError(
          "apply_store_snapshot 는 Phase 6 에서 구현됨. "
          "현재는 --pipeline 또는 기존 큐 모드를 사용하라.")


  def run_pipeline(page, store: dict, server: str, dry_run: bool, verbose: bool) -> dict:
      """기존 process_store 와 동일한 단일 가게 end-to-end 흐름."""
      return process_store(page, store, server, dry_run, verbose)
  ```

- [ ] **Step 2.1.6**: 기존 동작 회귀 검증.
  ```bash
  cd scripts && python naver_menu_scraper.py --dry-run --store-id 1 --server http://localhost:8080 2>&1 | head -20
  ```
  Expected: 기존과 동일 출력 (백엔드 미기동이라면 "fetch 실패" 도 OK — 핵심은 import error 없음).

- [ ] **Step 2.1.7**: Commit.
  ```bash
  git add scripts/naver_menu_scraper.py scripts/tests/test_pipeline_split.py
  git commit -m ":sparkles: Feat: Naver scraper 함수 분리 — match/crawl/apply 헬퍼 추가"
  ```

### Task 2.2: CLI 플래그 추가

**Files:** Modify: `scripts/naver_menu_scraper.py:687-770`

- [ ] **Step 2.2.1**: 실패 테스트 — argparse 가 `--match-only` 를 인식.

  Edit `scripts/tests/test_pipeline_split.py`:
  ```python
  def test_cli_has_match_only_flag():
      from naver_menu_scraper import build_parser
      p = build_parser()
      args = p.parse_args(["--match-only", "--store-id", "1"])
      assert args.match_only is True

  def test_cli_has_crawl_only_flag():
      from naver_menu_scraper import build_parser
      p = build_parser()
      args = p.parse_args(["--crawl-only", "--store-id", "1"])
      assert args.crawl_only is True

  def test_cli_has_apply_only_flag():
      from naver_menu_scraper import build_parser
      p = build_parser()
      args = p.parse_args(["--apply-only", "--snapshot-id", "5"])
      assert args.apply_only is True
      assert args.snapshot_id == 5

  def test_cli_pipeline_is_default():
      from naver_menu_scraper import build_parser
      p = build_parser()
      args = p.parse_args(["--pipeline", "--store-id", "1"])
      assert args.pipeline is True
  ```

- [ ] **Step 2.2.2**: Run test — FAIL.
  ```bash
  cd scripts && python -m pytest tests/test_pipeline_split.py::test_cli_has_match_only_flag -v
  ```

- [ ] **Step 2.2.3**: `build_parser()` 헬퍼 추출 + 새 플래그 추가.

  Edit `scripts/naver_menu_scraper.py` — `main()` 의 argparse 블록을 함수로 추출:
  ```python
  def build_parser() -> argparse.ArgumentParser:
      parser = argparse.ArgumentParser(description="네이버 플레이스 메뉴 크롤러 (빵긋 큐 연동)")
      # ... 기존 인자 그대로 유지 ...
      mode = parser.add_mutually_exclusive_group()
      mode.add_argument("--match-only", action="store_true",
          help="매칭만 수행. naver_place_id 까지만 저장하고 메뉴/사진은 건드리지 않음.")
      mode.add_argument("--crawl-only", action="store_true",
          help="naver_place_id 가 있는 가게의 raw 메뉴/사진을 snapshot 으로 저장. 메뉴/사진 DB·S3 미접촉. "
               "Phase 5 까지 NotImplemented.")
      mode.add_argument("--apply-only", action="store_true",
          help="snapshot 을 메뉴/사진 DB 에 idempotent 반영. Phase 6 까지 NotImplemented.")
      mode.add_argument("--pipeline", action="store_true",
          help="기존 process_store 와 동일한 단일 가게 end-to-end 흐름 (기본 동작).")
      parser.add_argument("--snapshot-id", type=int, default=0,
          help="--apply-only 에서 처리할 snapshot id.")
      return parser

  def main() -> None:
      args = build_parser().parse_args()
      # ... 기존 main() 의 나머지 ...
  ```

- [ ] **Step 2.2.4**: `main()` 에 분기 로직 추가 — 새 플래그는 dispatch 만 하고 동작은 함수가 위임.
  - `args.match_only` 면 `match_store` 호출 후 매칭 성공 시 `save_naver_place_id` 만 호출 (메뉴/사진 미접촉). 실패 시 candidates 저장 + mark_failed.
  - `args.crawl_only` 면 `crawl_store` 호출 (Phase 5 까지 raises NotImplementedError; 친절한 메시지로 catch 후 exit 2).
  - `args.apply_only` 면 `apply_store_snapshot` 호출 (동일).
  - 그 외 (`--pipeline` 명시 또는 default) 는 기존 `process_store` 로 갬.

  Edit `scripts/naver_menu_scraper.py:724-733` 영역 (단일 store 모드 분기):
  ```python
  if args.store_id:
      store = fetch_single_store(args.server, args.store_id)
      if not store:
          print(f"[종료] store_id={args.store_id} fetch 실패")
          browser.close()
          sys.exit(2)

      if args.match_only:
          place_id, scored, reason = match_store(page, store)
          if place_id is None:
              print(f"  → 매칭 실패: {reason}")
              if not args.dry_run:
                  # search_naver_candidates 결과를 다시 노출하기 위해 select_match 의 candidates 가 필요.
                  # scored 에서 dict 만 다시 추출.
                  raw_cands = [c for c, _ in scored]
                  if raw_cands:
                      save_candidates(args.server, args.store_id, raw_cands)
                  mark_failed(args.server, args.store_id)
              total["failed"] += 1
          else:
              print(f"  ✓ 매칭: naverPlaceId={place_id} ({reason})")
              if not args.dry_run:
                  saved, err = save_naver_place_id(args.server, args.store_id, place_id)
                  if not saved:
                      print(f"  → placeId 저장 실패({err}) — 메뉴/사진 진행 안 함")
                      if err == "STORE409_4":
                          raw_cands = [c for c, _ in scored]
                          if raw_cands:
                              save_candidates(args.server, args.store_id, raw_cands)
                      mark_failed(args.server, args.store_id)
                      total["failed"] += 1
                  else:
                      total["matched"] += 1
      elif args.crawl_only:
          try:
              result = crawl_store(page, store, args.server)
              for k in total: total[k] += result.get(k, 0)
          except NotImplementedError as e:
              print(f"[crawl-only NotImplemented] {e}")
              browser.close()
              sys.exit(4)
      elif args.apply_only:
          if not args.snapshot_id:
              print("[apply-only 에는 --snapshot-id 필요]")
              browser.close()
              sys.exit(2)
          try:
              result = apply_store_snapshot(args.snapshot_id, args.server)
              for k in total: total[k] += result.get(k, 0)
          except NotImplementedError as e:
              print(f"[apply-only NotImplemented] {e}")
              browser.close()
              sys.exit(4)
      else:
          # default 또는 --pipeline — 기존 흐름
          result = run_pipeline(page, store, args.server, args.dry_run, args.verbose)
          for k in total: total[k] += result.get(k, 0)
  ```

- [ ] **Step 2.2.5**: Run all pipeline tests — PASS.
  ```bash
  cd scripts && python -m pytest tests/test_pipeline_split.py -v
  ```
  Expected: 7 PASS.

- [ ] **Step 2.2.6**: 기존 큐 모드 회귀 확인 (선택, 백엔드 기동 가능할 때만).
  ```bash
  # 백엔드가 떠 있다면:
  python scripts/naver_menu_scraper.py --dry-run --store-id <기존 매칭됨 store id>
  ```

- [ ] **Step 2.2.7**: Commit.
  ```bash
  git add scripts/naver_menu_scraper.py scripts/tests/test_pipeline_split.py
  git commit -m ":sparkles: Feat: Naver scraper CLI — match/crawl/apply/pipeline 분기 추가"
  ```

### Task 2.3: match-only 회귀 검증 시나리오

- [ ] **Step 2.3.1**: 매뉴얼 dry-run — `--match-only --dry-run --store-id <X>` 는 메뉴/사진 DB 미변경. 백엔드 로그/DB 확인.
- [ ] **Step 2.3.2**: 결과 노트를 `docs/superpowers/specs/2026-05-29-naver-pipeline-validation.md` 에 한 줄씩 기록 (간이). 실 백엔드 미가용 시 SKIP 사유 기록.

---

## Phase 3 — DB Migration + Entity

**목표:** match 상태 컬럼과 snapshot 테이블을 추가. 기존 데이터 무손실.

### Task 3.1: NaverMatchStatus enum + 컬럼 마이그레이션

**Files:**
- Create: `docs/migrations/2026-05-30-naver-match-status.sql`
- Create: `src/main/java/sweetgirl/croissant/store/enums/NaverMatchStatus.java`
- Modify: `src/main/java/sweetgirl/croissant/store/entity/Store.java`
- Modify: `src/test/java/sweetgirl/croissant/admin/service/AdminStoreServiceTest.java` (기존 매처 깨질 수 있음)

- [ ] **Step 3.1.1**: Migration SQL 작성.

  Create `docs/migrations/2026-05-30-naver-match-status.sql`:
  ```sql
  -- 2026-05-30 — Store 에 naver_match_status / naver_match_error 추가
  -- 사용처: Match / Crawl / Apply 분리 구조에서 store 의 매칭 상태를 명시적으로 트래킹.
  -- 적용 방법:
  --   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-05-30-naver-match-status.sql
  -- 적용 후 backend 재기동 — ddl-auto=validate 통과 확인.

  ALTER TABLE store
      ADD COLUMN IF NOT EXISTS naver_match_status VARCHAR(20),
      ADD COLUMN IF NOT EXISTS naver_match_error  VARCHAR(500);

  -- 기존 데이터 backfill
  UPDATE store
     SET naver_match_status = CASE
       WHEN naver_place_id IS NOT NULL THEN 'MATCHED'
       WHEN naver_matched_at IS NOT NULL THEN 'FAILED'
       ELSE 'PENDING'
     END
   WHERE naver_match_status IS NULL;

  ALTER TABLE store
      ALTER COLUMN naver_match_status SET NOT NULL,
      ALTER COLUMN naver_match_status SET DEFAULT 'PENDING';

  CREATE INDEX IF NOT EXISTS idx_store_naver_match_status
      ON store (naver_match_status);
  ```

- [ ] **Step 3.1.2**: enum 추가.

  Create `src/main/java/sweetgirl/croissant/store/enums/NaverMatchStatus.java`:
  ```java
  package sweetgirl.croissant.store.enums;

  public enum NaverMatchStatus {
      PENDING,
      MATCHED,
      NEEDS_REVIEW,
      FAILED
  }
  ```

- [ ] **Step 3.1.3**: Store entity 에 컬럼 추가 + state 전이 메소드 갱신.

  Edit `Store.java`:
  - 새 필드 추가:
    ```java
    @Enumerated(EnumType.STRING)
    @Column(name = "naver_match_status", nullable = false, length = 20)
    private NaverMatchStatus naverMatchStatus = NaverMatchStatus.PENDING;

    @Column(name = "naver_match_error", length = 500)
    private String naverMatchError;
    ```
  - `markNaverMatched`: `this.naverMatchStatus = NaverMatchStatus.MATCHED; this.naverMatchError = null;` 추가.
  - `markNaverEnrichFailed(String reason)` 시그니처로 확장 (기존 호출지 모두 갱신):
    ```java
    public void markNaverEnrichFailed(String reason) {
        this.naverMatchedAt = Instant.now();
        this.naverMatchStatus = NaverMatchStatus.FAILED;
        this.naverMatchError = reason;
    }
    ```
    호환을 위해 기존 `markNaverEnrichFailed()` no-arg 도 유지하고 내부에서 `markNaverEnrichFailed(null)` 호출.
  - `clearNaverMatch`: `this.naverMatchStatus = NaverMatchStatus.PENDING; this.naverMatchError = null;` 추가.

- [ ] **Step 3.1.4**: Migration 수동 실행 — 사용자가 dev DB 에 SQL 적용.

  사용자에게 알림:
  > **이 단계는 사용자가 직접 실행해야 합니다:**
  > ```
  > psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-05-30-naver-match-status.sql
  > ```

- [ ] **Step 3.1.5**: 백엔드 재기동 — `ddl-auto=validate` 통과 확인.
  ```bash
  ./gradlew bootRun
  ```
  schema 검증 fail 시 entity 정의 ↔ 컬럼 타입 mismatch 디버깅.

- [ ] **Step 3.1.6**: 단위 테스트 - status 전이 검증.

  Edit (or create) `src/test/java/sweetgirl/croissant/store/entity/StoreNaverMatchStatusTest.java`:
  ```java
  @Test
  @DisplayName("markNaverMatched 호출 시 status 가 MATCHED, error 가 null 로 설정")
  void markNaverMatched_setsStatusAndClearsError() {
      Store store = Store.builder()...build();
      store.markNaverEnrichFailed("prior failure");
      store.markNaverMatched("naverid123");
      assertThat(store.getNaverMatchStatus()).isEqualTo(NaverMatchStatus.MATCHED);
      assertThat(store.getNaverMatchError()).isNull();
  }
  ```

- [ ] **Step 3.1.7**: Commit.
  ```bash
  git add docs/migrations/2026-05-30-naver-match-status.sql \
          src/main/java/sweetgirl/croissant/store/enums/NaverMatchStatus.java \
          src/main/java/sweetgirl/croissant/store/entity/Store.java \
          src/test/java/sweetgirl/croissant/store/entity/StoreNaverMatchStatusTest.java
  git commit -m ":sparkles: Feat: Store 에 naver_match_status 컬럼 추가 (PENDING/MATCHED/FAILED/NEEDS_REVIEW)"
  ```

### Task 3.2: NaverCrawlSnapshot 테이블 + Entity + Repository

**Files:**
- Create: `docs/migrations/2026-05-30-naver-crawl-snapshot.sql`
- Create: `src/main/java/sweetgirl/croissant/store/entity/NaverCrawlSnapshot.java`
- Create: `src/main/java/sweetgirl/croissant/store/enums/NaverCrawlStatus.java`
- Create: `src/main/java/sweetgirl/croissant/store/enums/NaverApplyStatus.java`
- Create: `src/main/java/sweetgirl/croissant/store/repository/NaverCrawlSnapshotRepository.java`

- [ ] **Step 3.2.1**: Migration SQL.
  ```sql
  -- 2026-05-30 — naver_crawl_snapshot 테이블 생성
  CREATE TABLE IF NOT EXISTS naver_crawl_snapshot (
      id                BIGSERIAL PRIMARY KEY,
      store_id          BIGINT      NOT NULL,
      naver_place_id    VARCHAR(50) NOT NULL,
      status            VARCHAR(20) NOT NULL,           -- SUCCESS | FAILED
      raw_menus_json    TEXT,                            -- snapshot.status=SUCCESS 만 채움
      raw_photo_urls_json TEXT,
      error_message     VARCHAR(1000),
      crawled_at        TIMESTAMP   NOT NULL,
      apply_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
      apply_error       VARCHAR(1000),
      applied_at        TIMESTAMP,
      created_at        TIMESTAMP   NOT NULL,
      updated_at        TIMESTAMP
  );

  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_store ON naver_crawl_snapshot (store_id);
  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_place ON naver_crawl_snapshot (naver_place_id);
  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_status ON naver_crawl_snapshot (status);
  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_apply_status ON naver_crawl_snapshot (apply_status);
  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_crawled_at ON naver_crawl_snapshot (crawled_at);
  ```

- [ ] **Step 3.2.2**: Enum.
  ```java
  // NaverCrawlStatus.java
  public enum NaverCrawlStatus { SUCCESS, FAILED }

  // NaverApplyStatus.java
  public enum NaverApplyStatus { PENDING, APPLIED, PARTIAL, FAILED }
  ```

- [ ] **Step 3.2.3**: Entity. `BaseTimeEntity` 상속.
  ```java
  @Entity
  @Table(name = "naver_crawl_snapshot")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class NaverCrawlSnapshot extends BaseTimeEntity {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      @Column(name = "store_id", nullable = false)
      private Long storeId;

      @Column(name = "naver_place_id", nullable = false, length = 50)
      private String naverPlaceId;

      @Enumerated(EnumType.STRING)
      @Column(nullable = false, length = 20)
      private NaverCrawlStatus status;

      @Column(name = "raw_menus_json", columnDefinition = "TEXT")
      private String rawMenusJson;

      @Column(name = "raw_photo_urls_json", columnDefinition = "TEXT")
      private String rawPhotoUrlsJson;

      @Column(name = "error_message", length = 1000)
      private String errorMessage;

      @Column(name = "crawled_at", nullable = false)
      private Instant crawledAt;

      @Enumerated(EnumType.STRING)
      @Column(name = "apply_status", nullable = false, length = 20)
      private NaverApplyStatus applyStatus = NaverApplyStatus.PENDING;

      @Column(name = "apply_error", length = 1000)
      private String applyError;

      @Column(name = "applied_at")
      private Instant appliedAt;

      @Builder
      public NaverCrawlSnapshot(Long storeId, String naverPlaceId, NaverCrawlStatus status,
              String rawMenusJson, String rawPhotoUrlsJson, String errorMessage, Instant crawledAt) {
          this.storeId = storeId;
          this.naverPlaceId = naverPlaceId;
          this.status = status;
          this.rawMenusJson = rawMenusJson;
          this.rawPhotoUrlsJson = rawPhotoUrlsJson;
          this.errorMessage = errorMessage;
          this.crawledAt = crawledAt;
          this.applyStatus = NaverApplyStatus.PENDING;
      }

      public void markApplied() { this.applyStatus = NaverApplyStatus.APPLIED; this.appliedAt = Instant.now(); this.applyError = null; }
      public void markPartial(String error) { this.applyStatus = NaverApplyStatus.PARTIAL; this.appliedAt = Instant.now(); this.applyError = error; }
      public void markApplyFailed(String error) { this.applyStatus = NaverApplyStatus.FAILED; this.appliedAt = Instant.now(); this.applyError = error; }
  }
  ```

- [ ] **Step 3.2.4**: Repository.
  ```java
  public interface NaverCrawlSnapshotRepository extends JpaRepository<NaverCrawlSnapshot, Long> {

      Optional<NaverCrawlSnapshot> findTopByStoreIdAndStatusOrderByCrawledAtDesc(
          Long storeId, NaverCrawlStatus status);

      // crawl-queue: naver_place_id 있는 store 중 최신 SUCCESS snapshot 없거나 FAILED snapshot 만 있는 것
      // (구체적 쿼리는 Phase 4 에서 작성)

      // apply-queue: status=SUCCESS && apply_status IN (PENDING, PARTIAL, FAILED)
      @Query("""
          SELECT s FROM NaverCrawlSnapshot s
          WHERE s.status = sweetgirl.croissant.store.enums.NaverCrawlStatus.SUCCESS
          AND s.applyStatus IN (
              sweetgirl.croissant.store.enums.NaverApplyStatus.PENDING,
              sweetgirl.croissant.store.enums.NaverApplyStatus.PARTIAL,
              sweetgirl.croissant.store.enums.NaverApplyStatus.FAILED
          )
          ORDER BY s.crawledAt ASC
      """)
      Page<NaverCrawlSnapshot> findApplyQueue(Pageable pageable);
  }
  ```

- [ ] **Step 3.2.5**: Migration 적용 — 사용자에게 알림 (Step 3.1.4 와 동일).

- [ ] **Step 3.2.6**: 백엔드 재기동 → validate 통과 확인.

- [ ] **Step 3.2.7**: Commit.
  ```bash
  git add docs/migrations/2026-05-30-naver-crawl-snapshot.sql \
          src/main/java/sweetgirl/croissant/store/enums/NaverCrawlStatus.java \
          src/main/java/sweetgirl/croissant/store/enums/NaverApplyStatus.java \
          src/main/java/sweetgirl/croissant/store/entity/NaverCrawlSnapshot.java \
          src/main/java/sweetgirl/croissant/store/repository/NaverCrawlSnapshotRepository.java
  git commit -m ":sparkles: Feat: naver_crawl_snapshot 테이블/entity/repository 추가"
  ```

---

## Phase 4 — Queue API 분리

**목표:** Match / Crawl / Apply 큐 endpoint 3개를 추가. 기존 `/naver-enrich-queue` 는 그대로 살리고 `getNaverMatchQueue` 와 동일 쿼리를 사용하게 한다.

### Task 4.1: Match queue endpoint

**Files:**
- Modify: `AdminStoreService.java` — `getNaverMatchQueue` 추가 (기존 `getNaverEnrichQueue` 와 동일 로직, 그대로 위임)
- Modify: `AdminStoreController.java` — `GET /naver-match-queue` 추가
- Test: `AdminStoreServiceTest.java` 에 케이스 추가

- [ ] **Step 4.1.1**: 실패 테스트 — `getNaverMatchQueue(pageable)` 가 `getNaverEnrichQueue(pageable)` 와 동일 결과 반환.
- [ ] **Step 4.1.2**: 구현. `AdminStoreService` 에 `getNaverMatchQueue` 추가 = 기존 호출 위임. 시그니처는 동일.
- [ ] **Step 4.1.3**: Controller endpoint 추가, `@GetMapping("/naver-match-queue")`.
- [ ] **Step 4.1.4**: 테스트 실행 PASS.
- [ ] **Step 4.1.5**: 통합 verify — `curl localhost:8080/api/admin/stores/naver-match-queue?page=0&size=5` ↔ `/naver-enrich-queue?page=0&size=5` 결과 동일.
- [ ] **Step 4.1.6**: Commit.

### Task 4.2: Crawl queue endpoint

**Files:**
- Modify: `StoreRepository.java` — `findNaverCrawlQueue` (`naverPlaceId IS NOT NULL` + 최신 SUCCESS snapshot 없음)
- Modify: `AdminStoreService.java`
- Modify: `AdminStoreController.java`

**쿼리 의도:** `Store.naverPlaceId IS NOT NULL` AND (
- 해당 store 의 NaverCrawlSnapshot 중 `status=SUCCESS` 인 row 가 하나도 없거나
- 마지막 snapshot 이 FAILED 이고 `crawled_at < retryThreshold` (예: 1일 후 재시도)
)

- [ ] **Step 4.2.1**: Repository query 작성 (JPQL):
  ```java
  @Query("""
      SELECT s FROM Store s
      WHERE s.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
      AND s.naverPlaceId IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM NaverCrawlSnapshot ns
          WHERE ns.storeId = s.id
          AND ns.status = sweetgirl.croissant.store.enums.NaverCrawlStatus.SUCCESS
      )
      ORDER BY s.id ASC
  """)
  Page<Store> findNaverCrawlQueue(Pageable pageable);
  ```
  - 단순화: SUCCESS snapshot 없는 가게만. FAILED 재시도는 Phase 7 의 retry 정책으로 분리.

- [ ] **Step 4.2.2**: Service `getNaverCrawlQueue(pageable)` 추가.
- [ ] **Step 4.2.3**: Controller endpoint 추가.
- [ ] **Step 4.2.4**: 테스트 + curl verify.
- [ ] **Step 4.2.5**: Commit.

### Task 4.3: Apply queue endpoint

**Files:**
- Modify: `AdminStoreService.java` + Controller
- Use: `NaverCrawlSnapshotRepository.findApplyQueue` (Phase 3.2 에 정의됨)

- [ ] **Step 4.3.1**: Service 메소드 `getNaverApplyQueue(pageable) -> Page<AdminNaverCrawlSnapshotResponse>`.
- [ ] **Step 4.3.2**: DTO `AdminNaverCrawlSnapshotResponse` 생성 — id, storeId, naverPlaceId, status, applyStatus, applyError, crawledAt, appliedAt.
- [ ] **Step 4.3.3**: Controller `GET /naver-apply-queue`.
- [ ] **Step 4.3.4**: 테스트 + curl verify.
- [ ] **Step 4.3.5**: Commit.

---

## Phase 5 — Crawl Snapshot 저장

**목표:** Python `crawl_store` 가 메뉴/사진 DB·S3 를 건드리지 않고 raw 결과만 `naver_crawl_snapshot` 에 저장.

### Task 5.1: Snapshot 저장 endpoint (Spring)

**Files:**
- Create: `admin/dto/request/AdminNaverCrawlSnapshotRequest.java`
- Create: `admin/service/NaverCrawlSnapshotService.java`
- Modify: `AdminStoreController.java` — `POST /{storeId}/naver-crawl-snapshots`

- [ ] **Step 5.1.1**: Request DTO. record. 필드: `naverPlaceId`, `status` (SUCCESS/FAILED), `rawMenusJson` (Map serialized), `rawPhotoUrlsJson` (List serialized), `errorMessage`.
- [ ] **Step 5.1.2**: Service:
  ```java
  @Service @RequiredArgsConstructor
  public class NaverCrawlSnapshotService {
      private final NaverCrawlSnapshotRepository repo;

      @Transactional
      public Long save(Long storeId, AdminNaverCrawlSnapshotRequest req) {
          NaverCrawlSnapshot s = NaverCrawlSnapshot.builder()
              .storeId(storeId)
              .naverPlaceId(req.naverPlaceId())
              .status(req.status())
              .rawMenusJson(req.rawMenusJson())
              .rawPhotoUrlsJson(req.rawPhotoUrlsJson())
              .errorMessage(req.errorMessage())
              .crawledAt(Instant.now())
              .build();
          return repo.save(s).getId();
      }
  }
  ```
- [ ] **Step 5.1.3**: Controller endpoint + 단위 테스트 (Mockito).
- [ ] **Step 5.1.4**: Commit.

### Task 5.2: Python `crawl_store` 본격 구현

**Files:** Modify: `scripts/naver_menu_scraper.py:crawl_store`

- [ ] **Step 5.2.1**: 실패 테스트 — `crawl_store` 가 `fetch_menu_data` / `fetch_store_photos` 호출 + `save_crawl_snapshot` 으로 POST.

  Edit `scripts/tests/test_pipeline_split.py`:
  ```python
  def test_crawl_store_saves_snapshot_no_db_writes(monkeypatch):
      calls = []
      monkeypatch.setattr("naver_menu_scraper.fetch_menu_data",
          lambda page, pid: {"소금빵": {"src": "http://x/a.jpg", "desc": None, "price": 3500}})
      monkeypatch.setattr("naver_menu_scraper.fetch_store_photos",
          lambda page, pid, limit: ["http://x/p1.jpg"])
      monkeypatch.setattr("naver_menu_scraper.create_menu",
          lambda *a, **kw: pytest.fail("crawl_store 는 메뉴를 만들면 안 됨"))
      monkeypatch.setattr("naver_menu_scraper.upload_images_from_urls",
          lambda *a, **kw: pytest.fail("crawl_store 는 사진 업로드를 하면 안 됨"))
      monkeypatch.setattr("naver_menu_scraper.save_crawl_snapshot",
          lambda server, sid, **payload: calls.append((sid, payload)) or 99)

      page = MagicMock()
      store = {"id": 7, "naverPlaceId": "ABC", "existingMenuCount": 0, "existingPhotoCount": 0}
      result = crawl_store(page, store, "http://localhost:8080")
      assert calls and calls[0][0] == 7
      assert calls[0][1]["status"] == "SUCCESS"
      assert "소금빵" in calls[0][1]["raw_menus_json"]
  ```

- [ ] **Step 5.2.2**: `save_crawl_snapshot(server, store_id, **payload) -> Optional[int]` 추가 — `POST /api/admin/stores/{id}/naver-crawl-snapshots`.

- [ ] **Step 5.2.3**: `crawl_store` 재작성 — 더 이상 `_enrich_with_place_id` 호출하지 않음:
  ```python
  def crawl_store(page, store: dict, server: str) -> dict:
      stats = {"matched": 0, "menus_created": 0, "images_uploaded": 0,
               "skipped_menu_crawl": 0, "photos_uploaded": 0, "skipped_photo_crawl": 0,
               "failed": 0, "snapshot_id": None}
      naver_id = store.get("naverPlaceId")
      if not naver_id:
          stats["failed"] = 1
          return stats
      try:
          menus = fetch_menu_data(page, naver_id) or {}
          photos = fetch_store_photos(page, naver_id, limit=STORE_PHOTO_LIMIT) or []
          snap_id = save_crawl_snapshot(server, store["id"],
              naver_place_id=naver_id,
              status="SUCCESS",
              raw_menus_json=json.dumps(menus, ensure_ascii=False),
              raw_photo_urls_json=json.dumps(photos, ensure_ascii=False),
              error_message=None)
          stats["matched"] = 1
          stats["snapshot_id"] = snap_id
      except Exception as e:
          save_crawl_snapshot(server, store["id"],
              naver_place_id=naver_id,
              status="FAILED",
              raw_menus_json=None,
              raw_photo_urls_json=None,
              error_message=str(e)[:1000])
          stats["failed"] = 1
      return stats
  ```

- [ ] **Step 5.2.4**: 테스트 PASS. Commit.

### Task 5.3: process_store 의 crawl 단계는 그대로 둠

기존 `process_store` 는 매칭 → save_place_id → `_enrich_with_place_id`(메뉴/사진 직접 DB 반영) 로 유지. 새 분리 흐름은 `--crawl-only` / `--apply-only` 로만 작동. Phase 6 가 끝나면 Phase 8 에서 `--pipeline` 도 내부적으로 match → crawl → apply 로 갈아끼움.

---

## Phase 6 — Apply Job 구현

**목표:** snapshot → 메뉴/사진 DB 반영. 같은 snapshot 을 N번 apply 해도 중복 없음.

### Task 6.1: Apply service (Spring)

**Files:**
- Create: `admin/service/NaverApplyService.java`
- Modify: `AdminStoreController.java` — `POST /naver-crawl-snapshots/{snapshotId}/apply`
- Test: `src/test/java/.../service/NaverApplyServiceTest.java`

- [ ] **Step 6.1.1**: 실패 테스트 — `applySnapshot(snapshotId)` 호출 시 같은 메뉴를 두 번 만들지 않음.

  Use mocks for `AdminMenuService`, `AdminImageService`, `NaverCrawlSnapshotRepository`. Snapshot 의 `rawMenusJson` 에 `{"소금빵": {...}}`, store 에 이미 활성 "소금빵" 메뉴 있음 → `AdminMenuService.createMenu` 호출되어도 idempotency 로 동일 menuId 반환 → 결과 신규 생성 0개, applyStatus=APPLIED.

- [ ] **Step 6.1.2**: 구현:
  ```java
  @Service @RequiredArgsConstructor
  public class NaverApplyService {
      private final NaverCrawlSnapshotRepository snapshotRepo;
      private final AdminMenuService adminMenuService;
      private final AdminImageService adminImageService;
      private final ObjectMapper objectMapper;

      @Transactional
      public ApplyResult applySnapshot(Long snapshotId) {
          NaverCrawlSnapshot s = snapshotRepo.findById(snapshotId)
              .orElseThrow(() -> new StoreException(StoreErrorCode.SNAPSHOT_NOT_FOUND));
          if (s.getStatus() != NaverCrawlStatus.SUCCESS) {
              s.markApplyFailed("snapshot status=" + s.getStatus());
              return ApplyResult.failed(...);
          }

          int menuFail = 0, photoFail = 0;
          // 메뉴: AdminMenuService.createMenu 가 normalize 매칭 시 기존 반환 → idempotent.
          Map<String, MenuPayload> menus = parseMenus(s.getRawMenusJson());
          List<Long> createdMenuIds = new ArrayList<>();
          for (var entry : menus.entrySet()) {
              try {
                  AdminMenuResponse resp = adminMenuService.createMenu(
                      s.getStoreId(),
                      new AdminMenuCreateRequest(entry.getKey(),
                          entry.getValue().price != null ? entry.getValue().price + "원" : null,
                          entry.getValue().desc,
                          guessCategory(entry.getKey()),  // null 도 OK
                          "active"));
                  createdMenuIds.add(resp.getId());
                  // 메뉴 이미지: src 있으면 from-url 호출 (skip dedup 은 이미지 service 가 처리)
                  if (entry.getValue().src != null) {
                      adminImageService.uploadImagesFromUrls("menus", resp.getId(),
                          List.of(new ImageUploadFromUrlRequest(entry.getValue().src, null)));
                  }
              } catch (Exception e) { menuFail++; }
          }

          // 가게 사진
          List<String> photoUrls = parsePhotos(s.getRawPhotoUrlsJson());
          List<ImageUploadFromUrlRequest> reqs = photoUrls.stream()
              .map(u -> new ImageUploadFromUrlRequest(u, null))
              .toList();
          if (!reqs.isEmpty()) {
              ImageUploadFromUrlResponse r = adminImageService.uploadImagesFromUrls(
                  "stores", s.getStoreId(), reqs);
              photoFail = r.failureCount();
          }

          if (menuFail == 0 && photoFail == 0) s.markApplied();
          else if (menuFail < menus.size() || photoFail < photoUrls.size()) {
              s.markPartial(menuFail + " menu, " + photoFail + " photo failed");
          } else s.markApplyFailed("all failed");

          return new ApplyResult(s.getApplyStatus(), menus.size() - menuFail, photoUrls.size() - photoFail);
      }
  }
  ```

- [ ] **Step 6.1.3**: 테스트 PASS — 두 번 apply 해도 메뉴 신규 생성 0개 검증.
- [ ] **Step 6.1.4**: Controller endpoint 추가 + 단위 테스트.
- [ ] **Step 6.1.5**: Commit.

### Task 6.2: Python `apply_store_snapshot` 본격 구현

**Files:** Modify `scripts/naver_menu_scraper.py:apply_store_snapshot`

- [ ] **Step 6.2.1**: 단순히 `POST /api/admin/naver-crawl-snapshots/{id}/apply` 호출하고 결과 stats 반환. Python 측에서 직접 메뉴/사진 처리하지 않음 (Spring 이 다 함).

- [ ] **Step 6.2.2**: CLI 통합 — `--apply-only --snapshot-id <id>` 가 동작.
- [ ] **Step 6.2.3**: Commit.

### Task 6.3: 중복 방지 회귀 테스트

- [ ] **Step 6.3.1**: 통합 시나리오 (수동) — 백엔드 띄우고 같은 snapshot 으로 두 번 `--apply-only` 실행. `SELECT COUNT(*) FROM menu WHERE store_id=X` 가 같은지 확인.
- [ ] **Step 6.3.2**: 결과 노트 `docs/superpowers/specs/2026-05-29-naver-pipeline-validation.md` 에 기록.

---

## Phase 7 — Claim/Lock 병렬 처리

**Files:**
- Create: `docs/migrations/2026-05-31-naver-claim-columns.sql`
- Modify: Store entity, NaverCrawlSnapshot entity — claim 필드 추가
- Create: `admin/service/NaverJobClaimService.java`
- Modify: `AdminStoreController.java` — 3개 claim endpoint

### Task 7.1: Claim 컬럼 추가

- [ ] **Step 7.1.1**: Migration.
  ```sql
  ALTER TABLE store
      ADD COLUMN IF NOT EXISTS naver_locked_by      VARCHAR(100),
      ADD COLUMN IF NOT EXISTS naver_locked_until   TIMESTAMP,
      ADD COLUMN IF NOT EXISTS naver_attempt_count  INTEGER NOT NULL DEFAULT 0,
      ADD COLUMN IF NOT EXISTS naver_next_retry_at  TIMESTAMP;

  CREATE INDEX IF NOT EXISTS idx_store_naver_locked_until ON store (naver_locked_until);

  ALTER TABLE naver_crawl_snapshot
      ADD COLUMN IF NOT EXISTS locked_by      VARCHAR(100),
      ADD COLUMN IF NOT EXISTS locked_until   TIMESTAMP,
      ADD COLUMN IF NOT EXISTS attempt_count  INTEGER NOT NULL DEFAULT 0,
      ADD COLUMN IF NOT EXISTS next_retry_at  TIMESTAMP;

  CREATE INDEX IF NOT EXISTS idx_naver_crawl_snapshot_locked_until ON naver_crawl_snapshot (locked_until);
  ```

- [ ] **Step 7.1.2**: Entity 컬럼 추가 + `claim(workerId, lockSeconds)` / `release()` / `markRetry(after)` 메소드.

### Task 7.2: Atomic claim native query

- [ ] **Step 7.2.1**: Repository — native query 로 atomic claim:
  ```java
  // StoreRepository.java — match claim
  @Modifying
  @Query(value = """
      UPDATE store
         SET naver_locked_by = :workerId,
             naver_locked_until = now() + interval '5 minutes',
             naver_attempt_count = naver_attempt_count + 1
       WHERE id IN (
           SELECT id FROM store
            WHERE status = 'ACTIVE'
              AND naver_place_id IS NULL
              AND (naver_locked_until IS NULL OR naver_locked_until < now())
              AND (naver_next_retry_at IS NULL OR naver_next_retry_at < now())
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
       )
       RETURNING id
  """, nativeQuery = true)
  List<Long> claimMatchJobs(@Param("workerId") String workerId, @Param("limit") int limit);
  ```
  PostgreSQL `FOR UPDATE SKIP LOCKED` + `RETURNING id` 가 atomic claim 의 핵심.

- [ ] **Step 7.2.2**: 같은 패턴으로 `claimCrawlJobs` (Store), `claimApplyJobs` (NaverCrawlSnapshot) 추가.

### Task 7.3: NaverJobClaimService + Controller

- [ ] **Step 7.3.1**: Service — `claim(JobType.MATCH, workerId, limit)` → `List<Long>` 반환.
- [ ] **Step 7.3.2**: Request DTO `AdminClaimRequest(String workerId, int limit)`.
- [ ] **Step 7.3.3**: Controller 3개 endpoint — `POST /naver-match-jobs/claim`, `/naver-crawl-jobs/claim`, `/naver-apply-jobs/claim`.

### Task 7.4: 동시성 테스트

- [ ] **Step 7.4.1**: `@DataJpaTest` 또는 `@SpringBootTest` 로 ExecutorService 두 개에서 동시에 `claimMatchJobs` 호출 → 반환 id 의 union 이 disjoint 검증.
- [ ] **Step 7.4.2**: lock 만료 후 재claim 검증 (`Thread.sleep` 대신 SQL `update set locked_until = now() - interval '1 min'` 으로 강제 만료).
- [ ] **Step 7.4.3**: Commit.

---

## Phase 8 — Admin UX 연결

**목표:** 기존 [Enrich] 버튼 + 새 pipeline 흐름 호환. BE 만 변경.

### Task 8.1: NaverEnrichRunner 를 pipeline 으로 갱신

**Files:** Modify `NaverEnrichRunner.java`

- [ ] **Step 8.1.1**: Python subprocess 호출 시 `--pipeline` 명시. 동작은 동일하므로 회귀 없음.
- [ ] **Step 8.1.2**: 추가로 `runMatchOnly(storeId)` / `runCrawlOnly(storeId)` / `runApplyOnly(snapshotId)` 메소드 추가 — 각각 `--match-only` / `--crawl-only` / `--apply-only` 로 subprocess 실행. 어드민이 단계별 실행 UI 를 만들 수 있게.
- [ ] **Step 8.1.3**: Controller endpoint — `POST /{storeId}/naver-match-now`, `/{storeId}/naver-crawl-now`, `/naver-crawl-snapshots/{snapshotId}/apply-now`.
- [ ] **Step 8.1.4**: 기존 `naver-enrich-now` 는 그대로 유지 (FE 호환).

---

## Phase 9 — 통합 검증

### Task 9.1: 핵심 안전 케이스 단위 테스트

- [ ] save 실패 → enrich 안 됨 (Python 단위)
- [ ] 409 충돌 → 메뉴/사진 안 붙음 (Python 단위)
- [ ] match-only → 메뉴/사진 DB unchanged (수동/통합)
- [ ] crawl-only → snapshot 만 생성 (수동/통합)
- [ ] apply-only 2회 → 중복 0개 (Spring 단위 + 수동)
- [ ] photo metadata 저장 실패 → PARTIAL (Spring 단위)
- [ ] apply 성공 → APPLIED + appliedAt set (Spring 단위)
- [ ] 동시 claim → 중복 없음 (Spring 단위 동시성)
- [ ] lock 만료 후 재시도 가능 (Spring 단위)
- [ ] enrich-now 버튼 → pipeline 으로 정상 (수동)
- [ ] /naver-enrich-queue 깨지지 않음 (수동 + 단위)

### Task 9.2: 통합 검증 문서

- [ ] `docs/superpowers/specs/2026-05-29-naver-pipeline-validation.md` 에 각 시나리오 실행 결과 (PASS/FAIL/SKIP 사유) 기록.

### Task 9.3: 최종 정리

- [ ] `./gradlew test` 전체 PASS 확인
- [ ] `./gradlew spotlessApply checkstyleMain` 통과
- [ ] `cd scripts && python -m pytest tests/ -v` PASS
- [ ] PR 본문 작성 — 변경 요약 + 마이그레이션 적용 순서

---

## Self-Review 결과

**Spec coverage:**
- Phase 1 → 코드상 already-implemented, plan 에 Phase 0 으로 진입 검증만 포함 ✅
- Phase 2 (Python 분리) → Task 2.1/2.2/2.3 ✅
- Phase 3 (Migration) → Task 3.1/3.2 ✅
- Phase 4 (Queue 분리) → Task 4.1/4.2/4.3 ✅
- Phase 5 (Snapshot 저장) → Task 5.1/5.2/5.3 ✅
- Phase 6 (Apply) → Task 6.1/6.2/6.3 ✅
- Phase 7 (Claim/Lock) → Task 7.1/7.2/7.3/7.4 ✅
- Phase 8 (Admin UX) → Task 8.1 ✅
- Phase 9 (검증) → Task 9.1/9.2/9.3 ✅

**Placeholder scan:** 일부 의도적 TODO 유지 — `crawl_store` 의 Phase 5 까지 wrapper 인 점, NaverEnrichRunner 의 stats 계산 (기존 그대로), `guessCategory` 호출 등은 상세 구현이 코드 인용으로 명시됨.

**Type consistency:** `NaverMatchStatus`, `NaverCrawlStatus`, `NaverApplyStatus` 모두 enum, 동일 이름으로 service/entity/repository 에서 사용. `applyStatus` / `applyError` / `appliedAt` 컬럼명·필드명 일관.

---

## 진행 순서 권장

각 Phase 가 끝날 때마다:
1. `./gradlew test` 전체 PASS
2. 사용자에게 결과 보고 + 다음 Phase 진행 confirm

Phase 3/Phase 7 의 migration 은 **사용자 수동 적용** 단계가 있으므로 반드시 멈춰서 사용자 확인 필요.
