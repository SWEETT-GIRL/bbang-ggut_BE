# 멀티 모듈 전환 + dataops 통합 + 배치 서버 CD

- 브랜치: `refactor/multi-module-batch` (base: `develop-v2`)
- 상태: Spec/Plan 초안 — **구현 전 승인 대기**

---

## 1. Spec

### 1.1 목표
1. `bbang-ggut_dataops_BE`(가게 최신화)의 **Java·Python 코드 전부**를 이 레포로 옮긴다.
2. Gradle 멀티 모듈 `core` / `api` / `batch` 로 나눈다.
3. `api` 는 지금처럼 v2 ASG(`cd-v2.yml`)로, `batch` 는 새 배치 EC2(SSM)로 배포한다.
4. 배치 전용 변경은 배치 EC2에만, 공통(`core`) 변경은 API·배치 둘 다 배포한다.

### 1.2 범위 밖 (이번에 안 함)
- `ServingWriter`(JdbcTemplate 네이티브 SQL)를 WAS 리포지토리로 갈아타기 — 동작 변경이라 별도 작업
- `agent/`(응답 구조 대응 에이전트) 상시 실행 — macOS launchd 전용 코드라 리눅스 전환이 따로 필요. 코드만 옮기고 끈다
- v1(`cd-prod`/`cd-staging`) 종료, WAS Flyway 실행 주체 이전

### 1.3 현재 상태 (분석 결과)
| 항목 | WAS (develop-v2) | dataops_BE |
|---|---|---|
| Boot / Java | 4.0.6 / 17 | 4.0.6 / 17 (동일) |
| main 코드 | 1,392 파일 (controller 76) | Java 153 + Python ~80 + agent 64 |
| 스케줄러 | `@Scheduled` 18개 — v2 API 는 `app.scheduling.enabled=false` 로 꺼져 있음 | 없음. 전부 Python 이 HTTP 로 호출 |
| DB | 운영 RDS(soboro), JPA + Flyway(v1 만 실행) | 주 DB = 별도 dataops PG(JPA·Flyway 44개), 운영 DB = 수동 Hikari + `ServingReader/Writer` |
| 인증 | JWT + Admin API 키 | 없음(localhost 전제) |
| 실행 | ASG 상시 | 집 PC 세션형(Windows 작업 스케줄러 → Mac launchd 전환 중) |

의존 방향 확인:
- `controller` 패키지를 import 하는 비-controller 클래스 **0개** → controller 만 떼어 내도 core 가 컴파일된다
- 스케줄러 18개는 자기 테스트 외 참조 **0개** → batch 로 옮겨도 core·api 영향 없음
- 테이블 이름 충돌 없음 (dataops 44개 테이블 vs WAS)
- URL 충돌 없음 (dataops `/api/snapshots` 등 vs WAS `/api/admin`, `/stores` 등)

### 1.4 결정 사항 (사용자 확인 완료)
| 결정 | 내용 |
|---|---|
| 모듈 | `core` / `api` / `batch` |
| dataops DB | 운영 RDS 안 `dataops` 스키마 |
| Python | `scripts/`, `agent/` 모두 이전, 배치 EC2 에서 실행 |
| 배치 EC2 | `i-0743d492943fec320`, 10.77.6.215, 퍼블릭 IP 없음, **t4g.small(ARM64, 2GB)** |
| 배포 방식 | SSM(SendCommand). 수동 점검은 bastion SSH |
| 트리거 | `develop-v2` 머지. API·배치 workflow 분리 |
| 실행 설정 | Parameter Store `/sweetgirl/prod/v2/batch` (SecureString), 배치에 필요한 값만 |
| GitHub Variables | `BATCH_INSTANCE_ID`, `BATCH_RUNTIME_PARAMETER_PREFIX`, `BATCH_AWS_ROLE_ARN`(역할 생성 후) |

### 1.5 미결 사항
- ~~Q1 배치 EC2 시작·중지~~ → **EventBridge 규칙 + Lambda 가 EC2 를 켜고 끈다** (2026-10-21)
- ~~Q2 WAS 스케줄러 활성 시점~~ → **사용자가 EventBridge 규칙으로 정한다. 코드에서 켜지 않는다**
- ~~Q3 `matching/`·`reference/`~~ → **레포에 그대로 넣는다**
- ~~Q4 EC2 가 켜진 뒤 작업 실행 주체~~ → **(B) Lambda 가 켠 뒤 SSM 으로 `--batch.job=<이름>` 실행**
- ~~Q5 수 초~수 분 주기 작업~~ → **(가) 실행 위치 기준 — 상시 작업은 api 모듈(ASG), 하루·주 단위만 batch**

---

## 2. Plan

### 2.1 모듈 구조
```
settings.gradle         include 'core', 'api', 'batch'
build.gradle            공통: java 17, spotless, checkstyle, lombok, test 태그 설정 (subprojects)
core/   java-library    sweetgirl.croissant.**  (controller·스케줄러·Security·Swagger 제외 전부)
                        src/main/resources: db/migration, 프롬프트·사전 txt, logback
                        src/test: Repository·Service 테스트 + 테스트 전용 @SpringBootApplication
api/    bootJar         */controller/** (76), config/security, config/docs, ApiApplication
                        application.properties, application-v2.properties, application-lab.properties
                        src/test: @WebMvcTest, controller 테스트, CroissantApplicationTests, V2ApplicationTest
batch/  bootJar         */batch/*Scheduler + 서비스 패키지 스케줄러 4개, SchedulerConfig, SchedulerLockConfig
                        sweetgirl.croissant.dataops.** (dataops Java 전체)
                        db/dataops-migration (dataops Flyway 44개)
                        BatchApplication, application-batch.properties
dataops/                Python — scripts/, agent/ (Gradle 밖, 배치 이미지에 포함)
```
- WAS 파일은 **패키지 그대로 위치만 이동**(`git mv`) → import 변경 없음, 히스토리 보존
- dataops 패키지 `sweetgirl.dataops` → `sweetgirl.croissant.dataops`
  (`ReplayBridgeRunner` 스택 필터 문자열, `RuntimeMeasurement` 다이제스트 대상, `diagnoseResponses` 태스크도 같이 수정)
- dataops 의 `global`(ApiResponse·예외) 은 WAS `global` 과 겹치므로 **dataops 쪽 것을 `dataops.global` 로 유지**(응답 형식이 Python 과 계약이라 WAS 것으로 바꾸지 않는다)

### 2.2 batch 의 DataSource
| | 운영(WAS) | dataops |
|---|---|---|
| 접속 | `spring.datasource.*` (`@Primary`) | `dataops.datasource.*` → 같은 RDS, `currentSchema=dataops`, 전용 계정 |
| JPA | WAS 엔티티 (core) | `@EnableJpaRepositories(basePackages = dataops)` + 별도 EMF·TxManager |
| Flyway | **OFF** (v1 이 실행) | 별도 Flyway 빈, `db/dataops-migration`, `schemas=dataops` |
- `DataSource` 를 타입으로만 받던 dataops 클래스 9개에 `@Qualifier("dataopsDataSource")` 추가
- `ProdDataSourceConfig` 의 수동 Hikari 제거 → `prodJdbcTemplate` 은 primary DataSource 로 생성 (`ServingReader/Writer`·`ProdSchemaGuard` 로직은 그대로)
- `dataops.serving.write-enabled` 기본값 `false` 유지 (반영은 사람이 켤 때만)

### 2.3 batch 의 HTTP
- batch 도 웹 앱으로 뜬다 (Python 워커가 `/api/snapshots`, `/api/sync-states/due` 등을 호출)
- 포트는 EC2 내부 전용 (퍼블릭 IP 없음, SG 로 막음) + **`X-Dataops-Key` API 키 필터** 추가
- WAS controller 는 api 모듈에만 있으므로 batch 에는 WAS API 가 노출되지 않는다

### 2.4 Python (dataops/)
- 하드코딩된 `localhost:8090`·`15433/15434`·`15432`(68곳)를 환경 변수로 교체
  - `DATAOPS_SERVER_URL`, `DATAOPS_DB_*`(→ RDS dataops 스키마), `PROD_DB_*`(→ RDS 직접, SSH 터널 제거)
- 일일 수집(`weekly_refresh.py nightly`)은 배치 EC2 의 systemd timer 로 실행 (Q1 결과에 따라 조정)
- 반영(`weekly_refresh.py apply`)·승인 스크립트는 **수동** — SSM Run Command 또는 `workflow_dispatch` 로 실행
- Windows `.bat`/`.ps1` 러너는 옮기지 않는다 (경로 하드코딩, 리눅스 대체)
- `requirements.txt` 신설 (지금 없음): requests, psycopg2-binary, playwright, pyproj, pillow, pydantic

### 2.5 배치 이미지 (`Dockerfile.batch`, linux/arm64)
- `eclipse-temurin:17-jre-jammy` + python3 + `requirements.txt` + Playwright Chromium(ARM64 지원)
- 한 이미지, 두 용도: ① Java batch 상주 컨테이너 ② `docker run --rm ... python3 dataops/scripts/...` 작업 컨테이너

### 2.6 CI/CD
| workflow | 변경 |
|---|---|
| `ci-v2.yml` | `./gradlew build` (전 모듈) + Python 단위 테스트(`dataops/scripts/tests`, `agent/tests`) |
| `cd-v2.yml` (API) | 경로 필터: `core/**`, `api/**`, 루트 gradle 파일, `Dockerfile.v2` 일 때만. `:api:bootJar` → `Dockerfile.v2` |
| `cd-batch.yml` (신규) | 경로 필터: `core/**`, `batch/**`, `dataops/**`, 루트 gradle, `Dockerfile.batch`, `scripts/deploy/batch/**`. arm64 이미지 push → OIDC 로 `BATCH_AWS_ROLE_ARN` → (중지 상태면 start) → SSM SendCommand 로 bootstrap 실행 → readiness 확인 |
| `Dockerfile`(v1) | 루트 `src` 가 없어지므로 `:api:bootJar` 로 수정 (develop-v2 가 main 으로 갈 때 v1 CD 가 깨지지 않게) |
- 배포 스크립트: `scripts/deploy/batch/{deploy.py, bootstrap.py, tests/}` — v2 의 bootstrap 구조(SSM 이름 허용 목록, SecureString 강제, digest 고정) 그대로 따른다
- Parameter Store (`/sweetgirl/prod/v2/batch`) 에 필요한 값: 구현 중 core 기동에 필요한 키를 실제로 확인해 확정. 지금 추정:
  `env/DB_*`, `env/DATAOPS_DB_*`, `env/DATAOPS_API_KEY`, `env/KAKAO_API_KEY`, `env/ENCRYPT_SECRET_KEY`, `env/AWS_S3_BUCKET`, `env/GEMINI_API_KEY`·`OPENAI`·`CLAUDE`(스케줄러용), `firebase-service-account`, `dockerhub/*`
- 사람이 할 일 (코드 밖):
  - `BATCH_AWS_ROLE_ARN` 역할: `ssm:SendCommand`(해당 인스턴스 + `AWS-RunShellScript`), `ssm:GetCommandInvocation`, `ec2:DescribeInstances`, `ec2:StartInstances`
  - 배치 EC2 인스턴스 역할: `/sweetgirl/prod/v2/batch/*` 읽기 + KMS Decrypt + `AmazonSSMManagedInstanceCore`
  - RDS: `dataops` 스키마·전용 계정 생성, 기존 오라클 PG 데이터 `pg_dump` → 복원 (runbook 제공)

### 2.7 구현 순서 (커밋 단위)
1. ♻️ Gradle 멀티 모듈 뼈대 + WAS 코드 `core`/`api` 이동 → `./gradlew build` 통과 확인 (동작 변화 0)
2. ♻️ 스케줄러 18개 → `batch` 이동, `BatchApplication` (+ 기동 테스트)
3. ✨ dataops Java 이전 + 패키지 변경 + 2번째 DataSource·Flyway (+ 테스트 53개 이전, DataSource 구성 테스트 추가)
4. ✨ dataops API 키 필터 (+ 테스트)
5. ✨ Python 이전 + 환경 변수화 + requirements
6. 💚 `Dockerfile.batch`, `cd-batch.yml`, 배포 스크립트·테스트, `cd-v2`/`ci-v2`/v1 `Dockerfile` 수정
7. 📝 runbook (Parameter Store 등록 목록, IAM, dataops 데이터 이전, 차단 모니터링)

### 2.7.1 1단계 결과 (완료, 미커밋)
- `core`(main 1,311 / test 382 / testFixtures 2) · `api`(main 81 / test 77) 로 `git mv` — 패키지·import 변경 없음
- 테스트: develop-v2 원본과 **케이스 이름까지 동일한 2,989개 전부 통과** (core 2,538 / api 451)
- 옮기면서 생긴 것
  - core 테스트용 `CoreTestApplication` (`@SpringBootApplication`)
  - 테스트 설정 `application.properties` 를 core·api 각자 둔다 — api 에서는 main 의 `application.properties` 가 클래스패스 앞에 와 테스트 설정을 가리기 때문
  - 공용 픽스처(`TutorialTemplateFixture`, `MonthlyReportFixtures`)·테스트 리소스는 `core/src/testFixtures`
  - core 에 `jackson-datatype-jsr310`·`swagger-annotations-jakarta` 직접 선언 (springdoc 을 통해 딸려 오던 것)
  - 모든 모듈에 `-parameters` (Boot 플러그인이 붙이던 것 — 빠지면 `@Query :name` 바인딩이 깨질 수 있음)
  - `.gitignore` 의 `src/main/resources/firebase/` → `**/...` (루트 고정 패턴이라 api 아래 Firebase 키가 무시되지 않았음)
- 경로 수정: `cd-prod`/`cd-staging`(properties·firebase → `api/src/main/resources`), `cd-v2`(`api/build/libs`), `ci.yml` 리포트, `fe-contract`(paths·`:api:test`·openapi 경로), v1 `Dockerfile`(`:api:bootJar`), `scripts/` 18곳
- 로컬 개발: `application-local.properties` 를 `api/src/main/resources/` 로 옮겨야 한다. 실행은 `./gradlew :api:bootRun`

### 2.7.2 2단계 결과 (완료, 미커밋)
- `batch` 모듈: `BatchApplication`(웹 없음, `batch` 프로필) + `@BatchJob` / `BatchJobRegistry` / `BatchJobRunner`
  - `java -jar batch.jar --batch.job=<이름>` → 작업 하나 실행 후 종료. 성공 0 · 실패(예외·모르는 이름) 1 — 실제 JAR 로 확인
  - 작업 메서드는 프록시 빈으로 호출 → `@SchedulerLock`(기존 락 이름 그대로) 이 걸려 v1 cron 과 겹쳐도 한 곳에서만 돈다
- 스케줄러 이동
  - batch(12 클래스·14 작업): `@Scheduled` → `@BatchJob`. 급부상은 평소 수준만 `StoreTrendingBaselineScheduler` 로 분리
  - api(상시 6): 월간 리포트 관리자·자동 준비, 메뉴 분류, 이미지 파이프라인 지표(Prometheus 게이지라 상시 프로세스여야 함), 급부상 순위표, 지역 해소 + `SchedulerConfig`. v2 에서 꺼 둔 스위치는 그대로(켜는 건 별도 판단)
- 로그인 클러스터(`member/jwt`, `member/oauth`, Auth·Apple·Guest·Kakao·Naver AuthService, CustomOAuth2UserService)를 api 로 — batch 에 JWT 비밀키를 넘기지 않으려고. api 에서만 쓰이던 것이라 core 컴파일 영향 없음
- 기타: S3 빈 `@Profile` 에 `batch` 추가, `logback-spring.xml` 에 `batch` 콘솔 출력(없으면 로그가 한 줄도 안 찍힘 — 회귀 테스트 추가), api 의 안 쓰는 cron 키 2개 삭제
- 테스트: 3,004개 통과. develop-v2 대비 차이는 의도한 것뿐(cron 검사 1·급부상 통합 2 → 분리·재작성, batch 신규)
- batch 필수 Parameter Store 값(기동 테스트로 확정): `env/DB_URL`, `env/DB_USERNAME`, `env/DB_PASSWORD`, `env/ENCRYPT_SECRET_KEY`, `env/AWS_S3_BUCKET`, `firebase-service-account`
  - 작업에 따라 필요: `REDIS_*`(급부상), `TRENDING_ENABLED`, `SEARCH_MISS_TRIAGE_*`·`CLAUDE_API_KEY`·`NAVER_CLIENT_*`(검색어 판정), `SLACK_*`, `KAKAO_API_KEY`, `GEMINI/OPENAI_API_KEY`

#### EventBridge 규칙용 작업 목록 (기존 cron 시각, KST)
| 작업 이름 (`--batch.job=`) | 기존 시각 | 비고 |
|---|---|---|
| `ranking-snapshot` | 매일 00:05 | |
| `ranking-reward-issue` | 월 00:15·00:45·06:30 | 재시도 겸 3회 |
| `store-popularity-score` | 월 03:00 | |
| `user-interaction-ingest` | 매일 04:00 | 취향 신호 적재 → 급부상 평소 수준보다 먼저 |
| `withdrawn-member-retention` | 매일 04:40 | |
| `admin-audit-log-retention` | 매일 04:50 | |
| `search-miss-retention` | 매일 04:50 | |
| `store-trending-baseline` | 매일 05:00 | `TRENDING_ENABLED=true` 일 때만 실제 계산 |
| `search-miss-triage` | 매일 06:00 | `SEARCH_MISS_TRIAGE_ENABLED=true` 일 때만, 락 상한 2시간 |
| `reward-second-morning-roulette` | 매일 09:00 | |
| `reward-letter-arrival` | 월 10:00 | |
| `course-reminder` | 매일 19:00 | |
| `reward-attendance-reminder` | 매일 20:00 | |
| `monthly-report-run-due` | (꺼져 있었음, `-`) | 규칙 없이 두면 안 돈다 |

### 2.7.3 3~6단계 결과 (브랜치 `feat/dataops-into-batch`)
- **3단계 — dataops Java → batch 모듈** (계획 변경: 패키지 `sweetgirl.dataops` 유지)
  - WAS 배치 앱 컨텍스트에 섞으면 `@Transactional` 32곳·`PlatformTransactionManager`/`DataSource` 타입 주입 10곳·엔티티/리포지토리 스캔을 전부 둘로 갈라야 하고, 하나라도 놓치면 dataops 쓰기가 WAS 트랜잭션으로 새는 조용한 버그가 된다
  - 그래서 같은 JAR 안의 **별도 Spring 앱**으로 둔다 — `sweetgirl.croissant` 스캔 밖이라 서로 안 섞이고, 트랜잭션·DataSource 코드는 무변경
  - 설정 `dataops.properties`(+`-local`·`-prod`), Flyway `db/dataops-migration`, 운영은 RDS `dataops` 스키마
  - Security 가 클래스패스에 들어와 `X-Dataops-Key` API 키 필터(키 없으면 로컬처럼 통과)
  - 원본 대비 수정: main 3·test 8 파일. dataops 테스트 489개 원본과 같은 결과(통과 424·건너뜀 65). dataops 패키지는 spotless·checkstyle 제외(원본 유지)
- **4단계 — Python·데이터 → `dataops/`**: 서버 호출 19곳 `X-Dataops-Key`, Java·WAS 소스 경로를 batch·core 기준으로, `requirements.txt` 신설, `local_db` 스키마(`DATAOPS_DB_SCHEMA`)·운영 DB 호스트(`PROD_DB_HOST`) 환경 변수. agent 는 옮기기만(신뢰 소스 사본 줄바꿈 보존)
- **5단계 — 이미지·CD**: `Dockerfile.batch`(temurin 17 noble + Python 3.12 venv + Playwright Chromium), `entrypoint.sh`(job·dataops-nightly·dataops-server·dataops), EC2 `bbang-batch`(runner.py: 실행 시 Parameter Store 읽기), `deploy.py`(꺼져 있으면 켜서 설치 후 다시 끔, 작업 중이면 안 끔), `cd-batch.yml`(`BATCH_DEPLOY_ENABLED` 게이트), `cd-v2` 는 배치 전용 경로 무시, `ci-v2` 에 Python·배포 테스트·이미지 빌드
- **6단계 — runbook**: `docs/batch-server-runbook.md` (IAM·Parameter Store·RDS 스키마·데이터 이전·Lambda 명령·EventBridge 표·차단 감시)
  - 수집은 10:10 권장 — t4g.small 은 컨테이너 하나만 감당하므로 04:00~09:00 작업과 겹치면 안 된다

### 2.8 위험 요소
| 위험 | 대응 |
|---|---|
| **네이버 IP 차단** — 설계 문서가 EC2 IP 를 탈락시킨 이유 | 첫 주 `sync_worker` 종료 코드 3·쿨다운 횟수를 매일 확인. 막히면 수집만 다른 곳으로 되돌릴 수 있게 서버 URL 은 환경 변수 |
| **t4g.small 2GB 메모리** — batch 는 core 전체 컨텍스트 + Chromium | Java 힙 상한 지정(~900MB). 일일 작업은 requests 기반 `sync_worker` 만, Playwright 작업은 수동 |
| v1 과 스케줄러 중복 실행 | Q2 — 배치 스케줄러 기본 OFF + ShedLock ON |
| 대량 파일 이동으로 다른 브랜치와 충돌 | 1단계(이동만)를 먼저 머지해 충돌 창을 짧게 |
| dataops 데이터 크기(`place_snapshot` 원본 JSON) 가 RDS 용량에 미치는 영향 | 이전 전 크기 측정 후 판단 |
| 로컬 dataops 트랜잭션 ↔ 운영 쓰기가 같은 RDS 지만 별도 DataSource 라 원자적이지 않음 | 지금과 동일(apply_log 선기록 구조 유지), 범위 밖 |

### 2.9 최종 점검
- `./gradlew spotlessApply && ./gradlew build && ./gradlew test` (전 모듈)
- 기존 테스트 460개 + dataops 53개가 모듈 이동 후 모두 통과
- 신규: BatchApplication 기동, dataops DataSource 분리, API 키 필터, 배포 스크립트 단위 테스트
