# CLAUDE.md — 빵긋 BE

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 최우선 지침

- 모든 답변과 플래닝은 **한글**로 작성
- 무조건 동의하지 말고, 다각도로 객관적 분석 후 답변
- **모호한 요구사항은 추측하지 말고 반드시 질문**
- 파일 생성/수정 전 무엇을 할지 **먼저 설명**하고 진행
- 파일 삭제(`rm` 등) 전 **반드시 사용자에게 확인**
- 개인 작업 TODO는 `dana_TODO.md`에 추가 (`TODO.md` 아님)

---

## 워크플로우

### 복잡한 작업 (워크플로우 권장)

다음 중 하나라도 해당하면 워크플로우 권장:
- 새 API 엔드포인트 2개 이상
- 새 Service 또는 새 도메인 패키지 생성
- 새 Entity/테이블 생성
- 여러 파일에 걸친 변경 (5개 이상)

**위 조건 해당 시 바로 구현하지 않고, 사용자에게 워크플로우 진행 여부 확인.**

**진행 순서:**
1. **Spec** — 요구사항 정리
2. **Plan** — 구현 계획 (파일 목록, 의존관계, 위험 요소)
3. **구현** — 기능 단위로 (Service → Service 테스트 → Controller → Controller 테스트)
4. **최종 점검** — 테스트 커버리지 체크리스트 확인

### 간단한 작업 (바로 구현 가능)
- 버그 수정, 단일 파일 수정, 기존 API 파라미터 추가 등

### 복잡한 탐색
서브에이전트 활용: `Task(subagent_type=Explore, prompt="...")`

### 플래닝 문서 관리
- **위치:** `.claude/planning/{브랜치명}.md`
- spec과 plan을 별도 파일로 쪼개지 말고 **브랜치명 단일 파일에 통합 관리**

---

## 구현 단계 필수 사항

### 테스트 작성 원칙

> ⚠️ **핵심: 기능 구현 직후 바로 테스트 작성. 모든 기능 구현 후 몰아서 작성 금지.**

- Service 메서드 구현 → **즉시** 단위 테스트 (Mockito)
- Controller 엔드포인트 구현 → **즉시** `@WebMvcTest`
- 커스텀 쿼리(Repository) → `@DataJpaTest`
- 기존 API 변경 → 기존 테스트 수정 + 새 케이스 추가
- 신규 서비스 메서드는 **성공 케이스 + 실패(예외) 케이스 최소 1개씩**
- PostGIS 공간 쿼리(`Store.location`)는 H2 불가 → Testcontainers / 통합 테스트로 분리

### 최종 점검 체크리스트

구현 완료 후 커밋 전 반드시 확인:
```
□ 새로 만든 모든 Service에 테스트 파일이 있는가?
□ 새로 만든 모든 Controller에 테스트 파일이 있는가?
□ 추가한 모든 public 메서드에 테스트 케이스가 있는가?
□ 예외 케이스(Exception)에 대한 테스트가 있는가?
```

**검증 순서:** `./gradlew spotlessApply && ./gradlew build && ./gradlew test`

---

## 프로젝트 정보

| 항목 | 내용 |
|------|------|
| Type | Spring Boot 4.0.6 REST API |
| Package | `sweetgirl.croissant` |
| Language | Java 17 |
| DB | PostgreSQL + Hibernate Spatial(PostGIS) · pg_bigm(가게 검색) · Flyway · (테스트 H2) |
| 외부 연동 | Firebase FCM · AWS S3 · Kakao Local · PostHog |
| 인증 | JWT — `JwtAuthenticationFilter`가 `Authorization: Bearer ...`를 검증, 컨트롤러에서 `@AuthenticationPrincipal Long memberId`로 사용 |
| Test | JUnit5 + Mockito + `@WebMvcTest` / `@DataJpaTest` |
| 스타일 | Naver Java 컨벤션 (spotless + checkstyle, pre-commit 자동) |

---

## 아키텍처 — 도메인 중심 DDD + CQRS

본 프로젝트는 **도메인 중심 패키지 구조**를 기반으로 한 **DDD + CQRS**를 사용한다.

- 각 기능은 **Controller · Service · Repository** 계층으로 분리한다.
- **비즈니스 로직은 Service 계층**에 위치시킨다.
- **Entity**는 데이터베이스 테이블과 매핑되는 객체로 사용한다.
- **Controller에서는 Entity를 직접 반환하지 않고 DTO**를 사용한다.
- Service는 **CommandService(쓰기, `@Transactional`)** 와 **QueryService(조회, `@Transactional(readOnly = true)`)** 로 분리한다. (CQRS)

```
sweetgirl.croissant
├── {domain}/   controller · service · repository · entity · dto
├── global/     ApiResponse, CustomException, GlobalExceptionHandler
├── config/
└── common/
```

> 상세 작성 규칙(Entity·Service·Controller·DTO·예외)은 `code-convention` 스킬을 참조한다.

---

## 금지 사항

- 엔티티 `@Setter` / public setter (상태 변경은 의미 있는 도메인 메서드로)
- 필드 `@Autowired` (생성자 주입 `@RequiredArgsConstructor` + `final`)
- enum `@Enumerated(EnumType.ORDINAL)` (STRING 고정)
- `null` 직접 반환 (Optional / 커스텀 예외 사용)
- 불필요한 `try-catch` / `printStackTrace()`
- 약어(`svc`, `repo`, `req`, `res` 등) · 의미 없는 변수명(`data`, `temp`, `obj`, `item` 등)
- Controller에 비즈니스 로직 작성
- Controller에서 Entity 직접 반환 (DTO 사용)
- 응답 DTO 필드 삭제·이름 변경·타입 변경 (아래 "API 응답 호환" 참고)

### API 응답 호환

설치된 구버전 앱은 지금 응답 구조에 맞춰 이미 배포돼 있다. 필드가 빠지면 그 앱은 업데이트 전까지 깨진다 (#818 → 1.5.x "가게 고르기" 크래시, 핫픽스 #849).

- 응답 필드는 **추가만** 한다
- 꼭 지워야 하면 순서를 지킨다
  1. FE가 그 필드를 안 쓰도록 먼저 배포
  2. Amplitude 앱 버전 분포로 구버전 사용자가 충분히 줄었는지 확인
  3. BE에서 삭제하고 응답 계약 테스트(`*ResponseContractTest`)를 갱신 — PR에 1·2의 근거를 첨부
- "앱은 이 필드를 안 쓴다"는 FE 코드에서 사용처를 검색한 결과로만 판단한다 (추측 금지)

---

## 커밋 / PR

- 커밋 메시지는 **한글**, **이모지 + 타입 접두어**:
  - `✨ feat: 관리자 신고 상세 조회 API 추가`
  - `🧪 test: 관리자 신고 상세 조회 테스트 추가`
  - `📝 docs: 신고 운영 정책 문서화`
  - `♻️ refactor: 신고 상세 응답 변환 로직 정리`
- PR 제목은 **`[타입] 제목`** 형식, 타입은 **대문자** (예: `[FEAT] 관리자 신고 상세 조회 API`, `[FIX] 구독 중복 저장 버그 수정`)
- PR 본문은 **한글**로 작성하며, `.github/pull_request_template.md`의 네 섹션(`💡 어떤 작업을 하셨나요?` · `📝 관련 이슈` · `🔗 API 호환성` · `🔍 리뷰어에게`)을 **모두 채운다**
  - 본문은 파일로 작성해 `gh pr create --body-file <파일>`로 넘긴다 (`--fill` 금지 — 커밋 메시지로 채워져 템플릿을 따르지 않음)
  - 관련 이슈가 없으면 `- 해당 없음`으로 적는다

---

## 관련 프로젝트

| 폴더 | 설명 | 스택 |
|------|------|------|
| `../bbang-ggut_admin_web/` | 관리자 웹 대시보드 | React 19, Vite, TypeScript, Tailwind CSS 4 |
| `../bbang-ggut_FE/bbanggut/` | 사용자 앱 | Expo SDK 54, TypeScript, NativeWind v4 |

---

## 주요 명령어

```bash
./gradlew bootRun                  # 실행 (기본 프로파일: local)
./gradlew build                    # 빌드 (checkstyle 포함)
./gradlew test                     # 전체 테스트
./gradlew test --tests "sweetgirl.croissant.<패키지>.<클래스>"  # 단일 테스트
./gradlew spotlessApply            # 포맷 적용 (Naver)
./gradlew checkstyleMain checkstyleTest  # 스타일 검사
```

> 첫 clone 후 `./gradlew compileJava` 실행 필요 — pre-commit 훅 자동 등록됨 (`spotlessApply` + `checkstyle`)
