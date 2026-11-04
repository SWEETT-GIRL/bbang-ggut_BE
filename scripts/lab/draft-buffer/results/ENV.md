# 측정 환경 (고정)

"전"과 "후"는 반드시 같은 환경에서 잰다. 아래를 채운 뒤 바꾸지 않는다. 바꾸면 전부 다시 잰다.

| 항목 | 값 |
|---|---|
| 측정일 | |
| 머신 (앱·DB·Redis·k6) | 예: MacBook Pro M3 Pro, 36GB — 한 머신이면 "한 머신" 이라고 적고, 수치는 구조 비교용임을 명시 |
| 앱 | 커밋 해시 · `--spring.profiles.active=local,lab --management.server.port=8091` |
| JVM | `java -version` 출력 · 힙 옵션 |
| Hikari | `maximum-pool-size=10` (application.properties 기본) |
| PostgreSQL | postgres-lab (docker-compose.lab-postgres.yml) · 덤프 날짜 · `shared_buffers=1GB` |
| 데이터량 | courses / course_members / course_store_memos / stores 행 수 |
| 캐시 Redis | docker-compose.yml redis (6379) — 켜짐/꺼짐 |
| 초안 Redis | docker-compose.draft-ha.yml (master 6380 · replica 6381 · sentinel 26379-26381) · AOF everysec · noeviction · 256mb |
| k6 | `k6 version` |
| 시드 | `seed-draft-courses.sql -v courses=N`, 멤버 8/코스, 정류장 5/코스 |
| 램프업 | `STAGES` 값 (기본 lib.js 참조) |
| 판정 기준 | 저장 p95 < 500ms 그리고 에러율 < 1% — 측정 전에 고정, 결과 보고 바꾸지 않는다 |
| 회차 | 3회 돌려 중앙값 |

## 회차 기록

| 날짜-시나리오 | 폴더 | 메모 |
|---|---|---|
| | `results/<날짜>-baseline/` | summary.json · server.log |
| | `results/<날짜>-buffered/` | |
| | `results/<날짜>-failover-on/` · `-off/` | kill-master.sh 출력(승격 ms) · verify-loss 결과 |
| | `results/<날짜>-latency-poll/` · `-push/` | |

## 환경 A — dev 서버 (2026-11-04 기준선 3회)

| 항목 | 값 |
|---|---|
| 측정일 | 2026-11-04 13:47~14:17 KST |
| 측정 대상 | dev WAS 10.3.31.174 — 앱 컨테이너 `bbang-ggut-be`(dev 브랜치 `13819464`, 프로파일 staging) + 같은 서버 Postgres 컨테이너 `staging-postgres`(PG 18.6) |
| 부하 생성기 | 노트북 k6 v2 → bastion 54.116.12.200 터널 `-L 18080:10.3.31.174:8080` (nginx 우회, 앱 직결) |
| Hikari | 10 (기본) |
| 데이터 | dev DB(운영 부분 덤프) + `seed-draft-courses.sql -v courses=40` (멤버 8/코스, 정류장 5/코스, 회원 320명) |
| 토큰 | `/dev/auth/test-login` 으로 320개 (dev JWT) |
| 램프업 | `STAGES` 기본값 16→16→40→40→80→80→160→160→320→320 (각 30s/60s, 총 7m30s), WRITE_INTERVAL_MS 500, SEARCH_RATE 5 |
| 판정 기준 | 저장 p95 < 500ms 그리고 에러율 < 1% (단계별 태그) |
| 서버 수집 | WAS 에서 `PG_CONTAINER=staging-postgres MGMT_PORT=8081 observe.sh /tmp/observe-baseline.log` (서버 시계 UTC) |
| 회차 | run1(단계 태그 없음) · run2 · run3 → `results/1104-baseline-dev*` |
| 주의 | dev 는 팀 공유 — 측정 중 dev 앱이 느려진다. "후" 측정은 PR #1005 머지·재배포 뒤 같은 조건으로 |
