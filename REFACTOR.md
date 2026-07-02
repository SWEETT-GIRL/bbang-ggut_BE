# REFACTOR.md — 빵긋 BE 전체 리팩토링 로드맵

> ## ⚠️ 커밋 규칙 (필수 · 최우선)
> **Phase 단위로 몰아서 커밋하지 말고, 최대한 작은 단위로 쪼개 커밋한다.**
> - 작업(`P{n}-T{m}`) 하나도 여러 커밋으로 나눈다. 예(P3 도메인 CQRS 분리):
>   `QueryService 분리` / `CommandService 분리` / `컨트롤러 주입 교체` / `테스트 재배치`를 **각각 별도 커밋**.
> - "리팩토링 커밋"과 "테스트 커밋"을 분리한다.
> - 한 커밋은 하나의 논리적 변경만 담고, 그 자체로 빌드/테스트가 통과해야 한다.
> - 커밋 메시지: **한글 + 유니코드 이모지 + 타입 접두어** (기존 커밋 형식 준수)
>   - `♻️ refactor: {내용}` · `🧪 test: {내용}` · `🔥 chore: {내용}` · `📝 docs: {내용}`
>   - 예: `♻️ refactor: MemberService 조회 로직 MemberQueryService로 분리`
> - 관련 이슈: **#493**

> 이 문서는 **Claude Code 에이전트가 작업 블록 단위로 실행**하기 위한 명세다.
> 각 작업(`P{n}-T{m}`)은 자체 완결적이어서, 해당 블록만 프롬프트로 넘겨도 실행 가능하도록 작성됐다.
> 진행 상황은 체크박스(`- [ ]` → `- [x]`)로 추적한다.

## 0. 이 문서 사용법 (에이전트 지침)

1. 작업은 **Phase 순서대로** 진행한다. Phase 내 작업은 ID 오름차순.
2. 각 작업 시작 시 **`in_progress`로 TaskUpdate**, 완료 시 체크박스 갱신 + 커밋.
3. **동작 보존(behavior-preserving)이 최우선.** 리팩토링은 외부 동작을 바꾸지 않는다. 동작 변경이 필요하면 "동작 변경 주의" 항목을 따르고 실패 재현 테스트를 먼저 작성한다.
4. 각 작업의 **DoD(완료 조건)와 검증 명령**을 반드시 통과시킨다.
5. 커밋 메시지는 한글 + 이모지 접두어(주로 `♻️ refactor:`, 테스트는 `🧪 test:`).
6. 모호하면 추측하지 말고 질문한다 (CLAUDE.md 최우선 지침).

## 1. 리팩토링 원칙

- **컨벤션 근거**: `.claude/skills/code-convention.md` (네이밍/Entity/Enum/Exception/Service(CQRS)/Controller/DTO/DB/Query)
- **테스트 근거**: `.claude/skills/test-guide.md` (Service=단위, Controller=`@WebMvcTest`, Repository=`@DataJpaTest`)
- **동작 보존 우선** — 리팩토링과 기능 변경을 한 커밋에 섞지 않는다.
- **작은 PR** — Phase(또는 도메인) 단위로 브랜치를 나눠 리뷰 가능하게 유지.
- **파일/함수 상한**: 파일 권장 200~400줄·최대 800줄, 함수 권장 20~30줄·최대 50줄, 중첩 최대 4단계.

## 2. 공통 검증 게이트

모든 작업 완료 후, 커밋 전 실행:

```bash
./gradlew spotlessApply
./gradlew build          # checkstyle 포함
./gradlew test
```

- 단일 테스트: `./gradlew test --tests "sweetgirl.croissant.<패키지>.<클래스>"`
- 실패 시 즉시 중단하고 원인 조사 (`systematic-debugging` 원칙).

## 3. 브랜치 / PR 전략

- 브랜치: `refactor/{phase}-{slug}` (예: `refactor/p1-response-format`, `refactor/p3-store-cqrs`)
- PR 제목: `[REFACTOR] {내용}` / 본문 한글 / 대상 Phase·작업 ID 명시
- 1 Phase = 1 PR 원칙, 단 **Phase 3(CQRS)은 도메인당 1 PR**로 세분화.

## 4. 현황 스냅샷 (탐색 근거)

| 항목 | 상태 | 근거 |
|------|------|------|
| CQRS 분리 | `course`, `store`(saved), `admin/course`만 적용. 나머지 혼재 | Phase 3 대상표 |
| 응답 규약 | `AuthController`에서 `Map.of("data",...)` ↔ `ApiResponse` 혼재 | `member/controller/AuthController.java:35,41,49,57` |
| 필드 주입 | `EncryptedStringConverter` `@Autowired` 필드 | `global/encrypt/EncryptedStringConverter.java:12-13` |
| 약어 파라미터 | `req` 다수 | `AuthController`, `admin/service/*` |
| `return null` | 서비스·필터·외부클라 다수 | Phase 5 대상표 |
| `@Enumerated` | 전부 STRING ✅ 변경 불필요 | — |
| 엔티티 `@Setter` | 없음 ✅ (config properties만 허용 패턴) | — |
| Controller Entity 반환 | 없음 ✅ | — |
| `printStackTrace()` | 0건 ✅ | — |

---

## Phase 0 — 사전 준비 (안전망)

- [x] **P0-T1**: 기준선 확보. `./gradlew spotlessApply && ./gradlew build && ./gradlew test`가 **깨끗하게 통과**하는지 확인, 실패 테스트가 있으면 목록화. (통과 상태여야 리팩토링 회귀 판별 가능)
  - DoD: 3개 명령 모두 green, 또는 기존 실패 테스트가 문서에 기록됨.
  - **결과 (2026-07-02)**: 최초 실행 시 `ReviewRepositoryTest` 6건 실패 — `Member`에 `member_type` 컬럼 추가(e876e10) 후 테스트 수기 H2 DDL 미반영이 원인. DDL에 컬럼 추가로 보정(커밋 b038850) 후 **3개 명령 모두 green** (501 tests passed).
- [x] **P0-T2**: 브랜치/PR 규칙(§3) 합의. `refactor/*` prefix 사용.
  - **결과**: §3 규칙대로 진행 확정. 현재 브랜치 `refactor/493-backend-refactoring-roadmap`도 prefix 준수.

---

## Phase 1 — 응답 규약 통일 (저위험 · 응답 구조 변경 가능)

**목적**: 모든 컨트롤러 응답을 표준 `ApiResponse<T>`(`{success, code, message, data}`)로 통일.

> ⚠️ **동작 변경 주의**: `Map.of("data", response)` → `ApiResponse.ok(response)`는 **JSON 응답 구조가 바뀐다**. FE(카카오/애플 로그인) 계약 확인 필수. FE가 이미 `ApiResponse` 형태를 기대하는지 먼저 확인하고, 아니면 FE 수정과 동기화하거나 보류 태그.

- [x] **P1-T1**: `member/controller/AuthController.java`의 `kakaoLogin`(35), `appleLogin`(41)을 `ApiResponse.ok(response)`로 통일 (같은 파일 49·57행 패턴과 일치).
  - before: `return ResponseEntity.ok(Map.of("data", response));`
  - after: `return ApiResponse.ok(response);` (반환 타입도 `ApiResponse<T>`로 정리, 미사용 import 제거)
  - DoD: 4개 엔드포인트 응답 구조 동일. FE 계약 확인 메모.
  - **FE 계약 확인 (2026-07-02)**: `authService.ts`가 kakao/apple을 `res.data.data`로 파싱 → ApiResponse의 `data` 필드 그대로 읽어 **하위 호환**(추가된 `success/code/message`는 무시). 같은 파일 `/auth/refresh`가 이미 ApiResponse를 반환하고 FE가 동일 방식으로 파싱 중인 것이 결정적 근거. apiClient 성공 인터셉터는 `success` 필드를 검증하지 않음(`client.ts`).
  - **결과**: 커밋 `♻️ 33463fa`. 테스트 통과.
- [x] **P1-T2**: 전 컨트롤러 비표준 응답 스윕.
  - 조사: `grep -rn 'Map.of("data"|ResponseEntity<?>|ResponseEntity<Map' src/main/java` + `ResponseEntity.ok(...)` 중 ApiResponse 미포함 전수 확인.
  - **조사 결과 (2026-07-02)**:
    - `AuthController` kakao/apple — 유일한 `Map.of("data",...)` → **P1-T1에서 표준화 완료**.
    - 그 외 대부분 컨트롤러는 `ResponseEntity<ApiResponse<T>>` 패턴(HTTP 상태 제어 + ApiResponse 바디)으로 이미 표준 준수.
    - **정당한 예외 (변경 보류 — 어드민 웹 계약)**: 아래 4개는 ApiResponse 미래핑으로 raw DTO/빈 바디 반환. 어드민 웹이 이 형식을 **의도적으로 문서화·의존** 중(`admin_web/.../banner.http.ts:55`, `image.http.ts:41` — "presigned-url은 ApiResponse 래핑 없이 바로 반환"). 동작 보존 우선 → 어드민 웹 동기화 없이 변경 금지.
      - `admin/controller/AdminImageController.getPresignedUrl`(49) — `ResponseEntity<PresignedUrlResponse>`
      - `admin/controller/AdminImageController.saveStoreImages`(65)·`saveMenuImages`(99) — `ResponseEntity<Void>` (`ok().build()`, 빈 바디)
      - `banner/controller/AdminBannerController.getPresignedUrl`(36) — `ResponseEntity<PresignedUrlResponse>`
  - DoD: 조사 결과 목록 + 각 항목 표준화 or "정당한 예외" 사유 기록. ✅
- [x] **P1-T3**: 변경 컨트롤러 `@WebMvcTest` 응답 구조 검증 추가/보강.
  - `AuthControllerTest`에 kakao/apple 케이스 추가 — ApiResponse 표준 구조(`success/code/message/data`) + `data` 내부 필드 검증. 커밋 `🧪 32af093`.

**PR**: `[REFACTOR] 컨트롤러 응답 규약 ApiResponse 통일`

---

## Phase 2 — 컨벤션 정리 (저위험 · 동작 불변)

- [x] **P2-T1**: 약어 파라미터/변수명 `req` → `request` 일괄 정리.
  - 대상(확인): `member/controller/AuthController.java`, `admin/service/AdminImageService.java`(100,103-105,120,123-125,203-204), `admin/service/AdminReportService.java`(507-511,550-554), `admin/service/AdminStoreService.java`(354-355)
  - 잔여 조사: `grep -rn "\breq\b\|\bres\b\|\bsvc\b\|\brepo\b" src/main/java --include=*.java` (정식 단어 오탐 제외)
  - DoD: 약어 파라미터명 제거. 무의미 변수명(`data`,`temp`,`obj`,`item`)도 점검.
  - 검증: `./gradlew spotlessApply checkstyleMain checkstyleTest`
  - **결과 (2026-07-02)**: `AuthController`는 P1에서 이미 `request`로 정리됨. `AdminReportService`(`req`→`imageRequest`, `src`→`sourceImage`), `AdminStoreService`(`req`→`candidateRequest`), `AdminImageService`(`req`→`saveRequest`/`urlRequest`, `item`→`uploadResult`) 정리. 잔여 조사 중 `CustomAuthorizationRequestResolver`(`repo`→`clientRegistrationRepository`), `S3Service` 람다(`obj`→`s3Object`)도 정리. `temp`는 실제 S3 폴더명·주석이라 유지. 커밋 `♻️ refactor: 약어 파라미터·변수명 정리`.
- [x] **P2-T2**: `global/encrypt/EncryptedStringConverter.java:12-13` `@Autowired` 필드 주입 제거.
  - JPA `AttributeConverter`는 스프링 빈이 아님 → **static holder** 또는 `@Configurable` 중 택1. 암복호화 왕복 테스트로 회귀 검증.
  - DoD: 필드 `@Autowired` 제거, `AesEncryptor` 주입 경로 명확화, 라운드트립 테스트 통과.
  - **결과 (2026-07-02)**: `@Configurable`(AspectJ 위빙 미설정)보다 **static holder** 채택. `EncryptorHolder` 컴포넌트 신설(생성자 주입 → static 보관), 컨버터는 `EncryptorHolder.getEncryptor()`로 조회. 라운드트립/null/랜덤IV 테스트 3건 추가·통과. 커밋 `♻️ refactor: 필드 @Autowired 제거` + `🧪 test: 라운드트립 테스트 추가`.
- [x] **P2-T3**: 파일/함수 길이 상한 초과 지점 목록화(실제 분해는 Phase 4). 부록 A에 기록만.
  - **결과 (2026-07-02)**: 부록 A-2에 파일 5건(1건 예외)·함수 11건 기록. Phase 4 분해 대상 연결.

**PR**: `[REFACTOR] 네이밍·주입 방식 컨벤션 정리`

---

## Phase 3 — CQRS 분리 확산 (전 도메인 · 고위험 · 도메인당 1 PR)

**목적**: 모든 도메인의 단일 서비스를 `{Domain}CommandService`(`@Transactional`) / `{Domain}QueryService`(`@Transactional(readOnly = true)`)로 분리. **이번 리팩토링에서 전 도메인 실행.**

**표준 절차 (도메인마다 반복)**:
1. 기존 서비스 메서드를 읽기(R)/쓰기(CUD)로 분류.
2. `QueryService`(readOnly) · `CommandService`(쓰기) 2개 클래스 생성, 로직 이동. (조회 전용 도메인은 QueryService만)
3. 컨트롤러 주입부를 두 서비스로 교체.
4. 기존 서비스 테스트를 두 테스트로 재배치, 통과 확인.
5. 도메인별 커밋 + PR.

**공통 DoD**: Command/Query 분리, 컨트롤러 주입 교체, 테스트 재배치·통과, `readOnly` 트랜잭션 정확성, 순환 의존 없음.

**실행 순서** (규모 작은 것 → 큰 것, 참고 패턴 먼저 익히고 대형으로):

- [x] **P3-T1 · (참고 학습)**: 이미 분리된 `course`, `store`의 saved 계열, `admin/course` 구조를 읽고 팀 표준 패턴 확정. 이후 도메인에 동일 패턴 적용.
  - **표준 (2026-07-02)**: `{Domain}QueryService`(`@Service @RequiredArgsConstructor @Transactional(readOnly = true)`, 조회 메서드), `{Domain}CommandService`(`@Transactional`, 쓰기 메서드). 컨트롤러가 두 서비스 주입. 조회 전용 도메인은 QueryService만, 쓰기 전용 도메인은 CommandService만. presigned-url 발급은 course 선례상 CommandService에 배치. 서비스 이동은 순수 move(네이밍은 P2에서 완료했으므로 람다 변수 등 유지).
- [x] **P3-T2 · policy**: `policy/service/*` — 정적 약관 조회 전용이면 `QueryService`만. (가장 단순, 워밍업)
  - **결과**: 조회 전용이라 `TermsService` → `TermsQueryService`(readOnly)로 리네임. 컨트롤러·테스트 참조 갱신. 커밋 `♻️ refactor: policy 서비스 CQRS 분리`.
- [x] **P3-T3 · banner**: `banner/service/BannerService.java` 분리.
  - **결과**: `BannerService` → `BannerQueryService`(readOnly, 사용자 조회), `AdminBannerService` → `AdminBannerCommandService`(쓰기 전용, create/delete/reorder/presigned-url). 어드민에 순수 조회 메서드가 없어 QueryService 미생성. 커밋 `♻️ refactor: banner 서비스 CQRS 분리`. (기존 banner 테스트 없음)
- [x] **P3-T4 · inquiry**: `inquiry/service/InquiryService.java` 분리.
  - **결과**: 쓰기 전용이라 `InquiryService` → `InquiryCommandService`로 리네임. 컨트롤러·서비스테스트(`InquiryCommandServiceTest`)·컨트롤러테스트 참조 갱신. 커밋 `♻️ refactor: inquiry 서비스 CQRS 분리`.
- [x] **P3-T5 · member**: `member/service/MemberService.java`(243줄) 분리.
  - **결과**: 조회 7종 → `MemberQueryService`(readOnly), 쓰기 7종 → `MemberCommandService`. 컨트롤러 두 서비스 주입, `MemberControllerTest` 목 2개로 분리. (pre-commit `git add -u`로 리팩토링+테스트가 한 커밋에 병합됨) 커밋 `♻️ refactor: member 서비스 CQRS 분리`. member 서비스 단위 테스트는 기존에 없었음(Phase 6에서 보강 대상).
- [x] **P3-T6 · alarm**: `alarm/service/AlarmService.java`(289줄) 분리.
  - **결과**: 조회 4종 → `AlarmQueryService`(readOnly), 쓰기 4종 → `AlarmCommandService`. 조회/쓰기 공유 get-or-create 헬퍼(`getNotificationSetting`)는 `NotificationSettingProvider` 컴포넌트로 추출(member `MemberReader` 선례). 컨트롤러 두 서비스 주입, `AlarmServiceTest` → `AlarmQueryServiceTest`/`AlarmCommandServiceTest` 재배치(provider 모킹 전환). 커밋 `♻️ refactor` + `🧪 test`.
- [x] **P3-T7 · abuse**: `abuse/service/*` 분리(현 분리 여부 확인 후).
  - **결과**: 두 서비스 모두 단일 `create*` 커맨드만 가진 쓰기 전용(클래스 readOnly는 무의미) → `AbuseReportService`→`AbuseReportCommandService`, `ModerationAppealService`→`ModerationAppealCommandService` 리네임(클래스 `@Transactional`, 중복 메서드 애노테이션 제거). 컨트롤러·테스트 참조 갱신. rename 기반이라 T4 선례대로 단일 커밋.
- [x] **P3-T8 · menu**: `menu/service/MenuService.java`, `menu/service/TrendService.java` 분리.
  - **결과**: `TrendService`는 전부 조회 → `TrendQueryService` 리네임. `MenuService`는 조회 3종(getCategories/getStoreMenus/getMenuDetail) → `MenuQueryService`(readOnly), 쓰기 2종(likeMenu/cancelMenuLike) → `MenuCommandService`. 컨트롤러 주입 교체, `MenuServiceTest` → `MenuQueryServiceTest`/`MenuCommandServiceTest` 재배치. 훅 `git add -u` 제약상 rename+split 단일 커밋.
- [ ] **P3-T9 · store**: `store/service/StoreService.java`(조회+`registerKakaoStore`(207)/`subscribeStore`(239)/`unsubscribeStore`(252) 혼재), `StoreReportService.java`(327줄) 분리. (saved 계열은 이미 분리됨 — 나머지 정리)
- [ ] **P3-T10 · review**: `review/service/ReviewService.java`(604줄) 분리. (Phase 4-대형분해와 병행)
- [ ] **P3-T11 · admin/***: `admin/service/AdminStoreService.java`(484줄), `AdminMenuService.java`, `AdminMemberService.java`, `AdminImageService.java`, `AdminModerationAppealService.java` 등 분리. `AdminReportService`(962줄)는 Phase 4-대형분해와 결합.
- [ ] **P3-T12 · 잔여 스윕**: `contribution`(service 없음)·`dev`(개발용) 등 CQRS 불필요 도메인은 "제외" 판정 기록. 나머지 서비스 중 누락 없는지 `grep -rL "CommandService\|QueryService" $(grep -rl "@Service" src/main/java)` 로 확인.

**PR (도메인별)**: `[REFACTOR] {domain} 서비스 CQRS 분리`

---

## Phase 4 — 대형 서비스 분해 (고위험 · Phase 3와 연속)

**목적**: `code-convention` 파일 상한(800줄) 초과 서비스를 책임 단위로 분해. Phase 3의 CQRS 분리와 결합해 진행.

- [ ] **P4-T1**: `admin/service/AdminReportService.java`(962줄) 분해 — Phase 3-T11의 CQRS 분리 위에서 신고 처리/조회/통계 등 책임 경계로 추가 분리.
- [ ] **P4-T2**: `review/service/ReviewService.java`(604줄) 분해 (Phase 3-T10 결합).
- [ ] **P4-T3**: 800줄 근접 서비스(`AdminStoreService` 484줄 등) 재점검 — 함수 50줄 상한 위반 메서드 우선 추출.

각 DoD: 분해 후 기존 테스트 통과 + 신규 클래스 테스트 추가. 순환 의존 없음.

**PR**: `[REFACTOR] 대형 서비스 책임 분해`

---

## Phase 5 — `return null` → Optional/예외 (중위험 · 새 구조 위에서)

**목적**: 서비스 레이어의 `return null`을 `.orElseThrow(커스텀예외)` 또는 `Optional`로 전환. **CQRS·분해 완료 후** 안정된 구조 위에서 수행(같은 대형 파일 재작업 방지).

> ⚠️ 필터/핸들러/DTO 컨버터/외부클라의 null은 정상 흐름일 수 있어 옥석 구분 필수.

- [ ] **P5-T1**: 서비스 레이어 null 전환(도메인 예외).
  - 대상: `admin/service/AdminReportService`(896,907)→분해 후 위치, `AdminStoreService`(451), `AdminMemberService`(129), `AdminMenuService`(178,191,197), `AdminModerationAppealService`(106), `menu/service/TrendService`(252)
  - 패턴: 조회 실패 → `.orElseThrow(() -> new {Domain}Exception.NotFound(...))`. 예외 없으면 `CustomException` 상속 static class 추가.
  - DoD: 대상 메서드 null 제거, 각 메서드 실패(예외) 테스트 1개 이상.
- [ ] **P5-T2**: 필터/외부클라 null 검토(전환 아닐 수 있음).
  - 대상: `member/jwt/JwtAuthenticationFilter`(109), `member/oauth/OAuth2SuccessHandler`(104), `global/advice/GlobalExceptionHandler`(81,162), `global/logging/filter/RequestLoggingFilter`(117,134,141,145), `external/opensearch/client/OpenSearchStoreClient`(258,262,267), `external/aws/util/ImageDownloader`(120,127,131)
  - DoD: 각 지점 "전환 or 유지" 판정 + 사유 기록.

**PR**: `[REFACTOR] 서비스 null 반환 → 예외/Optional 전환`

---

## Phase 6 — 마무리

- [ ] **P6-T1**: 전체 diff `/code-review` 실행, CRITICAL/WARNING 처리.
- [ ] **P6-T2**: 최종 체크리스트:
  - [ ] 새로 만든 모든 Service에 테스트 존재
  - [ ] 새로 만든 모든 Controller에 테스트 존재
  - [ ] 추가한 모든 public 메서드에 테스트 존재
  - [ ] 예외 케이스 테스트 존재
- [ ] **P6-T3**: `REFACTOR.md` 진행 현황 최종 갱신.

---

## 부록 A. 변경하지 않는 것 (합의된 예외)

- `@ConfigurationProperties` 클래스의 `@Setter` (`AwsProperties` 등) — 허용 패턴, 유지.
- `member/entity/NotificationSetting.java`의 `setDaysOfWeekList` — String↔List 변환 헬퍼, 유지.
- 정상 흐름 null 반환(필터/DTO 팩토리) — Phase 5에서 개별 판정.
- `contribution`(service 없음)·`dev`(개발용) — CQRS 제외.

## 부록 A-2. 길이 상한 초과 목록 (P2-T3 조사 결과 · Phase 4 분해 대상)

> 조사일 2026-07-02. 상한: 파일 권장 200~400줄·최대 800줄, 함수 권장 20~30줄·최대 50줄. **여기서는 기록만** 하고, 실제 분해는 Phase 4/Phase 3에서 수행.

### 파일 길이 (400줄 초과)

| 파일 | 줄수 | 판정 |
|------|------|------|
| `store/enums/Region3rd.java` | 2693 | **예외** — 행정구역 enum 상수 나열(데이터성). 분해 대상 아님 |
| `admin/service/AdminReportService.java` | 962 | 최대(800) 초과 → **P4-T1** 분해 |
| `review/service/ReviewService.java` | 604 | 최대 미만이나 권장 초과 → **P4-T2** 분해 |
| `admin/service/AdminStoreService.java` | 484 | 권장 초과 → **P4-T3** 재점검 |
| `store/repository/StoreRepository.java` | 460 | Repository(쿼리 선언 집합) → 분해 우선순위 낮음, 관찰 대상 |

### 함수 길이 (50줄 초과 · 11건)

| 메서드 | 줄수 | 위치 |
|--------|------|------|
| `AdminReportService.reviewReport()` | 97 | `admin/service/AdminReportService.java:269` |
| `NaverEnrichRunner.runForStore()` | 77 | `admin/service/NaverEnrichRunner.java:57` |
| `SecurityConfig.filterChain()` | 71 | `config/security/SecurityConfig.java:41` |
| `JwtAuthenticationFilter.doFilterInternal()` | 60 | `member/jwt/JwtAuthenticationFilter.java:38` |
| `AdminImageService.uploadOne()` | 59 | `admin/service/AdminImageService.java:218` |
| `StoreReportService.createMenuEditReport()` | 57 | `store/service/StoreReportService.java:244` |
| `StoreSearchQueryBuilder.build()` | 57 | `external/opensearch/query/StoreSearchQueryBuilder.java:78` |
| `StoreSearchQueryParser.parseContainedTokens()` | 55 | `store/service/search/StoreSearchQueryParser.java:110` |
| `TrendService.resolveRegion3rd()` | 54 | `menu/service/TrendService.java:190` |
| `StoreReportService.createImageReport()` | 53 | `store/service/StoreReportService.java:129` |
| `CustomOAuth2UserService.processOAuth2User()` | 52 | `member/service/CustomOAuth2UserService.java:55` |

> `SecurityConfig.filterChain()`는 스프링 설정 DSL 특성상 길어지기 쉬움(추출 시 가독성 저하 우려) — Phase 4에서 실익 판단 후 결정.

## 부록 B. 진행 요약 (수시 갱신)

| Phase | 상태 | PR | 비고 |
|-------|------|----|----|
| P0 사전 준비 | ✅ | — | 기존 실패 테스트 6건 보정 후 기준선 green |
| P1 응답 규약 | ✅ | — | AuthController kakao/apple 표준화(FE 하위호환 확인). admin image/banner presigned-url·save는 어드민 웹 계약상 정당한 예외로 보류 |
| P2 컨벤션 정리 | ✅ | — | 약어/무의미 변수명 정리, EncryptedStringConverter @Autowired 제거(static holder), 길이 상한 초과 부록 A-2 기록 |
| P3 CQRS(전 도메인) | 🚧 | — | T1~T5 완료(policy·banner·inquiry·member). T6~T12 남음. 현재 브랜치에 소단위 커밋 진행 |
| P4 대형 분해 | ⬜ | — | P3와 연속 |
| P5 null 정리 | ⬜ | — | 새 구조 위 |
| P6 마무리 | ⬜ | — | |
