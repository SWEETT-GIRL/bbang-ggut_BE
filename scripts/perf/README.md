# 선착순 쿠폰 부하 측정

설계와 측정 결과는 `.claude/planning/feat-coupon-concurrency.md`.

## 왜 부하 생성기를 분리하나

앱과 k6 를 한 머신(맥)에서 돌렸더니 **시스템 CPU 100% 중 JVM 이 19% 밖에 못 받았다.**
그 상태의 처리량은 서버 용량이 아니라 "CPU 를 19% 받았을 때의 값"이고, 같은 설정으로 두 번 재면
검색 p99 가 1.4배씩 흔들렸다. 병목을 고쳐 처리량이 5배가 되자 **부하 생성기가 새 병목**이 된 것이다.

그래서 측정은 두 머신으로 나눈다.

```
[부하 생성기 · k6]  ──사설 IP──>  [앱 + PostgreSQL]
 넉넉한 사양                       운영과 같은 사양
```

앱 쪽을 운영과 같은 사양으로 맞추는 것이 중요하다. 더 좋은 사양에서 재면 운영이 절대 못 내는
수치가 나온다.

## 준비 (앱 서버에서)

```bash
# 회원 5만 명 — 1인 1매라 서로 다른 회원이 그만큼 필요하고, FK 때문에 실제 행이어야 한다
psql -v target=50000 -f seed-coupon-members.sql

# 회원 id 를 뽑아
psql -At -c "SELECT id FROM member WHERE nickname LIKE '[부하]회원%' ORDER BY id" > member-ids.txt
```

## 준비 (부하 생성기에서)

```bash
# k6 — 결과를 비교하려면 버전을 맞춘다
K6=v2.2.0
curl -fsSL "https://github.com/grafana/k6/releases/download/${K6}/k6-${K6}-linux-arm64.tar.gz" | tar xz
sudo mv k6-${K6}-linux-arm64/k6 /usr/local/bin/

# 토큰 — member-ids.txt 를 받아와서
node generate-tokens.mjs member-ids.txt tokens.csv
```

**액세스 토큰은 1시간짜리다.** 만들고 바로 돌려야 한다. 만료된 파일로 재면 5만 건이 전부 401 이
되어 회차가 통째로 날아간다. `run-remote.sh` 가 시작 전에 만료를 확인한다.

인증을 우회하지 않는 이유는 **JWT 필터를 실제로 태워야 부하가 진짜이기 때문**이다. 반대로 k6 안에서
5만 개를 서명하면 부하 생성기 CPU 를 측정 대상이 아닌 일에 쓴다. 그래서 밖에서 미리 만든다.

## 측정

```bash
# 로컬 랩 (앱·DB·k6 가 한 머신) — 정합성 확인과 구조적 병목 찾기까지만 믿을 수 있다
MGMT_PORT=8091 ./run-measurement.sh R1 5000 10s 500

# 원격 (권장) — 처리량 수치를 믿으려면 이쪽
APP_HOST=10.3.31.174 PERF_KEY=<대시보드키> ./run-remote.sh R5 5000 10s 500
```

| 환경변수 | |
| --- | --- |
| `APP_HOST` | 앱의 **사설 IP**. 같은 AZ 면 전송료 0원 |
| `MGMT_PORT` | actuator 포트 (기본 8081) |
| `PERF_KEY` | `/dev/coupons/reset` 용. local 밖에서 필요 |
| `COUPON_ID` | 리셋을 못 쓸 때 쓸 쿠폰 id |
| `ALLOW_STAGING` | 스테이징 도메인을 때릴 때 `true`. 운영은 어떤 경우에도 막힌다 |

## 발급 방식 바꿔가며 재기

앱 기동 인자만 바꾸면 된다. 구현은 설정으로 고른다.

```bash
--coupon.issuer=pessimistic                              # 비관적 락 (기본값)
--coupon.issuer=atomic                                   # 조건부 UPDATE
--coupon.issuer=atomic --coupon.sold-out-flag.enabled=true   # + 품절 플래그
```

**한 번에 하나씩만 바꾼다.** 둘을 같이 넣으면 어느 쪽이 얼마나 기여했는지 알 수 없다.

## 읽을 때 주의

- **k6 는 밖에서 본 증상만 본다.** 500 이 풀 고갈인지 락 타임아웃인지는 actuator 와 앱 로그가 답한다.
  그래서 둘을 같은 타임스탬프로 맞춰 본다.
- **p99 는 응답을 받은 것들만의 분포다.** 아예 못 보낸 요청(dropped)은 거기 없다. 둘을 같이 봐야 한다.
- **배경 조회(`/stores/search`)는 트래픽 재현이 아니라 지연 프로브다.** 초당 50건은 쿠폰의 1% 라
  부하에 기여하지 않고, 40초면 표본 2,000건이라 p99 를 낼 만하다. "실제 트래픽과 공존하는가"는
  실제 초당 요청 수를 알아야 답할 수 있는 다른 질문이다.
- **쿼리의 한글은 반드시 인코딩한다.** 날것으로 두면 톰캣이 URI 단계에서 400 으로 거부하고 앱까지
  가지 않는다. 그걸 모르고 "검색이 느려졌다"고 읽을 뻔했다. `setup()` 이 부하 전에 200 을 확인한다.

## 파일

| | |
| --- | --- |
| `coupon-spike.js` | k6 시나리오 (도착률 기반 + 배경 조회 프로브) |
| `run-remote.sh` | 원격 측정 한 회차 — 지표 수집·요약까지 |
| `run-measurement.sh` | 로컬 랩 측정 한 회차 |
| `collect-metrics.sh` | actuator 1초 스크랩 (로컬용) |
| `summarize.sh` | k6 CSV → 초별·결과별 표 |
| `seed-coupon-members.sql` | 측정용 회원 시드 (멱등) |
| `generate-tokens.mjs` | JWT 5만 개 → CSV (의존성 없음) |
| `api-load.js` | 읽기 API 일반 부하 (쿠폰과 무관) |
