# 빵지순례 코스 CRUD — 구현 TODO

브랜치: `feat/461-BG-157-빵지순례-코스-CRUD`  
PRD: `docs/prd-course.md`

> **원칙**: Service 구현 직후 테스트 작성 — 몰아서 작성 금지 (CLAUDE.md)

---

## 커밋 메시지 규칙

형식: `emoji type: 한글 설명` — phase 번호 삽입 금지, 변경 성격별 작은 단위로 커밋

| 성격 | 예시 |
|------|------|
| Flyway 마이그레이션 추가 | `✨ feat: course_xxx 테이블 추가` |
| 엔티티·enum 추가 | `✨ feat: CourseXxx 엔티티 추가` |
| 저장소 추가 | `✨ feat: CourseXxx 저장소 추가` |
| 서비스 추가 | `✨ feat: 코스 조회 서비스 추가` |
| DTO 추가 | `✨ feat: 코스 응답 DTO 추가` |
| 컨트롤러 추가 | `✨ feat: 코스 컨트롤러 추가` |
| 테스트 추가 | `✅ test: 코스 조회 서비스 테스트 추가` |
| TODO·문서 갱신 | `📝 docs: 코스 TODO 진행 항목 갱신` |

---

## Flyway 주의사항

- **이미 dev 또는 prod에 적용된 migration 파일은 수정하지 않는다.**
- 스키마 변경은 항상 **새 migration 파일**로 추가한다.
- dev에 push하기 전 local에서 migration이 처음부터 정상 적용되는지 확인한다.
- dev → prod 이전에 prod 적용 버전과 충돌하지 않는지 확인한다.
- dev 전용 임시 migration을 만들지 않는다. prod까지 갈 수 있는 migration만 작성한다.
- 테이블·컬럼 삭제, rename, NOT NULL 추가는 기존 데이터 영향 확인 후 별도 phase에서 처리한다.
- backfill이 필요한 경우: **컬럼 추가 → 데이터 채움 → 제약조건 추가** 순서로 나눈다.

**파일명 규칙:** `V{YYYYMMDDHHMMSS}__{snake_case_description}.sql`  
예: `V20260623120000__add_course_xxx.sql`  
기존 파일 참고: `V20260622000000__align_course_saved_schema.sql`

---

## 전체 흐름 & API별 동작 정의

### 전체 플로우

```
[코스 생성]
FAB 탭 → 제목·공개여부 입력 + 커버 이미지(선택) → POST /courses → courseId 반환
→ (이미지 선택 시) POST /courses/{id}/images/presigned-url → S3 업로드 → POST /courses/{id}/images
→ 코스 편집 화면 (auto-save 시작)
  ├── 가게 추가       → POST   /courses/{id}/stores
  ├── 가게 제거       → DELETE /courses/{id}/stores/{storeId}
  ├── 메모·방문시간   → PATCH  /courses/{id}/stores/{storeId}
  ├── 순서 변경       → PATCH  /courses/{id}/stores/reorder  (드롭 완료 시)
  ├── 제목·공개여부   → PATCH  /courses/{id}
  └── 커버 이미지     → POST   /courses/{id}/images/presigned-url → S3 업로드 → POST /courses/{id}/images

[큐레이션 포크]
코스 상세 → [나만의 코스로 편집] → POST /courses/{id}/fork → 새 courseId
→ 새 코스 제목 = "copy of {원본제목}"
→ 위 코스 편집 화면으로 이동

[코스 찜]
코스 상세 → 찜 버튼 토글
  찜 추가 → POST   /courses/{id}/save
  찜 해제 → DELETE /courses/{id}/save

[가게 찜]
빵집 상세 → 찜하기 버튼 토글
  찜 추가 → POST   /stores/{storeId}/save
  찜 해제 → DELETE /stores/{storeId}/save
코스 편집 "가게 추가" → 찜한가게 탭 → GET /stores/saved
```

---

### 응답 DTO 필드

| DTO | 필드 |
|-----|------|
| `CourseSummaryResponse` | id, title, coverImageKey, storeCount, regions, courseType, authorName, badge(nullable·TBD) |
| `CourseDetailResponse` | CourseSummary 전체 + isSaved, savedCount, courseStores(`List<CourseStoreResponse>`) |
| `CourseStoreResponse` | visitOrder, storeId, storeName, latitude, longitude, region, memo(nullable), visitTime(nullable), walkingMinutesToNext(nullable) |
| `SavedStoreResponse` | storeId, storeName, region, latitude, longitude |

> `coverImageKey` = 코스에 등록된 CourseImageFile의 key (코스당 1개, 없으면 null)  
> `regions` = courseStores의 Store.region2nd 목록  
> `courseStores` = deletedAt IS NULL인 CourseStore, visitOrder ASC

---

### API 목록

| 메서드·경로 | 인증 | 주요 조건·동작 | 응답 `data` | 에러 |
|------------|:----:|--------------|------------|------|
| `GET /courses` | - | CURATED + deletedAt IS NULL | `List<CourseSummaryResponse>` | |
| `GET /courses/my` | ✓ | 내 CUSTOM+FORKED, deletedAt IS NULL | `List<CourseSummaryResponse>` | |
| `GET /courses/saved` | ✓ | 내가 찜한 코스, deletedAt IS NULL | `List<CourseSummaryResponse>` | |
| `GET /courses/{courseId}` | 선택 | 비공개+타인 → 403, 삭제 → 404 | `CourseDetailResponse` | 403, 404 |
| `POST /courses` | ✓ | CUSTOM 생성, isPublic 지정 | `{ courseId }` | |
| `PATCH /courses/{courseId}` | ✓ | title·isPublic partial update | 없음 | 403(소유자아님·CURATED), 400(FORKED 미수정) |
| `DELETE /courses/{courseId}` | ✓ | soft delete | 없음 | 403 |
| `POST /courses/{courseId}/images/presigned-url` | ✓ | S3 경로 생성 | `{ url, awsKey }` | 403 |
| `POST /courses/{courseId}/images` | ✓ | CourseImageFile 저장 (코스당 1개) | 없음 | 403 |
| `DELETE /courses/{courseId}/images` | ✓ | CourseImageFile 삭제 | 없음 | 403, 404 |
| `POST /courses/{courseId}/stores` | ✓ | 하나씩 즉시 반영, visitOrder 자동, 중복 불가 | `CourseStoreResponse` | 403, 400(중복) |
| `PATCH /courses/{courseId}/stores/{storeId}` | ✓ | memo·visitTime partial update | 없음 | 403 |
| `DELETE /courses/{courseId}/stores/{storeId}` | ✓ | CourseStore soft delete | 없음 | 403 |
| `PATCH /courses/{courseId}/stores/reorder` | ✓ | courseStoreIds 순서 = 새 visitOrder | 없음 | 403, 404(id 없음) |
| `POST /courses/{courseId}/save` | ✓ | 찜 추가 | 없음 | 400(중복) |
| `DELETE /courses/{courseId}/save` | ✓ | 찜 해제 | 없음 | 404 |
| `POST /courses/{courseId}/fork` | ✓ | CURATED만 가능, FORKED 신규 생성 | `{ courseId }` | 400(CURATED아님) |
| `GET /stores/saved` | ✓ | 내 찜한가게 목록 | `List<SavedStoreResponse>` | |
| `POST /stores/{storeId}/save` | ✓ | 가게 찜, 중복 불가 | 없음 | 400(중복) |
| `DELETE /stores/{storeId}/save` | ✓ | 가게 찜 해제 | 없음 | 404 |

---

### 동작 상세 (주요 케이스)

**`GET /courses/{courseId}`**
- isSaved: 로그인 시 SavedCourse 조회, 비로그인 시 false
- savedCount: 전체 찜 수 (비로그인도 표시)

**`PATCH /courses/{courseId}`**
- CURATED 수정 시도 → 403 / CUSTOM·FORKED는 소유자면 수정 가능
- FORKED이고 원본과 동일하면 → 400 COURSE_FORK_NOT_MODIFIED  
  비교 기준: title(prefix 제외), stores(storeId·visitOrder·memo·visitTime)  
  FE 1차 방어(저장 버튼 비활성화) + BE 2차 안전망

**`POST /courses/{courseId}/fork`**
- 새 코스: type=FORKED, title="copy of {원본제목}", isPublic=false, sourceCourseId=source.id
- CourseStore 전부 복사 (memo·visitTime·visitOrder 유지), CourseImageFile 복사 안 함

---

## Phase 1 — 기반 코드

- [ ] Flyway: `course` 테이블에 `source_course_id` 컬럼 추가
  - `source_course_id BIGINT NULL REFERENCES course(id)`
  - CUSTOM/CURATED는 NULL, FORKED만 값 있음
  - 커밋: `✨ feat: course 테이블에 source_course_id 컬럼 추가`

- [ ] Flyway: `course_store` 테이블에 `walking_minutes_to_next` 컬럼 추가
  - `walking_minutes_to_next INTEGER NULL` — 다음 가게까지 도보 시간(분), FE가 계산해서 전송, 마지막 가게는 null
  - 커밋: `✨ feat: course_store 테이블에 walking_minutes_to_next 컬럼 추가`

- [ ] `CourseErrorCode.java` + `CourseException.java`
  - 커밋: `✨ feat: 코스 에러 코드 및 예외 추가`
  - 에러 코드: COURSE_NOT_FOUND, COURSE_STORE_NOT_FOUND, COURSE_FORBIDDEN, COURSE_STORE_ALREADY_EXISTS, COURSE_SAVED_ALREADY, COURSE_SAVED_NOT_FOUND, COURSE_FORK_NOT_CURATED, COURSE_FORK_NOT_MODIFIED

- [ ] `CourseRepository.java`
  - 커밋: `✨ feat: Course 저장소 추가`

- [ ] `CourseStoreRepository.java`
  - 커밋: `✨ feat: CourseStore 저장소 추가`

- [ ] `SavedCourseRepository.java`
  - 커밋: `✨ feat: SavedCourse 저장소 추가`

- [ ] `CourseImageFileRepository.java`
  - 커밋: `✨ feat: CourseImageFile 저장소 추가`

---

## Phase 2 — 조회 서비스

- [ ] 응답 DTO 추가
  - `CourseSummaryResponse.java` — id, title, coverImageKey, storeCount, regions, courseType, authorName, badge(nullable, TBD)
  - `CourseStoreResponse.java` — visitOrder, storeId, storeName, latitude, longitude, region, memo(nullable), visitTime(nullable), walkingMinutesToNext(nullable)
    - `region` = `Store.region2nd` (구 단위)
    - `menu` 필드 제외 (category 정보 미포함)
  - `CourseDetailResponse.java` — 위 + courseStores(List<CourseStoreResponse>), isSaved, savedCount, authorName, courseType, badge(nullable, TBD)
  - 커밋: `✨ feat: 코스 조회 응답 DTO 추가`

- [ ] `CourseQueryService.java`
  - `getCurationCourses()` — 큐레이션 코스 목록
  - `getMyCourses(memberId)` — 내 커스텀 코스 목록
  - `getSavedCourses(memberId)` — 내가 찜한 코스 목록
  - `getCourseDetail(courseId, memberId?)` — 공개/비공개 접근 제어, isSaved + savedCount 포함
  - 커밋: `✨ feat: 코스 조회 서비스 추가`

- [ ] `CourseQueryServiceTest.java`
  - 큐레이션 코스 목록 조회 성공
  - 내 코스 목록 조회 성공 (CUSTOM + FORKED 포함)
  - 내가 찜한 코스 목록 조회 성공
  - 공개 코스 비로그인 조회 성공
  - 비공개 코스 — 소유자 조회 성공
  - 비공개 코스 — 타인 조회 → COURSE_FORBIDDEN
  - 삭제된 코스 조회 → COURSE_NOT_FOUND
  - 커밋: `✅ test: 코스 조회 서비스 테스트 추가`

---

## Phase 3 — 코스 CRUD 서비스

- [ ] 요청 DTO 추가
  - `CourseCreateRequest.java` — title, isPublic (가게는 생성 후 개별 추가)
  - `CourseUpdateRequest.java` — title?, isPublic?
  - `CourseImageRegisterRequest.java` — awsKey, mimeType, name
  - 커밋: `✨ feat: 코스 생성·수정 요청 DTO 추가`

- [ ] `CourseCommandService.java` — 코스 생성·수정·삭제
  - `createCourse(memberId, request)`
  - `updateCourse(memberId, courseId, request)` — 소유자 검증
  - `deleteCourse(memberId, courseId)` — soft delete, 소유자 검증
  - 커밋: `✨ feat: 코스 생성·수정·삭제 서비스 추가`

- [ ] 코스 CRUD 테스트
  - 코스 생성 성공
  - 코스 수정 — 소유자 성공
  - 코스 수정 — 타인 → COURSE_FORBIDDEN
  - 코스 삭제 — soft delete 확인
  - 커밋: `✅ test: 코스 생성·수정·삭제 서비스 테스트 추가`

- [ ] 가게 관리 요청 DTO
  - `CourseStoreAddRequest.java` — storeId, memo?(nullable), visitTime?(nullable), walkingMinutesToNext?(nullable)
  - `CourseStoreUpdateRequest.java` — memo?, visitTime?
  - `CourseStoreReorderRequest.java` — `List<CourseStoreOrderItem>` (courseStoreId, walkingMinutesToNext?)
    - 배열 순서 = 새 visitOrder, FE가 reorder 후 계산한 도보 시간 함께 전송
  - 커밋: `✨ feat: 코스 가게 요청 DTO 추가`

- [ ] `CourseCommandService.java` — 가게 추가·수정·제거·순서변경 (위 클래스에 추가)
  - `addStore(memberId, courseId, request)` — 중복 검증, visitOrder 현재 최대값+1 자동 부여, memo/visitTime/walkingMinutesToNext nullable
  - `updateStore(memberId, courseId, storeId, request)` — memo, visitTime 수정 (즉시 반영)
  - `removeStore(memberId, courseId, storeId)` — soft delete (즉시 반영)
  - `reorderStores(memberId, courseId, request)` — 드롭 완료 시 전체 순서 + 도보 시간 일괄 갱신 (BannerReorderRequest 패턴 참고)
  - 커밋: `✨ feat: 코스 가게 추가·수정·제거·순서변경 서비스 추가`

- [ ] 가게 관리 테스트
  - 가게 추가 성공
  - 가게 추가 — 중복 → COURSE_STORE_ALREADY_EXISTS
  - 가게 수정 성공
  - 가게 제거 성공
  - 순서 변경 성공
  - 커밋: `✅ test: 코스 가게 서비스 테스트 추가`

- [ ] `CourseCommandService.java` — 이미지 업로드·삭제 (위 클래스에 추가)
  - `getPresignedUrl(memberId, courseId, mimeType)` — S3Service 활용
  - `registerImage(memberId, courseId, request)` — CourseImageFile 저장 (코스당 1개)
  - `deleteImage(memberId, courseId)` — CourseImageFile 삭제, 없으면 404
  - 커밋: `✨ feat: 코스 이미지 업로드·삭제 서비스 추가`

- [ ] 이미지 테스트
  - presigned URL 발급 성공
  - 이미지 등록 성공
  - 이미지 삭제 성공
  - 커밋: `✅ test: 코스 이미지 서비스 테스트 추가`

- [ ] `CourseCommandService.java` — 포크 (위 클래스에 추가)
  - `forkCourse(memberId, courseId)` — CURATED 검증, CourseStore 전부 복사, 이미지는 복사 안 함
  - 커밋: `✨ feat: 코스 포크 서비스 추가`

- [ ] 포크 테스트
  - CURATED → FORKED 포크 성공 (title = "copy of {원본제목}", member = 현재 사용자, isPublic = false)
  - CUSTOM/FORKED 포크 시도 → COURSE_FORK_NOT_CURATED
  - 커밋: `✅ test: 코스 포크 서비스 테스트 추가`

---

## Phase 4 — 코스 찜 서비스

- [ ] `SavedCourseCommandService.java`
  - `saveCourse(memberId, courseId)` — 중복 검증
  - `unsaveCourse(memberId, courseId)` — 없으면 COURSE_SAVED_NOT_FOUND
  - 커밋: `✨ feat: 코스 찜 서비스 추가`

- [ ] `SavedCourseCommandServiceTest.java`
  - 찜 추가 성공
  - 찜 추가 — 중복 → COURSE_SAVED_ALREADY
  - 찜 해제 성공
  - 찜 해제 — 없는 찜 → COURSE_SAVED_NOT_FOUND
  - 커밋: `✅ test: 코스 찜 서비스 테스트 추가`

---

## Phase 5 — 컨트롤러

- [ ] `CourseController.java` — 전체 16개 엔드포인트
  - 커밋: `✨ feat: 코스 컨트롤러 추가`

- [ ] `CourseControllerTest.java` (standaloneSetup + MockMvc)
  - `GET /courses` 200
  - `GET /courses/my` 200
  - `GET /courses/saved` 200
  - `GET /courses/{courseId}` 200 / 403 / 404
  - `POST /courses` 201
  - `PATCH /courses/{courseId}` 200 / 403
  - `DELETE /courses/{courseId}` 200
  - `POST /courses/{courseId}/images/presigned-url` 200
  - `POST /courses/{courseId}/images` 200
  - `POST /courses/{courseId}/stores` 200 / 400
  - `PATCH /courses/{courseId}/stores/{storeId}` 200
  - `DELETE /courses/{courseId}/stores/{storeId}` 200
  - `PATCH /courses/{courseId}/stores/reorder` 200
  - `POST /courses/{courseId}/save` 200 / 400
  - `DELETE /courses/{courseId}/save` 200 / 404
  - `POST /courses/{courseId}/fork` 201 / 400
  - 커밋: `✅ test: 코스 컨트롤러 테스트 추가`

---

## Phase 6 — 찜한가게 API (코스 CRUD 완료 후 진행)

가게 찜 추가/해제 + 목록 조회. 코스 편집 화면 "가게 추가 → 찜한가게 탭"과 빵집 상세 "찜하기 버튼"에서 사용.

- [ ] `SavedStoreRepository.java`
  - `findAllByMemberId(Long memberId)`
  - `existsByMemberIdAndStoreId(Long memberId, Long storeId)`
  - `deleteByMemberIdAndStoreId(Long memberId, Long storeId)`
  - 커밋: `✨ feat: SavedStore 저장소 추가`

- [ ] `SavedStoreErrorCode.java` + `SavedStoreException.java`
  - `SAVED_STORE_ALREADY(BAD_REQUEST, "SAVED_STORE400_1", "이미 찜한 가게입니다.")`
  - `SAVED_STORE_NOT_FOUND(NOT_FOUND, "SAVED_STORE404_1", "찜한 가게를 찾을 수 없습니다.")`
  - 커밋: `✨ feat: 찜한가게 에러 코드 및 예외 추가`

- [ ] `SavedStoreCommandService.java` — 찜 추가/해제
  - `saveStore(memberId, storeId)` — 중복 검증
  - `unsaveStore(memberId, storeId)` — 없으면 SAVED_STORE_NOT_FOUND
  - 커밋: `✨ feat: 가게 찜 추가·해제 서비스 추가`

- [ ] `SavedStoreCommandServiceTest.java`
  - 찜 추가 성공
  - 찜 추가 — 중복 → SAVED_STORE_ALREADY
  - 찜 해제 성공
  - 찜 해제 — 없는 찜 → SAVED_STORE_NOT_FOUND
  - 커밋: `✅ test: 가게 찜 서비스 테스트 추가`

- [ ] `SavedStoreQueryService.java` — 목록 조회
  - `getSavedStores(memberId)` (@Transactional readOnly)
  - 커밋: `✨ feat: 찜한가게 조회 서비스 추가`

- [ ] `SavedStoreQueryServiceTest.java`
  - 찜한가게 목록 조회 성공
  - 커밋: `✅ test: 찜한가게 조회 서비스 테스트 추가`

- [ ] `SavedStoreResponse.java` — storeId, storeName, region, latitude, longitude
  - 커밋: `✨ feat: 찜한가게 응답 DTO 추가`

- [ ] `SavedStoreController.java` — `/stores`
  - `GET /stores/saved` — 내 찜한가게 목록
  - `POST /stores/{storeId}/save` — 가게 찜
  - `DELETE /stores/{storeId}/save` — 가게 찜 해제
  - 커밋: `✨ feat: 찜한가게 컨트롤러 추가`

- [ ] `SavedStoreControllerTest.java`
  - `GET /stores/saved` 200
  - `POST /stores/{storeId}/save` 200 / 400(중복)
  - `DELETE /stores/{storeId}/save` 200 / 404
  - 커밋: `✅ test: 찜한가게 컨트롤러 테스트 추가`

---

## Phase 7 — 최종 점검

```bash
./gradlew spotlessApply
./gradlew build
./gradlew test --tests "sweetgirl.croissant.course.*"
./gradlew test --tests "sweetgirl.croissant.store.*SavedStore*"
```

- [ ] 새로 만든 모든 Service에 테스트 파일이 있는가?
- [ ] 새로 만든 모든 Controller에 테스트 파일이 있는가?
- [ ] 추가한 모든 public 메서드에 테스트 케이스가 있는가?
- [ ] 예외 케이스(Exception)에 대한 테스트가 있는가?
- [ ] `spotlessApply` 통과하는가?
- [ ] `build` (checkstyle 포함) 통과하는가?
- 커밋: `📝 docs: 코스 TODO 최종 완료 갱신`
