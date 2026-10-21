# 배치 EC2 runbook

배치 EC2(`i-0743d492943fec320`, t4g.small ARM64, 퍼블릭 IP 없음)는 평소 꺼져 있다. EventBridge 규칙이 시각마다 Lambda 를 부르고,
Lambda 가 EC2 를 켜 SSM 으로 `bbang-batch <명령>` 을 보낸 뒤 끝나면 끈다. 이미지 설치는 `develop-v2` 머지 시 `cd-batch.yml` 이 한다.

```
EventBridge ─▶ Lambda ─(StartInstances, SSM SendCommand)─▶ 배치 EC2
                                                             └ bbang-batch job ranking-snapshot
                                                                └ docker run <batch 이미지@digest> job ranking-snapshot
                                                                   └ java -jar batch.jar --batch.job=ranking-snapshot  (종료 코드 0/1)
```

## 1. 준비 순서 (처음 한 번)

1. RDS 에 `dataops` 스키마·계정 만들기 (§4) → 기존 dataops 데이터 옮기기 (§5)
2. IAM 역할 두 개 (§2) — 배포 역할 ARN 을 GitHub Variable `BATCH_AWS_ROLE_ARN` 에
3. Parameter Store 등록 (§3)
4. GitHub Variables: `BATCH_INSTANCE_ID=i-0743d492943fec320`, `BATCH_RUNTIME_PARAMETER_PREFIX=/sweetgirl/prod/v2/batch`, `BATCH_AWS_ROLE_ARN`
5. `BATCH_DEPLOY_ENABLED=true` → 다음 develop-v2 머지(또는 마지막 cd-batch 실행 Re-run)로 설치
6. 수동 점검 (§6) 후 EventBridge 규칙·Lambda (§7)

> EC2 OS 는 Ubuntu 를 가정한다(배포가 없으면 `apt-get install docker.io python3-boto3`). Amazon Linux 면 설치 줄을 바꿔야 한다.
> 컨테이너가 인스턴스 역할로 S3 에 접근하려면 IMDSv2 **hop limit 을 2** 로 둔다(v2 WAS 와 같다): `aws ec2 modify-instance-metadata-options --instance-id i-0743d492943fec320 --http-put-response-hop-limit 2 --http-tokens required`

## 2. IAM

### 배포 역할 (`BATCH_AWS_ROLE_ARN`) — GitHub OIDC
신뢰 정책 조건: `token.actions.githubusercontent.com:sub` = `repo:SWEETT-GIRL/bbang-ggut_BE:ref:refs/heads/develop-v2`, `aud` = `sts.amazonaws.com`

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["ec2:DescribeInstances"], "Resource": "*" },
    { "Effect": "Allow", "Action": ["ec2:StartInstances", "ec2:StopInstances"],
      "Resource": "arn:aws:ec2:ap-northeast-2:210447604031:instance/i-0743d492943fec320" },
    { "Effect": "Allow", "Action": ["ssm:SendCommand"],
      "Resource": ["arn:aws:ec2:ap-northeast-2:210447604031:instance/i-0743d492943fec320",
                   "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"] },
    { "Effect": "Allow", "Action": ["ssm:GetCommandInvocation", "ssm:DescribeInstanceInformation"], "Resource": "*" }
  ]
}
```

### 배치 EC2 인스턴스 역할
- `AmazonSSMManagedInstanceCore` (SSM 명령 수신)
- `ssm:GetParameters` on `arn:aws:ssm:ap-northeast-2:210447604031:parameter/sweetgirl/prod/v2/batch/*` + 그 SecureString 키의 `kms:Decrypt`
- S3 버킷 권한 — v2 WAS 인스턴스 역할과 같은 범위(배치 작업이 S3 를 쓴다)

## 3. Parameter Store (`/sweetgirl/prod/v2/batch`, 전부 SecureString)

`runner.py` 는 아래 이름만 읽는다(허용 목록 밖은 무시). 값을 바꾸면 다음 실행부터 바로 반영된다 — 재배포 불필요.

| 이름 | 필수 | 용도 |
|---|---|---|
| `env/DB_URL` · `env/DB_USERNAME` · `env/DB_PASSWORD` | ✅ | 운영 DB (WAS 와 같은 RDS) |
| `env/ENCRYPT_SECRET_KEY` · `env/AWS_S3_BUCKET` | ✅ | 회원 암호화 · S3 |
| `firebase-service-account` (JSON) | ✅ | 푸시 작업 |
| `dockerhub/username` · `dockerhub/token` | ✅ | 이미지 pull |
| `env/DATAOPS_DB_URL` · `env/DATAOPS_DB_USERNAME` · `env/DATAOPS_DB_PASSWORD` | dataops | `jdbc:postgresql://<rds>:5432/soboro?currentSchema=dataops` |
| `env/DATAOPS_API_KEY` | dataops | 판정 서버 API 키(32자 이상 무작위) |
| `env/DATAOPS_PROD_DB_URL` · `_USERNAME` · `_PASSWORD` | 반영 시 | 운영 쓰기 계정. 없으면 `DB_*` 를 쓴다 |
| `env/ADMIN_API_KEY` · `env/WAS_SERVER_URL` | 사진 반영 | 본 앱 어드민 API(`image_backfill_worker`) |
| `env/REDIS_HOST` · `env/REDIS_PORT` · `env/REDIS_PASSWORD` · `env/TRENDING_ENABLED` | 급부상 | `store-trending-baseline` |
| `env/SEARCH_MISS_TRIAGE_ENABLED` · `_AUTO_APPLY_VERDICTS` · `_MODEL` · `env/CLAUDE_API_KEY` · `env/NAVER_CLIENT_ID` · `env/NAVER_CLIENT_SECRET` | 검색어 판정 | `search-miss-triage` |
| `env/SLACK_*_WEBHOOK_URL` · `env/SLACK_NOTIFICATION_ENABLED` | 선택 | 알림 |
| `env/KAKAO_API_KEY` · `env/GEMINI_API_KEY` · `env/OPENAI_API_KEY` | 선택 | |

스위치(`*_ENABLED`)가 없으면 해당 작업은 실행돼도 아무것도 하지 않고 성공(0)으로 끝난다.

## 4. RDS — dataops 스키마

```sql
CREATE ROLE dataops_service LOGIN PASSWORD '<Parameter Store 의 DATAOPS_DB_PASSWORD>';
CREATE SCHEMA dataops AUTHORIZATION dataops_service;
-- 판정 서버가 운영 테이블을 읽는 건 DB_* 계정(별도 커넥션)이다. dataops_service 에 운영 테이블 권한을 주지 않는다
```
테이블은 판정 서버가 처음 뜰 때 Flyway(`batch/src/main/resources/db/dataops-migration`)가 `dataops` 스키마에만 만든다.

## 5. 기존 dataops 데이터 옮기기 (오라클 PG → RDS)

옛 DB 는 `public` 스키마라 바로 복원하면 운영 `public` 에 섞인다. 임시 PG 에서 스키마 이름을 바꾼 뒤 옮긴다.

```bash
pg_dump -Fc -n public -h <옛 dataops 호스트> -U dataops dataops > dataops.dump
docker run -d --name dataops-tmp -e POSTGRES_PASSWORD=tmp -p 55432:5432 postgres:16-alpine
createdb -h localhost -p 55432 -U postgres dataops
pg_restore --no-owner --no-acl -h localhost -p 55432 -U postgres -d dataops dataops.dump
psql -h localhost -p 55432 -U postgres -d dataops -c 'ALTER SCHEMA public RENAME TO dataops; CREATE SCHEMA public;'
pg_dump -Fc -n dataops -h localhost -p 55432 -U postgres dataops > dataops-renamed.dump
pg_restore --no-owner --no-acl --role=dataops_service -h <RDS> -U <관리 계정> -d soboro dataops-renamed.dump
```
- 복원 전 `SELECT pg_size_pretty(sum(pg_total_relation_size(c.oid))) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public';` 로 옛 DB 크기를 보고 RDS 여유 공간을 확인한다(`place_snapshot` 원본 JSON 이 크다)
- `flyway_schema_history` 도 함께 옮겨지므로 서버가 뜰 때 이미 적용된 것으로 본다

## 6. 수동 점검 (bastion SSH 또는 SSM)

```bash
bbang-batch job ranking-snapshot; echo "exit=$?"     # 0 성공 · 1 실패/모르는 작업
bbang-batch job typo                                   # 가능한 작업 목록이 로그에 나온다
bbang-batch dataops-nightly 0.1                       # 6분만 수집해 본다
bbang-batch dataops weekly_refresh.py preflight --server http://127.0.0.1:8090   # (dataops-server 를 띄운 뒤)
```
- 수집 상태·보고서·캡처 스풀은 `/opt/bbang-batch/dataops-logs` 에 남는다(`weekly/report_*.md`)
- 반영(apply)·승인 스크립트는 사람이 판단해 `bbang-batch dataops <스크립트> ...` 로 돌린다

## 7. EventBridge·Lambda

Lambda 는 EC2 를 켜고 SSM 명령을 보낸 뒤 바로 끝낸다(수집은 6시간이라 Lambda 15분 제한을 넘는다). 끄기는 명령 끝에 EC2 가 스스로 한다:

```bash
bbang-batch job ranking-snapshot; code=$?
bbang-batch busy || shutdown -h +1      # 다른 작업이 돌고 있으면 끄지 않는다
exit $code
```
(인스턴스 종료 동작이 `stop` 이어야 한다 — 기본값.) SSM 명령 결과(종료 코드·출력)는 Run Command 기록·CloudWatch 에 남는다.

| 작업 (`bbang-batch ...`) | 기존 시각(KST) | 비고 |
|---|---|---|
| `job ranking-snapshot` | 매일 00:05 | |
| `job ranking-reward-issue` | 월 00:15·00:45·06:30 | 재시도 겸 3회 |
| `job store-popularity-score` | 월 03:00 | |
| `job user-interaction-ingest` | 매일 04:00 | 급부상 평소 수준보다 먼저 |
| `job withdrawn-member-retention` | 매일 04:40 | |
| `job admin-audit-log-retention` · `job search-miss-retention` | 매일 04:50 | |
| `job store-trending-baseline` | 매일 05:00 | `TRENDING_ENABLED` |
| `job search-miss-triage` | 매일 06:00 | `SEARCH_MISS_TRIAGE_ENABLED`, 최대 2시간 |
| `job reward-second-morning-roulette` | 매일 09:00 | |
| `job reward-letter-arrival` | 월 10:00 | |
| `dataops-nightly 6` | **매일 10:10 권장** (집 PC 시절 03:00) | 최대 6시간 — 다른 작업과 겹치지 않는 빈 시간대 |
| `job course-reminder` | 매일 19:00 | |
| `job reward-attendance-reminder` | 매일 20:00 | |
| `job monthly-report-run-due` | (꺼져 있었음) | 규칙 없이 둔다 |

**t4g.small(2GB)에는 컨테이너가 한 번에 하나만 떠야 한다**(각 1.6GB 제한·JVM 하나). 수집을 03:00 에 두면 04:00~09:00 작업과 겹쳐 메모리가 모자란다 — 그래서 빈 시간대인 10:10~16:10 에 둔다. 겹치게 하려면 t4g.medium 으로 올린다.

v1 WAS 가 같은 작업을 cron 으로 돌리는 동안에는 ShedLock 이 한 곳에서만 돌게 막는다(같은 락 이름). v1 을 내릴 때까지는 규칙을 켜지 않아도 된다.

## 8. 네이버 차단 감시 (첫 주 매일)

설계 문서가 데이터센터 IP 수집을 위험으로 봤다. `/opt/bbang-batch/dataops-logs/weekly/report_*.md` 에서
- `sync_worker` 종료 코드 3(차단 쿨다운 소진)·쿨다운 횟수
- 수집 가게 수가 집 PC 시절(6시간 ≈ 1만 곳)보다 크게 적은지

를 본다. 막히면 수집만 다른 곳(집 PC)에서 `--server` 로 배치 EC2 의 판정 서버를 부르게 되돌릴 수 있다(서버 주소·API 키는 인자·환경 변수).

## 9. 롤백

- 이미지: 이전 cd-batch 실행을 Re-run 하면 그 digest 로 다시 설치된다(직전 이미지는 일주일 남는다)
- 작업 하나가 문제면 그 EventBridge 규칙만 끈다

## 알려진 한계

- `dataops/agent`(응답 구조 대응 에이전트)는 옮기기만 했다 — 옛 레포 경로·단독 Gradle 빌드·macOS launchd 기준이라 재작업 전에는 돌지 않는다
- dataops Java 코드(`sweetgirl.dataops`)는 원본 그대로 두려고 spotless·checkstyle 대상에서 뺐다
