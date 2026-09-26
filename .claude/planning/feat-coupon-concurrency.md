# 선착순 쿠폰 동시성 — 1차: 비관적 락

작성 2026-09-21 · 브랜치 `feat/coupon-concurrency` · base `origin/dev`

> **이 문서는 비관적 락 한 가지만 다룬다.**
> 다음에 뭘 할지는 여기서 정하지 않는다 — §12의 측정 결과를 보고 그때 정한다.

---

## 0. 무엇을 하는가

**선착순 쿠폰 발급을 비관적 락으로 만들고, 재고 100장에 5만 명이 몰릴 때 무슨 일이 벌어지는지 측정한다.**

목적이 기능이 아니라 측정이므로, 쿠폰 도메인은 **측정에 필요한 최소한만** 만든다.

## 1. 이번에 답할 질문

| # | 질문 | 어떻게 판정하나 |
|---|---|---|
| Q1 | 락이 없으면 정말 초과 발급이 나는가 | JUnit 동시성 테스트. 발급 행 > 100 이면 재현 |
| Q2 | 비관락이 정합성을 지키는가 | 정확히 100행, 중복 0 |
| Q3 | 5만이 몰리면 무엇이 먼저 마르는가 | k6 + actuator + 로그를 시각으로 붙여서 |
| Q4 | 커넥션 풀을 10→50으로 늘리면 달라지는가 | 같은 시나리오 재측정 |
| Q5 | 쿠폰 폭주가 다른 API에 영향을 주는가 | 동시에 도는 `/stores/search` p99 |

**Q1은 대비군이다.** 락을 뺐을 때 실제로 깨지는 걸 봐야 비관락이 일하고 있다는 게 증명된다.

## 2. 범위

### 만든다
- 쿠폰 발급 API 1개 (`POST /coupons/{couponId}/issue`)
- 비관적 락 구현 + 락 없는 구현(대비군, 테스트 전용)
- 실험 반복용 리셋 엔드포인트 (dev 전용)
- k6 시나리오 + actuator·로그 수집 스크립트
- JUnit 동시성 테스트

### 안 만든다
- 쿠폰 사용·만료·취소, 내 쿠폰함 조회
- 어드민 쿠폰 생성 API — 마이그레이션 시드와 dev 리셋으로 충분
- **다음 단계 대비 설계 일체.** 측정 결과를 보고 다음 브랜치에서 정한다

## 3. 테이블

마이그레이션은 `V20260921HHMMSS__create_coupons.sql`. 테이블명은 **복수형** — 최근 도메인 관례(`world_planets`, `dessert_item_*`)를 따른다.

### `coupons`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `name` | VARCHAR(100) NOT NULL | |
| `total_quantity` | INTEGER NOT NULL | 재고 |
| `issued_quantity` | INTEGER NOT NULL DEFAULT 0 | **모든 요청이 줄 서는 지점** |
| `opens_at` | TIMESTAMP NOT NULL | 오픈 시각 |
| `created_at` / `updated_at` | TIMESTAMP | `BaseTimeEntity` |

**`issued_quantity <= total_quantity` CHECK 제약은 걸지 않는다.** 걸면 Q1(락 없을 때 초과 발급)이 DB에서 막혀 재현이 안 된다. 실험이 끝나고 이 컬럼을 계속 쓸지 정해진 뒤에 추가한다.

### `coupon_issues`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `coupon_id` | BIGINT NOT NULL REFERENCES coupons (id) | |
| `member_id` | BIGINT NOT NULL REFERENCES member (id) | |
| `issued_at` | TIMESTAMP NOT NULL | |

`CONSTRAINT uq_coupon_issue UNIQUE (coupon_id, member_id)` — 1인 1매.

### 왜 `member_id`를 `@ManyToOne`이 아니라 `Long`으로 두는가

이 레포는 두 방식이 다 쓰인다 (`BreadRecord`는 `@ManyToOne Member`, `MemberWorld`·`UserTasteProfile`은 `Long memberId`).

**쿠폰은 `Long memberId`를 쓴다.** `@ManyToOne`이면 발급할 때마다 `Member`를 로드하고, 그 왕복이 **락을 쥔 채로** 일어난다. 락 보유 시간이 이 측정의 핵심 변수인데 거기에 쿼리를 하나 더 얹을 이유가 없다. 발급 경로에서 회원 정보를 읽을 일도 없다.

FK는 건다 — 관례이기도 하고, 5만 명 시드가 실제 `member` 행이어야 한다는 제약이 되기도 한다.

### 왜 `issued_quantity` 카운터를 두는가

`count(*)`로 매번 세면 그 자체가 정합성 판정 기준이 못 된다. 카운터를 따로 두면 **"카운터와 실제 행 수가 일치하는가"**가 검증 항목이 된다 — 락이 깨지면 여기서 어긋난다.

## 4. 비관적 락 구현

락은 **PostgreSQL이 행에 건다.** 코드에서는 JPA 어노테이션으로 쓰고, JPA가 `SELECT ... FOR UPDATE` SQL을 만든다. 락은 **트랜잭션이 끝날 때 풀린다.**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
Optional<Coupon> findWithLockById(Long id);
```

선례가 이미 있다 — `CourseRepository.findWithLockByIdAndDeletedAtIsNull` (코스 초대 정원 9명).

### 발급 흐름

```
@Transactional 시작
  1. findWithLockById      → FOR UPDATE, 여기서 줄 선다
  2. 재고 검사              → 소진이면 409
  3. issued_quantity + 1
  4. coupon_issues INSERT  → 유니크 위반이면 409 (중복 발급)
@Transactional 종료 → 락 해제
```

### 주의점

- **self-invocation 금지.** 같은 클래스 안에서 부르면 프록시를 안 타 트랜잭션이 없고, 락이 즉시 풀린다. 가장 흔한 버그다
- **락 타임아웃을 반드시 준다.** 없으면 무한 대기. 3초로 시작하고 Q3 결과를 보고 조정한다
- `LockTimeoutException` / `PessimisticLockException`은 재시도 안내 응답으로 매핑한다
- 1인 1매는 락과 별개로 **DB 유니크 제약**이 최종 방어선이다

### 대비군 — 락 없는 구현

`CouponIssuer` 인터페이스를 두고 구현 두 개를 만든다. **인터페이스는 대비군 때문에 두는 것이지 나중을 위한 확장점이 아니다.**

| 구현 | 쓰는 곳 |
|---|---|
| `PessimisticCouponIssuer` | 실제 경로 |
| `NoLockCouponIssuer` | **테스트 전용.** Q1 재현 |

## 5. 부하 실험

재고 100장 / 10초간 5만 건 / 배경 조회 부하 동시.

| 회차 | 풀 | 보는 것 |
|---|---|---|
| R1 | 10 (기본값) | 기준선. 무엇이 먼저 마르나 (Q2·Q3·Q5) |
| R2 | 50 | 풀을 늘리면 달라지는가 (Q4) |

**여기까지다.** R3 이후는 R1·R2 결과를 보고 정한다.

### 왜 10초에 5만인가

5만 명이 동시 접속한다는 뜻이 아니다. **도착률** 5,000 req/s다. k6의 `constant-arrival-rate`가 이걸 직접 만든다 — 응답이 느려지면 VU를 알아서 늘린다.

## 6. 측정

증거는 세 겹이고 **타임스탬프로 맞춰 본다.**

| 출처 | 무엇 | 수집 |
|---|---|---|
| k6 | 초별 요청·성공·409·500·연결실패 | `--out csv=result.csv` |
| actuator | Hikari active/pending/timeout, 톰캣 busy | 1초 간격 curl 루프 |
| 앱 로그 | 풀 고갈·락 타임아웃 **정확한 건수** | `grep -c` |

k6는 **밖에서 본 증상**만 본다. 500이 왜 났는지(풀 고갈인지 락 타임아웃인지)는 actuator와 로그가 답한다.

### 정합성 (전부 통과해야 함)

```sql
select count(*) from coupon_issues where coupon_id = 1;            -- = 100
select issued_quantity from coupons where id = 1;                  -- = 100
select count(*) from (select member_id from coupon_issues
  where coupon_id = 1 group by member_id having count(*) > 1) t;   -- = 0
```

### 성능·영향

- 전체 소진 시간, 발급 p99, 409 p99, **초별 에러 분포**
- 쿠폰 폭주 중 `/stores/search` p99

## 7. 부하 생성

### 회원 5만 명
랩 DB에 현재 10,102명. FK 때문에 실제 행이 필요하다. 부족분을 시드한다 (`scripts/perf/seed-coupon-members.sql`). `member.nickname`이 NOT NULL이라 유니크한 값을 만들어 넣는다.

### JWT 5만 개
`JwtProvider.generateAccessToken(Long memberId)`가 memberId만 받는다. **JWT 필터를 실제로 태워야 부하가 진짜이므로** 인증 우회는 쓰지 않는다. 미리 뽑아 CSV로 만들고 k6 `SharedArray`로 읽는다. 만료가 짧으면 실험 중 죽으므로 **생성 직후 바로 돌린다.**

### k6 시나리오
```
쿠폰 스파이크:  constant-arrival-rate  5000/s × 10s
배경 조회부하:  constant-arrival-rate    50/s × 30s  (/stores/search)
```

응답은 `ApiResponse.code`를 태그로 읽어 분류한다. `http.setResponseCallback(http.expectedStatuses(200, 201, 409))`로 정상 거절을 실패율에서 뺀다. 연결 실패는 `res.status === 0` + `error_code`로 따로 센다.

## 8. 파일 구성

```
coupon/entity/Coupon.java                  extends BaseTimeEntity, @Table(name="coupons")
coupon/entity/CouponIssue.java             Long memberId (§3)
coupon/repository/CouponRepository.java    findWithLockById
coupon/repository/CouponIssueRepository.java
coupon/issuer/CouponIssuer.java            + Pessimistic / NoLock 두 구현
coupon/service/CouponCommandService.java   @Service @Transactional
coupon/controller/CouponController.java    @RequestMapping("/coupons")
coupon/code/CouponErrorCode.java           implements BaseErrorCode
coupon/exception/CouponException.java      extends CustomException
coupon/dto/response/CouponIssueResponse.java
```

- `/api` prefix는 붙이지 않는다 (클라이언트 API 관례)
- Swagger `@Tag`(클래스) + `@Operation`(메서드) **한국어 필수**
- 인증 주체는 `@AuthenticationPrincipal Long memberId`
- 에러코드 문자열은 서술형(`"COUPON_SOLD_OUT"`) — 최신 도메인(breadlog) 방식
- 조회가 없으므로 `QueryService`는 만들지 않는다

## 9. 테스트

CLAUDE.md 원칙대로 **기능 구현 직후 바로** 쓴다.

| 대상 | 종류 | 케이스 |
|---|---|---|
| `PessimisticCouponIssuer` | 동시성 통합 `@Tag("lab")` | 1000 스레드 → 정확히 100 |
| `NoLockCouponIssuer` | 동시성 통합 `@Tag("lab")` | 1000 스레드 → **100 초과**(Q1) |
| `CouponCommandService` | 단위 (Mockito) | 성공 / 품절 / 중복 / 락타임아웃 |
| `CouponController` | `@WebMvcTest` | 201 / 409 / 401 |
| 마이그레이션 | `@Tag("lab")` 스키마 검증 | 기존 `*SchemaLabTest` 패턴 |

lab 테스트는 `@TestPropertySource`에 랩 PG 접속값을 직접 박는다 (기존 `*LabTest` 방식).

```java
@Tag("lab")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
    "spring.datasource.username=${lab.db.user:sweetgirl}",
    "spring.datasource.password=${lab.db.password:soboro-lab}",
    "spring.jpa.hibernate.ddl-auto=validate"
})
```

**동시성 테스트는 `@DataJpaTest`가 아니라 `@SpringBootTest`다.** 슬라이스 테스트는 테스트 트랜잭션으로 감싸 롤백하는데, 동시성 테스트는 워커 스레드가 실제로 커밋해야 의미가 있다.

- **테스트 메서드에 `@Transactional` 금지** — 워커는 별도 트랜잭션이라 커밋을 못 본다. 정리는 `@AfterEach`에서 직접
- `CountDownLatch`로 동시 출발. 스레드 1000개 수준 (그 이상은 컨텍스트 스위칭이 지배)

실행: `./gradlew test -PlabTests`

## 10. 확정 전제

- **로컬 전용이다.** staging·prod 프로파일은 ERROR 로그를 전부 슬랙 웹훅으로 보낸다. 500이 수만 건이면 채널이 마비된다
- **랩 PG(5432)에만 붙는다.** 실행은 반드시 두 프로파일을 켠다:
  `./gradlew bootRun --args='--spring.profiles.active=local,lab'`
- ⚠️ **`local` 단독으로 띄우지 않는다.** `application-local.properties`는 `localhost:15432`를 가리키는데,
  `application-lab.properties` 주석은 15432를 **운영 터널**로, local을 15433(개발 DB)으로 적고 있다 —
  **주석과 설정이 어긋난다.** 어느 쪽이 맞든 local 단독 실행은 랩이 아닌 DB에 Flyway를 실행한다.
  (현재 15432는 닫혀 있어 연결 실패로 끝나지만, 터널이 열려 있으면 그리로 적용된다.)
  → 이 불일치는 별도로 확인해 고쳐야 한다
- 테이블은 **Flyway가 앱 기동 때 만든다.** 한 번 적용되면 브랜치를 되돌려도 남는다. 랩은 `scripts/lab/restore-lab.sh`로 다시 만들 수 있다
- `ddl-auto=validate`다 (local은 `none`). 엔티티만 만들면 랩 테스트가 깨진다 — **마이그레이션이 먼저다**
- 로컬 수치는 prod보다 낙관적이다. app↔RDS 네트워크 왕복 때문에 **prod 직렬 처리량은 로컬의 1/5~1/10**. 결론에 안전계수를 적어둔다
- 대상 앱은 도커로 CPU·메모리를 제한해 띄운다. 맥북 성능 그대로면 비교 의미가 없다
- 다계정 어뷰징은 막지 않는다. 1인 1매는 memberId 기준이다
- **이 브랜치를 dev에 머지할지는 측정 후에 정한다.** 머지하면 스테이징·prod에도 테이블이 생긴다

## 11. 뒤집은 결정

| 원안 | 최종 | 이유 |
|---|---|---|
| 오라클 클라우드에 부하 서버 구축 | **로컬 단독** | 5만 명 ≠ VU 5만. 도착률 모델이면 맥북으로 충분하다. 공용 인터넷이 끼면 지연이 서버 탓인지 네트워크 탓인지 못 가린다 |
| nGrinder | **k6** | nGrinder는 VU(스레드) 기반이라 도착률 모델이 없다. 1초에 5천 건을 정확히 못 만든다. k6는 이미 `scripts/perf/`에 있다 |
| Prometheus + Grafana 구축 | **curl 1초 루프** | 기본 스크랩 15초는 10초 테스트를 통째로 놓친다 |
| 전략 4종 + 목표 아키텍처 선설계 | **비관락 하나만** | 아직 데이터가 없다. 다음 수를 미리 정하면 측정이 결론을 확인하는 절차로 전락한다 |

## 12. 작업 순서

1. 마이그레이션 + 엔티티 + 스키마 랩 테스트
2. `CouponIssuer` + `PessimisticCouponIssuer` → **동시성 랩 테스트** (Q2)
3. `NoLockCouponIssuer` → **동시성 랩 테스트로 초과 발급 재현** (Q1)
4. `CouponCommandService` + 단위 테스트
5. `CouponController` + `@WebMvcTest`
6. dev 리셋 엔드포인트
7. 회원 시드 + JWT CSV 생성 스크립트
8. k6 시나리오 + actuator·로그 수집 스크립트
9. **R1·R2 측정**
10. 결과를 §13에 정리 → **여기서 다음 스텝을 정한다**

## 13. 측정 결과

조건: 도착률 5,000/s × 10초, 배경 검색 50/s. 랩 PG, 앱은 `local,lab` 프로파일.

### 정합성 — 전 회차 통과

재고 100이면 정확히 100장, 500이면 정확히 500장. 카운터 = 실제 발급 행 수, 중복 0.
HTTP·JWT 필터·톰캣을 전부 거쳐도 그렇다. **Q2 답: 비관적 락은 정합성을 지킨다.**

### 처리량 — 설정을 바꿔도 움직이지 않는다

| 회차 | 커넥션 | 톰캣 스레드 | 재고 | 처리량 | 검색 p99 |
|---|---|---|---|---|---|
| R1 | 10 | 200 | 100 | 774/s | 11,132ms |
| R2 | **50** | 200 | 100 | 810/s | 10,317ms |
| R3 | 50 | 200 | **500** | 794/s | — |
| R4 | 50 | **1000** | 500 | **680/s** | — |
| (참고) JUnit | — | **웹 계층 없음** | 100 | 694/s | — |

**Q4 답: 풀을 5배 늘려도 -0.05%. 스레드를 5배 늘리면 -6.2%.**

JUnit(HTTP·톰캣·JWT 전부 없음, 서비스 직접 호출)이 694/s로 톰캣 1000개짜리 R4(680/s)와 같다.
**웹 계층은 병목이 아니다.**

### Q3 답 — 무엇이 먼저 마르나

```
톰캣 스레드 200  →  1초 만에 포화, 20초 유지
Hikari 대기 195  →  200개 중 195개가 커넥션 대기
Hikari 타임아웃   →  0건
락 타임아웃      →  0건
```

타임아웃이 둘 다 0건인데 p99는 11초다. **어느 한 구간도 자기 타임아웃을 안 넘기지만 합치면 11초**다.

깔때기(R1):

| 어디서 멈췄나 | 건수 | 비율 |
|---|---|---|
| k6가 발사조차 못함 (VU 1만 고갈) | 33,044 | 66% |
| TCP 연결 실패 (accept 큐 초과) | 1,825 | 4% |
| 스레드 배정 → 응답 (21초 소요) | 15,192 | 30% |

**앞의 둘은 마지막 하나 때문에 생긴 결과다.** 한 군데가 막히면 위로 세 겹이 밀린다.

### Q5 답 — 다른 API도 같이 죽는다

배경 검색: p99 **11초**, 최악 55초, 성공률 59%. 쿠폰과 무관한 API가 사실상 불능.

### 병목의 정체

```
처리량 800/s  →  뒤집으면 한 건당 1.25ms
              →  쿠폰 행 하나를 잠그고 검사하고 푸는 시간
```

재고가 남았든 소진됐든 **전원이 이 문을 하나씩 통과한다.** `SELECT ... FOR UPDATE` 는 조건이 없어서
소진 판정을 하려면 일단 잠가야 하기 때문이다. 앞에 놓인 커넥션·스레드는 전부 **대기실**이지 창구가 아니다.

5만 건을 전부 소화하려면 `50,000 ÷ 800 = 62초`. 재고가 떨어진 뒤 "품절"이라고 답하는 데만 1분이다.

### 결론 → 다음 단계

> **비관적 락은 정합성을 완벽히 지키고, DB 의 직렬화 구간 하나가 전체 처리량의 천장이 된다.**
> 설정으로는 못 넘는다. 늘린 것들은 전부 대기실이었지 창구가 아니었다.

판단 기준이 하나 생겼다 — **직렬 구간을 타는 요청 수를 줄이는가.**

| | 무엇을 | 기대 | 측정 |
|---|---|---|---|
| 원자적 UPDATE | 검사를 SQL 안으로 → 소진 경로는 **아예 안 잠금** | 회전 자체가 사라짐 | **R5** |
| 품절 플래그 | 소진 뒤엔 **커넥션도 안 잡음** | 다른 API 자원 보호 | **R6** |
| 격리(벌크헤드) | 소진 **전**에도 쿠폰이 커넥션을 독점 못 하게 | 검색 p99 회복 | R6 결과 보고 판단 |

플래그는 설정(`coupon.sold-out-flag.enabled`)으로 켜고 꺼서 기여도를 분리한다.
비관락 구현은 지우지 않는다 — 같은 시나리오로 다시 비교해야 한다.

**Redis 는 넣지 않는다.** 지금 필요한 건 카운터가 아니라 플래그고, 플래그는 인스턴스마다 따로 켜져도
틀리는 방향이 안전하다(늦게 켜지면 DB 까지 가서 정확히 거절된다). 평시 1대이기도 하다.

---

## §14 R5·R6 측정 (로컬 랩) — 5.4배, 그런데 믿을 수 없는 5.4배

조건: 도착률 5,000/s × 10s(= 5만 건), 재고 500, 커넥션 50, 톰캣 200.

| 회차 | 구현 | 처리량 | vs R3 |
|---|---|---|---|
| R3 | 비관적 락 | 794/s | — |
| R5 | 조건부 UPDATE | 1,546/s | 1.9배 |
| R6 | 조건부 UPDATE + 품절 플래그 | **4,283/s** | **5.4배** |
| R6b | R6 재측정(동일 설정) | 4,060/s | 5.1배 |

기여도가 갈린다 — 조건부 UPDATE 만으로 1.9배, **플래그가 거기서 2.8배를 더 얹는다.** 커넥션을
아예 안 잡는 쪽이 잠금을 줄이는 쪽보다 크다. 다만 **이 수치는 서버 용량이 아니다.**

```
시스템 CPU 100%  /  JVM CPU 19%     ← 나머지 81% 는 k6(VU 1만)
```

R6 에서 톰캣 스레드 200개가 12초 내내 포화였다. 플래그 경로는 `AtomicBoolean.get()`(실측 0.24ns)
하나인데 역산하면 요청당 47ms 를 붙잡고 있었다. 앱이 CPU 를 19% 밖에 못 받아서다.
같은 설정 재측정에서 검색 p99 가 1.4배 흔들렸다.

> **병목을 고치니 부하 생성기가 새 병목이 됐다.** 로컬 랩은 정합성 검증과 구조적 병목 식별까지만
> 유효하다. 개선 후 처리량은 생성기를 분리해서 다시 재야 한다.

---

## §15 원격 측정 (생성기 분리) — 최적화가 1.1배로 줄었다

```
[c7g.xlarge · k6]  ──10.3.x 사설망──>  [t3.small · 앱 + PostgreSQL]
 4 vCPU                                 2 vCPU (운영 t3.medium 과 CPU 동일)
```

조건 동일(5,000/s × 10s, 재고 500). `APP_HOST=10.3.31.174 ./run-remote.sh <회차> 5000 10s 500`.

| 순서 | 구현 | 처리량 | 거절 p99 | 검색 p99 |
|---|---|---|---|---|
| 1 | 비관적 락 | 213/s | 34,406ms | 39,468ms |
| 2 | 조건부 UPDATE | 265/s | — | — |
| 3 | 조건부 UPDATE + 플래그 | 225/s | 34,344ms | 38,544ms |
| 4 | 비관적 락 **(대조군)** | 223/s | 34,192ms | 38,622ms |
| 5 | 비관적 락, 스레드 **32** | 208/s | — | — |
| 6 | 비관적 락, **Scouter 제거** | **254/s** | 33,120ms | 34,403ms |
| 7 | 조건부+플래그, **Scouter 제거** | **279/s** | 31,195ms | 31,602ms |

**정합성: 재고 500 / 카운터 500 / 발급행 500 / 중복회원 0 — 전 회차 정확.**

### 원인을 지워나간 순서

| 가설 | 검증 | 결과 |
|---|---|---|
| t3 크레딧 고갈로 뒤 회차 불리 | 1회차를 마지막에 재측정(순서 4) | 213 vs 223 → **재현됨, 아님** |
| 2 vCPU 에 스레드 200 은 스위칭 과다 | 스레드 32 로 축소(순서 5) | 208/s → **아님** |
| 하이퍼바이저 스로틀링 | 2코어 10초 소진 후 `/proc/stat` | steal **0%**, 실사용 91% → **아님** |
| GC 압박(힙 2GB) | `jvm_gc_pause_seconds_sum` | 기동 이후 총 **0.78초** → **아님** |
| 그럼 CPU 를 누가 쓰나 | `JAVA_TOOL_OPTIONS` 확인 | **Scouter APM 에이전트** |

CPU 100% 인데 JVM 이 90~95% 를 쓰고 있었다. **락에 막혀 있으면 CPU 가 낮아야 하는데 반대**라서
"대기"가 아니라 "계산"이라고 보고 무엇이 계산하는지를 찾았다.

```
-javaagent:/scouter/scouter.agent.jar -Dnet_collector_ip=172.31.24.193
```

수집기 IP 가 `172.31.x` 인데 앱은 `10.3.x` 대역 — **닿지도 않는데 계측은 계속한다.**
제거 효과: 213→254(+19%), 225→279(+24%).

### 결론 — §13 의 결론을 수정한다

| 환경 | 비관적 락 | 조건부+플래그 | 배수 |
|---|---|---|---|
| 로컬 (10코어, CPU 여유) | 794/s | 4,283/s | **5.4배** |
| 원격 (2 vCPU, 운영급) | 254/s | 279/s | **1.1배** |

> ~~직렬 구간을 타는 요청 수를 줄이면 처리량이 5배가 된다~~
>
> **직렬 구간 축소는 CPU 에 여유가 있을 때만 효과가 있다.** 2 vCPU 에서는 HTTP 파싱·JWT 검증·
> JSON 직렬화 + 같은 호스트의 PostgreSQL 이 CPU 를 먼저 다 쓴다(요청당 약 8ms). 락의 직렬
> 구간은 그중 일부라, 없애도 나머지가 그대로 천장이 된다.

### 다음 단계 재정렬

§13 에서 후보로 뒀던 **격리(벌크헤드)와 Redis 는 둘 다 순서가 틀렸다.** CPU 가 천장인데 락 앞단을
더 손봐야 소용없다.

1. **Scouter 에이전트 정리** — 20% 를 코드 변경 없이 얻는다. 수집기에 닿지도 않아 지금은 비용만 낸다
2. **요청당 CPU 를 줄이거나 vCPU 를 늘리거나** — 이게 현재 천장
3. 그 다음에야 락·격리·Redis

현재 처리량 279/s 로 5만 건 소화에 **179초**. 재고가 500장이면 실제로 문제되는 구간은 소진 전
2초뿐이고, 나머지는 "품절" 응답 지연이다. 이게 얼마나 급한지는 **실제 동시 접속 추정치**가 필요하다.

---

## §16 §15 의 결론을 철회한다 — 측정이 JIT 워밍업을 재고 있었다

§15 를 쓴 뒤 "맥 4,060/s vs 서버 279/s" 를 코어 수로 설명하려다 산술이 안 맞는 걸 발견했다.

| | JVM CPU | 앱이 받은 실제 코어 | 처리량 |
|---|---|---|---|
| 맥 R6b | `process_cpu_usage` 0.19 × 10코어 | **1.9코어** | 4,060/s |
| t3.small R9 | `process_cpu_usage` 0.94 × 2 vCPU | **1.88코어** | 279/s |

**코어 수가 같은데 15배 차이다.** "2 vCPU 라서 CPU 가 천장" 은 t3.small 의 포화 상태는 맞게
짚었지만 이 격차를 설명하지 못한다. 배제한 것: 스왑 0(`pswpout 0`), 메모리 부족 아님.
CPU 는 Xeon 8175M @2.5GHz — 맥 M 계열 대비 코어당 2~3배는 되겠지만 15배는 아니다.

### 회차 내부 추이를 봤더니

| 구간 | R1 | R8 | R9 |
|---|---|---|---|
| 0~4s | 35/s | 46/s | 54/s |
| 20~24s | 189/s | 257/s | 279/s |
| 30~34s | **244/s** | **263/s** | **320/s** |

**한 회차 안에서 5~7배 오른다.** 회차마다 설정을 바꾸려고 컨테이너를 재시작했으니
**매번 콜드 JVM 에 10초 버스트**를 때린 것이다. 10초는 C2 컴파일이 끝나기 전이다.

### 웜 상태 재측정 (R10warm)

재시작 없이(= R9 가 이미 덕인 JIT 유지) 동일 부하. 쿠폰은 소진 상태라 전량 거절 경로.

| | 0~4s | 정상 구간 | 평균 | 409 p50 |
|---|---|---|---|---|
| R9 (콜드) | 54/s | 320/s | 279/s | 22,317ms |
| **R10warm (웜)** | **246/s** | **371/s** | **331/s** | **14,255ms** |

시작 구간이 4.6배, 평탄부가 1.16배, 거절 p50 이 22.3초→14.3초.
**거절 경로 정상 상태 처리량은 279/s 가 아니라 약 370/s 다.**

### 그래서 지금 확정된 것과 안 된 것

| | |
|---|---|
| ✅ 확정 | **정합성** — 전 회차 재고 500 / 카운터 500 / 발급행 500 / 중복 0 |
| ✅ 확정 | **§15 원격 수치는 전부 워밍업 오염** — 정상 상태가 아니다 |
| ✅ 확정 | 맥 측정이 무효인 이유(생성기가 CPU 81% 점유) — §14 그대로 유효 |
| ❌ 철회 | "운영급에서 락 최적화는 1.1배" — 비교 대상 두 회차가 **둘 다 콜드**라 비율도 못 믿는다 |
| ❌ 철회 | "2 vCPU 라서 CPU 가 천장이고 그래서 최적화가 안 보인다" — 15배 격차를 설명 못 함 |
| ❓ 미해결 | 맥↔서버 코어당 11배 격차. 워밍업으로 일부만 설명됨. 네트워크 경로(루프백 vs VPC)·t3 버스트 미확인 |

### 다시 측정하려면

1. **회차마다 워밍업 구간을 먼저 돌린다** — 재시작 직후 측정 금지. 최소 1분 예열 후 본 측정
2. **비관락 vs 조건부+플래그를 둘 다 웜 상태로** 재측정해야 락 최적화 효과를 말할 수 있다
3. 필요한 것: `PERF_DASHBOARD_ACCESS_KEY`(회차 간 리셋용, k6 박스에 배치) · 컨테이너 재시작 권한
4. 토큰은 1시간 — 예열까지 감안하면 회차당 약 5분, 한 토큰으로 최대 8~9회차

> **교훈: 재시작 직후 짧은 버스트로 측정하면 JVM 워밍업을 재게 된다.**
> §14 에서 "부하 생성기를 분리해야 한다" 를 배웠고, 여기서 "예열하고 재야 한다" 를 배웠다.
> 두 번 다 **측정 환경이 결론을 만들어냈다.**

---

## 부록 — 미결

- [ ] 락 타임아웃 3초가 적절한지 — R1 보고 조정
- [ ] 도커 리소스 제한값 — 운영 서버 스펙 확인 후
- [ ] ephemeral port 16,384개에 걸리는지 — `TIME_WAIT` 수로 판단

## 부록 — 확인 완료

| 항목 | 결과 |
|---|---|
| k6 | v2.2.0 설치됨 |
| 랩 PG | `postgres-lab` healthy, 5432 |
| prod 터널(15432) | **닫힘** — 열지 않는다 |
| 랩 DB 회원 수 | 10,102명 → 4만 명 추가 시드 필요 |
| `ddl-auto` | `validate` (local은 `none`) |
| JWT 발급 | `JwtProvider.generateAccessToken(Long memberId)` |
| dev 엔드포인트 선례 | `DevPerfController` — `@Profile` + 헤더 키 가드 |
| 톰캣·Hikari | **설정 없음 = 전부 기본값** (스레드 200, accept 100, 풀 10) |
| 비관락 선례 | `CourseRepository.findWithLockByIdAndDeletedAtIsNull` |
