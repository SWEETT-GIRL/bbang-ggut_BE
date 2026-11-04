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
