# feat/course-collab-members — 빵투어 동시편집 Phase 2 (멤버·초대 기반)

작성일 2026-09-03 · 기준 문서: `docs/be-tasks-course-collab.md` Phase 2 · ERD: `docs/course-collab-phases.md` 「ERD」(9/3 검토 완료)
출발선: dev `6728a87a`(baseline) — `./gradlew test` 782개 중 실패 7개(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1). **PR 전 기준은 "실패가 이 7개에서 늘지 않았는가".**

## 1. Spec (문서 2-1 ~ 2-5 그대로)

- 테이블 2개(`course_members` · `course_invites`) + `courses` 컬럼 4개(`day_count` · `visibility` · `share_token` · `version`, 9/3 저녁 승인) — ERD 표 그대로, 마이그레이션 3파일
- 편집 권한 `validateOwner` 9곳 → `validateCanEdit` 5곳(`updateCourse`·`addStore`·`updateStore`·`removeStore`·`reorderStores`) + `validateOwner` 4곳 유지(`deleteCourse`·커버 3개). **CURATED 검사가 소유자 판정보다 먼저**
- 비관적 락 5곳: `addStore` · `removeStore` · `reorderStores` · `createInvite` · `acceptInvite`
- API 5개: `POST /courses/{id}/invites` · `GET /courses/invites/{token}`(비로그인 허용) · `POST /courses/invites/{token}/accept` · `DELETE /courses/{id}/members/me` · `PATCH /courses/{id}/members/me`
- `PATCH /courses/{id}`: 협업 중이면 `isPublic=true` 거부, `dayCount`는 늘리기만
- `courses.version`: 쓰기 경로 전부에서 `bumpVersion` +1 (폴링 ETag. 상세 GET 의 ETag/304 는 Phase 5)
- 순서 CAS: `CourseStoreReorderRequest.expectedOrder`(optional) — 락 안에서 현재 순서와 다르면 `COURSE_STALE`(409)
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
| 11 | `Course.version` 매핑 | `insertable=false, updatable=false` 읽기 전용 + 네이티브 `bumpVersion` | Hibernate 는 UPDATE 에 전 컬럼을 쓰므로 `bumpVersion` 뒤 엔티티 flush 가 메모리의 낡은 값으로 되돌릴 수 있다. `@Version` 아님(문서). H2 DDL 은 `columnDefinition` 으로 DEFAULT 0 |
| 12 | `createInvite` 의 `bumpVersion` | 방장 행이 **새로 생길 때만** | 문서 목록엔 없지만 참여자 목록이 바뀌는 쓰기다. 링크 재사용만 하는 호출은 변경이 없어 안 올린다 |
| 13 | `dayCount` 락 | `updateCourse` 는 일반 조회로 시작하고 `dayCount` 가 올 때만 `findWithLock…` 으로 다시 잡는다 | 기존 `updateCourse` 테스트(락 미사용)를 그대로 두라는 문서 ②③ 지시와 "락 안에서" 요구를 둘 다 만족 |

## 3. 커밋 계획 (문서 2-3 ①~⑥ 순서 준수)

- [x] A `✨ feat` 마이그레이션 3 · 엔티티(`CourseMember(Id)`·`CourseInvite`·`CourseVisibility`) · 리포지토리 2 · `Course` 컬럼 · `CollabTokenGenerator` · 리포지토리/엔티티 테스트
- [x] B `♻️ refactor` ①② `findWithLockByIdAndDeletedAtIsNull` + `addStore`·`removeStore`·`reorderStores` 교체 — 깨지는 테스트 **10개**(문서 추정 9 + `reorderStores_otherCourseStore`) 확인 후 커밋
- [x] C `✅ test` ③ 그 10개 스텁만 치환 → 실패 7개 복귀
- [x] D `✨ feat` ④⑤ `validateCanEdit` 신설·5곳 교체 + 큐레이션 방어 테스트 5개 + 멤버 편집 4개
- [x] E `✨ feat` ⑥ 에러 코드 7 · `CourseMemberService` · DTO · 컨트롤러 5 · `SecurityConfig` · 테스트(서비스 35 · 컨트롤러 8)
- [x] V `✨ feat` `courses.version` + `bumpVersion` 12곳 + 경로별 1회 호출 테스트 (9/3 저녁 지시서 갱신 반영)
- [x] R `✨ feat` 순서 CAS `expectedOrder` → `COURSE_STALE` 409, 테스트 4 + 컨트롤러 409 (9/3 저녁)
- [x] F `✨ feat` `updateCourse` 협업 공개 차단 · `dayCount` · `addStore`/`reorderStores` 일차 수 동기화
- [x] G `✨ feat` 게스트 이관 `deleteGuestDuplicates` + `moveOwner` 2
- [x] 최종: `./gradlew test` 866개 중 실패 7개 = baseline 과 동일(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1). spotless·checkstyle 은 커밋마다 훅으로 통과

## 4. 검증 체크리스트 (문서 2-5)

- [ ] 멤버로 가게 추가·수정·삭제·순서 변경 성공 / 코스 삭제·공개 전환·커버 변경 403
- [ ] 큐레이션 코스에 멤버 행이 있어도 편집 403 (테스트 5개)
- [ ] 초대 생성: 방장 행 생성·닉네임, 재호출 `reused: true`, 1일 미만이면 재발급 + 이전 soft delete
- [ ] 수락: 만료·무효·정원 거부, 방장·기존 멤버 200, 찜 해제, PUBLIC → PRIVATE
- [ ] 미리보기 비로그인 200, `accept` 비로그인 401
- [ ] 게스트 방장 이관 뒤 `course_members`·`course_invites`가 새 id
- [x] 실패 목록 7개 유지 (866개 중 7개)
- 위 나머지 항목은 실제 DB·동시성·curl 검증이라 로컬 서버 없이 못 했다 — PR 전 수동 확인 필요
