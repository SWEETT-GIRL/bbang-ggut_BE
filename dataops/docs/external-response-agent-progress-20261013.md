# 외부 응답 변경 대응 — 구현·검증 기록

2026-10-13 · P0/P1 부분 진행. Claude 에이전트 완성/운영 반영 보고가 아니다.

최신 업데이트: 이후 사용자 요청으로 [실제 100곳 수집과 전체 보류 영향 측정](./response-sample-20261013.md)을 수행했다. 정상 76·보류 24, 신규 PENDING 제안 90·운영 반영 0. 메뉴 때문에 보류된 가게 중 영업시간 보완/차이 후보는 5곳이었다. 서버는 현재 기동 중이고 신규 migration은 로컬 DB에 적용됐다. 아래 서버 미기동·원본 DB migration 미적용·수집 미실행 설명은 최초 오프라인 검증 시점의 기록이다.

후속 업데이트: 사용자의 키 설정·실호출 요청 뒤 [Sonnet 도구 루프와 Opus 1회 대조](./claude-tool-smoke-20261013.md)를 추가로 수행했다. 아래 API 0건·LangGraph 미구현 표기는 최초 P1 검증 시점의 기록이다. 현재는 합성 read-only probe만 구현·실검증했으며 전체 유지보수 agent는 미완성이다.

## 결정과 환경

- 대상: `bbang-ggut_dataops_BE`, 시작 commit `fae24e8f17c944dfe3970faa8b2b8c89975ef1e3`, 작업 branch `codex/response-change-agent`.
- 사용자는 현재 Mac 상시 실행과 첫 버전의 관측 전체 보류를 선택했다. 중요한 미결정 사항에서는 멈추고 질문한다.
- Python 3.12.14 전용 `.venv-agent`, Java 17.0.19, PostgreSQL 16.15. LangGraph/Claude 연동과 의존성 lockfile은 아직 미구현이다.
- 기존 DB 컨테이너 `bbanggut-dataops-db`, host port 15434. 8090 서버는 이 작업에서 기동하지 않았다.
- Claude API 호출 0건. $3/run은 예상 사용액이나 전체 가게 배치 가격이 아닌 사건 분석 상한 제안이었다. 단위 재질문 이후 기존 동의를 유료 호출 근거로 사용하지 않으며 pilot 예산을 다시 합의한다.

## 읽기 전용 기준선과 복원

기존 DB 조회 결과:

| 항목 | 결과 |
|---|---:|
| place_snapshot | 104,818 |
| 최신 snapshot fetched_at | 2026-09-27 17:14:48.447366 |
| menu_evidence_observation | 327 |
| 최신 메뉴 관측 시각 | 2026-09-27 17:15:08.196905 |
| proposal 전체 | 670,675 |
| APPROVED / PENDING | 564,321 / 97,202 |
| REJECTED / SUPERSEDED / FAILED | 8,884 / 267 / 1 |
| next_due_at가 현재보다 과거인 sync state | 50,747 |

마지막 수치는 ACTIVE 상태·배부 lease를 모두 적용한 실제 수집 대상 수가 아니다. 최신 snapshot 시각도 마지막 전체 수집 성공을 증명하지 않는다.

- `backups/dataops-before-response-agent-20261013.dump`: pg_dump custom 형식, 약 2.3 GB, 권한 600, Git 제외.
- `dataops-response-agent-audit`: `postgres:16-alpine`, network none, host port 미공개. `response_audit` DB에 `pg_restore --no-owner --no-privileges --exit-on-error` 성공.
- 복원 사본의 snapshot 104,818 / proposal 670,675 행 수를 원본과 대조했다. 전체 데이터의 행별 무결성 비교나 실제 재해 복구 리허설 완료를 뜻하지 않는다.
- 신규 `V20261013100000` migration은 이 사본에서 성공했다. 원본 DB에는 적용하지 않았다.

## 추가한 동작

- worker는 제출 전에 원본·관측 ID·관측 시각을 로컬 spool에 원자적으로 보존한다. 미확인 제출부터 같은 ID로 재전송한다. 서버의 capture ID/판정 receipt가 맞아야 처리 완료로 표시한다.
- HTTP 실패, 차단, Apollo marker 부재, JSON 손상, 정상 추출을 구분한다. NUL 정제 전 원본도 로컬에 보관한다. spool 용량 한도는 수집을 멈추며 기존 근거를 지우지 않는다.
- 서버 raw capture는 별도 트랜잭션으로 저장한다. 외부 판정 트랜잭션 실패 시 원본이 살아남고, 동일 ID에 다른 요청이 들어오면 충돌로 거부한다.
- 진단 이상은 전체 관측 HELD로 반환한다. submit 경로에서 메뉴 관측, hash, 영업시간 기억, 신호, reconcile에 전달하지 않는다.
- 동일 진단·파서/계약 버전의 사건과 sample을 unique 제약으로 병합한다. 알려진 null 메뉴를 신형 구조로 오인하지 않는다.
- ServingWriter 생성은 `dataops.serving.write-enabled=true` 명시가 필요하며 기본값 false다. 수집 CLI는 서버 capabilities로 원본 보존·전체 보류·쓰기 비활성을 확인한다.
- `weekly_refresh.py nightly`와 `collect-only`는 일요일에도 수집만 한다. 기존 `--apply` 결합 옵션을 거부한다. 별도 `apply`는 기존 흐름이 남아 있으며 신규 파서 승인/근거 gate 연결 전 실제 실행하지 않는다.

## 검증

| 검사 | 결과 |
|---|---|
| `bash gradlew test --no-daemon` | 328개, 실패/오류/skip 0 |
| Python spool / capture / 기존 worker identity / weekly collection | 18개 통과; HTTP/DB/시간 대기는 mock |
| DB 복원 사본의 신규 SQL migration | 성공 |
| 저장 원본 100건 offline diagnostic | READY 58 / HELD 42 |

Python 대상: `test_capture_spool.py`, `test_sync_capture.py`, `test_sync_worker_evidence.py`, `test_weekly_collection.py`.

100건은 저장된 가게별 최신 snapshot을 store ID의 md5 순서로 고른 결정적 표본이다. 무작위 대표 표본이나 현재 NAVER 응답은 아니며, 저장 snapshot 선택 편향이 있다. 외부 수집·서버 제출 없이 CLI에서 검사했다.

| 표본 판정 이유 | 건수 |
|---|---:|
| READY | 58 |
| 메뉴 완전성 근거 부족만 있음 | 40 |
| 메뉴 완전성 부족 + 파서 간 메뉴 불일치 + 해석 못한 시간 | 1 |
| 명시적 페이지 null | 1 |

42건 보류 중 현재 규칙상 모델 분석 후보는 1건이다. 이 후보의 원인이 새로운 구조 변경이라는 의미는 아니며, 분석 전 정확도를 주장하지 않는다. 나머지는 불완전 근거/명시적 부재로 보류한다. 전체 개수가 없는 옛 메뉴 배열도 완전성을 증명하지 못한다.

- 입력: `artifacts/response-agent/preflight/saved-sample.jsonl`, Git 제외·권한 600.
- 입력 SHA-256: `336d26f944e842d9de28c60d742b448bc14f8b5272ee245011d2e91692c80371`.
- 출력: 같은 디렉터리의 `diagnostic-results.jsonl`. parser digest와 원본 digest를 포함하고 raw 내용은 반복 출력하지 않는다.
- 실행: `bash gradlew diagnoseResponses --no-daemon -PdiagnosticInput=artifacts/response-agent/preflight/saved-sample.jsonl -PdiagnosticOutput=artifacts/response-agent/preflight/diagnostic-results.jsonl`.

## 진행을 멈출 결정 지점

**보류된 관측의 재수집 시각을 아직 정하지 않았다.** 현재 HELD는 raw/사건만 저장하고 sync state를 건드리지 않는다. 기존 배부 제한은 2시간이어서 due 값이 그대로면 그 이후 재배부될 수 있다. 기존 fetch 실패 처리는 10회 누적 시 ID 연결을 해제하므로 의미/파싱 보류에 재사용하면 안 된다.

제안은 성공 시각·hash·실패 카운터를 보존하면서 별도 보류 재시도를 24시간 뒤로 잡는 것이다. 대안은 검수/파서 승인까지 해당 대상 수집을 대기시키는 것이다. 사용자가 정책을 정하기 전 이 동작을 구현하거나 실제 수집/스케줄을 시작하지 않는다.

## 아직 완료하지 않은 범위

- 신구 구조 서명 비교, 선택 필드 누락·의미 불명 가격의 완전한 계약, origin/version별 정상 기준.
- marker 부재/손상 원본의 서버 사건화, 보존 기간/용량 운영 정책과 5만 곳 spool 성능 검증.
- 수동 재판정·재베이스라인 우회 경로의 진단/승인 gate. ServingWriter의 실제 서버 통합 기동 검증.
- 과거 파서 격리 재현, 인간이 확정한 기대값/독립 holdout, sandbox 도구, LangGraph/Claude 실행·평가·검수 보고서.
- 승인 파서 replay와 최신 근거 비교, 표본 100→1,000→전체 신규 수집, 운영 반영, Mac 스케줄·재부팅 복구.

기존 등록/영업시간 보완 성과를 이번 변경의 성과로 재귀속하지 않는다. 이 시점의 결과는 안전 장치 일부 구현과 오프라인 검증이다.

## 후속 개선 — 메뉴 완전성 단독 PARTIAL (2026-10-13 23:53 KST)

이 절 이전의 전체 보류 수치와 미완료 항목은 각 실행 시점의 기록이다. 메뉴 범위 개선의 구현·검증 결과는 아래에 기록한다. 이번 spec/plan 문서는 로컬에만 보관한다.

- 메뉴 완전성 단독 문제는 가게/영업시간만 처리하고 메뉴 제안·만료·기준선·신호를 보존한다. 실제 파싱 오류는 전체 HELD를 유지한다.
- 처리 완료 receipt를 동일 트랜잭션에 저장해 원장만 남은 실패를 재전송으로 복구한다. 오래된 관측/attempt는 명시적 HELD 사유로 반환한다.
- 부재 삭제 승인 시 제안과 최신 원본의 완전성·대상·메뉴 재출현을 검사한다. 수동 재판정·신호 rebuild·해시 재기준의 우회와 오래된 근거 덮어쓰기를 막았다.
- Java 366 통과, Python 167 통과/12 skip, 저장 실원본 100건 READY76/PARTIAL17/HELD7, 영업시간 제안 호출 4건, 메뉴 제안0·모의 메뉴 PENDING51/51 보존. 저장소/조회는 mock이라 제안 DB 저장·승인 성과가 아니다.
- 이번 migration은 `dataops-response-agent-audit` 복원 DB에서만 확인했다. 기존 실행 서버·main DB·운영 데이터에는 이번 개선을 반영하지 않았다. 신규 수집·Claude 호출도 없다.
- 원본 불완전 메뉴 상태에서는 리뷰를 포함한 전체 StoreSignal 갱신도 지연된다. 전체 HELD 재수집 주기와 실제 운영 배포·확대 실행은 미완료다.

## 후속 에이전트 검토 (2026-10-14)

후속 검토에서 미구현 경계와 실제 Sonnet 재검증 실패를 확인했다. 현재 수집 파이프라인에는 사건을 Claude로 넘기는 소비자가 없으므로 실제 100곳의 호출 0회를 올바른 에이전트 선택의 검증으로 해석하지 않는다. 별도 합성 probe는 Sonnet 4회/$0.024816 추정, 도구 왕복은 동작했지만 필수 근거 ID가 빠져 최종 REVIEW였다. Opus·운영 원본 전송·패치 실행·운영 쓰기는 없었다. 기존 수집 보호 계층의 테스트 결과와 에이전트 완료 여부를 구분한다.


## 실제 과거 사건 후속 검증 (2026-10-14)

[별도 실행 기록](./agent-historical-replay-20261014.md)에서 실제 원본 쌍→Sonnet 코드 조회/패치→격리 검증→검수 자료 경로를 확인했다. 생성 요청5회, 도구7회 오류0, 비용$0.166162 추정. 고정 평가 통과 뒤 추가 코드 검토의 계약 위반을 재현해 후보는HELD이며 운영 반영은0이다. 합성 smoke·이번 실사례·전체 운영 agent 완성을 구분한다. 운영 consumer/재개/스케줄은 아직 미완료다.


## PR 전 회귀 수정과 통합 검증 (2026-10-14)

새 서버 계약은 관측 ID/외부 ID/관측 시각을 요구하지만 기존 신규등록 worker는 이를 보내지 않아 첫 스냅샷이 HELD이고 HTTP200을 성공으로 집계하는 회귀를 발견했다. 등록 직후 스냅샷도 동일한 capture 제출 계약을 사용하도록 수정했다. fetch 시 관측 ID/시각 고정, 배치 원본을 먼저 spool에 저장, lost ACK 동일 ID 재전송, READY/PARTIAL/HELD 분리 집계를 추가했다. preview에서는 제출하지 않는다. 등록 API 자체의 결과 불명 복구를 확장한 변경은 아니다.

등록 전달 회귀6개와 main 병합 회귀4개를 포함한 Python 수집/스크립트 전체196개 중184통과·12skip, 별도 에이전트 계약31개 통과. 이번 검증에는 유료 모델/운영 호출이 없다. main의 관리자 API 헤더와 기존 신구 메뉴 이미지 추출·원격 DB 조회를 함께 보존했다.

현재 최신 관측 검사와 실제 메뉴 삭제 쓰기는 새 수집과 원자적으로 직렬화되지 않는다. 동시 수집·삭제 승인 경합과 운영 agent consumer/checkpoint/재개는 배포 전 미완료로 명시하고 Draft PR로 검토한다. 이번 external-response-agent spec/plan은 로컬에만 보존한다.

## 후속 구현·실행 — 2026-10-14 11:33 KST 이후

이전 절의 미완료 상태를 [후속 검증 기록](./agent-followup-verification-20261014.md)으로 갱신한다.
가게별 수신/메뉴 승인 잠금, PostgreSQL consumer와 제한된 재개, 45,000파일 저장 비용을 보완했다.
Sonnet v2 실호출 5회/$0.161850 추정, 도구 7회 오류 0, 고정 11+사례 10+생성 3개 통과와
후속 독립 19개 통과를 확인했다. 모델 후보는 검수 대기이며 운영 파서에 적용하지 않았다.

최신 로컬 서버·main DataOps migration 적용 후 100곳 수집은 READY56/PARTIAL33/HELD11,
새 스냅샷47·제안63·PENDING순증47·승인/운영반영0이다. 이 배치에 연결된 모델 적격 sample은0이며,
상시 agent worker는 설치하지 않았다. 지원 catalog 밖의 사례 준비, 장기 운영 정책과 스케줄,
승인된 agent 패치의 현재 파서 배포·운영 재처리 연결은 남아 있다.
