# 메모 초안 버퍼 측정

설계·결정·측정 계획은 `.claude/planning/feat-course-draft-buffer.md`. 여기는 돌리는 법만.

## 무엇을 재나 (대표 숫자 3개)

| # | 지표 | 전 | 후 | 스크립트 |
|---|---|---|---|---|
| 1 | 버틸 수 있는 동시 편집자 수 — 저장 p95 < 500ms **그리고** 에러율 < 1% 가 처음 깨지는 단계의 직전 값 | PATCH 0.5초 | PUT memo-draft 0.5초 | `baseline.js` → `buffered.js` |
| 2 | 페일오버 중 유실 건수 — 부하 중 마스터 kill → 끝난 뒤 DB 와 비교 | 약속 끔 | 약속 켬(목표 0건) | `failover.js` + `kill-master.sh` + `verify-loss.sql` |
| 3 | 일행 화면 반영 지연 p95 — 쓰는 쪽 요청 → 받는 쪽이 새 내용을 손에 넣기까지 | 8초 폴링 + ETag | STOMP DRAFT | `latency-probe.js` (MODE=poll / push) |

보조 숫자(합치기 비율 · 무관 API p95 · DB 락 대기 · Hikari pending · 플러시 지연·묶음 · backlog · 503 비율 · 재전송 · 승격 시간)는
`observe.sh` 와 k6 summary 에서 나온다.

## 준비

```bash
# 1. 랩 PG (운영 덤프 복제본) + 캐시 Redis
docker compose -f docker-compose.lab-postgres.yml up -d
docker compose up -d redis

# 2. 초안 Redis HA — /etc/hosts 에 두 줄 먼저 (compose 파일 머리 주석 참조)
docker compose -f docker-compose.draft-ha.yml up -d
redis-cli -p 26379 SENTINEL get-master-addr-by-name draft     # redis-draft-master / 6380

# 3. 시드 — 코스 N개 × 멤버 8 × 정류장 5
docker exec -i postgres-lab psql -U sweetgirl -d soboro -v courses=50 -f - < scripts/lab/draft-buffer/seed-draft-courses.sql
# courses.csv · member-ids.txt · tokens.csv — seed-draft-courses.sql 끝의 주석 세 명령 (토큰은 1시간짜리, 만들고 바로 돈다)

# 4. 앱 (lab 프로파일 = 랩 PG). "후" 측정만 초안 Redis 를 켠다
./gradlew bootRun --args='--spring.profiles.active=local,lab --management.server.port=8091'
./gradlew bootRun --args='--spring.profiles.active=local,lab --management.server.port=8091 \
  --course.draft.redis.mode=sentinel --course.draft.redis.sentinel-nodes=localhost:26379,localhost:26380,localhost:26381 \
  --course.draft.write-enabled=true'

# 5. ENV.md 를 채운다 — 전·후 사이에 아무것도 바꾸지 않는다
```

## 돌리기

```bash
D=$(date +%m%d)
# 서버 쪽 숫자(1초마다) — 각 회차마다 따로 켠다
scripts/lab/draft-buffer/observe.sh scripts/lab/draft-buffer/results/${D}-baseline/server.log &

# 대표 숫자 1 (전 / 후) — 램프업으로 깨지는 단계를 찾는다. 3회.
k6 run --summary-export scripts/lab/draft-buffer/results/${D}-baseline/summary.json scripts/lab/draft-buffer/baseline.js
k6 run --summary-export scripts/lab/draft-buffer/results/${D}-buffered/summary.json scripts/lab/draft-buffer/buffered.js

# 대표 숫자 2 — 켬/끔 두 번. 돌아가는 중간(30초쯤)에 다른 터미널에서 kill-master.sh.
PROMISE=on  k6 run --summary-export scripts/lab/draft-buffer/results/${D}-failover-on/summary.json  scripts/lab/draft-buffer/failover.js
PROMISE=off k6 run --summary-export scripts/lab/draft-buffer/results/${D}-failover-off/summary.json scripts/lab/draft-buffer/failover.js
scripts/lab/draft-buffer/kill-master.sh            # 승격 ms 가 찍힌다. 끝나면 --revive
docker exec postgres-lab psql -U sweetgirl -d soboro -v ended_at="'<편집 끝난 시각 KST>'" -f - < scripts/lab/draft-buffer/verify-loss.sql
redis-cli -p <새 마스터 포트> ZCARD draft:due      # 0 이어야 한다

# 대표 숫자 3 (전 / 후)
MODE=poll k6 run --summary-export scripts/lab/draft-buffer/results/${D}-latency-poll/summary.json scripts/lab/draft-buffer/latency-probe.js
MODE=push k6 run --summary-export scripts/lab/draft-buffer/results/${D}-latency-push/summary.json scripts/lab/draft-buffer/latency-probe.js
```

## 읽을 때

- 깨지는 단계는 k6 가 단계마다 찍는 `memo_save_duration p(95)` 와 `memo_save_failed` 로 본다 — 두 기준 중 하나라도 넘긴 첫 단계의 **직전 target** 이 답이다. 기준(500ms · 1%)은 결과를 보고 바꾸지 않는다.
- 전·후가 다른 이유는 `server.log` 의 `lock_waits` · `hikari_pending` 으로 설명한다 — 전은 코스 행 락 대기와 풀 고갈이 같이 오르고, 후는 둘이 평평해야 한다.
- 합치기 비율 = `draft_write_accepted` 증가분 / `draft_flush_batch_sum` 증가분.
- 한 머신에서 앱·DB·Redis·k6 를 같이 돌리면 수치는 "구조 비교용"이다(`scripts/perf/README.md` 의 교훈). 절대값을 말할 땐 그렇게 적는다.
- 결과는 `results/<날짜>-<시나리오>/` 에 k6 원본(summary.json)·server.log·kill-master 출력을 그대로 둔다. 3회 중앙값을 계획 문서 "측정 결과"에 적는다.
