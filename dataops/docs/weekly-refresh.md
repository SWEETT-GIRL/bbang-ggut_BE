# 주간 최신화 운영 (집 윈도우 노트북)

작성 2026-09-27 · 스크립트: `scripts/weekly_refresh.py` · `scripts/run_nightly.ps1` · `scripts/register_nightly_task.ps1`

## 1. 무엇이 언제 도나

| 언제 | 무엇 | 운영 쓰기 |
|---|---|---|
| 매일 03:00 (최대 6시간) | 네이버 수집 주기(7일)가 된 가게만 재수집 → 관측·신호 갱신 → 변경이면 스냅샷 저장·재판정(제안 생성) | 없음 |
| 일요일, 그날 수집이 끝난 뒤 | 지난 반영 뒤로 새로 생긴 제안 중 **채움·메뉴 추가 자동 승인** → 그 가게들 **사진 전송** → 보고서 | 있음(서버 조건부 쓰기 · 본 앱 이미지 API) |

- 한 주 수집량은 약 5만 곳(곳당 약 2초 → 28시간)이라 매일 나눠 돈다. 가게마다 한 주에 한 번씩 최신화되는 것은 같다.
- 영업시간 변경 감지는 정기 요일 기억 비교(해시 3세대)라, 날짜 붙은 임시 일정 때문에 스냅샷이 쌓이지 않는다 — [설계](./hours-change-detection-design.md).

## 2. 자동 반영 범위 (2026-09-27 사용자 결정)

자동 승인 — 그 주에 **새로 생긴** 제안만(오래 쌓인 대기분을 한꺼번에 승인하지 않게):
- 채움: 운영에 값이 없던 영업시간(7요일이 다 있는 것만, 24시간 제외) · 인스타 · 전화 — `approve_fill_pending.py`
  - 7요일만인 이유: BE 는 빠진 요일을 휴무로 본다(`StoreBusinessHourSyncService`). 명절 주 스냅샷은 날짜 붙은 요일이 빠진다
  - 24시간 제외: 주문 제작 케이크 가게의 "매일 00~24" 는 연락 가능 시간인 경우가 많다
- 메뉴 추가: 정크 규칙(음료·옵션·식사·문의·굿즈)으로 반려한 뒤 나머지 승인 — `approve_menu_add_pending.py`, 규칙 `menu_add_junk_rules.py`
  - 사용자 등록 메뉴와 이름이 겹치면 서버가 FAILED(운영 미반영)로 확정한다
- 사진: 이번에 승인된 가게의 메뉴·가게 사진(허용 호스트 ldb-phinf, 업체 등록 사진만) — `image_backfill_worker.py`

사람 검토 — 보고서의 "사람 검토 대기" 표에 모은다: 영업시간 갱신·부분 요일, 메뉴 삭제, 메뉴 가격·설명·분류 변경,
이름·전화·주소 변경, 규칙 밖 메뉴 추가.

## 3. 윈도우 노트북 준비 (한 번)

1. **레포와 .env** — 이 레포를 받고 `.env` 에 `PROD_DB_URL` · `PROD_DB_USERNAME` · `PROD_DB_PASSWORD`(읽기 계정) ·
   `DATAOPS_WRITER_PASSWORD`(쓰기 계정 `dataops_writer`) 를 넣는다. 값은 어디에도 출력하지 않는다.
2. **dataops DB** — 오라클 무료 인스턴스에 올린다(2026-09-27 결정). 수집은 노트북(가정용 IP)에서 돌고 DB 만 원격이다.
   - PostgreSQL 포트를 인터넷에 열지 않는다. 노트북에서 오라클 VM 으로 SSH 터널을 두어 `localhost:15434` 로 붙인다 —
     그러면 스크립트 기본값(`DATAOPS_DB_HOST=localhost`, `DATAOPS_DB_PORT=15434`)을 그대로 쓴다.
     터널 대신 직접 붙이면 `run_nightly.ps1 -DbHost <주소>` 와 VM 방화벽·SSL 을 따로 챙긴다.
   - 사용자·DB 이름은 `dataops`/`dataops` 관례(`local_db.py`). 다르면 `DATAOPS_DB_USER` 등 환경변수로 준다.
   - 스크립트는 로컬 DB 컨테이너가 없어도 돈다(이미지 워커도 2026-09-27부터 도커 psql 대신 직접 접속).
3. **DB 옮기기** — 지금 DB 는 이 Mac 의 도커에 있다.
   - Mac: `docker exec bbanggut-dataops-db pg_dump -U dataops -Fc dataops > dataops_YYYYMMDD.dump`
   - 오라클 VM 으로 옮긴 뒤: `createdb -U dataops dataops` (없으면) →
     `pg_restore -U dataops -d dataops --clean --if-exists --no-owner dataops_YYYYMMDD.dump`
   - 서버를 한 번 띄우면 Flyway 가 스키마 버전을 확인한다(`flyway_schema_history` 가 덤프에 들어 있다).
   - 옮긴 뒤에는 Mac 쪽에서 수집·승인을 돌리지 않는다(두 DB 가 갈라진다).
4. **운영 DB 터널** — 127.0.0.1:15432. 로그인하면 뜨고 끊기면 다시 붙게(예: OpenSSH `ServerAliveInterval` + 작업 스케줄러
   "로그온 시" 재시작 반복). 터널은 사람이 설정한다 — 스크립트는 열려 있는지만 보고, 닫혀 있으면 그날 실행을 건너뛰고 보고서에 남긴다.
5. **파이썬** — `requests`, `psycopg2-binary`. **JDK** — `gradlew` 가 쓰는 버전.
6. **전원** — 절전 해제 타이머 허용, 덮개를 닫아도 절전 안 함, 윈도우 업데이트 재부팅 시간을 03~10시 밖으로.
7. **반영 기준 시각** — `python scripts\weekly_refresh.py init` (지금 시각). 이 뒤에 새로 생긴 제안만 자동 반영한다. 기준이 없으면 수집만 하고 반영은 하지 않는다 — 오래 쌓인 대기 제안을 한꺼번에 승인하지 않게.
8. **등록** — `powershell -ExecutionPolicy Bypass -File scripts\register_nightly_task.ps1` (시각 바꾸기: `-At 02:30`).
9. **첫 확인** — 작업 스케줄러에서 한 번 수동 실행하거나
   `powershell -ExecutionPolicy Bypass -File scripts\run_nightly.ps1 -CrawlHours 0.05` 로 짧게 돌리고 보고서를 본다.
   일요일 반영만 먼저 보려면 서버를 쓰기 계정으로 띄운 뒤 `python scripts\weekly_refresh.py apply --dry-run`.

## 4. 보고서와 로그

- `dataops-logs/weekly/report_YYYYMMDD_HHMM_nightly.md` — 기간·수집 수치·자동 반영 단계별 결과·승인 표·사람 검토 대기 표
- `dataops-logs/weekly/weekly_refresh.log` — 하위 스크립트 출력 전체, `run_nightly.log` — 래퍼 단계
- `dataops-logs/weekly/state.json` — 마지막 반영 시각(`last_apply_at`). 반영 단계가 하나라도 실패하면 갱신하지 않아 다음 실행이 같은 기간을 이어서 본다

## 5. 문제 해결

| 증상 | 확인 |
|---|---|
| 보고서 "문제"에 터널 닫힘 | 터널 서비스 상태. 연 뒤 `run_nightly.ps1 -ForceApply` 로 다시 돌릴 수 있다 |
| 서버가 이미 떠 있어 반영을 건너뜀 | 다른 서버(run_loop 등)를 내리고 다시. 자격을 알 수 없는 서버로는 반영하지 않는다 |
| 수집 워커 종료 코드 3 | 네이버 차단 쿨다운 소진 — 다음 날 이어서 돈다. 반영은 모인 것으로 진행한다 |
| 메뉴 추가에 음료가 섞임 | `menu_add_junk_rules.py` 규칙과 `scripts/tests/test_menu_add_junk_rules.py` 에 사례를 더한다 |
