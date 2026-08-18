# traceId 로그 상관관계 (feat/trace-id-logging)

## 1. 배경

지금 로그는 **한 줄씩 독립적**이다. 어떤 줄들이 같은 요청에서 나왔는지 알 방법이 없다.

```
2026-08-14 22:01:03.412 [http-nio-8080-exec-3] WARN  StoreSearchService - 결과 없음
2026-08-14 22:01:03.455 [http-nio-8080-exec-7] ERROR ImageCallbackService - 처리 실패
2026-08-14 22:01:03.688 [http-nio-8080-exec-3] INFO  RequestLoggingFilter - API_PERF ...
```

스레드 이름(`exec-3`)으로 어렴풋이 묶이지만, 톰캣이 스레드를 **재사용**하므로 다음 요청도 `exec-3`을 쓴다.
동시 요청이 많으면 사실상 구분이 불가능하다.

특히 슬랙 ERROR 알림이 문제다. `SlackWebhookAppender`는 **메시지 한 줄과 스택트레이스만** 보낸다.

```java
List<SlackMessage.Field> fields = List.of(
    new SlackMessage.Field(LOGGER_FIELD_NAME, simpleClassName(event.getLoggerName()), true));
```

알림을 받아도 "어떤 요청이 무엇을 하다가 터졌는지"로 이어갈 실마리가 없다.

---

## 2. 목표

- 요청 하나에 **짧은 ID**를 발급해 그 요청에서 나온 모든 로그 줄에 붙인다
- **슬랙 ERROR 알림에 그 ID를 실어** 로그 검색의 출발점을 만든다
- `@Async` 스레드(로그 적재, 검색 실패 적재)에서도 ID가 유지되게 한다
- `request_log` 테이블에도 남겨 DB 로그와 텍스트 로그를 이어붙일 수 있게 한다

### 목표가 아닌 것

- **분산 추적이 아니다.** 서비스가 하나뿐이라 서비스 간 전파(W3C `traceparent`, Zipkin/Jaeger)는 대상이 아니다.
  Lambda 콜백도 별개 요청으로 취급하며 원본 요청과 잇지 않는다
- 스팬(span) 단위 구간 측정은 하지 않는다. 응답 시간은 이미 `RequestLog.responseTimeMs`에 있다
- Micrometer Tracing / Sleuth 의존성을 추가하지 않는다 — 서블릿 필터 + MDC로 충분하고,
  의존성이 늘면 Spring Boot 4 호환 문제를 떠안게 된다

---

## 3. 설계 결정

### 3-1. ID 형식 — UUID 앞 8자

```
a3f2c81b
```

전체 UUID(36자)는 로그 줄을 밀어내서 읽기 나쁘다. 8 hex = 약 43억 가지.
**충돌해도 무해하다** — 용도가 "로그를 시간 범위 안에서 묶어보기"라서, 몇 시간 뒤 같은 값이 다시 나와도 헷갈릴 일이 없다.

### 3-2. 외부 입력을 그대로 믿지 않는다

`X-Trace-Id` 헤더가 오면 재사용하되 **반드시 검증한다.**

```
허용: 영숫자 + 하이픈, 최대 32자
위반: 새로 발급
```

검증 없이 MDC에 넣으면 개행 문자를 섞어 **가짜 로그 줄을 주입**할 수 있다(로그 인젝션).
`RequestLoggingFilter.normalizeLogValue`가 이미 같은 이유로 `[\r\n\t]`를 제거하고 있다.

### 3-3. 필터 순서 — 제일 바깥

```
TraceIdFilter          HIGHEST_PRECEDENCE   ← 신규. 가장 먼저 들어가고 가장 늦게 나온다
  └ Security          -100
      └ RequestLoggingFilter  LOWEST_PRECEDENCE
          └ 컨트롤러
```

`RequestLoggingFilter`가 `LOWEST_PRECEDENCE`라 이미 안쪽이다. 바깥에 두면 그 필터의 `API_PERF` 로그와
`GlobalExceptionHandler`가 남기는 로그까지 전부 ID를 갖는다.

### 3-4. MDC 정리는 필수

톰캣은 스레드를 재사용한다. 지우지 않으면 **다음 요청이 이전 요청의 ID를 달고 나온다.**
`finally`에서 `MDC.remove(KEY)`로 지운다. `MDC.clear()`는 다른 코드가 넣은 값까지 날리므로 쓰지 않는다.

### 3-5. `@Async` 전파 — TaskDecorator

MDC는 `ThreadLocal`이라 스레드가 바뀌면 사라진다. 영향받는 곳:

| 위치 | 실행기 |
|---|---|
| `RequestLogService.save` | `logExecutor` |
| `SearchMissEventListener` | `logExecutor` |
| 가게 색인 등 | 기본 실행기 |

제출 시점의 MDC를 복사해 실행 스레드에 심고, 끝나면 지우는 `TaskDecorator`를 3개 실행기에 건다.

> `reindexExecutor`는 요청 컨텍스트 밖(스케줄러)에서 도는 경우가 많아 실익이 적지만,
> 빠뜨리면 "왜 여기만 안 붙지"가 되므로 동일하게 적용한다.

### 3-6. 응답 헤더로 내보낸다

```
X-Trace-Id: a3f2c81b
```

사용자가 "에러 났어요"라고 제보할 때 프론트가 이 값을 같이 보여주면 바로 로그를 찾을 수 있다.
(프론트 대응은 이번 범위 밖. 헤더만 준비해둔다.)

---

## 4. 파일별 변경

| # | 파일 | 구분 | 내용 |
|---|---|---|---|
| 1 | `global/logging/TraceId.java` | 신규 | MDC 키 상수 + 발급/검증 |
| 2 | `global/logging/filter/TraceIdFilter.java` | 신규 | 발급 · MDC 주입 · 응답 헤더 · 정리 |
| 3 | `global/logging/MdcTaskDecorator.java` | 신규 | `@Async` 스레드로 MDC 복사 |
| 4 | `config/web/LoggingConfig.java` | 수정 | `TraceIdFilter` 등록 (`HIGHEST_PRECEDENCE`) |
| 5 | `config/async/AsyncConfig.java` | 수정 | 실행기 3개에 `MdcTaskDecorator` 적용 |
| 6 | `resources/logback-spring.xml` | 수정 | 패턴에 `[%X{traceId:-}]` |
| 7 | `global/logging/SlackWebhookAppender.java` | 수정 | 알림에 `Trace` 필드 추가 |
| 8 | `global/logging/entity/RequestLog.java` | 수정 | `traceId` 필드 + 빌더 |
| 9 | `global/logging/filter/RequestLoggingFilter.java` | 수정 | 저장 시 MDC에서 꺼내 담기 |
| 10 | `db/migration/V20260814HHMMSS__add_trace_id_to_request_log.sql` | 신규 | `trace_id VARCHAR(32) NULL` |

### 마이그레이션

```sql
ALTER TABLE request_log ADD COLUMN trace_id VARCHAR(32);
```

`NULL` 허용 컬럼 추가라 PostgreSQL 11+ 에서는 **메타데이터만 바꾸고 즉시 끝난다.** 테이블 재작성이 없다.
기존 행은 `NULL`로 남는다(소급 적용 불가).

**인덱스는 이번에 만들지 않는다.**
- 주 사용처는 슬랙 알림 → 텍스트 로그 검색이며, DB 조회는 보조 수단이다
- `request_log`는 적재량이 큰 테이블이라 인덱스가 INSERT 비용을 늘린다
- DB에서 찾을 때는 `created_at` 범위로 좁힌 뒤 `trace_id`로 거르면 실용적으로 충분하다

> **미결:** 프로덕션 `request_log` 행 수·크기를 아직 못 쟀다(SSH 터널 내려감).
> 인덱스가 필요할 만큼 큰지는 배포 후 실제 조회 패턴을 보고 판단한다.

---

## 5. 위험 요소

| 위험 | 영향 | 대응 |
|---|---|---|
| MDC 미정리 | 다음 요청이 남의 ID를 달고 나옴 — **잘못된 추적** | `finally` + 필터 테스트로 검증 |
| 로그 인젝션 | 헤더에 개행을 넣어 가짜 로그 줄 생성 | 형식 검증 후 위반 시 새로 발급 |
| `CallerRunsPolicy` | 기본 실행기는 큐가 차면 **호출 스레드**에서 실행 — 데코레이터가 MDC를 지워버릴 수 있음 | 데코레이터가 원래 값을 복원하도록 구현 |
| 로그 패턴 변경 | 로그 파싱하는 것이 있으면 깨짐 | 현재 파싱 대상 없음(콘솔 출력만) — 확인 완료 |
| 슬랙 필드 추가 | 알림 레이아웃 변경 | 필드 1개 추가일 뿐. 기존 테스트로 회귀 확인 |

---

## 6. 테스트 계획

기능 구현 직후 바로 작성한다.

| 대상 | 방식 | 케이스 |
|---|---|---|
| `TraceIdFilter` | `MockHttpServletRequest/Response` | ① 헤더 없으면 새로 발급 ② 정상 헤더면 재사용 ③ **개행 포함 헤더는 거부하고 새로 발급** ④ 응답 헤더에 실림 ⑤ **필터 종료 후 MDC가 비어 있음** ⑥ 예외가 나도 MDC 정리됨 |
| `MdcTaskDecorator` | 실제 `ThreadPoolTaskExecutor` | ① 다른 스레드에서 값이 보임 ② 실행 후 그 스레드의 MDC가 원상복구 ③ MDC가 비어 있어도 예외 없음 |
| `SlackWebhookAppender` | 기존 테스트 확장 | ① MDC에 traceId 있으면 필드에 포함 ② 없으면 필드 생략 |
| `RequestLoggingFilter` | — | 기존 테스트 없음. `traceId`가 `RequestLog`에 담기는지 새로 추가 |

---

## 7. 구현 순서

```
1. TraceId + TraceIdFilter        → 즉시 테스트
2. LoggingConfig 등록 + logback 패턴
3. MdcTaskDecorator + AsyncConfig  → 즉시 테스트
4. SlackWebhookAppender            → 기존 테스트 확장
5. RequestLog + 필터 + 마이그레이션 → 즉시 테스트
6. ./gradlew spotlessApply && build && test
```

## 8. 검증

```bash
./gradlew spotlessApply && ./gradlew build && ./gradlew test
```

로컬 기동 후 육안 확인:

```bash
curl -i localhost:8080/stores/1                 # 응답에 X-Trace-Id 있는지
curl -i -H "X-Trace-Id: mine-123" localhost:8080/stores/1   # 그대로 돌아오는지
curl -i -H "X-Trace-Id: bad$(printf '\r\n')x" localhost:8080/stores/1  # 새로 발급되는지
```

콘솔 로그에서 같은 요청의 줄들이 동일한 ID를 달고 나오는지 확인한다.

---

## 9. 브랜치

`dev` 기준 `feat/trace-id-logging`. PR은 `dev` → 확인 후 `main`.
