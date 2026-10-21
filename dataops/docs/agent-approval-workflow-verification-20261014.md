# 응답 변경 에이전트 승인 흐름 구현·검증 기록 — 2026-10-14

현재 파서에 대한 도구 선택·프로필 수정·독립 검증과 GitHub 보고서 게시·알림 수신까지
실행했습니다. 배포 controller, 저장 원본 replay, 데이터 승인 gate는 구현하고 격리
환경에서 시험했습니다. 후속 작업으로 실제 JAR 측정, 전용 서비스 host adapter, 전체 서비스
registry, Python↔Java 재처리·반영 연결까지 구현·격리 검증했습니다. 실제 launchd 설치와
운영 활성화는 아직 하지 않았습니다. 테스트 통과를 운영 배포나 가게 등록·갱신 실적으로 표현하지 않습니다.

## 실제 Sonnet 실행

| 항목 | 결과 |
|---|---|
| 입력 | `ControlledMenuV9`라는 새 typename을 넣은 통제 합성 사례 |
| 기준선 | 현재 파서와 JAR의 class bytes 일치, 고정 Java 테스트 40개 통과 |
| 원래 실패 | 새 typename을 현재 구조 프로필이 지원하지 못함 |
| 모델 | `claude-sonnet-5-5`, 도구 선택 `auto`, SDK 자동 재시도 0 |
| 추론 호출 / 도구 호출 | 9회 / 13회 |
| 도구 오류 | 0회 |
| 후보 | 프로필 alias 추가, 검증 실패 후 생성 테스트의 기대값 보완 |
| 실패 원인 | 모델 생성 테스트 2개에서 `groupName: null` 누락 |
| 마지막 검증 | 고정 회귀 40개 + 독립 사례 3개 + 생성 테스트 2개 통과 |
| 실행 시간 | 62.623초; 최초 현재 기준선 구성 시간은 별도 |
| 확인된 usage의 예상 비용 | $0.303422, 미확정 예약 비용 $0 |
| 중복 전달 | `IDLE`, 추가 모델 호출 0 |
| 실행 판정 | `AWAITING_REVIEW` |
| 운영 배포 / 재처리 / 쓰기 | 각각 0 |

모델이 직접 사건 읽기·파일 읽기·검색·패치 제안·검증·분석 종료를 선택했습니다.
처음에는 프로필 수정 자체와 독립 사례가 통과했지만, 자신이 만든 테스트의 예상 메뉴
객체에서 `groupName`을 빠뜨렸습니다. 모델은 검증 실패를 읽고 코드를 검색한 뒤 테스트를
보완했으며, 호스트가 수행하는 마지막 검증도 통과했습니다. 이를 독립 평가 실패를
발견한 성과나 새로운 코드 수정 두 건으로 세지 않습니다.

가격은 [Anthropic Sonnet 안내](https://www.anthropic.com/claude/sonnet)의 입력 $2·출력 $10/
백만 토큰을 사용했습니다. 청구서 확정액이 아니라 응답 usage에 따른 예상액입니다.
실행당 $0.50 상한과 사건 누적·일일 예약 원장을 적용했고, 이 실험 DB의 하루 한도는
확인한 당일 이전 두 Sonnet 실행 $0.328012를 차감한 $1.671988로 더 낮게 설정했습니다.
별도 실험 DB를 전체 계정의 통합 청구 원장이라고 주장하지 않습니다.

실행 근거는 무시되는 로컬 `artifacts/response-agent/current-live-controlled-20261014/`에
있습니다. run ID는 `f977386f-3cde-4b1f-ad87-22be5887dd07`입니다. 실험 PostgreSQL은 비용·실행
원장 보존을 위해 유지했으며, 종료하더라도 원장을 먼저 보관해야 합니다.

## GitHub 보고서와 실제 수신

[보고서 PR #20](https://github.com/SWEETT-GIRL/bbang-ggut_dataops_BE/pull/20)은 REPORT_ONLY
Draft입니다. 문서만 포함하며 병합해도 파서 배포·원본 재처리·운영 쓰기 권한이 없습니다.
게시에는 새 LLM 호출을 하지 않았습니다. 완료된 실행을 outbox로 가져와 게시한 연결
시험이며, 자연발생 사건의 자동 스케줄 실행은 아닙니다.

GitHub 게시 성공과 PR 파일 범위를 확인했고 사용자가 이 대화에서 **“GitHub 알림에서
확인됨”**이라고 답했습니다. 최초 알림 전달 시험까지 확인했습니다.

## 추가 검토에서 고친 사항

1. 정제 과정에서 목록 밖 캐시 메뉴를 없애면 원본에서는 `MENU_OUTPUT_MISMATCH`인데
   축소 fixture에서는 정상으로 보일 수 있었습니다. 지원하거나 추가할 타입의 모든
   최상위 엔티티가 정확한 목록과 일치해야 하며 인라인 메뉴를 승격하지 못하게 했습니다.
2. PR 문서에 복구 epoch/path까지 해시하면 Git branch 준비 후 DB transaction 실패 시
   같은 사건을 복구할 수 없었습니다. 실행 근거와 가변 전달 메타데이터를 구분했습니다.
3. 재처리 manifest에서 store ID·attempt를 문자열로 바꾸던 부분을 고치고 시간대를
   포함한 관측 시각과 정수 순번을 보존했습니다.
4. 중단 뒤 새 sample이나 이전 정상 자료를 다시 선택하면 같은 유료 실행의 입력이
   달라질 수 있었습니다. 처음 정제한 JSON 텍스트와 원본 binding을 PostgreSQL에 고정하고
   복구 시 동일 capture만 검증합니다. JSONB 키 정렬로 메뉴 순서가 바뀌지 않게 했습니다.
5. canary/replay 명령이 이전 서비스 generation을 전달하던 부분을 배포된 candidate
   generation으로 수정하고 실제 live generation을 검사하는 adapter 회귀를 추가했습니다.
6. exact data apply는 이름·전화·Instagram·메뉴 가격/설명을 지원하며, 후속 검증 후
   전용 세트 교체를 사용하는 영업시간도 추가했습니다. 기존 메뉴 upsert의
   의미 확대, 주소와 좌표/지역의 불완전 CAS, 삭제 및 동적 category 경로는 보류합니다.
7. 영업시간 SQL에서 old=08:00 비교 후 DELETE 대기 중 어드민의 09:00이 커밋되면 최종
   10:00으로 덮어쓰고 성공을 반환하는 경쟁을 격리 PostgreSQL에서 재현했습니다. 당시에는
   claim 전 보류로 막았고, 후속 작업에서 수동·agent 두 경로의 세트 교체 자체를 수정했습니다.
   부모·자식 행 잠금, 잠금 후 fresh read·old 집합 재비교, 한 transaction의 삭제·삽입을
   적용합니다. 유효한 nondeferrable FK·활성 RI trigger·필요 권한이 없으면 쓰기 전에 멈춥니다.
8. DataOps 매핑만 확인하던 빈틈을 막기 위해 실제 serving external ID도 모든 지원 DML에
   묶습니다. 메뉴는 부모 행을 잠그고 수정하며 부모 매핑이 바뀐 뒤에는 0행으로 멈춥니다.
9. 전용 writer는 cluster system identifier + DB OID/name을 측정해 trusted runtime과
   비교하고, 고정 search_path와 public table 해석을 확인합니다. attestor·권한이 없으면
   멈추며 직접 GRANT하지 않습니다. clone/복제본의 절대 식별까지 보장하는 값은 아닙니다.

위 수정은 실제 유료 실행 후 추가한 방어입니다. 같은 사례로 다시 과금하지 않았으며,
추가 반례와 고정 후보의 격리 검증으로 확인합니다. 실패·보완 이력을 삭제하지 않습니다.

## 구현 경계와 검증

- 현재 JAR와 파서 계열 소스·프로필·보호 테스트·검증기 버전을 고정했습니다. parser family
  검증은 전체 JAR의 Git 출처나 현재 서버를 증명하지 않으므로 운영 controller에서 별도 검사합니다.
- 사건·KST 날짜별 예약과 정산, 미확정 비용 유지, 원장 불변성, 동시 예약을 실제 PostgreSQL로 시험했습니다.
- report/parser/data PR의 권한을 구분하고 actor·head·merge tree·파일 bytes·manifest·만료를 검사합니다.
- 서비스별 PostgreSQL 잠금과 fencing, 승인 소비와 release 작업 생성의 원자성, 중단 후 대사,
  만료 후 새 배포 차단 및 기존 artifact 복구를 시험했습니다. 초기 fake 시험 후 실제 HTTP
  subprocess host 시험과 실제 Boot JAR replay/apply 시험도 추가했습니다. launchd 자체의
  설치·재시작은 아직 실제로 시험하지 않았습니다.
- Java replay는 기존 capture 처리 receipt와 방문 횟수·snapshot 이력을 바꾸지 않습니다.
  원 관측 시각·순번, 최신 원본, parser/profile/artifact/policy를 검사하고 새/재사용 제안을 구분합니다.
- 별도 데이터 승인에서는 지원 필드의 정확한 전후 값·제안 ID·replay 출처를 확인하고 관측
  잠금 아래 점유·CAS 쓰기로 연결합니다. CREATED의 일반 승인 우회도 막았습니다.
- 동일 proposal/승인 실행의 미확정 결과는 재시도하지 않습니다. 후속 migration의 DB fence는
  같은 `store_id × target`의 APPLYING/UNKNOWN 앞에서 새 producer의 제안 생성·재반영도
  차단합니다. 서로 다른 target이 물리적으로 같은 행에 영향을 주는 모든 경우까지 포괄하지
  않습니다. UNKNOWN 해제에는 불변 대사 근거와 신뢰된 authority가 필요하며, 실제 운영
  대사 adapter는 아직 연결하지 않았으므로 자동 해제하지 않습니다.

## 초기 승인 흐름 검증 실행

아래는 후속 adapter 연결 전 기준선입니다. 최신 검증은 다음 절에 구분합니다. 묶음 간 중복이 있으므로 합산 성공 건수로 보고하지 않습니다.

| 실행 | 결과 | 범위 |
|---|---|---|
| 전체 Java `bash gradlew test --no-daemon` | 424개 중 397통과·27skip·실패 0 | 별도 DB 환경변수가 필요한 선택적 시험은 제외 |
| exact apply + 기존 승인 테스트 | 67통과·skip 0 | 신규 26개(실제 격리 PostgreSQL 14 포함) + 기존 승인 41 |
| `RUN_PG_AGENT_TESTS=true` response_change 전체 | 146개 중 144통과·2skip | 실제 PG dispatch·release·예산/복구 포함; manifest 입력 요구 2개 제외 |
| agent/tests 전체 | 57통과 | 모델 도구·상한·승인/outbox 계약 |
| 고정 current 후보 + 최신 bundle 실제 Docker 검증 | 1개 통합 시험 통과 | 추가 LLM 호출 없이 Java 고정 40 + 독립 3 검증 |

영업시간 반례는 별도 격리 PostgreSQL에서 실제 덮어쓰기를 확인한 실패 재현입니다.
당시 이 결함을 자동 경로에서 보류한 시험과, 후속 세트 교체 수정 검증은 구분합니다. 초기 코드 검토에서
범위 제한·일반 승인 우회 차단·운영 매핑 CAS·부모 행 잠금·DB/스키마 검사를 확인했습니다.

## 후속 실행 연결과 정합성 보강

- **전체 서비스 계보:** 고정 Git commit을 네트워크 없는 Docker에서 `test bootJar`로 빌드합니다.
  소스·빌드 recipe·이미지·의존성 cache·산출물 digest를 기록합니다. 빌드 성공만으로 현재
  배포를 등록하지 않으며, 실제 runtime·복구 자료 확인 후 별도로 base를 등록합니다.
- **후보 검증:** 수정 가능한 파서/프로필 외 JAR 항목의 불변성을 확인합니다. 실제 parser
  package의 Spring scan·생성자 주입을 검사해 `@Component` 제거도 거부합니다. 전체 저장
  원본의 ParsedPlace 11개 필드를 독립 기대값과 비교하며, 한 검토안은 최대 100원본,
  canary는 최대 20개입니다. 후보 자신의 결과로 기대값을 만들 수 없습니다.
- **원본 불변:** 후보 파서에 복사본을 넘기고 입력을 수정했으면 `PARSER_INPUT_MUTATION`으로
  보류합니다. 원본 ID·메뉴 수를 수정해 후속 진단을 통과시키는 반례를 차단했습니다.
  보호 코드와 harness digest가 바뀌어 이전 bundle 검증을 새 release에 재사용하지 않습니다.
- **실행 연결:** 실행 JAR·parser/profile/contract/policy·DataOps/serving DB를 측정하고 승인된
  release·generation·fence·manifest와 묶습니다. Java bridge는 durable intent 뒤 한 번만
  실행하며 응답 유실 시 STATUS receipt를 조회합니다. exit code 0은 완료 증거가 아닙니다.
- **서비스 전환:** 전용 launchd job의 소유·PID·인증된 loopback runtime 응답을 함께 확인합니다.
  drain은 새 요청을 막고 처리 중인 요청이 끝나기를 기다립니다. rollback은 기존 JAR에
  새 release ID/generation을 부여하며 데이터 rollback 권한을 만들지 않습니다.
- **승인 연결:** parser/data 검토안은 실제 원장·검증 자료에서 고정 renderer로 준비하고 종류별
  outbox에서 게시합니다. `release_cli`는 기본 preview이며 `--live`도 승인된 단계 하나만
  수행합니다. PR 생성·병합·배포·운영 반영은 서로 다른 권한입니다.
- **발견한 오류:** capture 저장과 비교 시각의 microsecond 정밀도를 맞췄고, replay journal이
  serving JdbcTemplate로 연결될 수 있던 주입을 DataOps DataSource로 고쳤습니다.

## 최신 검증 — 후속 구현

묶음 간 테스트는 겹치므로 합산하지 않습니다. 이번 후속 검증의 모델 호출은 0회입니다.

| 실행 | 결과 | 한계 |
|---|---|---|
| 고정 commit `a5918e4` 전체 offline `test bootJar` | 486개 중 421통과·65skip·실패 0, 실제 Spring parser probe 통과 | DB/통합 입력을 요구하는 조건부 시험 제외; 운영 등록 아님 |
| 영업시간·UNKNOWN·기존 승인·시각 검증 | 128통과·skip 0, 실제 PG 포함 | 운영 FK/권한은 미변경 |
| runtime authority·Java bridge 관련 Java 검증 | 41통과·skip 0, 실제 PG 포함 | 운영 승인 아님 |
| Python 전체 suite | 252개 중 247통과·5skip·실패 0, 실제 격리 PG 97개 포함 | Docker/JAR 입력 조건부 5개는 별도 수행으로 구분 |
| CLI help·종류별 publisher smoke | 6통과 | 외부 연결 mock; 실제 승인/게시 결과 아님 |
| 새 JAR·fresh bundle 실제 후보 검증 | 추가 2통과, 고정 회귀 44/44·후보 사례 3/3 | 새 형식의 변경 전 실패 재현; E1 provenance는 production_eligible=false |
| 실제 Docker 후보 반례 | 2통과 | 입력 변조 및 Spring metadata 제거를 각각 거부 |
| 실제 Python adapter→Boot JAR→격리 PG/PostGIS | 1개 통합 시험 통과 | GitHub 승인은 합성 fixture |

마지막 통합은 canary→replay→새 제안 3건→별도 데이터 승인→이름 CAS 1회를 실행했습니다.
같은 승인의 반복 호출은 추가 UPDATE 0회였고, generation이 바뀐 전화번호 변경은
NOT_ATTEMPTED로 남았습니다. replay의 운영 형태 DB 쓰기는 0회, 별도 apply의 **격리 DB**
쓰기는 1회, **실제 운영 DB 쓰기는 0회**입니다. 새 방문/snapshot도 만들지 않았습니다.

고정 commit 빌드 JAR와 통합 시험 JAR의 SHA-256은 동일합니다:
`4ab65e9de091b626ab4d7ed87758167d3e459b6dcafeae96146cd3c5f48d7071`.
빌드 receipt는 `022b3cce59d1a4e9ab3b81adfb71661d09325a48e6e6ac349c836c111d2e31cf`이며,
이는 artifact 증거이지 현재 운영 runtime 등록/승인 증거가 아닙니다.

로컬 ignored 근거는 `artifacts/response-agent/reviews/`의
`final-committed-build-20261014.json`, `java-bridge-e2e-20261014.json`,
`java-execution-authority-20261014.json`, `hours-manual-fence-and-timestamp-20261014.json`,
`parser-input-and-spring-probe-20261014.json`, `python-final-20261014.json`에 보존합니다. 원본·접속 설정은 Git에 싣지 않습니다.

## 아직 확인하지 않은 운영 결과

- 실제 launchd 설치·서버 전환·readiness·rollback, 운영 role와 backup 복원/schema preflight
- 실제 파서 PR 병합 승인 → 배포 → 저장 원본 replay → 별도 데이터 PR 승인 → 운영 값 반영
- worker 완료에서 full candidate/독립 전체 원본 기대값·parser 검토안을 준비하는 호출자,
  replay 완료에서 DATA_APPLY 검토안을 만드는 호출자. 현재 정의·테스트 외에는 미연결
- 새 승인 발견·반복 단계 진행의 orchestration, 종료 결과 알림
- UNKNOWN 해제를 위한 실제 운영 대사 authority; 신규 등록·주소·추가/삭제 등 별도 adapter
- 서로 다른 DB의 분산 원자성, 잠금 연결 상실 구간, 모든 필드의 ABA/변경 이력 version CAS
- 상시 수집/승인 조회 주기, HELD 재수집, 원본·artifact 보존 기간과 quota

현재 수집 서버의 전역 쓰기를 켜지 않았고 운영 DB migration도 적용하지 않았습니다.
기존 등록 3,114곳과 영업시간 보완 7,537곳은 기존 파이프라인 성과이며, 이번 에이전트의
성과로 다시 집계하지 않습니다. 수동 기준선이 없어 시간·비용 절감률도 산출하지 않습니다.
