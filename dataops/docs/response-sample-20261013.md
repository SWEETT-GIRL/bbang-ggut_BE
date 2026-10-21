# 관측 전체 보류의 비용 — 실제 100곳 표본 수집

2026-10-13 (Asia/Seoul). 사용자 요청에 따라 실제 NAVER 상세 원본을 수집하고 첫 버전의 전체 보류 영향을 측정했다. 스케줄이나 보류 재시도 정책을 확정한 실행은 아니다.

## 결과

| 항목 | 결과 |
|---|---:|
| 수집 구간 | 23:14:14 ~ 23:17:27 KST |
| 소요 시간 | 192.913초 (약 3분 13초) |
| 수집·원본 접수 | 100곳 / 100건 |
| 정상 접수 | 76곳 |
| 정상 중 의미 변경 / 무변경 | 20 / 56곳 |
| 관측 전체 보류 | 24곳 (24%) |
| 성공 배치의 수집·제출 실패 | 0건 |
| 신규 변경 제안 | PENDING 90건 |
| 운영 반영 | 0건 |
| Claude 호출 / 비용 | 0회 / $0 |
| 로컬 spool 보관량 | 14,989,929 bytes (약 14.3 MiB) |

수집은 기한이 지난 ACTIVE NAVER 대상 순서의 표본이다. 무작위 대표 표본이 아니며 전체 약 5만 곳의 보류율/실행 시간을 추정하지 않는다. 신규 가게 등록 배치가 아닌 기존 가게 최신화 표본이다.

## 전체 보류가 막은 다른 정보의 검토

| 보류 원인 | 가게 수 |
|---|---:|
| 메뉴 완전성 근거 부족만 있음 | 17 |
| 명시적 페이지 부재 | 6 |
| 영업시간 의미 불명 | 1 |

메뉴 문제만 있던 17곳에서 파서는 영업시간 10곳, 전화번호 4곳, 인스타그램 5곳의 값을 읽었다. 이 수치는 추출 결과의 존재를 뜻하며, 정확성·실제 변경·승인 적격성까지 확정하지 않는다.

영업시간이 읽힌 10곳은 모두 운영 상태 ACTIVE였다. 운영 DB를 `transaction_read_only=on`으로 조회해 요일·시작·종료·휴무를 비교했다.

| 현재 운영 영업시간과 비교 | 가게 수 |
|---|---:|
| 이미 같음 | 5 |
| 값이 다름 | 3 |
| 운영 값이 비어 있음 | 2 |

**이번 100곳에서는 메뉴 문제 때문에 영업시간 보완·변경 후보 5곳의 검토도 함께 막혔다.** 이 중 어떤 값이 실제로 승인 가능할지는 provenance·시간 의미·기존 제안·검수 정책을 추가 확인해야 한다. 10곳 중 9곳은 7요일, 1곳은 6요일이 추출됐다. 부분 요일 처리를 무조건 전체 대체해도 된다는 근거가 아니다.

전화번호·인스타그램은 운영 값과의 차이를 이번에 대조하지 않았다. 필드 간 중복이 있으므로 위 수치를 합쳐 가게 수로 사용하지 않는다. 실제 지연 기간은 재처리/검수 시점이 정해져야 측정할 수 있다.

이 결과는 전체 보류의 단순한 보호 경계와 정상 정보의 검토 지연 사이에 실제 비용이 있다는 근거다. 한 표본만으로 필드별 처리로 전환하거나 메뉴 부재 계약을 완화하지 않는다.

## 원본·판정·쓰기 대사

- 서버 response_capture 100건: READY 76 / HELD 24. 로컬 spool receipt와 일치했다.
- 별도 CLI 재진단 100건의 보류 사유는 서버 결과와 모두 일치했다.
- 전체 보류 24곳은 사전 값 대비 next_due_at·last_success_at·consecutive_failures·content_hash·visit_count 변화 0이었다.
- 보류 24곳의 menu evidence 추가 0건, 신규 proposal 0건이었다.
- 전체 DB 증분: place_snapshot +20, menu_evidence_observation +76, change_proposal +90, response_capture +100.
- 신규 proposal 90건은 모두 PENDING이다. apply_log는 701,453건으로 변화 없고, 실행 구간의 apply 기록도 0건이다.
- 진단 사건은 3종으로 병합됐다(17/6/1개 표본). 현재 규칙상 모두 model_eligible=false다.
- 자동 Claude 분석/패치 worker는 아직 연결하지 않았다. 호출 0건을 에이전트 효율이나 모델의 올바른 무호출 판단 성과로 세지 않는다. 이전 합성 Claude probe의 비용은 별도 [실검증 기록](./claude-tool-smoke-20261013.md)에 있다.

원본 수집 HTTP의 합산 소요는 36.133초, 중앙값 0.332초였다. 서버 제출 합산은 5.228초, 중앙값 0.030초였다. 전체 시간에는 요청 간 1.5초 대기와 원본 저장 등이 포함된다. 이는 전체 운영 비용이나 유지보수 인건비 측정이 아니다.

## 실행 중 발견한 실패와 수정

1. 첫 서버 기동은 운영 DB 조회 터널이 닫혀 실패했다. 사용자가 터널을 다시 열었고 운영 스키마 7개 테이블/46개 컬럼 검증 후 기동했다. 새 raw/incident migration은 기존 백업·복원 사본 검증을 거쳐 이번 기동에서 로컬 DataOps DB에 적용됐다.
2. 첫 배치 `batch-20261013-01`은 첫 HTTP 200 응답을 BLOCKED로 오탐해 중단했다. 추가 진단 1회에서 유효한 Apollo 원본과 정상 페이지에 포함된 `initNcaptcha` 라이브러리를 확인했다.
3. 이번 작업에서 추가한 HTML 전체 문자열 검사 오류였다. 유효한 Apollo 추출을 먼저 처리하고, 원본이 없을 때 script/style/template을 제외한 페이지 텍스트로 차단을 판단하도록 수정했다. 403/429 및 CAPTCHA URL 차단은 유지하고 실패 응답 일부도 보존하도록 했다.
4. CAPTCHA 라이브러리가 있는 정상 페이지의 성공과 실제 차단 화면/403/429의 중단을 회귀 테스트로 검증했다. 관련 Python 테스트 13개 통과.
5. 실패 기록을 지우지 않고 새 run `batch-20261013-02`에서 100곳을 수집했다. 위 성공 배치 통계에는 첫 중단 1회와 진단 HTTP 1회를 포함하지 않는다. 최초 배부된 10곳은 기존 2시간 lease가 남으며 첫 배치에서 완료 처리하거나 실패 카운터를 올리지 않았다.

이 감지 코드 수정은 이번 개발 과정에서 직접 수행한 수정이다. Claude 유지보수 에이전트가 자동으로 고친 사례로 세지 않는다.

## 실행 상태와 재현 근거

- 현재 로컬 DataOps 서버는 `127.0.0.1:8090`, 운영 writer 비활성·PROPOSAL_ONLY로 기동 중이다. 운영 조회 연결에는 PostgreSQL read-only 기본값을 지정했다.
- 100곳·30분 상한·차단 시 즉시 중단·1회 실행. 다음 배치/자동 스케줄은 등록하지 않았다. 전체 보류의 장기 재수집 간격은 미결정이며 이번 실행에서 바꾸지 않았다.
- 코드: `scripts/measure_response_sample.py`, `scripts/sync_worker.py`, `ResponseDiagnosticCli.java`.
- 성공 run 디렉터리: `artifacts/response-agent/batch-20261013-02/` (Git 제외).
- 근거: `before.json`, `collection-summary.json`, `timings.jsonl`, `captures/`, `diagnostic-input.jsonl`, `diagnostic-results.jsonl`, `metrics.json`, `held-hours-comparison.json`.
- 진단 입력 SHA-256: `77c4f78729ee4852cbcfd75931681e286a3be1af04c776aa10fd50c8f076bb38`.
- 실패 및 서버 로그: `artifacts/response-agent/batch-20261013-01/`. 기존 원본·운영 기록을 덮어쓰거나 삭제하지 않았다.

수집 재현 명령(새 수집/로컬 변경 제안을 생성하므로 fresh run 디렉터리 필요):

```sh
.venv-agent/bin/python scripts/measure_response_sample.py --run-dir artifacts/response-agent/새-run --max 100
```

저장 원본만 재진단하는 명령:

```sh
bash gradlew diagnoseResponses --no-daemon \
  -PdiagnosticInput=artifacts/response-agent/batch-20261013-02/diagnostic-input.jsonl \
  -PdiagnosticOutput=artifacts/response-agent/batch-20261013-02/diagnostic-results.jsonl
```

실제 파서 패치 생성·검증 비용, 수동 수정 대비 절감률, 재처리/반영까지 걸린 시간은 아직 측정하지 않았다. 기존 등록 3,114곳·영업시간 보완 7,537곳과 이번 표본 결과를 합산하지 않는다.

## 에이전트 호출 0회의 해석 정정 (2026-10-14 검토)

이 표본의 model_eligible=false와 별개로, 당시 구현에는 response_incident를 소비해 Claude를 호출하는 경로가 없다. 따라서 호출 0회는 비용 측정 사실이며, 정상/불완전 원본에서 agent가 올바르게 호출을 생략한다는 라우팅 검증이 아니다. Sonnet 도구 왕복 및 종료 계약은 [별도 검토](./external-response-agent-progress-20261013.md#후속-에이전트-검토-2026-10-14)의 실패/미완료 결과와 구분한다.
