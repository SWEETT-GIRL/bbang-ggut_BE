# feat/course-store-memos — 빵투어 동시편집 Phase 3 (메모 사람별 분리)

작성일 2026-09-03 · 기준 문서: `docs/be-tasks-course-collab.md` Phase 3 · ERD: `docs/course-collab-phases.md` 「ERD」 표 3
출발선: dev `c197e958`(Phase 2 PR #638 머지) — 실패 7개(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1). **늘지 않으면 OK.**

## 1. Spec (문서 3-1 ~ 3-5)

- `course_store_memos (course_store_id, member_id) PK`, `content VARCHAR(200)` — 키는 **정류장 행**. 가게를 빼면 전원 메모 soft delete, 다시 담아도 안 돌아온다
- 이관: CUSTOM·FORKED 의 `course_stores.memo` 를 방장 메모로 복사. CURATED 는 그대로(어드민 CLI 가 쓴다). 컬럼은 안 지운다
- 쓰기: `updateStore.memo` = 내 메모 upsert(빈 값 = 삭제) · `addStore.memo` = 내 메모 insert · `removeStore` → 정류장 메모 전원 soft delete · `leave` → 나간 사람 메모 soft delete · `forkCourse` 메모 복사 제외 · `isSameAsSource` memo 비교 제외 · `@Size(max=200)`
- 읽기: `CourseStoreResponse` 에 `myMemo` · `memos[]`(참여 순서, 내 것 포함, displayName 은 `course_members` → 없으면 닉네임) 추가. 기존 `memo` 유지 = myMemo (CURATED 는 `course_stores.memo`)
- 게스트 이관 `moveOwner`

## 2. 문서에 없어서 정한 것 (리뷰 포인트)

| # | 항목 | 결정 | 이유 |
|---|---|---|---|
| 1 | `memo` 필드 값 | CURATED 는 `course_stores.memo`, 그 외는 `myMemo` | 큐레이션은 새 테이블에 행이 없다. 사용자 코스에서 legacy 컬럼을 폴백으로 쓰면 멤버가 방장 메모를 자기 것처럼 본다 |
| 2 | `addStore` 응답 | 방금 저장한 내 메모를 `memo`·`myMemo`·`memos[0]` 에 채워 준다 | 정류장 생성 직후라 조회 없이 만들 수 있다 |
| 3 | 메모 작성자 이름 | `findAllByIdCourseStoreIdIn…` 에 `@EntityGraph(member)` | 행 없는 사람(옛 데이터)의 닉네임 폴백을 N+1 없이. `MemberReader.getMember` 금지(탈퇴자 예외) |
| 4 | `memos` 정렬 | `course_members.joined_at` 순, 행 없는 사람은 뒤에 memberId 순 | 문서 "서버가 정렬" |
| 5 | 게스트 이관 | `deleteGuestDuplicates` 먼저 | PK `(course_store_id, member_id)` 충돌 방어 — `course_members` 와 같은 관례 |
| 6 | 방장 나가기(승계) 때 메모 | 멤버와 같이 soft delete | 문서 "탈퇴한 멤버·방장도 같은 규칙" |
| 7 | 이관 전 200자 초과 개수 | **미확인** — 운영 DB 접속 필요 | PR 전 `SELECT COUNT(*) FROM course_stores WHERE length(memo) > 200` 실행해 0 이 아니면 알린다 |
| 8 | H2 리포지토리 테스트 | `store`·`course_stores`·`course_store_memos` 세 테이블을 테스트가 직접 만든다 | H2 에는 `Store.location`(geography) DDL 이 실패해 store 와 그것을 참조하는 테이블이 없다. Store 는 `getReference` 로 FK 만 채운다 |
| 9 | 새 메모 행 저장 | `repository.save` — `@EmbeddedId` 라 Spring Data 가 merge 로 넣는다 | `CourseMember`·`CourseStoreVisit` 과 같은 관례. addStore 응답의 `updatedAt` 은 `now()` |

## 3. 커밋 계획

- [x] A `✨ feat` 마이그레이션 · `CourseStoreMemo(Id)` · `CourseStoreMemoRepository` · 테스트 (`13b2d4b0`)
- [x] C `✨ feat` 읽기 경로(`CourseStoreResponse` memos·myMemo, `getCourseDetail` 한 방 조회) + 테스트 (`983d51cd`) — addStore 응답이 새 DTO 모양을 쓰므로 B 보다 먼저
- [x] B `✨ feat` 쓰기 경로(updateStore·addStore·removeStore·leave·fork·isSameAsSource·@Size) + 테스트 (`74118038`)
- [x] D `✨ feat` 게스트 이관 `deleteGuestDuplicates` + `moveOwner` (`38a66f80`)
- [x] 최종 `./gradlew test` 880개 중 실패 7개 = baseline 과 동일(`ReviewRepositoryTest` 4 · `TrendQueryServiceTest` 2 · `KakaoAuthServiceTest` 1)

## 4. 검증 (문서 3-5)

- [x] 두 계정 각각 메모 → 서로 안 덮어씀, `memos` 2개, 각자 `myMemo` 자기 것 (`CourseQueryServiceTest` 메모 3개 · `CourseCommandServiceTest` upsert 3개)
- [ ] 기존 코스 메모가 주인 메모로 보이고 `memo` 로도 내려온다 — 마이그레이션 SQL 은 문서 그대로, 실제 DB 로는 미검증
- [x] 큐레이션 코스는 `course_stores.memo` 그대로 (`getCourseDetail_curated_keepsLegacyMemo`)
- [x] 가게를 빼면 정류장 메모 전원 삭제 (`CourseStoreMemoRepositoryTest` · `removeStore_success`). 재추가 시 새 정류장 행이라 안 돌아옴
- [x] 포크본 메모 없음 · 동일 FORKED 공개 시 `COURSE_FORK_NOT_MODIFIED` 유지
- [x] 201자 메모 400 (`storeMemo_over200Chars_returns400`)
