# feat/course-collab-members — 빵투어 동시편집 Phase 2 (멤버·초대 기반)

작성일 2026-09-03 · 기준 문서: `docs/be-tasks-course-collab.md` Phase 2 · ERD: `docs/course-collab-phases.md` 「ERD」(9/3 검토 완료)
출발선: dev `6728a87a`(baseline) — `./gradlew test` 782개 중 실패 7개(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1). **PR 전 기준은 "실패가 이 7개에서 늘지 않았는가".**

## 1. Spec (문서 2-1 ~ 2-5 그대로)

- 테이블 3개(`course_members` · `course_invites`) + `courses` 컬럼 3개(`day_count` · `visibility` · `share_token`) — ERD 표 그대로, 마이그레이션 3파일
- 편집 권한 `validateOwner` 9곳 → `validateCanEdit` 5곳(`updateCourse`·`addStore`·`updateStore`·`removeStore`·`reorderStores`) + `validateOwner` 4곳 유지(`deleteCourse`·커버 3개). **CURATED 검사가 소유자 판정보다 먼저**
- 비관적 락 5곳: `addStore` · `removeStore` · `reorderStores` · `createInvite` · `acceptInvite`
- API 5개: `POST /courses/{id}/invites` · `GET /courses/invites/{token}`(비로그인 허용) · `POST /courses/invites/{token}/accept` · `DELETE /courses/{id}/members/me` · `PATCH /courses/{id}/members/me`
- `PATCH /courses/{id}`: 협업 중이면 `isPublic=true` 거부, `dayCount`는 늘리기만
- 게스트 이관에 `course_members` · `course_invites` `moveOwner`

## 2. 문서에 없어서 정한 것 (리뷰 포인트)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| 1 | 게스트의 수락 차단 응답 | `COURSE_FORBIDDEN`(403) | FE `apiClient`가 401을 받으면 게스트 세션을 자동 재발급한다(FE 문서 0절) — 401이면 루프. 정상 경로는 FE가 `useLoginGate`로 먼저 막는다 |
| 2 | 방장 `display_name` 기본값 | 닉네임 앞 10자 | 닉네임 컬럼은 `VARCHAR(100)`, `display_name`은 `VARCHAR(10)` |
| 3 | `courses.visibility` 매핑 시점 | Phase 2에서 엔티티에 매핑하고 `updatePublic`이 같이 쓴다 | 문서 "5-B 전까지 쓰기는 `is_public`과 동기화". 매핑 안 하면 새 코스가 전부 DEFAULT `PRIVATE`으로 어긋난다. 판정에는 안 쓴다 |
| 4 | `courses.share_token` 매핑 | 5-B까지 미매핑 | nullable 이라 해 없음 |
| 5 | `day_count` 동기화 | `addStore`·`reorderStores`가 `max(day)`보다 작으면 올려준다(줄이진 않는다) | 마이그레이션이 세운 불변식 `day_count ≥ max(day)` 유지. 두 메서드는 이미 락을 잡는다 |
| 6 | 복합키 테이블 `moveOwner` | 네이티브 UPDATE | `CourseProgressRepository` 선례(JPQL로 식별자 컬럼 갱신 회피) |
| 7 | `course_members` 게스트 이관 | `deleteGuestDuplicates` 먼저 | PK `(course_id, member_id)` 충돌 방어 — `saved_courses`와 같은 관례 |
| 8 | `CourseInvite` 타임스탬프 | `BaseTimeEntity` 상속 안 함, `@CreatedDate`만 | ERD에 `updated_at`이 없다 |
| 9 | 초대 생성 시 이전 토큰 정리 | 만료됐든 아니든 `deleted_at IS NULL`인 행은 전부 `revoke()` | "코스당 살아있는 링크 1개" 조회를 단순하게 |
| 10 | `transferOwnership(course, from)` | 승계 + `from` 행 `leave()`까지 한 함수 | Phase 4 탈퇴와 "같은 함수"로 쓰려면 두 호출부가 같은 후처리를 반복하지 않게 |

## 3. 커밋 계획 (문서 2-3 ①~⑥ 순서 준수)

- [x] A `✨ feat` 마이그레이션 3 · 엔티티(`CourseMember(Id)`·`CourseInvite`·`CourseVisibility`) · 리포지토리 2 · `Course` 컬럼 · `CollabTokenGenerator` · 리포지토리/엔티티 테스트
- [ ] B `♻️ refactor` ①② `findWithLockByIdAndDeletedAtIsNull` + `addStore`·`removeStore`·`reorderStores` 교체 — 깨지는 테스트 9개 확인 후 커밋
- [ ] C `✅ test` ③ 그 9개 스텁만 치환 → 실패 7개 복귀
- [ ] D `✨ feat` ④⑤ `validateCanEdit` 신설·5곳 교체 + 큐레이션 방어 테스트 5개
- [ ] E `✨ feat` ⑥ 에러 코드 7 · `CourseMemberService` · DTO · 컨트롤러 5 · `SecurityConfig` · 테스트
- [ ] F `✨ feat` `updateCourse` 협업 공개 차단 · `dayCount`
- [ ] G `✨ feat` 게스트 이관 `moveOwner`
- [ ] 최종: `./gradlew spotlessApply && ./gradlew build && ./gradlew test` → 실패 7개

## 4. 검증 체크리스트 (문서 2-5)

- [ ] 멤버로 가게 추가·수정·삭제·순서 변경 성공 / 코스 삭제·공개 전환·커버 변경 403
- [ ] 큐레이션 코스에 멤버 행이 있어도 편집 403 (테스트 5개)
- [ ] 초대 생성: 방장 행 생성·닉네임, 재호출 `reused: true`, 1일 미만이면 재발급 + 이전 soft delete
- [ ] 수락: 만료·무효·정원 거부, 방장·기존 멤버 200, 찜 해제, PUBLIC → PRIVATE
- [ ] 미리보기 비로그인 200, `accept` 비로그인 401
- [ ] 게스트 방장 이관 뒤 `course_members`·`course_invites`가 새 id
- [ ] 실패 목록 7개 유지
