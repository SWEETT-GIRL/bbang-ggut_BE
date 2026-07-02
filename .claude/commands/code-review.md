# 코드 리뷰 커맨드

현재 브랜치의 변경사항에 대해 코드 리뷰를 수행합니다.

**권장:** 구현한 세션과 다른 새 세션에서 실행 (더 객관적인 리뷰)

---

## 진행 순서

### 1. 변경사항 확인
- `git diff dev...HEAD --stat` 으로 커밋된 변경사항 확인 (기본 브랜치 `dev` — 개발·검증, `main`은 운영)
- `dev`가 없으면 `origin/dev` 사용
- `git status --short` 으로 커밋되지 않은 변경사항 확인

### 2. 변경된 파일 분석
변경된 Java 파일들을 읽고 분석:
- Entity, Service(Command/Query), Controller, DTO 파일 확인
- 각 파일의 코드 품질 검토

### 3. 우선순위별 이슈 분류

#### CRITICAL (반드시 수정 - 머지 차단)

**보안:**
- 하드코딩된 시크릿, API 키, 비밀번호
- SQL 인젝션, XSS 취약점
- 인증/인가 누락
- 민감 정보 로깅 (비밀번호, 토큰, 개인정보, 위치 좌표)

**데이터 정합성:**
- 동시성 이슈 (Race Condition)
  - 추천 수·구독 수·재고 등 공유 자원 수정 시 락 없음
  - 읽기-수정-쓰기 패턴에서 동시 접근 가능성
- 트랜잭션 경계 오류 (원자성 보장 실패)
- 데이터 손실/덮어쓰기 가능성
- 멱등성 미보장 (중복 실행 시 문제)

**외부 시스템 연동:**
- 외부 API(S3·FCM·Kakao·OpenSearch) 호출 시 타임아웃 미설정
- 재시도/실패 처리 없는 외부 호출
- **외부 API 호출이 트랜잭션 내부에 있음** (커넥션 점유·롤백 불일치)

**장애 유발:**
- 무한 루프, 재귀 종료 조건 누락
- 메모리 누수 (스트림 미close, 커넥션 미반환)
- 동기/비동기 처리 오선택 (알림·이메일 발송을 동기로 등)

#### WARNING (권장 수정)

**테스트 누락:**
- 핵심 비즈니스 로직 테스트 없음
- 예외/실패 케이스 테스트 없음
- 경계값 테스트 없음 (0, 음수, 최대값, null)

**성능:**
- N+1 쿼리 가능성
- 페이징 없는 전체 조회 (`findAll`)
- 루프 내 DB/API 호출

**설계:**
- 긴 함수 (50줄 초과)
- 깊은 중첩 (4단계 초과)
- 한 메서드에 여러 책임

**코드 컨벤션 (code-convention 스킬 기준):**
- 엔티티 `@Setter` / public setter
- enum `@Enumerated(EnumType.ORDINAL)` (STRING 이어야 함)
- Controller에 비즈니스 로직
- Controller에서 Entity 직접 반환 (DTO 사용해야 함)
- Service 미분리 (CommandService / QueryService)
- `null` 직접 반환, 불필요한 `try-catch`, `printStackTrace()`
- 약어(`svc`, `repo`) · 의미 없는 변수명(`data`, `temp`)

#### SUGGESTION (개선 고려)
- 네이밍 개선
- 코드 중복
- 더 나은 패턴 제안

---

## 리뷰 체크리스트

### 동시성 & 데이터 정합성 (최우선)
- [ ] 공유 자원 수정 시 동시성 제어 (추천 수, 구독, 재고, 잔액 등)
  - 비관적 락 (`@Lock(PESSIMISTIC_WRITE)`)
  - 낙관적 락 (`@Version`)
  - DB 레벨 (`UPDATE ... WHERE count >= :n`)
- [ ] 읽기-수정-쓰기 패턴에서 Race Condition 가능성
- [ ] 트랜잭션 범위 적절성
- [ ] 멱등성 보장 (같은 요청 2번 와도 안전한지)

### 외부 시스템 연동 (장애 전파 방지)
- [ ] 타임아웃 설정 (S3 / FCM / Kakao / OpenSearch 클라이언트)
- [ ] 재시도 / Fallback 처리
- [ ] **외부 API 호출이 트랜잭션 밖에 있는지**
- [ ] 비동기 처리 필요 여부 (알림, 이메일, 인덱싱 등)

### Security
- [ ] 하드코딩된 시크릿 없음
- [ ] 입력값 검증 (`@Valid`, `@NotBlank` 등)
- [ ] SQL 인젝션 방지
- [ ] 권한 체크 누락 없음
- [ ] 민감 정보(토큰·위치) 로깅 여부

### Performance
- [ ] N+1 쿼리 가능성
- [ ] 페이징 없는 전체 조회
- [ ] 루프 내 DB/API 호출

### Architecture (DDD + CQRS)
- [ ] 계층 분리 준수 (Controller → Service → Repository)
- [ ] 비즈니스 로직이 Service에 위치 (Controller 로직 0줄)
- [ ] CommandService(`@Transactional`) / QueryService(`@Transactional(readOnly = true)`) 분리
- [ ] Controller가 Entity가 아닌 DTO 반환

### Java & Spring (code-convention)
- [ ] Entity는 `@NoArgsConstructor(access = PROTECTED)` + 정적 팩토리, `@Setter` 없음
- [ ] DTO는 `record` (presentation `Request`/`Response`, application `RequestDto`/`ResponseDto`)
- [ ] enum은 `@Enumerated(EnumType.STRING)`
- [ ] null 직접 반환 대신 Optional / 커스텀 예외
- [ ] 예외는 도메인 Exception + `GlobalExceptionHandler` 공통 처리
- [ ] 의존성은 생성자 주입 (`@RequiredArgsConstructor` + `final`)

### Test Coverage (중요 로직 필수)
- [ ] 새 Service(Command/Query)에 테스트 파일 존재
- [ ] 새 Controller에 `@WebMvcTest` 존재
- [ ] 예외/실패 케이스 테스트 존재
- [ ] 경계값 테스트 (0, 음수, 최대값, null)

---

## 출력 형식

```markdown
## 리뷰 결과: 승인 / 경고 / 차단

### Summary
- 변경 파일: N개
- CRITICAL: N개
- WARNING: N개
- SUGGESTION: N개

### 이슈 목록

[CRITICAL] 파일경로:라인번호
- 문제: 설명
- 해결: 구체적 수정 방법

[WARNING] 파일경로:라인번호
- 문제: 설명
- 해결: 구체적 수정 방법

[SUGGESTION] 파일경로:라인번호
- 제안: 설명

### 판정
- 승인: CRITICAL 없음
- 경고: WARNING만 존재 (주의하며 머지 가능)
- 차단: CRITICAL 존재 (수정 필수)
```

---

## 주의사항
- 변경사항이 없으면 "변경사항이 없습니다" 메시지 출력
- 테스트 파일은 리뷰 대상이되, 테스트 편의 코드(픽스처 등)는 관대하게 판단
- 자동 생성 코드는 리뷰 제외
- **빌드/테스트 실행 금지**: 코드 리뷰는 정적 분석만 수행. `./gradlew build`, `./gradlew test` 등 실행하지 않음

---

## 동시성 이슈 탐지 가이드

다음 패턴이 보이면 동시성 이슈를 의심:

### 위험 패턴 1: 읽기-수정-쓰기 (카운트 증감)
```java
// 위험: 동시 요청 시 추천 수가 어긋날 수 있음
Menu menu = menuRepository.findById(id).orElseThrow(...);
menu.increaseRecommendCount(); // Race Condition
```

### 위험 패턴 2: 중복 체크 후 저장
```java
// 위험: 동시 구독 요청 시 중복 저장 가능
if (!subscribedStoreRepository.existsByMemberIdAndStoreId(memberId, storeId)) {
    subscribedStoreRepository.save(...); // Race Condition
}
```

### 해결책 제안:
1. **DB 레벨**: `UPDATE ... SET count = count + 1 WHERE id = :id`
2. **비관적 락**: `@Lock(LockModeType.PESSIMISTIC_WRITE)`
3. **낙관적 락**: Entity에 `@Version`
4. **유니크 제약**: 중복 저장은 DB 유니크 키 + 예외 처리

---

## 외부 API 연동 이슈 탐지 가이드

### 위험 패턴 1: 트랜잭션 내 외부 호출
```java
@Transactional
public void createBanner(...) {
    Banner banner = bannerRepository.save(...);
    s3Client.upload(...);   // 위험: 트랜잭션이 커넥션을 잡은 채 외부 I/O
}
```
**해결:** 외부 호출은 트랜잭션 밖에서, 또는 커밋 후 이벤트(`@TransactionalEventListener(AFTER_COMMIT)`)

### 위험 패턴 2: 동기로 처리하면 안 되는 작업
```java
@Transactional
public void notifySubscribers(...) {
    fcmClient.send(...);    // 위험: 발송 지연 = API 응답 지연
}
```
**해결:** `@Async`, 이벤트 발행, 메시지 큐

### 위험 패턴 3: 타임아웃 없는 외부 호출
```java
// 위험: 외부 응답 지연 시 스레드 고갈
var response = kakaoClient.search(...);
```
**해결:** 클라이언트에 connect/read 타임아웃 설정

---

## 테스트 누락 탐지 가이드

### 반드시 테스트가 필요한 로직
**1. 데이터 정합성 관련 (CRITICAL)**
- 추천/좋아요 수 증감, 구독 추가/취소, 상태 변경

**2. 핵심 비즈니스 로직 (WARNING)**
- 신고/제재 처리, 알림 발송 조건, 검색 정렬

**3. 검증해야 할 케이스**
- 정상 케이스 (Happy path)
- 실패 케이스 (예외 발생)
- 경계값 (0, 음수, 최대값, null)
- 권한 없는 사용자 / 중복 요청

### 테스트 누락 판단 기준
```
새 Service 메서드가 있는데 테스트가 없다면:
- 데이터 정합성 관련 → CRITICAL
- 핵심 비즈니스 → WARNING
- 단순 CRUD → SUGGESTION
```
