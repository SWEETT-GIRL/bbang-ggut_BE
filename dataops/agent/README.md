# 외부 응답 구조 변경 에이전트

현재 구현은 Sonnet이 제한된 도구를 선택해 현재 파서 또는 구조 프로필을 수정하고,
독립 검증을 거친 결과를 GitHub에서 검토하도록 제공합니다. 승인·배포·재처리·데이터
반영은 서로 다른 권한과 원장으로 구분합니다. 아직 상시 운영을 활성화하지 않았습니다.

## 현재 파서 경로

- 입력: 검증된 현재 소스/JAR manifest와 수집된 `response_incident`.
- 자동 자료 구성: 완전한 메뉴 목록에서 고정 원화 가격의 의미는 유지되고 엔티티 타입명이
  바뀐 경우. 원본 ID·가게명·이미지 URL·자유 문장은 모델에 전달하지 않습니다.
- 의미 불명, 범위를 벗어난 캐시 메뉴, 인라인 메뉴, 불완전 목록은 보류합니다.
- 도구: 사건 읽기, 코드 검색, 허용 파일 읽기, 패치 제안, 검증, 분석 종료.
  Sonnet이 호출 순서를 선택합니다. 모델에 shell·SQL·승인·배포 도구는 없습니다.
- 편집: `NaverPlaceParser.java`와 구조 프로필 JSON. 고정 테스트와 의미 계약은 보호합니다.
- 검증: 실제 JAR의 파서 계열 class/내부 class와 격리 재컴파일 결과의 바이트 일치,
  manifest에 고정한 기존 Java 회귀, 정상·신규·완전성 반례, 모델 생성 테스트를 별도로 검사합니다.
  JAR 전체의 출처나 현재 실행 서버의 신원까지 증명하는 manifest는 아닙니다.

현재 manifest를 만드는 작업은 신뢰된 운영자 코드입니다. Docker 이미지·의존성을 준비한
뒤 `current_manifest.freeze_deployment()`로 소스와 실제 빌드 JAR를 고정하고, 반환된
`manifest_digest`를 별도의 신뢰 설정에 보관합니다. 전달받은 JSON에 적힌 digest를 그대로
신뢰하거나 검증을 생략하지 않습니다.

```sh
# 읽기 전용 미리 보기. API 호출 없음.
.venv-agent/bin/python -m agent.response_change.worker_cli \
  --deployment-manifest /absolute/path/manifest.json --manifest-digest TRUSTED_SHA256

# 한 사건만 처리. Sonnet만 사용하며 SDK 자동 재시도 없음.
.venv-agent/bin/python -m agent.response_change.worker_cli \
  --deployment-manifest /absolute/path/manifest.json --manifest-digest TRUSTED_SHA256 \
  --live --budget-usd 0.50
```

`AGENT_DISPATCH_DSN`과 `ANTHROPIC_API_KEY`는 무시되는 로컬 `.env` 또는 프로세스 환경에
설정합니다. 사건별 누적 상한은 $0.50, KST 하루 상한은 $2입니다. 요청 전에 두 예산을
PostgreSQL에서 함께 예약하며 결과 불명 비용은 해제하지 않습니다. 구조 진단 signature가
다른 revision의 동일 원인 여부는 사람이 계보를 확인해야 합니다. 기존 유료 실행이 있는
DB에 새 예산 migration을 적용하면 과거 비용 대사 전에는 호출을 차단합니다.

역할은 `provisioning.py`의 명시적 권한 표로 분리합니다. worker는 원본·진단을 읽고
실행·예산 원장과 REPORT_ONLY 보고서만 기록합니다. DB의 RLS가 worker의 parser/data
manifest 발급을 막습니다. 모델은 DB 접속 정보와 권한 부여 도구를 받지 않습니다.

## GitHub 검수와 두 단계 승인

`--review-config`를 worker에 전달하면 종료 상태와 보고서 outbox를 같은 transaction에
저장합니다. 설정은 운영자가 고정한 `repository`, `base_commit`, `base_branch`,
`expires_at`, `evidence_origin`을 포함합니다. 이 경로는 현재 **REPORT_ONLY**입니다.
별도 index로 `docs/` 브랜치를 준비하므로 실행 중 checkout과 사용자 index를 바꾸지 않습니다.
게시기는 `AGENT_PUBLICATION_DSN`으로 outbox를 읽고 다음 명령으로 한 건만 전달합니다.

```sh
.venv-agent/bin/python -m agent.response_change.publication_cli \
  --repository SWEETT-GIRL/bbang-ggut_dataops_BE --live
```

보고서 저장 worker에는 review manifest/outbox의 SELECT·INSERT 권한이 필요하고, 게시기는
outbox SELECT·UPDATE와 publication SELECT·INSERT를 사용합니다. 승인 생성 권한은
인증된 GitHub 병합을 확인하는 신뢰 controller에만 부여하고, apply 역할은 승인 소비에
필요한 상태 컬럼만 갱신합니다.

- `PARSER_REPAIR / DEPLOY_REPLAY_PROPOSE`: 허용된 사람이 정확한 파서 PR을 병합하면 고정
  artifact 배포·canary·저장 원본 재처리·변경 제안을 승인합니다. 운영 데이터 쓰기는 제외합니다.
- `DATA_APPLY / APPLY_EXACT_MANIFEST`: 별도 데이터 PR의 정확한 ID·전후 값만 기존 CAS 경로로
  반영하도록 승인합니다. 범위 밖 제안과 향후 만들어질 제안은 포함하지 않습니다.
- `REPORT_ONLY / NONE`: 보류·실험 결과 설명서입니다. 병합해도 실행 권한이 생기지 않습니다.

approval verifier는 repository·actor·병합 상태·head·merge tree·파일 bytes·만료를 검사합니다.
DB 원장은 승인 일회 소비와 작업 생성을 원자적으로 연결합니다. 전달 실패는 marker로
대사하며 새 모델 호출로 해결하지 않습니다. GitHub 게시 성공과 사용자 알림 수신은 따로 기록합니다.

## 현재 연결 상태와 실제 운영 경계

현재 소스/JAR 검증, 전체 서비스 registry, 실행 중 JAR·두 DB 신원 측정, Python↔Java
bridge, 전용 launchd host, candidate 준비·승인 관찰·재처리·데이터 PR 준비와 다음 기준
버전 승격을 구현하고 격리 시험했습니다. 각 단계의 완료·보류·결과 불명을 GitHub PR 댓글
outbox로 연결했습니다. 아래 [상시 운영 구성](#상시-운영-구성)에 계약과 남은 설치 검증을 설명합니다.

**이 문서 갱신 시점에는 실제 launchd 설치·기존 서버 전환·상시 스케줄 활성화 전입니다.**
실제 JAR와 격리 PostgreSQL/PostGIS의 합성 승인 시험은 운영 승인·운영 반영 결과가 아닙니다.
실제 설치 결과는 이후 설치 receipt와 PR에 별도로 남깁니다.

전용 data apply runner는 웹 서버를 열지 않고 별도 쓰기 연결을 사용합니다. 수집 서버는
`write-enabled=false`와 `PROPOSAL_ONLY`를 유지합니다. 현재 exact apply는 이름·전화·
Instagram, 메뉴 가격·설명, 영업시간 세트 교체를 지원합니다. 주소·지역·메뉴 추가/삭제·
신규 등록은 별도 조건부 adapter가 갖춰지기 전 자동 반영하지 않습니다.

영업시간 수동·agent 경로는 부모/기존 자식 행을 잠근 뒤 최신 값을 다시 읽고 하나의
transaction에서 교체합니다. FK·trigger·잠금 권한이 부족하면 쓰기 전에 보류합니다.
사용자가 serving DB의 `UPDATE(day_of_week)` 최소 권한 추가를 승인했지만, 관리자 접속이
없어 아직 적용하지 않았습니다. 동일 store×target의 APPLYING/UNKNOWN은 다른 producer의
새 제안과 승인도 차단합니다. 서로 다른 target의 중첩까지 직렬화하는 전역 잠금은 아닙니다.
CREATED replay 제안은 일반 승인으로 우회할 수 없으며 REUSED는 기존 수동 권한을 유지합니다.

## 과거 원본 재현 경로

아래는 기존 historical evaluation 사용법입니다. 현재 파서 경로와 혼동하지 않습니다.

This is a bounded, local evaluation of one real `Menu` → `PlaceMenuItem` incident.
The opt-in PostgreSQL adapter consumes `response_incident` identities, but requires
an explicitly approved, redacted historical bundle for the exact incident and
deployment identity. Unknown cases are held without model calls. It does not
approve patches, deploy parsers or write serving data. The synthetic probe remains
in `scripts/claude_tool_smoke.py`.

## Prepare

Use Python 3.12 and the pinned `scripts/requirements-agent-smoke.lock` environment.
Provide `ANTHROPIC_API_KEY` in the repository's ignored `.env` or process environment.
Only the coordinator reads that key. Never place it in fixtures or selection files.

The validator requires Docker, a locally available `eclipse-temurin:17-jdk` image,
and the exact Java dependency jars listed in `response_change/validator.py` in the
local Gradle cache. Dependency/image preparation is a trusted setup operation;
candidate execution has no network. The image ID and jar digests are pinned in an
ignored runtime manifest when first used. No host Java fallback is allowed.

`scripts/prepare_menu_agent_case.py --selection <private-selection.json>` extracts
the chosen old snapshot from the restored local audit DB and the new snapshot from
a saved sample. The private selection contains `case_id`, `parser_base_commit`,
`before_snapshot`, `after_snapshot`, `store_id`, and independently transcribed
`expected_prices`. Both observations must match the same store and menu names.
The script removes identifiers, names and original image URLs, freezes expected
results, and refuses to replace changed frozen files. Source data is not in Git.

## Execute

From the repository root:

```sh
# Reproduce the old parser failure and enqueue, without model calls.
.venv-agent/bin/python -m agent.response_change.cli \
  --case-dir artifacts/response-agent/historical-menu-case

# Explicit paid run: Sonnet only; $0.50 estimated exposure cap, no SDK retries.
.venv-agent/bin/python -m agent.response_change.cli \
  --case-dir artifacts/response-agent/historical-menu-case --live

.venv-agent/bin/python -m unittest discover -s agent/tests -v
.venv-agent/bin/python -m unittest discover -s agent/response_change/tests -v
```

The graph exposes six tools: case read, literal code search, allowed-file read,
exact parser replacement proposal with declarative tests, fixed validation, and
final analysis. Sonnet chooses among them with `tool_choice=auto`. No command,
arbitrary path, oracle edit, approval or deployment tool exists. The historical
parser is isolated from the running application; current fixed code is not model
context. Old tests are copied byte-for-byte and checked against Git source hashes.

Limits: 10 inference requests, 24 tool calls, 3 candidate patches, 4 requested
validations, 20 active minutes, 24,000 counted input tokens and 4,096 output tokens
per request. A final validation is mandatory after model completion. Generated
tests supplement 11 historical tests and 7 independently frozen cases; they cannot
replace them. Runtime stdout and exception strings are kept out of model feedback.
Oracle v2 retains those 7 cases and adds 3 known price-contract counterexamples
from subsequent review. They are regression cases, not a fresh unseen evaluation.

## State and review

The PostgreSQL path can be checked with a temporary database and a real saved
historical source without enabling a recurring worker:

```sh
# Dry run: Docker baseline + PostgreSQL handoff, no model call.
.venv-agent/bin/python -m scripts.run_response_agent_pg_replay \
  --case-dir artifacts/response-agent/historical-menu-case-v2 \
  --saved-sample artifacts/response-agent/preflight/saved-sample.jsonl \
  --output artifacts/response-agent/new-dry-run --budget 0.50
# Add --live only for an explicitly authorized paid evaluation in a fresh output.
RUN_PG_AGENT_TESTS=true .venv-agent/bin/python -m unittest \
  agent.response_change.tests.test_dispatch -v
```

The local SQLite inbox atomically claims each parser/contract/normalized-shape
incident once. Repeated delivery returns the existing run with zero additional API
calls. This older SQLite evaluation entry point leaves a crash `RUNNING` for manual
reconciliation. Never delete/reset the inbox to retry a paid run.

`worker_cli` is the separate PostgreSQL entry point. It defaults to read-only
preview and requires `--live --budget-usd 0.50` to consume at most one incident.
`AGENT_DISPATCH_DSN` must use a dedicated role with SELECT on `response_capture`,
`response_incident`, `response_incident_sample` and SELECT/INSERT/UPDATE on
`response_agent_run`. Apply the new migration through the usual trusted deployment
path. The worker cannot grant privileges or run migrations.

The operator-owned catalog has `deployment_parser_digest`, `contract_version` and
`entries` containing `fingerprint`, `case_dir`, `manifest_sha256` and
`capture_payload_digests`. `case_dir` is relative to that catalog. The deployment
identity is the deployed Java class digest, separate from the historical parser
source digest. A changed binding requires explicit review.

The PostgreSQL journal uses leased claims and fencing epochs. Reservations commit
before each paid call. It stores serialized graph messages, tool cursors, evidence,
patch state, limits and usage together with the domain state; it does not use a
separate LangGraph checkpointer. Completed boundaries resume without repeating
paid responses or successful tools. Unknown paid outcomes, interrupted tool side
effects, changed runtime/inputs and missing artifacts require review. Terminal
states are never automatically requeued. No recurring schedule is installed.
Final review files are regenerated from PostgreSQL into `dispatch-epoch-N`; use
the returned report path. A former owner may leave a stale working `report.json`,
but it cannot replace the new epoch's final export.

`AWAITING_REVIEW` means fixed validation passed, not human approval. A later review
can use `review.hold_candidate` to bind failed independent evidence to that exact
source digest and leave the incident `HELD`. This function is not exposed to the
model. The original graph report is retained and the later rejection is recorded
separately. No automatic paid retry follows a review rejection.

Docker uses an offline network, read-only root/source/dependencies, an unprivileged
UID, no capabilities, no host secrets/socket, resource/time/output limits and a
temporary work directory. A narrow source capability guard blocks straightforward
I/O, reflection and output forgery before execution. It is defense in depth, not a
formal proof against malicious Java. Hidden expectations are compared outside
the container; a finite test set is not proof of general parser correctness.

See [the actual replay result](../docs/agent-historical-replay-20261014.md) for the
first paid run and the subsequently discovered contract violation. The generated
patch was held; it was not applied to the current parser.


## PR review and rollout boundary

The Java menu-removal gate and raw reception/assessment now share a per-store
PostgreSQL transaction advisory lock. Approval holds an independent connection
from evidence check through serving DML and settlement, so it does not exhaust the
JPA connection pool. Contention fails immediately. Known connection loss is checked
before writing. All writers must run this protocol; it does not provide distributed
atomicity between DataOps and the separate serving database. Writer-disabled
collection is not an operational approval test. This PR remains a draft.

The capture spool counts quota once per exclusive batch and updates byte totals
after durable replacement. Failure invalidates the cache, and all public writers
honor the lock. ACK preserves raw evidence. A 45,000-file synthetic benchmark
measures this storage operation only. Incident/day budgets are fixed at $0.50/$2. Retention/archive policy, HELD
retry timing and actual unattended activation remain rollout checks.

See [follow-up verification](../docs/agent-followup-verification-20261014.md) for
the revised Sonnet candidate, real PostgreSQL recovery tests, storage benchmark
and separate 100-store collection results. The live collection itself made no
model calls and applied no serving changes.

## 상시 운영 구성

`full_registry.py`는 정확한 Git commit을 offline `test bootJar`로 빌드하고,
실제 runtime·복원 근거를 별도로 확인한 뒤 서비스 기준으로 등록합니다. 후보 검증은 보호된
JAR 항목, 실제 Spring parser 구성, 최대 100개 전체 원본의 독립 `ParsedPlace` 기대값을
검사합니다. canary는 승인 대상 중 최대 20개입니다. 후보가 입력 JsonNode를 바꾸면 보류하며,
보호 코드나 harness가 바뀌면 과거 검증 보고서를 새 버전의 근거로 재사용하지 않습니다.

`candidate_preparation.py`와 `orchestration.py`는 완료된 분석을 고정 후보 검증·
PARSER_REPAIR PR 준비로 연결하고, 인증된 병합 이후 배포·canary·replay·DATA_APPLY PR
준비를 진행합니다. 독립 oracle이 없거나 지원 범위를 벗어난 사건은 보류합니다. 모델이나
후보 출력으로 정답을 만들어 통과시키지 않습니다. `REPORT_ONLY`는 계속 승인 권한이 없습니다.

`baseline_promotion.py`는 성공한 ACTIVE release의 소비 승인, 정확한 merge source,
실행 JAR, 원본 재처리 결과를 검증하고 같은 후보 JAR을 다음 기준으로 승격합니다. 소스와
실제 클래스의 일치도 다시 검증합니다. 별도 배포 없이 generation에 묶인 기준 포인터를
원자적으로 바꾸며, worker와 다음 후보 준비는 이 포인터와 실제 ACTIVE runtime을 대조합니다.

`REPLAY_HELD`는 정상 완료가 아니며 `REPLAY_REVIEW_REQUIRED`로 검토를 요청합니다.
`ROLLED_BACK`도 `BASELINE_RECOVERY_REQUIRED` 보류입니다. 새 기준 승격과 다음 사건의
분석은 중단하고, 사람이 실패 원인을 확인한 뒤 승인된 복구 절차로 기준을 다시 확정합니다.
REPLAY_HELD의 완료 영수증에 이미 성공한 PROPOSED·CREATED 항목이 있다면 그 항목만
별도 DATA_APPLY PR에서 검토할 수 있습니다. 실패·미시도·REUSED 항목을 섞거나 파서 승인만으로
쓰기 권한을 얻지 않습니다.

`status_notification.py`는 단계 원장과 같은 transaction에서 결과 알림을 기록합니다.
`status_publication_cli`는 해당 PR/head와 댓글 marker를 대조해 게시하고, 응답 유실 시
중복 댓글이나 새 모델 호출 대신 전달 결과를 확인합니다. 댓글 게시 성공과 사용자의 알림
수신은 별도 결과이며, 아직 실제 운영 종료 알림을 받았다는 근거는 없습니다.

### 전용 launchd 서비스와 예약 작업

- 상시 서버: 전용 LaunchAgent label, `KeepAlive=true`, `ThrottleInterval=10`, 전환 시
  `bootout` 후 고정 계획으로 `bootstrap`. 복구 범위는 Mac 사용자 로그인 이후입니다.
- 수집 예약: 매일 **09:00 Asia/Seoul**, 한 번에 **최대 2시간**. 한 작업의 중복 실행은
  잠금으로 차단합니다. 2시간 제한 안에서 모든 가게가 매일 수집된다고 보장하지 않습니다.
- 분석 worker·승인 orchestration·PR/결과 게시기: 각각 **60초 주기**의 제한된 작업.
  아무 사건이나 승인 변화가 없으면 유료 호출·배포·운영 쓰기를 만들지 않습니다.
- 모델: Sonnet, 동일 사건의 재시도·재개를 합쳐 **$0.50**, KST 하루 전체 **$2**.
  미확정 과금은 예약을 유지하며 Opus로 자동 대체하지 않습니다.

각 launch plan에는 private `OPEN`/`CLOSED` admission 파일이 있습니다. 초기 시작과 새
후보는 CLOSED이고, 실제 healthy identity와 DB ACTIVE/current 기록을 확인한 뒤 OPEN으로
바꿉니다. drain 전에 CLOSED를 저장하므로 프로세스가 다시 떠도 업무 요청을 받지 않습니다.
토큰 인증 loopback control/health는 이때도 확인할 수 있습니다. Java apply는 실행 전체에
공유 서비스 잠금을 잡고, 배포 controller는 배타 잠금과 fence를 사용합니다.

`service_cli.py`는 `prepare → start → reconcile`을 제공합니다. 초기 계획은
PREPARED/IN_FLIGHT/ENROLLED로 남으며 DB commit 직후 로컬 기록 전 중단도 대사합니다.
응답 없는 실행은 UNKNOWN으로 두고 PID를 추측해 종료하지 않습니다. 명시적
`--resume-absent`도 launchd가 그 job의 부재를 확인한 경우에만 시작을 재시도합니다.

```sh
# 개인 설정과 full-build receipt를 대조해 계획만 준비합니다. DB 연결/기동 없음.
.venv-agent/bin/python -m agent.response_change.service_cli \
  --configuration /absolute/private/service.json prepare \
  --runtime /absolute/private/initial-runtime.json --build-receipt TRUSTED_BUILD_SHA256

# 준비한 계획 확인. 실제 설치/시작은 별도 start --live 단계입니다.
.venv-agent/bin/python -m agent.response_change.service_cli \
  --configuration /absolute/private/service.json show
```

### 고정 코드와 private 설정

[service 설정 예시](config/service.example.json), [서버 설정 예시](config/server.properties.example),
[초기 runtime 예시](config/initial-runtime.example.json)의 placeholder를 운영자가 채웁니다.
설정·토큰·자격 파일은 본인 소유 0600, 상태 디렉터리는 0700, 실행 JAR는 고정 해시의 0444
파일입니다. 키·비밀번호를 Git, PR, 모델 입력, launchd plist의 문자열에 넣지 않습니다.

`job_runner.py`는 파일별 해시를 기록한 `FROZEN_CONTROLLER_SOURCE_V1` 설치 receipt를
확인하고 고정 설치본에서만 실행합니다. Git 검토 자료를 다루는 `repository_path`는 실행
코드 디렉터리와 분리하며, 사용자 작업 checkout을 되돌리지 않습니다. 작업별 private
secrets 파일은 필요한 환경 변수만 허용하고 대화형 shell의 모델 키·DB 비밀번호를 상속하지
않습니다. 분석 작업에만 Anthropic 키, 게시 작업에만 publication DSN을 전달합니다.

`provisioning.py`는 명시한 DataOps admin 연결에서 아래 여섯 LOGIN 역할을 구성합니다.
모든 역할은 NOINHERIT/NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS입니다.
기존 자격·역할·권한이 충돌하면 비밀번호를 재설정하거나 권한을 자동 확대하지 않고 보류합니다.

| 역할 | 맡는 작업 |
|---|---|
| `dataops_service` | 수집·관측·기존 제안 기록. 승인·예산·배포 원장 변경 불가 |
| `dataops_agent_worker` | 분석 실행·예산 예약, REPORT_ONLY/NONE manifest·outbox만 기록 |
| `dataops_agent_publisher` | 고정 PR·결과 알림 게시 원장. 승인 발급 불가 |
| `dataops_agent_controller` | orchestration·정확한 승인·배포·실행 연결. 원본/제안/serving 값 수정 불가 |
| `dataops_replay` | 승인된 원본 재처리 원장과 제안 생성. 운영 쓰기 불가 |
| `dataops_manifest_apply` | 별도 승인 소비·제안 점유·apply 기록. 운영 연결은 private runner 설정으로 별도 제공 |

worker의 REPORT_ONLY 제한은 DB RLS로도 검사합니다. append-only 예산 이력에 DELETE 권한을
주지 않으며, Java의 FOR SHARE/UPDATE에는 필요한 잠금용 컬럼 권한만 추가합니다.
`pg_control_system()`의 PUBLIC EXECUTE는 **DataOps DB에서만** 회수하고 측정이 필요한
service/controller/replay/apply에 부여합니다. provisioning helper는 serving DB에 연결하거나
그 DB의 권한을 변경하지 않습니다.

### 확인한 결과와 남은 실제 설치 검증

실제 JAR·격리 PostgreSQL/PostGIS에서 최소권한 controller/replay/apply로 canary→replay→
합성 2차 승인→정확한 이름 CAS 1회, 중복 추가 쓰기 0, 실행 세대 변경 시 미시도를 확인했습니다.
별도 PostgreSQL 16 권한 시험 12개는 실제 SCRAM 로그인, worker 승인 위조 거부, 원장 삭제
거부, 최소 잠금 권한, 비밀번호·권한 충돌 보류와 초기 등록 대사를 검증했습니다. 이 수치는
운영 DB 변경 건수나 자연발생 구조 변경의 자동 해결률이 아닙니다.

실제 설치 시에는 전용 경로·역할·backup 복원·migration preflight·실행 JAR를 고정한 뒤
기존 서비스 전환, live runtime 등록과 기준 포인터, 예약 작업 등록을 확인해야 합니다.
이후 실제 두 PR 승인 표본과 운영 결과 알림을 별도로 검증합니다. UNKNOWN의 실제 운영
대사 authority, HELD 재수집 간격과 보존·삭제 정책은 명시한 범위 밖의 자동 동작을 추가하지
않습니다. [검증 기록](../docs/agent-approval-workflow-verification-20261014.md)과 설치 receipt,
PR의 최신 결과를 함께 확인합니다.
