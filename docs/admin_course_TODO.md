# 어드민 코스 CRUD — 구현 TODO

브랜치: `feat/어드민-코스-CRUD` (별도 브랜치, `feat/461-BG-157-빵지순례-코스-CRUD` 머지 후 진행)

> **원칙**: Service 구현 직후 테스트 작성 — 몰아서 작성 금지 (CLAUDE.md)

---

## 배경 & 제약

- CURATED 코스는 어드민이 직접 생성·관리. 앱 API(`PATCH /courses/{courseId}`)로는 수정 불가(403).
- CURATED 특성: `type = CURATED`, `member = null`, `isPublic = true` (고정), `badge` nullable
- 어드민 인증: `/api/admin/**` 경로는 security config에서 처리. 컨트롤러에 `@AuthenticationPrincipal` 없음 (AdminStoreController 패턴 동일)
- 기반 코드(Repository, Entity, ErrorCode)는 `feat/461-BG-157-빵지순례-코스-CRUD` 브랜치에서 이미 작성됨

---

## API 목록

| 메서드·경로 | 주요 조건·동작 | 응답 `data` | 에러 |
|------------|--------------|------------|------|
| `GET /api/admin/courses` | CURATED 전체 목록, deletedAt IS NULL, 최신순 | `List<AdminCourseResponse>` | |
| `GET /api/admin/courses/{courseId}` | 코스 상세 (courseStores 포함) | `AdminCourseDetailResponse` | 404 |
| `POST /api/admin/courses` | CURATED 생성 (title, badge?) | `{ courseId }` | |
| `PATCH /api/admin/courses/{courseId}` | title·badge partial update | 없음 | 404 |
| `DELETE /api/admin/courses/{courseId}` | soft delete | 없음 | 404 |
| `POST /api/admin/courses/{courseId}/images/presigned-url` | S3 경로 생성 | `{ url, awsKey }` | 404 |
| `POST /api/admin/courses/{courseId}/images` | CourseImageFile 저장 (코스당 1개) | 없음 | 404 |
| `DELETE /api/admin/courses/{courseId}/images` | CourseImageFile 삭제 | 없음 | 404(코스 없음), 404(이미지 없음) |
| `POST /api/admin/courses/{courseId}/stores` | 가게 추가, visitOrder 자동, 중복 불가 | `CourseStoreResponse` | 404, 400(중복) |
| `PATCH /api/admin/courses/{courseId}/stores/{storeId}` | memo·visitTime partial update | 없음 | 404 |
| `DELETE /api/admin/courses/{courseId}/stores/{storeId}` | CourseStore soft delete | 없음 | 404 |
| `PATCH /api/admin/courses/{courseId}/stores/reorder` | courseStoreIds 순서 = 새 visitOrder | 없음 | 404(id 없음) |

---

## 응답 DTO 필드

| DTO | 필드 |
|-----|------|
| `AdminCourseResponse` | id, title, badge(nullable), coverImageKey(nullable), storeCount, savedCount, createdAt |
| `AdminCourseDetailResponse` | AdminCourseResponse 전체 + courseStores(`List<CourseStoreResponse>`) |

> `CourseStoreResponse`는 앱 코스 TODO에서 정의한 DTO 재사용

---

## Phase 1 — 응답 DTO

- [ ] `AdminCourseResponse.java`
  - 커밋: `✨ feat: 어드민 코스 응답 DTO 추가`

- [ ] `AdminCourseDetailResponse.java`
  - 커밋: `✨ feat: 어드민 코스 상세 응답 DTO 추가`

---

## Phase 2 — 서비스

- [ ] 요청 DTO 추가
  - `AdminCourseCreateRequest.java` — title, badge?
  - `AdminCourseUpdateRequest.java` — title?, badge?
  - 커밋: `✨ feat: 어드민 코스 요청 DTO 추가`

- [ ] `AdminCourseQueryService.java`
  - `getCuratedCourses()` — CURATED 목록, deletedAt IS NULL, 최신순
  - `getCuratedCourseDetail(courseId)` — courseStores 포함
  - 커밋: `✨ feat: 어드민 코스 조회 서비스 추가`

- [ ] `AdminCourseQueryServiceTest.java`
  - CURATED 목록 조회 성공
  - 코스 상세 조회 성공
  - 삭제된 코스 조회 → COURSE_NOT_FOUND
  - 커밋: `✅ test: 어드민 코스 조회 서비스 테스트 추가`

- [ ] `AdminCourseCommandService.java` — 코스 생성·수정·삭제
  - `createCuratedCourse(request)` — `Course.createCurated()` 사용
  - `updateCuratedCourse(courseId, request)` — title, badge partial update
  - `deleteCuratedCourse(courseId)` — soft delete
  - 커밋: `✨ feat: 어드민 코스 생성·수정·삭제 서비스 추가`

- [ ] 코스 CRUD 테스트
  - 코스 생성 성공 (type=CURATED, member=null, isPublic=true)
  - 코스 수정 성공
  - 코스 수정 — 없는 코스 → COURSE_NOT_FOUND
  - 코스 삭제 성공 — soft delete 확인
  - 커밋: `✅ test: 어드민 코스 생성·수정·삭제 서비스 테스트 추가`

- [ ] `AdminCourseCommandService.java` — 이미지 업로드·삭제 (위 클래스에 추가)
  - `getPresignedUrl(courseId, mimeType)`
  - `registerImage(courseId, request)` — CourseImageFile 저장 (코스당 1개)
  - `deleteImage(courseId)` — 없으면 COURSE_IMAGE_NOT_FOUND
  - 커밋: `✨ feat: 어드민 코스 이미지 서비스 추가`

- [ ] 이미지 테스트
  - presigned URL 발급 성공
  - 이미지 등록 성공
  - 이미지 삭제 성공
  - 이미지 삭제 — 없는 이미지 → COURSE_IMAGE_NOT_FOUND
  - 커밋: `✅ test: 어드민 코스 이미지 서비스 테스트 추가`

- [ ] `AdminCourseCommandService.java` — 가게 추가·수정·제거·순서변경 (위 클래스에 추가)
  - `addStore(courseId, request)` — 중복 검증, visitOrder 자동
  - `updateStore(courseId, storeId, request)` — memo, visitTime
  - `removeStore(courseId, storeId)` — soft delete
  - `reorderStores(courseId, request)`
  - 커밋: `✨ feat: 어드민 코스 가게 관리 서비스 추가`

- [ ] 가게 관리 테스트
  - 가게 추가 성공
  - 가게 추가 — 중복 → COURSE_STORE_ALREADY_EXISTS
  - 가게 수정 성공
  - 가게 제거 성공
  - 순서 변경 성공
  - 커밋: `✅ test: 어드민 코스 가게 서비스 테스트 추가`

---

## Phase 3 — 컨트롤러

- [ ] `AdminCourseController.java` — 조회
  - `GET /api/admin/courses`, `GET /api/admin/courses/{courseId}`
  - 커밋: `✨ feat: 어드민 코스 조회 컨트롤러 추가`

- [ ] `AdminCourseControllerTest.java` — 조회
  - `GET /api/admin/courses` 200
  - `GET /api/admin/courses/{courseId}` 200 / 404
  - 커밋: `✅ test: 어드민 코스 조회 컨트롤러 테스트 추가`

- [ ] `AdminCourseController.java` — 코스 생성·수정·삭제 (위 클래스에 추가)
  - `POST /api/admin/courses`, `PATCH /api/admin/courses/{courseId}`, `DELETE /api/admin/courses/{courseId}`
  - 커밋: `✨ feat: 어드민 코스 생성·수정·삭제 컨트롤러 추가`

- [ ] `AdminCourseControllerTest.java` — 코스 생성·수정·삭제
  - `POST /api/admin/courses` 201
  - `PATCH /api/admin/courses/{courseId}` 200 / 404
  - `DELETE /api/admin/courses/{courseId}` 200
  - 커밋: `✅ test: 어드민 코스 생성·수정·삭제 컨트롤러 테스트 추가`

- [ ] `AdminCourseController.java` — 이미지 관리 (위 클래스에 추가)
  - `POST /api/admin/courses/{courseId}/images/presigned-url`, `POST /api/admin/courses/{courseId}/images`, `DELETE /api/admin/courses/{courseId}/images`
  - 커밋: `✨ feat: 어드민 코스 이미지 컨트롤러 추가`

- [ ] `AdminCourseControllerTest.java` — 이미지
  - `POST /api/admin/courses/{courseId}/images/presigned-url` 200
  - `POST /api/admin/courses/{courseId}/images` 200
  - `DELETE /api/admin/courses/{courseId}/images` 200 / 404
  - 커밋: `✅ test: 어드민 코스 이미지 컨트롤러 테스트 추가`

- [ ] `AdminCourseController.java` — 가게 관리 (위 클래스에 추가)
  - `POST /api/admin/courses/{courseId}/stores`, `PATCH /api/admin/courses/{courseId}/stores/{storeId}`, `DELETE /api/admin/courses/{courseId}/stores/{storeId}`, `PATCH /api/admin/courses/{courseId}/stores/reorder`
  - 커밋: `✨ feat: 어드민 코스 가게 관리 컨트롤러 추가`

- [ ] `AdminCourseControllerTest.java` — 가게 관리
  - `POST /api/admin/courses/{courseId}/stores` 200 / 400
  - `PATCH /api/admin/courses/{courseId}/stores/{storeId}` 200
  - `DELETE /api/admin/courses/{courseId}/stores/{storeId}` 200
  - `PATCH /api/admin/courses/{courseId}/stores/reorder` 200
  - 커밋: `✅ test: 어드민 코스 가게 관리 컨트롤러 테스트 추가`

---

## Phase 4 — 최종 점검

```bash
./gradlew spotlessApply
./gradlew build
./gradlew test --tests "sweetgirl.croissant.admin.*Course*"
```

- [ ] 새로 만든 모든 Service에 테스트 파일이 있는가?
- [ ] 새로 만든 모든 Controller에 테스트 파일이 있는가?
- [ ] 추가한 모든 public 메서드에 테스트 케이스가 있는가?
- [ ] 예외 케이스(Exception)에 대한 테스트가 있는가?
- [ ] `spotlessApply` 통과하는가?
- [ ] `build` (checkstyle 포함) 통과하는가?
- 커밋: `📝 docs: 어드민 코스 TODO 최종 완료 갱신`
