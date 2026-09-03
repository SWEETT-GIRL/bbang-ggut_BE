> **원본은 FE 레포 `daedongbbangmap/docs/be-tasks-course-collab.md`** (2026-09-03 스냅샷 복사본). 정책·ERD가 바뀌면 원본을 먼저 고치고 여기를 따라 맞춘다. 이 레포 안의 경로 참조는 이 복사본 기준으로 읽으면 된다.

# 빵투어 동시편집 — BE 작업 지시서

작성: 2026-09-03
대상 레포: `~/bbanggut/bbang-ggut_BE` (Spring Boot · PostgreSQL · Flyway)
FE 레포: `~/Desktop/daedongbbangmap` (Expo / React Native)
총괄 문서: `docs/course-collab-phases.md` — **확정 정책·ERD·순서·범위 밖**은 거기가 기준이다. 이 문서는 BE가 phase별로 **정확히 무엇을 어떻게** 만드는지만 적는다.
FE 작업서: `docs/course-collab-fe-phases.md`

---

## 0. 이 문서를 읽는 방법

빵투어(코스)에 친구를 초대해 **같이 편집**하게 만든다. 상대 변경은 FE가 **8초 폴링**으로 받아온다. 웹소켓·푸시·알림은 없다. BE가 할 일은 네 덩어리다.

| Phase | 브랜치 | 한 줄 |
|---|---|---|
| **baseline** | (dev에 바로) | 낡은 테스트 3개를 초록으로 — 안 그러면 Phase 2에서 내가 깬 것과 원래 깨진 것을 못 가른다 |
| **2** 멤버·초대 | `feat/course-collab-members` | 테이블 3개 + `courses` 컬럼 3개, 권한을 "소유자"에서 "소유자 또는 멤버"로, 초대 API 5개 |
| **3** 메모 | `feat/course-store-memos` | `course_stores.memo` 한 칸을 (정류장 × 사람)으로 |
| **4** 부수 정리 | `fix/course-collab-side-effects` | 협업이 열리면서 드러나는 기존 결함 4개 |
| **5** 응답 확장 | `feat/course-collab-response` | 상세·목록 응답에 `canEdit`·`members`·`isMember` |
| **5-B** 링크 공개 | `feat/course-visibility` | `is_public` 2단계를 `visibility` 3단계로, `share_token` |

**순서는 위 그대로.** phase 하나 = 브랜치 하나 = PR 하나. `feat/* → dev → main`, **main 직행 PR 금지.** PR 전에 이슈 먼저(Jira 연동). 커밋은 `<gitmoji> <type>: <한국어 평서문>`.

**DB 규칙**: 스키마는 총괄 문서의 「ERD」 섹션(2026-09-03 검토 완료)이 기준이다. **그 표에서 벗어나는 테이블·컬럼·키·인덱스는 만들기 전에 다시 검토받는다.** 마이그레이션 파일명은 `docs/flyway-migration-naming-convention.md`(`V{yyyyMMddHHmmss}__{action}_{target}.sql`, KST).

**테스트 기준**: `./gradlew test`는 원래 **782개 중 10개가 빨간불**이다(아래 baseline). PR 전 확인은 "전체 초록"이 아니라 **"실패 목록이 이 10개에서 늘지 않았는가"**다.

**소유자 판정이 6곳에 복붙돼 있다.** 한 곳만 고치면 나머지가 조용히 어긋난다. phase마다 이 표를 펼쳐놓고 확인할 것.

| 위치 | 하는 일 | 고치는 phase |
|---|---|---|
| `CourseCommandService:346` `validateOwner` | 편집 권한 | **2** |
| `CourseCommandService:315` `forkCourse` | 비공개 코스 포크 차단 | **4** |
| `CourseProgressService:204` `getAccessibleCourse` | 비공개 코스 따라가기 차단 | **4** |
| `CourseQueryService:62` `getCourseDetail` | 비공개 코스 상세 차단 | **5** |
| `CourseQueryService:76` | `isMine` 계산 | **5** |
| `SavedCourseCommandService:35` | 내 코스는 찜 못 함 | (그대로) |

> 줄 번호는 2026-08-20 실측. BE는 그 뒤로 코스 파일이 안 바뀌어 아직 맞지만, 착수 전에 `grep -n`으로 한 번 더 잰다.

---

## baseline — `CourseCommandServiceTest` 3개 먼저 초록으로

`./gradlew test` 결과 **782개 중 10개 실패.** 이번 작업과 무관한 7개(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1)는 건드리지 않는다. 문제는 **`CourseCommandServiceTest`의 3개가 우리가 고칠 파일에 있다**는 것.

셋 다 프로덕션 버그가 아니라 **테스트가 틀린 것**이다.

| 테스트 | 왜 빨간가 | 고치는 법 |
|---|---|---|
| `forkCourse: 비공개 코스 포크 시도 시 COURSE_FORK_NOT_PUBLIC을 던진다`(691) | `mockCourse(courseId, ..., memberId, ...)`로 **코스 주인을 호출자 본인**으로 만들어 `isOwner == true`가 되어 가드를 통과하고, 스텁 안 된 `memberRepository`(`:322`)에서 `MemberException` | 코스 주인을 다른 id(`2L`)로 |
| `forkCourse: 비공개 FORKED 코스 포크 시도 시 COURSE_FORK_NOT_PUBLIC을 던진다`(705) | 같음 | 같음 |
| `updateCourse: FORKED 코스이고 원본과 동일하면 COURSE_FORK_NOT_MODIFIED를 던진다`(224) | `mockCourse` 헬퍼(717)가 **`isPublic()`을 스텁하지 않아** 항상 `false`, 테스트도 `request.getIsPublic()`을 `null`로 둬서 `willBePublic == false` → 가드가 건너뛰어진다 | `request.getIsPublic()`을 `true`로 |

- [ ] 별도 커밋: `🐛 fix: 포크 권한 테스트가 코스 주인을 호출자로 만들어 가드를 못 태우던 문제를 고친다`
- [ ] 이 커밋 뒤 실패 목록이 **7개**인지 확인. 이게 Phase 2의 출발선이다

> `mockCourse`가 `isPublic`을 스텁하지 않는 건 이 파일 전체에 영향을 준다. 공개 여부에 기대는 테스트를 새로 쓸 때 주의.

---

## Phase 2 — 멤버·초대 기반 (`feat/course-collab-members`)

### 배경

지금 코스 편집은 `validateOwner`(`CourseCommandService:346`) 하나로 막혀 있고 **9곳**에서 불린다(95, 126, 133, 186, 198, 210, 274, 281, 301). "소유자 또는 멤버"로 넓히는 게 이 phase의 본체인데, 함정이 하나 있다.

> ⚠️ **`validateOwner`는 `course.getMember() == null`이면 거부한다.** 큐레이션(`CURATED`) 코스는 `member_id`가 NULL이라 **이 조건이 큐레이션을 우연히 막고 있다.** `updateCourse:91`만 CURATED를 명시적으로 막고 나머지 8곳은 이 암묵 방어에 기댄다. "소유자 또는 멤버"로 완화하는 순간 **`course_members`에 행 하나만 심으면 공식 추천 코스를 아무나 편집**하게 된다. 아래 `validateCanEdit`의 `CURATED` 검사가 이 문서에서 제일 중요한 한 줄이다.

### 2-1. 마이그레이션 — 파일 3개

ERD(총괄 문서) 그대로. 선례: 복합 PK·부분 인덱스는 `V20260804124500__create_course_progress.sql`, 타임스탬프는 `V20260820140000__create_course_store_visits.sql`.

- [ ] `V..__create_course_members.sql`
  ```sql
  CREATE TABLE course_members (
      course_id     BIGINT      NOT NULL REFERENCES courses (id),
      member_id     BIGINT      NOT NULL REFERENCES member (id),
      display_name  VARCHAR(10) NOT NULL,   -- 코스 안에서 쓰는 이름. DB에서도 막는다(확정)
      joined_at     TIMESTAMP   NOT NULL,   -- 참여 순서 = 메모 정렬·승계 순서
      created_at    TIMESTAMP   NOT NULL,
      updated_at    TIMESTAMP,
      deleted_at    TIMESTAMP,              -- 나가기·탈퇴
      PRIMARY KEY (course_id, member_id)
  );
  CREATE INDEX idx_course_members_member ON course_members (member_id) WHERE deleted_at IS NULL;
  ```
  - role 컬럼 없음(전원 편집자), 초대 상태 컬럼 없음(수락 = insert). 방장 여부는 `courses.member_id`로만
  - **방장도 행을 갖는다** — 첫 초대 링크 생성 시 만든다. 기존 코스 이관 없음
- [ ] `V..__create_course_invites.sql`
  ```sql
  CREATE TABLE course_invites (
      id          BIGSERIAL   PRIMARY KEY,
      course_id   BIGINT      NOT NULL REFERENCES courses (id),
      token       VARCHAR(64) NOT NULL UNIQUE,   -- SecureRandom 32B → base64url(43자)
      created_by  BIGINT      NOT NULL REFERENCES member (id),
      expires_at  TIMESTAMP   NOT NULL,          -- 생성 + 7일
      created_at  TIMESTAMP   NOT NULL,
      deleted_at  TIMESTAMP                      -- 재생성으로 무효화 = soft delete
  );
  CREATE INDEX idx_course_invites_course_active ON course_invites (course_id) WHERE deleted_at IS NULL;
  ```
  - "코스당 활성 링크 1개"는 DB 제약이 아니라 **락 안의 서비스 로직**(`expires_at > now()`는 부분 인덱스 조건에 못 쓴다)
  - 하드 삭제 없음. 만료는 `expires_at`으로 판정
- [ ] `V..__add_collab_columns_to_courses.sql` — **세 컬럼을 한 파일로**(확정). 7-5·5-B가 쓰지만 미리 있어도 해가 없다
  ```sql
  ALTER TABLE courses ADD COLUMN day_count   INTEGER     NOT NULL DEFAULT 1;
  ALTER TABLE courses ADD COLUMN visibility  VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
  ALTER TABLE courses ADD COLUMN share_token VARCHAR(64) UNIQUE;
  UPDATE courses SET visibility = CASE WHEN is_public THEN 'PUBLIC' ELSE 'PRIVATE' END;
  UPDATE courses c SET day_count = GREATEST(1, COALESCE((SELECT MAX(day) FROM course_stores s WHERE s.course_id = c.id AND s.deleted_at IS NULL), 1));
  ```
  - `is_public`은 **지우지 않는다**(구버전 앱이 읽는다). 5-B 전까지 `visibility`는 읽기만 하고 쓰기는 `is_public`과 동기화. 다음 릴리즈에서 drop

### 2-2. 엔티티·리포지토리

- [ ] `course/entity/CourseMember.java` + `CourseMemberId.java` — `CourseProgress`/`CourseProgressId`(`@Embeddable` + `@EmbeddedId`, `BaseTimeEntity`) 복제 수준
  - 필드: `id(courseId, memberId)`, `displayName`, `joinedAt`, `deletedAt`
  - 메서드: `static of(Course, Member, String displayName)`, `rename(String)`, `leave()`(`deletedAt = now`), `rejoin(String displayName)`(`deletedAt = null`, `joinedAt = now` — 나갔다 다시 들어오면 **같은 PK**라 새 행이 아니다), `isDeleted()`
- [ ] `course/entity/CourseInvite.java` — `id`, `course`, `token`, `createdBy`, `expiresAt`, `deletedAt`. `isExpired(now)`, `isActive(now)`, `revoke()`
- [ ] `CourseMemberRepository`
  ```java
  boolean existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(Long courseId, Long memberId);   // validateCanEdit
  Optional<CourseMember> findByIdCourseIdAndIdMemberId(Long courseId, Long memberId);          // 재참여용 — deleted 포함
  List<CourseMember> findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(Long courseId);   // 참여자 목록·메모 정렬·승계
  long countByIdCourseIdAndDeletedAtIsNull(Long courseId);                                       // 정원
  List<CourseMember> findAllByIdMemberIdAndDeletedAtIsNull(Long memberId);                       // 내가 참여한 코스
  @Modifying @Query("UPDATE CourseMember m SET m.id.memberId = :to WHERE m.id.memberId = :from")
  int moveOwner(@Param("from") Long from, @Param("to") Long to);                                 // 게스트 이관
  ```
- [ ] `CourseInviteRepository`
  ```java
  Optional<CourseInvite> findByTokenAndDeletedAtIsNull(String token);
  Optional<CourseInvite> findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long courseId);
  @Modifying @Query("UPDATE CourseInvite i SET i.createdBy.id = :to WHERE i.createdBy.id = :from")
  int moveOwner(@Param("from") Long from, @Param("to") Long to);
  ```
- [ ] `CourseRepository`에 비관적 락 조회 추가. **기존 `findByIdAndDeletedAtIsNull`은 그대로 둔다**(전 조회에 락을 걸면 성능이 죽는다)
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Course> findWithLockByIdAndDeletedAtIsNull(Long id);
  ```
  락을 타는 곳 **5개**: `addStore` · `removeStore` · `reorderStores`(동시 순서 저장 500 방지) + **`createInvite` · `acceptInvite`**(정원 8명·활성 링크 1개는 count 뒤 insert라 락 없이는 수락 둘이 동시에 오면 9명, 생성 둘이 동시에 오면 링크 2개)
- [ ] 토큰 생성 유틸 `course/util/CollabTokenGenerator.java` — `SecureRandom` 32B → `Base64.getUrlEncoder().withoutPadding()`. 초대 토큰과 5-B의 `share_token`이 **같은 유틸을 쓰되 컬럼은 분리**한다(유출 피해 등급이 다르다 — 초대 토큰은 편집자가 되고 share_token은 보기만)
- [ ] **게스트 이관** — `GuestDataMigrationService`는 테이블마다 `moveOwner`를 명시적으로 부른다(리포지토리 12개). `courseMemberRepository.moveOwner`·`courseInviteRepository.moveOwner`를 **추가**하지 않으면 게스트가 방장으로 시작한 협업 코스의 멤버 행·토큰이 로그인 뒤 게스트 id에 남는다(방문 체크 때 같은 함정, `docs/course-visit-record-link.md`). 게스트는 멤버가 될 수 없지만 **방장은 될 수 있다**

### 2-3. 권한 교체 — 커밋을 반드시 나눈다

락 교체와 권한 교체를 한 커밋에 섞으면 **깨진 테스트가 둘 중 어느 것 때문인지 못 찾는다.**

```
① CourseRepository에 락 조회 추가        → verify: 컴파일
② 서비스 3곳(addStore·removeStore·reorderStores)을 락 조회로 교체
                                          → verify: 깨진 테스트 목록을 눈으로 확인 (여기서 커밋)
③ 그 테스트들의 스텁만 치환              → verify: 실패 목록이 7개로 복귀 (여기서 커밋)
④ validateCanEdit 신설 + 5곳 교체        → verify: 7개
⑤ 큐레이션 방어 테스트 5개 추가          → verify: 7개
⑥ 초대 생성·수락에 CURATED 차단          → verify: 7개
```

- [ ] ②에서 깨지는 테스트(추정 9개): `CourseCommandServiceTest`가 `courseRepository.findByIdAndDeletedAtIsNull`를 **24곳** 스텁하는데 Mockito 스텁은 메서드 이름이 정확히 일치해야 걸린다. 락으로 바꾼 3개 메서드의 테스트는 `Optional.empty()` → `COURSE_NOT_FOUND`로 실패한다 — `addStore_success`(249) · `addStore_duplicate_throwsAlreadyExists`(279) · `addStore_withDay_insertsAfterRequestedDay`(298) · `addStore_dayOutOfRange_throwsDayInvalid`(332) · `removeStore_success`(385) · `reorderStores_success`(406) · `reorderStores_withDays_updatesDays`(455) · `reorderStores_dayGap_throwsDayInvalid`(495) · `reorderStores_duplicateCourseStore_throwsInvalidOrder`(532). **전역 치환 금지** — `updateCourse`/`deleteCourse` 테스트는 락을 안 쓰는 메서드라 그대로여야 한다
- [ ] ④ `validateCanEdit(course, memberId)` — **CURATED 검사를 소유자 판정보다 먼저**
  ```java
  // ⚠️ 이 CURATED 검사를 지우지 말 것.
  // 기존 validateOwner는 course.getMember() == null 조건으로 큐레이션을 "우연히" 막고 있었다.
  // 소유자 조건을 멤버까지 넓히는 순간 그 우연한 방어가 사라진다 —
  // course_members에 행 하나만 심으면 공식 추천 코스를 아무나 편집하게 된다.
  private void validateCanEdit(Course course, Long memberId) {
      if (course.getType() == CourseType.CURATED) {
          throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
      }
      if (isOwner(course, memberId)) {
          return;
      }
      if (!courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId)) {
          throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
      }
  }
  private boolean isOwner(Course course, Long memberId) {
      return course.getMember() != null && course.getMember().getId().equals(memberId);
  }
  ```
- [ ] **`validateCanEdit`로 교체 (5곳)**: `updateCourse`(단 `isPublic` 변경 분기는 **owner 전용으로 남긴다**), `addStore`, `updateStore`, `removeStore`, `reorderStores`
- [ ] **`validateOwner` 유지 (4곳)**: `deleteCourse`, `getPresignedUrl`, `registerImage`, `deleteImage` — 커버는 방장 전용(확정)
- [ ] `updateCourse:91`의 기존 CURATED 차단은 **지우지 말 것** — 이중 방어
- [ ] ⑤ 못 박는 테스트 — `addStore` / `updateStore` / `removeStore` / `reorderStores` / `updateCourse` **5개 전부**에
  ```java
  @Test
  @DisplayName("큐레이션 코스는 course_members에 행이 있어도 편집이 막힌다")
  void curatedCourse_withMemberRow_stillForbidden() {
      // given: type=CURATED, member=null 코스 + 호출자가 활성 멤버
      given(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId)).willReturn(true);
      // when & then: COURSE_FORBIDDEN
  }
  ```
  나중에 누가 `validateCanEdit`의 CURATED 줄을 지우면 5개가 동시에 빨간불이 된다. DB 트리거 같은 3차 방어는 **V1에서 하지 않는다**(이 레포에 트리거 선례가 없고 PostgreSQL CHECK는 다른 테이블을 못 본다)

### 2-4. 초대·멤버 API — 계약

컨트롤러는 `CourseController`(`@RequestMapping("/courses")`)에 얹고, 서비스는 신규 `course/service/CourseMemberService.java`. 응답은 기존처럼 `ResponseEntity<ApiResponse<T>>`.

#### 새 에러 코드 (`course/code/CourseErrorCode.java`, 기존 번호 이어서)

| 코드 | HTTP | 문자열 | 메시지 |
|---|---|---|---|
| `COURSE_INVITE_INVALID` | 404 | `COURSE404_6` | 유효하지 않은 초대예요. |
| `COURSE_INVITE_EXPIRED` | 400 | `COURSE400_14` | 초대 링크가 만료됐어요. 새 링크를 받아주세요. |
| `COURSE_MEMBER_LIMIT` | 400 | `COURSE400_15` | 빵투어 인원이 다 찼어요. (방장 포함 8명) |
| `COURSE_COLLAB_CANNOT_BE_PUBLIC` | 400 | `COURSE400_16` | 같이 편집 중인 빵투어는 공개할 수 없어요. |
| `COURSE_MEMBER_NOT_FOUND` | 404 | `COURSE404_7` | 이 빵투어의 일행이 아니에요. |
| `COURSE_NO_SUCCESSOR` | 400 | `COURSE400_17` | 넘겨줄 일행이 없어요. 빵투어를 삭제해주세요. |
| `COURSE_DAY_COUNT_INVALID` | 400 | `COURSE400_18` | 일차 수는 줄일 수 없어요. (7-5) |

밴 계정은 기존 `MemberRestrictionReader.validateCanWrite(memberId)`가 던지는 코드를 그대로 쓴다. 이름 길이(1~10, 공백만 거부)는 `@Size(max = 10)` + `@NotBlank` → 기존 검증 400.

#### `POST /courses/{courseId}/invites` — 초대 링크 만들기·받기

- 인증 필수. 권한: **방장 또는 활성 멤버**(`validateCanEdit`와 같은 판정 → CURATED 차단 포함). **밴 계정 차단**(`validateCanWrite`, 확정)
- **락 조회**(`findWithLockByIdAndDeletedAtIsNull`) 안에서:
  1. 방장의 `course_members` 행이 없으면 **여기서 만든다** — `display_name = 방장 닉네임`, `joined_at = now`. 협업은 이 호출로 시작된다
  2. 활성 토큰(`deleted_at IS NULL AND expires_at > now`)이 있고 **남은 기간이 1일 이상이면 재사용**
  3. 아니면 새로 발급하고 **이전 활성 토큰은 `revoke()`(soft delete)** — 코스당 살아있는 링크는 항상 1개
- 응답 `200`
  ```json
  { "token": "k9J...43자", "expiresAt": "2026-09-10T14:03:00", "reused": false }
  ```
  URL(`{SITE_BASE}/i/{token}`)은 FE가 조립한다. 서버는 도메인을 모른다

#### `GET /courses/invites/{token}` — 수락 화면·랜딩용 미리보기

- **비로그인 허용**(랜딩은 사용자 토큰이 없는 서버다). `SecurityConfig:86`의 매처는 **숫자 전용**이라 이대로면 401 — 좁게 연다:
  ```java
  // 기존: RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/\\d+(\\?.*)?$")
  RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/invites/[A-Za-z0-9_-]+(\\?.*)?$")
  ```
  **`/courses/**`로 열면 안 된다**(비공개 코스 API가 통째로 열린다). **수락(POST)은 절대 열지 않는다**
- 만료·무효·정원초과도 **예외 대신 필드로 200** — 랜딩이 200을 받아야 카드가 뜬다
- 응답
  ```json
  {
    "state": "OK",                 // OK | EXPIRED | INVALID | FULL | DELETED
    "courseId": 12,                // INVALID 이면 null
    "title": "성수 소금빵 투어",
    "ownerName": "다나",           // 방장 닉네임
    "memberCount": 3,              // 방장 포함 활성 멤버 수
    "memberLimit": 8,
    "expiresAt": "2026-09-10T14:03:00",
    "viewer": { "isMember": false, "isOwner": false }   // 인증 헤더가 있을 때만 채움, 없으면 null
  }
  ```
  - **가게 목록·메모를 넣지 않는다.** 토큰을 아는 사람은 "초대받은 사람"이지 아직 멤버가 아니다
  - `viewer`는 FE가 "이미 멤버·본인이 방장이면 수락 화면 대신 바로 코스로" 보내는 데 쓴다. 인증이 optional인 엔드포인트라 `@AuthenticationPrincipal`이 null일 수 있다 — 기존 `getCourseDetail`(`memberId == null` 분기)과 같은 패턴

#### `POST /courses/invites/{token}/accept` — 참여

- 인증 필수. 게스트 불가(로그인 필수 — 확정). **밴 계정 차단**(`validateCanWrite`)
- 요청
  ```json
  { "displayName": "지수" }     // @NotBlank @Size(max = 10)
  ```
- **락 조회** 안에서 검증 순서: 토큰 존재·soft delete 아님(`COURSE_INVITE_INVALID`) → 만료(`COURSE_INVITE_EXPIRED`) → 코스 삭제(`COURSE_NOT_FOUND`) → CURATED(`COURSE_FORBIDDEN`, 2차 방어) → **이미 멤버이거나 본인이 방장이면 아무것도 안 하고 200**(자기 링크를 눌러보는 건 흔하다 — 에러 금지) → 정원(`countByIdCourseIdAndDeletedAtIsNull >= 8` → `COURSE_MEMBER_LIMIT`)
- 부수 처리(같은 트랜잭션):
  1. `course_members` insert. 나갔던 사람이면 같은 PK 행을 `rejoin(displayName)`
  2. 그 코스를 **찜해뒀다면 자동 해제**(`saved_courses` 행 삭제)
  3. **코스가 PUBLIC이면 PRIVATE으로 전환**(확정 — 전환 시점은 링크 생성이 아니라 **첫 수락**이다. 링크만 만들고 아무도 안 오면 공개 그대로). 5-B 뒤에는 `visibility = PRIVATE`으로
  4. 방장 행이 없으면 만든다(정상 경로에선 `createInvite`가 만들었지만 방어)
- 응답 `200`
  ```json
  { "courseId": 12, "alreadyMember": false }
  ```

#### `DELETE /courses/{courseId}/members/me` — 나가기

- 인증 필수. 호출자가 활성 멤버가 아니면 `COURSE_MEMBER_NOT_FOUND`
- **멤버**: `leave()`. **담았던 가게는 코스에 남긴다.** 메모는 Phase 3에서 이 함수에 이어 붙인다(여기선 멤버 행만)
- **방장**: "승계하고 나가기" — 활성 멤버 중 `joined_at`이 가장 오래된 사람(방장 본인 제외)에게 `courses.member_id`를 넘기고 본인 행을 `leave()`. 넘길 사람이 없으면 `COURSE_NO_SUCCESSOR`(FE는 그때 "삭제"만 보여준다). 새 방장의 `course_members` 행은 **남긴다**(방장도 코스 내 이름을 갖는다 — 확정). Phase 4의 탈퇴 승계와 **같은 함수** `CourseMemberService.transferOwnership(course, from)`를 쓴다
- 응답 `200` 빈 body

#### `PATCH /courses/{courseId}/members/me` — 코스 내 이름 변경

- 요청 `{ "displayName": "지수" }`, 검증 동일. 방장도 가능(행이 있으므로). 응답 `200`

#### `PATCH /courses/{courseId}` — 기존 수정 API에 두 가지

- `isPublic = true`로 바꾸려는데 **활성 멤버가 1명 이상**(방장 행 제외 — 방장 행만 있으면 협업이 아직 아니다)이면 `COURSE_COLLAB_CANNOT_BE_PUBLIC`. 5-B 뒤에는 **PUBLIC만 차단**, UNLISTED는 허용
- `dayCount`(7-5): 요청에 `dayCount`가 오면 **락 안에서** `max(course_stores.day)` 이상이고 **현재 값 이상**일 때만 저장, 아니면 `COURSE_DAY_COUNT_INVALID`. **늘어나기만 한다**(확정). 응답 상세에 내려주는 건 Phase 5

### 2-5. 검증

- [ ] 멤버 계정으로 가게 추가·수정·삭제·순서 변경 성공, 코스 삭제·공개 전환·커버 변경은 403
- [ ] **큐레이션 코스에 `course_members` 행을 강제로 넣어도 편집 403** (테스트 5개 + 실제 DB로 한 번)
- [ ] 초대 생성 시 방장 행이 생기고 `display_name`이 닉네임. 두 번 부르면 같은 토큰(`reused: true`). 만료 하루 전 토큰은 재발급되고 이전 토큰은 soft delete
- [ ] 수락: 만료·무효·정원초과 거부, 방장·기존 멤버는 200, 찜 해제됨, PUBLIC → PRIVATE
- [ ] 스레드 2개로 동시 reorder에서 500 없음 · 동시 수락 8명째 둘 중 하나만 성공
- [ ] 미리보기는 비로그인 curl로 200, `POST .../accept`는 비로그인 401
- [ ] 게스트가 방장으로 초대 링크를 만든 뒤 로그인 → 이관 뒤에도 `course_members`·`course_invites`가 새 회원 id
- [ ] 실패 목록이 baseline 7개에서 늘지 않음

### FE에서 켜지는 것

초대 시트 실기능(링크 생성·공유), 수락 화면, 멤버의 편집. FE 문서 Phase 6.

---

## Phase 3 — 메모 사람별 분리 (`feat/course-store-memos`)

### 배경

지금 메모는 `course_stores.memo` 한 칸(`TEXT`, 검증 없음). 이걸 (정류장 × 사람)으로 쪼갠다. 방장이 쓴 기존 메모는 방장 메모로 이관하고, 큐레이션 코스의 메모는 코스 설명 역할이라 **건드리지 않는다**(`AdminCourseCommandService:136,145,158`과 `~/bbanggut/bbang-course.sh`가 계속 쓴다).

### 3-1. 마이그레이션 — 파일 1개

- [ ] `V..__create_course_store_memos.sql`
  ```sql
  CREATE TABLE course_store_memos (
      course_store_id BIGINT       NOT NULL REFERENCES course_stores (id),
      member_id       BIGINT       NOT NULL REFERENCES member (id),
      content         VARCHAR(200) NOT NULL,
      created_at      TIMESTAMP    NOT NULL,
      updated_at      TIMESTAMP,
      deleted_at      TIMESTAMP,              -- 가게 뺄 때·나가기·탈퇴
      PRIMARY KEY (course_store_id, member_id)
  );

  -- 방장이 쓴 기존 메모를 방장 메모로. CURATED는 작성자가 없으니 그대로 둔다.
  INSERT INTO course_store_memos (course_store_id, member_id, content, created_at)
  SELECT cs.id, c.member_id, LEFT(cs.memo, 200), NOW()
  FROM course_stores cs
  JOIN courses c ON c.id = cs.course_id
  WHERE c.type IN ('CUSTOM', 'FORKED')
    AND c.member_id IS NOT NULL
    AND c.deleted_at IS NULL
    AND cs.deleted_at IS NULL
    AND cs.memo IS NOT NULL AND btrim(cs.memo) <> '';
  ```
  - 키는 **정류장 행**(`course_stores.id`)이다(확정). 가게를 빼면 정류장 행과 함께 메모도 전원 soft delete 되고, 다시 담으면 새 정류장 행이 생기므로(`uq_course_store_store`는 활성 행 한정 부분 인덱스, `V20260804150000`) 메모는 돌아오지 않는다. **방문 체크(`course_store_visits`)가 `(course_id, store_id)` 자연키로 "남기되 숨김"인 것과 일부러 다르다**
  - `LEFT(memo, 200)`: 기존 메모가 200자를 넘는 게 있으면 잘린다. 이관 전에 `SELECT COUNT(*) FROM course_stores WHERE length(memo) > 200`으로 개수를 보고, 0이 아니면 사용자에게 알린다
  - `course_stores.memo` 컬럼은 **지우지 않는다**

### 3-2. 엔티티·리포지토리

- [ ] `course/entity/CourseStoreMemo.java` + `CourseStoreMemoId.java`(`courseStoreId`, `memberId`). `updateContent(String)`, `softDelete()`, `restore(String)`(같은 PK로 다시 쓰는 경우)
- [ ] `CourseStoreMemoRepository`
  ```java
  Optional<CourseStoreMemo> findByIdCourseStoreIdAndIdMemberId(Long courseStoreId, Long memberId);           // 내 메모 upsert (deleted 포함)
  List<CourseStoreMemo> findAllByIdCourseStoreIdInAndDeletedAtIsNull(Collection<Long> courseStoreIds);       // 상세 한 방 조회
  @Modifying @Query("UPDATE CourseStoreMemo m SET m.deletedAt = CURRENT_TIMESTAMP WHERE m.id.courseStoreId = :courseStoreId AND m.deletedAt IS NULL")
  int softDeleteAllByCourseStoreId(@Param("courseStoreId") Long courseStoreId);                                // 가게 뺄 때
  @Modifying @Query("UPDATE CourseStoreMemo m SET m.deletedAt = CURRENT_TIMESTAMP WHERE m.id.memberId = :memberId AND m.id.courseStoreId IN (SELECT s.id FROM CourseStore s WHERE s.course.id = :courseId) AND m.deletedAt IS NULL")
  int softDeleteAllByCourseIdAndMemberId(@Param("courseId") Long courseId, @Param("memberId") Long memberId); // 나가기·탈퇴
  @Modifying @Query("UPDATE CourseStoreMemo m SET m.id.memberId = :to WHERE m.id.memberId = :from")
  int moveOwner(@Param("from") Long from, @Param("to") Long to);                                                // 게스트 이관
  ```
- [ ] **게스트 이관**에 `courseStoreMemoRepository.moveOwner` 추가. 게스트가 자기 코스에 쓴 메모는 이관된 `course_stores.memo`에서 온 것이라 여기 안 옮기면 로그인 뒤 사라진다

### 3-3. 쓰기 경로

- [ ] `PATCH /courses/{courseId}/stores/{storeId}` (`updateStore`) 의미 변경 — 요청 DTO는 그대로(`memo`, `visitTime`)
  - `memo`: **내 메모만** upsert. 빈 문자열·null이면 내 메모 soft delete. `@Size(max = 200)` 추가(`CourseStoreUpdateRequest`·`CourseStoreAddRequest` 둘 다 — 지금은 검증이 **전혀 없다**)
  - `visitTime`: 공통 1개, 지금처럼 `course_stores.visit_time`
  - **큐레이션 코스는 이 경로에 못 온다**(`validateCanEdit`가 막는다). 어드민 경로는 새 테이블로 옮기지 않는다
- [ ] `addStore`의 `memo`도 내 메모로(정류장 생성 직후 insert)
- [ ] `removeStore`: `courseStore.softDelete()` 직후 `softDeleteAllByCourseStoreId`
- [ ] `DELETE /courses/{courseId}/members/me`(Phase 2)에 `softDeleteAllByCourseIdAndMemberId` 이어 붙인다 — **나간 멤버의 메모는 삭제**(확정)
- [ ] `MemberCommandService.deleteMember`(Phase 4)에서도 같은 것 — **탈퇴한 멤버·방장의 메모 삭제**(확정)
- [ ] `forkCourse`(`:333~341`)에서 **메모 복사 제외** — 지금은 `source.getMemo()`를 그대로 복제한다(`:338`). 가게·순서·일차·방문시간만
- [ ] `isSameAsSource`(`CourseCommandService:383`)에서 **memo 비교를 뺀다**(확정) — 가게·순서·일차·방문시간 4개로만. 안 빼면 `course_stores.memo`가 사용자 코스에서 더 이상 안 써져 항상 `null == null`로 통과해 판정이 조용히 느슨해진다. 주석으로 이유를 남길 것

### 3-4. 읽기 경로 — 응답 계약

`CourseStoreResponse`(record)에 두 필드를 **더한다.** 기존 `memo`는 **없애지 않는다.**

```json
{
  "courseStoreId": 501, "visitOrder": 1, "day": 1, "storeId": 77, "storeName": "성수 소금빵집",
  "latitude": 37.5, "longitude": 127.0, "region": "성수동",
  "visitTime": "14:00", "visited": false, "visitedRecordId": null,
  "memo": "소금빵 꼭 먹기",                      // = myMemo. 구버전 앱은 이것만 읽는다 — 호환용
  "myMemo": "소금빵 꼭 먹기",
  "memos": [                                     // 참여 순서(joined_at ASC). 서버가 정렬해서 내려준다
    { "memberId": 1, "displayName": "다나", "content": "소금빵 꼭 먹기", "updatedAt": "2026-09-03T10:00:00", "isMine": true },
    { "memberId": 5, "displayName": "지수", "content": "주차 어렵대",   "updatedAt": "2026-09-03T11:20:00", "isMine": false }
  ]
}
```

- `memos`에 **내 메모도 포함**한다(빼면 "일행 N명" 카운트가 사람마다 달라진다). 메모 없는 일행은 안 나온다
- 정렬은 **서버가** `course_members.joined_at` 오름차순으로. FE가 매번 정렬하지 않게. 방장은 첫 초대 때 행이 생기므로 항상 맨 앞
- `displayName`은 `course_members`에서. 행이 없는 사람(이관 전 옛 데이터)은 닉네임으로 폴백
- **구버전 호환**: OTA를 안 받은 앱은 `memo` 하나만 읽고 `PATCH { memo }`만 보낸다. 둘 다 계속 동작해야 한다. `is_public`을 한 릴리즈 남기는 것과 같은 이유
- 조회는 `findAllByIdCourseStoreIdInAndDeletedAtIsNull(정류장 id 전부)` **한 방** + 멤버 목록 한 방. 정류장마다 돌지 말 것(`buildSummaryResponses`가 `IN` 배치로 하는 것과 같은 방식)
- 메모 작성자 조회에 `MemberReader.getMember`를 **쓰지 말 것** — 탈퇴자면 예외를 던져 응답 전체가 죽는다. 탈퇴자의 메모는 어차피 삭제되지만(3-3) 경계 순간이 있다

### 3-5. 검증

- [ ] 같은 가게에 두 계정이 각각 메모 → 서로 안 덮어씀, `memos` 2개, 각자 `myMemo`는 자기 것
- [ ] 기존 코스의 메모가 주인 메모로 보이고 `memo` 필드로도 그대로 내려온다
- [ ] 큐레이션 코스는 `course_stores.memo`가 그대로 보인다
- [ ] 가게를 빼면 그 정류장 메모가 전원에게서 사라지고, 다시 담아도 안 돌아온다
- [ ] 포크본에 메모 없음 · 원본과 가게·순서·일차·시간이 같은 FORKED를 공개하려 하면 여전히 `COURSE_FORK_NOT_MODIFIED`
- [ ] 201자 메모 400

### FE에서 켜지는 것

사람별 메모 카드. FE 문서 Phase 6.

---

## Phase 4 — 부수 정리 (`fix/course-collab-side-effects`)

협업이 열리면서 드러나는 **기존 결함**이다. 협업과 무관해 보여도 멤버가 생기는 순간 실제 사용자에게 닿는다.

### 4-1. 가게 삭제 시 완주 부당 승격 차단

> 방문 체크 작업(`docs/course-visit-record-link.md`, 2026-08-20 배포)으로 집계는 `bread_record`가 아니라 `course_store_visits` 조인이 됐다(`findAggregates`·`findMemberAggregates`). 하지만 **`updateCompletion`의 단방향 승격은 그대로다.**

`CourseProgressService.refreshForCourse`(`:125`)가 `updateCompletion`(`:166`)을 부르는데 `visited >= storeCount`면 `markFinished`뿐, **`else`가 없다.** 완주는 되돌리는 경로가 코드에 없는 단방향이다. 빵집 5곳 중 3곳 방문한 팔로워가 있는데 누가 안 간 2곳을 지우면 분모만 줄어 **3/3 완주 + 뱃지 부당 지급**. 협업에서는 "누가"가 멤버 전원이 되니 훨씬 자주 일어난다.

- [ ] 의도를 파라미터로 넘긴다
  ```java
  // 가게가 늘어난 경우에만 완주 승격을 허용한다.
  // 삭제로 storeCount가 줄면 방문 수는 그대로인 채 분모만 작아져
  // 가지 않은 코스가 완주 처리된다 (되돌리는 경로가 없어 뱃지까지 나간다).
  public void refreshForCourse(Long courseId, boolean allowPromotion)
  ```
  | 호출부 | 값 |
  |---|---|
  | `addStore`(`:178`) | `true` |
  | `removeStore`(`:204`) | `false` — 진행률 숫자만 갱신, 승격 금지 |

  `updateCompletion` 맨 위에 `if (!allowPromotion) return;`. 다른 호출부 `refreshForMember`(`BreadRecordCommandService` 3곳, `GuestDataMigrationService` 1곳)는 빵로그·이관 쪽이라 무관
- [ ] **"완주 취소"는 만들지 않는다.** 이미 나간 뱃지 회수는 훨씬 큰 작업이고 UX도 나쁘다. 기존 잘못 지급된 데이터는 건드리지 않는다
- [ ] 검증 — **4번을 빠뜨리지 말 것**(삭제 승격을 막다가 정상 완주까지 막으면 안 된다)
  ```
  1. 5곳 코스에 팔로워가 3곳 방문 (IN_PROGRESS, 3/5)
  2. 주인이 안 간 2곳 삭제
  3. → 팔로워가 3/3인데도 IN_PROGRESS 유지되는가
  4. 주인이 1곳 추가 → 3/4, 여전히 IN_PROGRESS
  5. 팔로워가 그 1곳 방문 → 4/4 → FINISHED
  ```

### 4-2. 멤버의 포크·따라가기 허용

- [ ] `forkCourse`(`:315~318`)의 `isOwner` 검사에 활성 멤버를 더한다. 지금은 비공개 협업 코스를 멤버가 포크하면 `COURSE_FORK_NOT_PUBLIC`
- [ ] `CourseProgressService.getAccessibleCourse`(`:201~207`)의 판정에 활성 멤버를 더한다. 협업 코스는 PRIVATE이라 멤버가 이 조건에 걸려 **시작조차 못 하는 상태**다. 소유자는 이미 통과한다(확인함)
  ```java
  boolean isOwner = course.getMember() != null && course.getMember().getId().equals(memberId);
  boolean isMember = courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId);
  if (!course.isPublic() && !isOwner && !isMember) { throw ...COURSE_FORBIDDEN; }
  ```
  판정 헬퍼 `course.isEditableBy(memberId)`처럼 한 곳으로 모으는 걸 검토하되, 이 phase 범위를 넘는 리팩터는 하지 않는다

### 4-3. 회원 탈퇴 — 승계와 정리

`MemberCommandService.deleteMember`(`:165~`)는 코스를 **전혀 건드리지 않는다.** 여기에 협업 정리를 붙인다.

- [ ] **방장이 탈퇴하면** 협업 코스마다(`courses.member_id = 탈퇴자` AND 활성 멤버 ≥ 1) `CourseMemberService.transferOwnership(course, from)` — Phase 2의 "방장 나가기"와 **같은 함수**. `joined_at` 가장 오래된 멤버에게 `courses.member_id` 이전, 새 방장의 행은 남김, 탈퇴자 행 `leave()`
- [ ] 멤버가 없는 코스는 지금처럼(탈퇴자 소유로 남거나 기존 정책대로)
- [ ] **방장이 아닌 멤버가 탈퇴하면** 참여 중인 코스 전부에서 `leave()` + 메모 soft delete(3-3). 계획서가 원래 빠뜨렸던 경로
- [ ] 탈퇴자가 만든 활성 초대 토큰은 **그대로 둔다**(코스의 것이지 사람의 것이 아니다)
- [ ] **탈퇴자 방어** — `MemberReader.getMember`는 `deletedAt != null`이면 `MEMBER_DELETED`를 던진다(`MemberReader:23~25`). 멤버 목록·메모 작성자 조회에 이걸 쓰면 **탈퇴자 한 명 때문에 응답 전체가 실패**한다. 조회 경로는 `Member`를 조인으로 읽고 탈퇴자는 건너뛴다

### 4-4. 검증

- [ ] 팔로워가 있는 코스에서 가게 삭제 → 팔로워 완주 상태 변화 없음 · 위 5단계 시나리오
- [ ] 멤버 계정으로 포크·따라가기 성공 · 제3자는 여전히 403
- [ ] 방장 탈퇴 → 가장 오래된 멤버가 방장, 그 사람 `course_members` 행 유지 · 멤버 탈퇴 → 행·메모 닫힘
- [ ] 멤버 중 한 명을 탈퇴시켜도 상세 조회 성공

### FE에서 켜지는 것

멤버의 따라가기·포크. FE 문서 Phase 6.

---

## Phase 5 — 응답 확장 (`feat/course-collab-response`)

`CourseQueryService`. FE가 권한 게이트(`canEdit`)와 '함께' 배지를 켜는 데 필요한 것.

### 5-1. 상세 `GET /courses/{courseId}`

- [ ] 비공개 차단(`:62`)에 **멤버 예외**를 더한다(4-2와 같은 판정)
- [ ] `CourseDetailResponse`(record)에 추가
  ```json
  {
    "...기존 필드 그대로 (isPublic, isMine 유지)...",
    "isOwner": true,                // = isMine. 이름을 하나 더 두는 이유: FE에서 "방장 전용"과 "편집 가능"을 갈라 읽게
    "canEdit": true,                // 방장 또는 활성 멤버. CURATED는 항상 false
    "isMember": false,              // 방장 아닌 활성 멤버
    "memberCount": 3,               // 방장 포함 활성 멤버 수. 협업 아니면 0 (방장 행도 없다)
    "members": [                    // joined_at ASC. 협업 아니면 []
      { "memberId": 1, "displayName": "다나", "isOwner": true,  "isMe": true  },
      { "memberId": 5, "displayName": "지수", "isOwner": false, "isMe": false }
    ],
    "myDisplayName": "다나",        // 내 course_members 행. 없으면 null
    "dayCount": 3,                  // courses.day_count (7-5)
    "visibility": "PRIVATE",        // 5-B 전엔 is_public에서 파생해 내려준다
    "shareToken": null              // 5-B. 방장에게만, UNLISTED일 때만
  }
  ```
  `isMine`은 **없애지 않는다**(FE 38곳이 읽는다). `courseStores[]`는 Phase 3의 `memos`·`myMemo` 포함
- [ ] `isMine` 계산(`:76`)은 소유자 그대로. `canEdit = isOwner || isMember`

### 5-2. 목록 `GET /courses/my`

- [ ] 지금은 `findAllByMemberIdAndTypeInAndDeletedAtIsNull`(소유 기준, `:52`). **참여 코스를 union**한다 — `courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull` → 코스 id → `CourseRepository.findAllByIdInAndDeletedAtIsNull`
- [ ] **dedup은 필수다.** 방장도 `course_members` 행을 가지므로 협업 중인 내 코스는 소유·참여 양쪽에 다 잡힌다. `buildSummaryResponses`에 넘기기 전에 courseId 기준 중복 제거
- [ ] 정렬: 소유·참여 **섞어서 최근 수정순**(`updated_at DESC`, 확정)
- [ ] `CourseSummaryResponse`(record)에 `isMember`(방장 아닌 활성 멤버 → '함께' 배지) 추가. `CourseSummaryResponse.of(...)`는 `getMyCourses`·`getSavedCourses`·공개 목록이 **공유**하므로 나머지 호출부는 `false`를 넘긴다
- [ ] N+1: `buildSummaryResponses`(97~135)는 가게·커버·찜수·완주수·썸네일을 `IN`으로 배치 조회해 **안전**하다(실측). union이 `List<Course>`만 만들어 넘기면 된다. 단 `getSavedCourses`(`:50~53`)는 `findAllByMemberId`에 fetch join이 없어 **이미 N+1**이다 — 이번 범위는 아니지만 이 메서드를 건드릴 거면 같이 고칠 것

### 5-3. 찜 목록 `GET /courses/saved`

- [ ] 참여 시 찜을 해제하므로 중복은 앞으로 안 생기지만 **기존 데이터**에 남아 있을 수 있다. 참여 코스는 찜 목록에서 뺀다(응답에서 필터)

### 5-4. 검증

- [ ] 초대받은 계정의 `/courses/my`에 그 코스가 `isMember: true`로 한 번만 뜸(방장 계정에는 소유로 한 번만)
- [ ] 비공개 협업 코스를 멤버가 상세 조회 성공, `canEdit: true`, `members` 정렬이 참여 순 · 제3자는 403
- [ ] 큐레이션 코스는 `canEdit: false`

### FE에서 켜지는 것

권한 게이트(`canEdit`), 참여자 칩, '함께' 배지, 서버 `dayCount`. FE 문서 Phase 6·7.

---

## Phase 5-B — 링크 공개 UNLISTED (`feat/course-visibility`)

**왜 이번에 같이 하나**: "협업 코스는 무조건 비공개"는 링크로 남에게 보여줄 방법이 없어서 나온 궁여지책이다. 링크 공개가 생기면 비공개로 강등하지 않고 링크 공개로 내리면 되므로 그 규칙이 자연스러워진다. 초대 토큰 유틸(Phase 2)을 그대로 재사용한다. **순서는 Phase 5 직후로 확정.**

### 5-B-1. 왜 `share_token`이 필수인가

`Course.id`는 `IDENTITY` 순차 정수다(`Course.java:31~33`). 공유 URL이 `/c/12`인 이상 1부터 세면 전부 열린다. "링크 아는 사람만"은 URL이 **추측 불가능**해야 성립한다.

| | `course_invites.token` | `courses.share_token` |
|---|---|---|
| 수락하면 | **편집자가 된다** | 아무것도 안 된다(보기만) |
| 개수 | 코스당 1개, 7일 만료, 재생성 시 이전 무효 | 코스당 1개, 만료 없음 |
| URL | `/i/{token}` | `/c/{shareToken}` |
| 유출되면 | 남이 내 코스를 편집 | 남이 내 코스를 본다 |

한 컬럼으로 합치자는 유혹이 있는데 **유출 시 피해 등급이 다르다.** 분리한다. 컬럼은 Phase 2에서 이미 만들어져 있다.

### 5-B-2. 엔티티·판정

- [ ] `Course`에 `Visibility` enum(`PUBLIC`/`UNLISTED`/`PRIVATE`) 필드 + `shareToken`. `updatePublic(boolean)`(`:145`)을 `updateVisibility(Visibility)`로 교체하되 **`is_public`도 같이 써준다**(한 릴리즈 동안 구버전 앱이 읽는다)
- [ ] `share_token`은 **UNLISTED로 처음 바꿀 때** 발급(`CollabTokenGenerator`). 전 코스 선발급 금지. **만료 없음, PUBLIC·PRIVATE로 돌아가도 보존**(왕복 시 기존 공유 링크가 살아 있어야). 재발급 UI는 V1 없음
- [ ] **`isPublic` 판정 7곳을 전부 다시 정한다** — 한 곳도 빠짐없이

  | 위치 | 지금 | UNLISTED일 때 |
  |---|---|---|
  | `CourseRepository:28` | 공개 목록 쿼리 | **제외**(목록에 안 뜨는 게 본질) |
  | `CourseQueryService:52` | 찜 목록 필터 | **제외** — 찜은 PUBLIC 전용 |
  | `CourseQueryService:61` | 상세 차단 | **`share_token` 제시하면 허용** |
  | `SavedCourseCommandService:38` | 찜 가능 | **거부** — 링크 공개는 공유용이지 보관용이 아니다. 찜 개수가 공개 랭킹(`CourseRepository:28`)에 쓰여 숨은 코스에서 쌓이면 안 된다 |
  | `CourseProgressService:205` | 따라가기 | **제3자 거부**, 소유자·멤버 허용 — 진행률이 원본에 매달려 방장이 PRIVATE으로 내리면 깨진다 |
  | `CourseCommandService:99` | FORKED 공개 전환 시 원본 동일 검사 | PUBLIC일 때만(지금과 동일) |
  | `CourseCommandService:317` | 포크 가능 | **허용** — 조회 가능하면 포크 가능 |

  > **포크와 따라가기가 갈린다.** 포크는 복제라 원본이 사라져도 안 깨진다. 따라가기는 원본에 매달린다. 한 함수로 묶지 말 것. 두 경우 모두 **소유자·활성 멤버는 visibility와 무관하게 허용**(4-2) — 빠뜨리면 자기 비공개 협업 코스를 자기가 포크 못 한다.
  > `Course.createForked`(`:136`)가 `isPublic(false)`로 만들어 UNLISTED 코스를 포크해도 복제본은 PRIVATE에서 시작한다(확인함). 포크한 사람이 고친 뒤 PUBLIC으로 올리는 건 막지 않는다 — 링크를 받은 시점에 이미 볼 수 있는 내용이다

- [ ] 판정 헬퍼 `course.isViewableBy(memberId, shareToken)` 하나로 모은다. 범위를 넘는 리팩터는 말고 헬퍼만

### 5-B-3. API 계약

- [ ] `PATCH /courses/{courseId}` — `visibility` 수용(`isPublic`도 계속 받는다: `true → PUBLIC`, `false → PRIVATE`). **UNLISTED로 처음 바꿀 때 `share_token` 발급**하고 응답에 내려준다. 방장 전용(멤버는 403). 협업 코스는 **PUBLIC만 거부**(`COURSE_COLLAB_CANNOT_BE_PUBLIC`), UNLISTED·PRIVATE 허용
  ```json
  // 요청
  { "visibility": "UNLISTED" }
  // 응답 200
  { "visibility": "UNLISTED", "shareToken": "Qm7...43자" }
  ```
- [ ] `GET /courses/{courseId}?t={shareToken}` — 판정 순서를 **이 순서로**
  ```
  PRIVATE   → 소유자·멤버만. 토큰이 맞아도 403   ← 토큰보다 PRIVATE이 강하다
  UNLISTED  → 소유자·멤버 이거나, t가 share_token과 일치하면 허용
  PUBLIC    → 누구나
  ```
  토큰 비교는 **상수 시간**(`MessageDigest.isEqual`) — `String.equals`는 앞자리부터 끊겨 길이·접두사가 샌다. `SecurityConfig`의 기존 매처가 쿼리스트링을 이미 허용하므로 **추가 작업 없음**(확인함)
- [ ] `GET /courses/by-share/{shareToken}` 신설 — 랜딩이 `/c/{shareToken}`으로 받으면 **코스 id를 모른다.** 토큰만으로 찾는 입구
  ```java
  @GetMapping("/by-share/{shareToken}")
  public ResponseEntity<ApiResponse<CourseDetailResponse>> getByShareToken(@PathVariable String shareToken)
  ```
  - 응답 DTO는 `getCourseDetail`과 **같은 것**(랜딩이 이미 그 모양을 파싱한다)
  - 서비스: 토큰으로 조회 → 없으면 404 → `visibility != UNLISTED`면 **404**(403이 아니라 — "토큰은 맞는데 지금은 못 본다"를 알려주면 토큰 유효성이 샌다) → UNLISTED면 상세
  - `SecurityConfig` permitAll 추가(랜딩은 사용자 토큰이 없다). 좁게:
    ```java
    RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/by-share/[A-Za-z0-9_-]+(\\?.*)?$")
    ```
- [ ] 상세 응답의 `shareToken`은 **방장에게만, UNLISTED일 때만**(5-1)

### 5-B-4. 검증

- [ ] UNLISTED 코스가 공개 목록·검색·찜 목록에 안 뜬다 · 토큰 없이 id로 비로그인 접근 403 · `?t=` 붙이면 비로그인 조회 성공
- [ ] PRIVATE + 맞는 토큰 → 403. `by-share`로 PRIVATE 코스 → 404
- [ ] PUBLIC → UNLISTED → PUBLIC 왕복 시 `share_token` 유지
- [ ] 협업 코스 PUBLIC 전환 400, UNLISTED 전환 200
- [ ] UNLISTED 코스 찜 400, 따라가기 403(제3자), 포크 200

### FE·랜딩에서 켜지는 것

공유 시트의 "링크 아는 사람만" 전환, 랜딩 `/c/{shareToken}`·noindex. FE 문서 Phase 5-B.

---

## 참고: BE 파일 지도

| 파일 | 이번 작업에서 |
|---|---|
| `course/service/CourseCommandService.java` | `validateOwner` 9곳 → `validateCanEdit` 5곳 + owner 4곳, 락 3곳, `forkCourse` 멤버 허용·메모 제외, `isSameAsSource` memo 제외 |
| `course/service/CourseMemberService.java` (신규) | 초대 생성·미리보기·수락·나가기·이름 변경·`transferOwnership` |
| `course/service/CourseQueryService.java` | 상세 멤버 예외, `canEdit`·`members`, `getMyCourses` union·dedup, 찜 필터 |
| `course/service/CourseProgressService.java` | `refreshForCourse(allowPromotion)`, `getAccessibleCourse` 멤버 허용 |
| `course/entity/Course.java` | `dayCount`·`visibility`·`shareToken`, `updateVisibility` |
| `course/entity/CourseMember(Id)`·`CourseInvite`·`CourseStoreMemo(Id)` (신규) | |
| `course/repository/*` | 락 조회, 신규 리포지토리 3개, `moveOwner` 3개 |
| `course/code/CourseErrorCode.java` | 7개 추가 |
| `course/dto/request/CourseStoreUpdateRequest`·`CourseStoreAddRequest` | `@Size(max = 200)` |
| `course/dto/response/CourseDetailResponse`·`CourseStoreResponse`·`CourseSummaryResponse` | 필드 추가 (기존 필드 유지) |
| `member/service/MemberCommandService.java` | `deleteMember` 협업 정리 |
| `member/service/GuestDataMigrationService.java` | `moveOwner` 3개 추가 |
| `config/security/SecurityConfig.java` | permitAll 2개(초대 미리보기, by-share) |
| `src/main/resources/db/migration/` | 파일 4개(Phase 2에 3, Phase 3에 1) |

## 참고: 건드리지 않는 것

`alarm` 패키지 전부(알림은 V1 범위 밖) · `AdminCourseCommandService`의 memo 경로(큐레이션은 `course_stores.memo` 그대로) · `course_store_visits`(이미 `member_id`가 있어 멤버별로 분리된다) · `course_progress` 스키마 · 웹소켓·Redis·낙관적 락(`@Version`) — 왜 안 넣는지는 총괄 문서 「절대 하지 말 것」
