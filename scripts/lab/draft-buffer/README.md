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

## Docker 없이 페일오버 재기

`failover-native.sh` 머리 주석 참고. 끝나기 3초 전에 마스터를 죽이고, Redis 1번 DB 가 빌 때까지 기다린 뒤 `compare-loss.py` 로 칸 단위 유실을 센다.

## 읽을 때

- 깨지는 단계는 k6 가 단계마다 찍는 `memo_save_duration p(95)` 와 `memo_save_failed` 로 본다 — 두 기준 중 하나라도 넘긴 첫 단계의 **직전 target** 이 답이다. 기준(500ms · 1%)은 결과를 보고 바꾸지 않는다.
- 전·후가 다른 이유는 `server.log` 의 `lock_waits` · `hikari_pending` 으로 설명한다 — 전은 코스 행 락 대기와 풀 고갈이 같이 오르고, 후는 둘이 평평해야 한다.
- 합치기 비율 = `draft_write_accepted` 증가분 / `draft_flush_batch_sum` 증가분.
- 한 머신에서 앱·DB·Redis·k6 를 같이 돌리면 수치는 "구조 비교용"이다(`scripts/perf/README.md` 의 교훈). 절대값을 말할 땐 그렇게 적는다.
- 결과는 `results/<날짜>-<시나리오>/` 에 k6 원본(summary.json)·server.log·kill-master 출력을 그대로 둔다. 3회 중앙값을 계획 문서 "측정 결과"에 적는다.

## 운영 VPC 실험 (2026-11-09 ~ 11)

노트북 한 대가 아니라 운영 VPC 안의 서버들(앱 서버 1 · 부하 발생기 1 · 초안 Redis 3)에서 돌린 것. `infra/` 의 스크립트는 노트북에서
bastion 을 거쳐 각 서버에 명령을 보낸다. **측정 당시 스크립트 그대로이고 호스트 · 경로만 환경 변수로 바꿨다** — `infra/env.example.sh`
를 채워 `source` 한 뒤 돌린다. 숫자와 해석은 `.claude/planning/feat-course-draft-buffer.md`.

| 무엇 | 스크립트 | k6 · 클라이언트 |
|---|---|---|
| 복제 지연과 유실 경계 · `min-replicas-to-write` | `infra/r1-replication.sh` | `loss.js` |
| AOF `everysec` 대 `always` | `infra/r2-fsync.sh` | `buffered.js` |
| 코스 하나가 Redis 에서 차지하는 메모리 · 가득 찼을 때 | `infra/r3-memory.sh` · `infra/r3-full.sh` | — |
| 네트워크 분리(split-brain) — standalone 앱 / sentinel 앱 | `infra/n1-partition.sh` · `infra/n1-partition-sentinel.sh` | `loss.js` |
| VM 정지 페일오버 | `infra/v1-vmstop.sh` | `loss.js` |
| 페일오버 체감 복구 전후(readFrom 제거 · 재연결 간격) | `infra/f3-failover-lab.sh` · `infra/big-failover.sh` | `loss.js` |
| 앱 2대 — 유실 · 중복 · 초당 제한 · 인스턴스 간 신호 | `infra/two-instance.sh` | `loss.js` · `ratelimit.js` · `cross-probe.js` |
| 소켓 수 한계 · 재접속 폭주 · 느린 소비자 | `infra/w1-*.sh` · `infra/w2-storm.sh` · `infra/w3-slow.sh` | `sockets.js` · `storm.js` · `slow_consumers.py` |
| 큰 사양 한 대의 한계와 병목(램프 · 덤프 · JFR) | `infra/big-ramp.sh` · `infra/big-diag.sh` · `infra/big-jfr.sh` | `buffered.js` |
| 동시 접속 4만 · 8만 | `infra/big-sockets.sh` · `infra/big-sockets80k.sh` | `mass_sockets.py` |
| 요청 로그 묶어 쓰기 전후 | `infra/reqlog-ab.sh` (`reqlog-ramp.sh` 를 전 · 후 번갈아) | `buffered.js` |
| 배포 중 소켓 끊김 · 재접속 | `infra/deploy_probe.mjs` | — |
| 신호가 흐르는 상태의 전달 지연(받는 쪽을 다른 서버로) | `infra/fanout-dist.sh` (한 서버에서 다 돌리던 옛 방식은 `infra/fanout-single-host.sh`) | `fanout_probe.py` · `shed.js` |
| DB 를 죽였다 살리기 | `infra/db-outage.sh` | `loss.js` · `sample-db.sh` |
| 수평 확장 1 · 2 · 4대 / 발생기 2대로 3대 | `infra/scale.sh` · `infra/scale-two-generators.sh` | `scale.js` |
| Redis 가 죽을 때와 멈출 때 · 끊김 중 즉시 거절 | `infra/redis-kill-pause.sh` | `failfast.js` · `sample-fo.sh` |
| 멈춘 Redis 에서 재연결(40초 pause 전 · 후) | `infra/redis-pause-reset.sh` | `failfast.js` |
| 인기 코스 쏠림 | `infra/hot-course.sh` | `fanout_probe.py`(`HOT_COURSE`) · `scale.js` |
| 대규모 재접속 폭주 | `infra/reconnect-storm.sh` | `storm_sockets.py` |
| 장기 부하(새는 것) | `drive-soak.sh`(발생기) + `soak-sample.sh`(앱 서버) | `fanout_probe.py` · `failfast.js` |
| 노트북 없이 서버끼리 묶음 실행 | `night-lab.sh`(앱 서버) + `night-gen.sh`(발생기) | — |

주의:

- 복제본 kill · `CONFIG SET` · iptables · EC2 정지처럼 **운영 Redis 를 건드리는 스크립트**(`r1` · `r2` · `n1*` · `v1`)는 같은 서버의
  캐시 Redis 도 같이 흔든다. 팀에 알리고 돌린다.
- 발생기 한 대는 출발 포트 수(기본 28,232개)만큼만 접속을 만든다. 그 이상은 `net.ipv4.ip_local_port_range` 를 넓히거나 발생기를 늘린다
  (`big-sockets80k.sh` 가 둘 다 한다).
- **받는 쪽 측정 프로그램을 쓰는 쪽(k6)과 같은 서버에 두지 않는다.** 받는 쪽이 CPU 를 못 받아 밀리면 "서버가 늦게 보냈다"와 구분되지 않는다.
  `fanout_probe.py` 한 프로세스에 소켓 1,000개쯤, 서버를 나눠서 돌린다(`infra/fanout-dist.sh`). 시계는 chrony 동기(1ms 안쪽)를 시작 때 찍어 둔다.
- **토큰은 1시간짜리다.** 서버끼리 파일을 넘기는 단계가 조용히 실패하면 만료된 토큰으로 돈다 — 시작 전에 토큰으로 API 를 한 번 불러 200 을 확인한다(`infra/hot-course.sh`).
- **원격 명령줄에 `pkill -f` 의 대상 문자열을 넣지 않는다.** 그 명령을 실행하는 셸 자신이 걸려 죽는다(`pkill -f 'watch-queue[s]'` 처럼 한 글자를 대괄호로 감싼다).
- **도는 스크립트 파일을 고치지 않는다.** bash 는 파일을 읽어 가며 실행한다 — 복사본을 고쳐 다음 회차에 쓴다.
- 보안그룹이 서버끼리 22번을 막아 두었다. 서버끼리 주고받을 때는 8080~8091(같은 보안그룹 안에서 열림)의 HTTP 를 쓴다(`night-lab.sh`).
- k6 는 `ramping-vus` 의 최대 VU 를 미리 만든다(소켓 VU 하나 ≈ 0.67MB). 수천 VU 는 `--compatibility-mode=base` 로 돌린다.
