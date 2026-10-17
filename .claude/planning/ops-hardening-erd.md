# [운영 보강] ERD — ShedLock · 어드민 감사 로그

브랜치 `feat/ops-hardening` (dev 기준).

> **상태: 승인됨, 구현함 (2026-10-16).** 승인은 작업을 맡긴 코디네이터를 통해 전달받았다 — 머지 전에 사용자가 이 문서의 DDL 을 한 번 더 확인한다.

같은 브랜치에서 표 변경 없이 구현한 것: 5xx 슬랙 알림 · 요청 id · IP 횟수 제한 · 탈퇴 개인 정보 익명화 · `X-Server-Time` 헤더.

---

## 1. ShedLock (`shedlock`) — 구현함

### 왜 필요한가
- 운영 WAS 는 1대지만 **배포는 같은 서버 안 blue-green** 이다(`chore-prod-single-server.md`). 새 컨테이너가 뜨고 옛 컨테이너가 graceful shutdown(20초)으로 내려가기까지 **둘이 같이 떠 있는 구간**이 있다. 그 사이 cron 이 걸리면 두 번 돈다.
- 일부 작업은 각자 막고 있었다(advisory lock · 선점 행 · 유니크). 막는 방법이 작업마다 달랐고, **푸시 발송처럼 아무것도 안 막는 작업이 있었다.**

### DDL — `V20261016020000__create_shedlock.sql`

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

### 구현
- 의존성 `net.javacrumbs.shedlock:shedlock-spring` · `shedlock-provider-jdbc-template` **7.10.1** — 5.x 는 Spring 6(Boot 3)용이라 Boot 4.0.6(Spring 7)에 맞는 7.x 를 썼다.
- `SchedulerLockConfig`: `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT30S")`, `JdbcTemplateLockProvider` + `usingDbTime()`. `shedlock.enabled=false` 면 꺼진다(테스트).
- **모든 `@Scheduled` 메서드에 `@SchedulerLock(name = "클래스_메서드")`**. `SchedulerLockConfigTest` 가 클래스패스를 훑어 락 없는 `@Scheduled` 가 생기면 깨진다.
- 기존 advisory lock · 선점 행 · 유니크는 그대로 둔다(어드민 수동 실행과의 겹침도 막고 있어 지우면 안 된다).

### 작업별 락 (22개)

| 락 이름 | 주기 | lockAtMostFor | lockAtLeastFor | 기존 장치 |
|------|------|:--:|:--:|------|
| `RewardPushScheduler_attendanceReminders` | 매일 20:00 | 10분 | 30초 | 없음 → 중복 푸시 막힘 |
| `RewardPushScheduler_letterArrivals` | 월 10:00 | 10분 | 30초 | 없음 |
| `RewardPushScheduler_secondMorningRoulette` | 매일 09:00 | 10분 | 30초 | 없음 |
| `CourseReminderScheduler_tomorrowReminders` | 매일 19:00 | 10분 | 30초 | 없음 |
| `RankingSnapshotScheduler_dailySnapshot` | 매일 00:05 | 10분 | 30초 | 선점 행 |
| `RankingRewardScheduler_issueLastWeek` | 월 00:15·00:45·06:30 | 10분 | 30초 | (주, 회원) 유니크 |
| `UserInteractionIngestScheduler_dailyIngestAndRecompute` | 매일 04:00 | 30분 | 30초 | advisory lock |
| `StorePopularityScoreScheduler_weeklyPopularityRefresh` | 월 03:00 | 30분 | 30초 | advisory lock |
| `StoreTrendingScheduler_dailyBaselineRefresh` | 매일 05:00 | 10분 | 30초 | 없음 |
| `StoreTrendingScheduler_refreshRanking` | 5분마다 | 4분 | 30초 | Redis SETNX |
| `StoreRegionResolutionScheduler_refresh` | 30초 폴링 | 2분 | **10초** | 없음(멱등) |
| `MenuClassificationScheduler_classifyNextBatch` | 1분 폴링 | 10분 | 30초 | 없음 — LLM 비용 중복 막힘 |
| `MonthlyReportAdminScheduler_processActiveReportRuns` | 5초 폴링 | 5분 | **3초** | 상태 전이 |
| `MonthlyReportAdminScheduler_sendScheduledReportPushes` | 10초 폴링 | 5분 | **5초** | 상태 전이 |
| `MonthlyReportAutoPreparationScheduler_prepare` | 매시 05분 + 기동 시 | 10분 | 30초 | 스위치 |
| `MonthlyReportScheduler_runDue` | 기본 비활성 | 30분 | 30초 | — |
| `ImagePipelineMetricsScheduler_refreshBacklogGauges` | 1분 | 2분 | 30초 | — |
| `ImagePipelineMetricsScheduler_refreshS3ReconcileGauges` | 매일 04:00 | 30분 | 30초 | — |
| `WithdrawnMemberRetentionScheduler_anonymizeExpired` (이 브랜치) | 매일 04:40 | 5분 | 30초 | 멱등 UPDATE |
| `SearchMissTriageScheduler_triageDaily` | 매일 06:00 | **2시간** | 30초 | 없음 — LLM·네이버 호출과 Slack 리포트 중복 막힘 |
| `SearchMissRetentionScheduler_deleteHandled` | 매일 04:50 | 10분 | 30초 | 멱등 DELETE |
| `AdminAuditLogRetentionScheduler_deleteExpired` (이 브랜치) | 매일 04:50 | 10분 | 30초 | 멱등 DELETE |

- 짧은 폴링 3개는 lockAtLeastFor 를 주기보다 짧게 뒀다. 기본 30초를 걸면 5초 폴링이 30초로 느려진다.
- 알아 둘 점: `ImagePipelineMetrics` 게이지는 **인스턴스마다 메모리에 있다.** 락이 걸리면 락을 못 잡은 쪽 게이지는 갱신되지 않는다 — 지금은 한 대라 문제없고, WAS 를 늘리면 이 두 작업은 락을 풀거나 게이지를 DB 기준으로 바꾼다.

---

## 2. 어드민 감사 로그 (`admin_audit_logs`) — 구현함

### 왜 필요한가
- 어드민 API 는 `X-Admin-Key` 하나(`AdminApiKeyFilter`)로 열린다. 누가 언제 무엇을 바꿨는지 남는 곳이 `request_logs` 뿐이었는데, 사용자 요청과 섞여 있고 보관 정책도 다르다.

### DDL — `V20261016021000__create_admin_audit_logs.sql`

```sql
CREATE TABLE admin_audit_logs (
    id              BIGSERIAL    PRIMARY KEY,
    admin_label     VARCHAR(50),
    method          VARCHAR(10)  NOT NULL,
    path            VARCHAR(255) NOT NULL,
    query           VARCHAR(500),
    target_type     VARCHAR(50),
    target_id       VARCHAR(64),
    request_summary TEXT,
    status_code     INT,
    ip              VARCHAR(45),
    user_agent      VARCHAR(255),
    request_id      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_audit_logs_created_at ON admin_audit_logs (created_at DESC);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs (target_type, target_id);
```

### 구현
- `AdminAuditLogFilter` — `AdminApiKeyFilter`(HIGHEST_PRECEDENCE+10) 바로 뒤(+11). `/admin/**` · `/api/admin/**` 의 **쓰기(POST·PUT·PATCH·DELETE)** 와 **회원 개인 정보 조회 `GET /api/admin/members…`** 를 남긴다. 열쇠를 통과한 요청만 닿는다.
- `admin_label`: `admin.audit.key-label`(env `ADMIN_AUDIT_KEY_LABEL`)이 있으면 그것, 없으면 `key:` + 열쇠 SHA-256 앞 8자리. 열쇠 원문은 남기지 않는다.
- `request_summary`: JSON 이면 이름이 `key` 로 끝나거나 password·passwd·token·secret·phone·email 을 품은 필드를 `***` 로 가리고 2KB 로 자른다. JSON 이 아니면 `(JSON 아님 · N bytes)`. 컨트롤러가 바디를 읽기 전에 끝난 요청(404 등)은 비어 있다.
- `target_type`/`target_id`: 경로 첫 칸에서 뽑는다(members→MEMBER, courses→COURSE, stores→STORE, menus→MENU, notifications→PUSH, rewards→REWARD, letters→LETTER, banners→NOTICE, reports→REPORT, world→WORLD, monthly-reports→MONTHLY_REPORT, 그 밖은 대문자). 다음 칸이 숫자·UUID·월·날짜면 id.
- 저장은 `REQUIRES_NEW` 로 따로 커밋하고, 실패하면 경고 로그만 — 어드민 요청 결과는 바뀌지 않는다. 어드민 요청이 적어 동기 저장이다.
- 조회: `GET /admin/audit-logs?page&size&targetType&targetId` — 최신순, size 최대 100.
- 보관: `admin.audit.retention-days`(기본 180) 지난 줄을 매일 04:50 지운다(ShedLock).
- 스위치: `admin.audit.enabled`(기본 true).
