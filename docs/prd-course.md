# PRD: 빵지순례 코스 기능

**작성일:** 2026-06-17  
**최종 수정:** 2026-06-23  
**상태:** BE 구현 완료 (feat/461-BG-157-빵지순례-코스-CRUD)

---

## 1. 개요

사용자가 여러 빵집을 묶어 하나의 방문 경로(코스)로 계획할 수 있는 기능.  
운영팀이 제공하는 **큐레이션 코스**를 포크하거나, 직접 빵집을 골라 **커스텀 코스**를 만들 수 있다.

---

## 2. 목표

- 빵집 탐색을 "단일 빵집 방문"에서 "코스 여행"으로 확장
- 큐레이션 코스로 신규 사용자의 탐색 허들 낮추기
- 커스텀 코스로 재방문·활성 사용자의 앱 체류 동기 강화

---

## 3. 앱 네비게이션 구조

```
바텀 네비게이션: 홈 | 코스 | 찜한가게 | 마이
```

| 탭 | 내용 |
|----|------|
| 홈 | 기존 홈 피드 |
| 코스 | 코스 큐레이션 서브탭 + 나의 코스 서브탭 |
| 찜한가게 | 관심 빵집 위시리스트 (SavedStore) |
| 마이 | 마이페이지 |

---

## 4. 코스 타입 (CourseType)

| 타입 | 설명 | 생성 주체 | member | isPublic |
|------|------|-----------|--------|----------|
| `CURATED` | 운영팀 큐레이션 코스 | 어드민 | null | true 고정 |
| `CUSTOM` | 사용자 직접 생성 코스 | 사용자 | 본인 | 선택 |
| `FORKED` | 큐레이션 코스를 포크한 코스 | 사용자 (포크) | 본인 | false 초기값 |

- **나의 코스 목록** = CUSTOM + FORKED 타입 전체
- **큐레이션 코스 목록** = `isPublic=true && !deleted` 인 코스 (pinOrder ASC → savedCount DESC 정렬)

---

## 5. 기능 범위

### 5-1. 코스 탭 서브탭 구성

| 서브탭 | 표시 내용 |
|--------|-----------|
| 코스 큐레이션 | isPublic=true인 코스 목록 (핀된 것 우선, 그다음 찜 수 많은 순) |
| 나의 코스 | 내가 생성한 CUSTOM + FORKED 코스 목록 (로그인 필요) |

> ⚠️ **"저장한 코스" 서브탭 없음** — 찜(SavedCourse)은 별도 탭이 아닌 기능으로 제공.  
> 코스 찜 여부는 코스 상세 내 찜 버튼으로만 토글한다.

### 5-2. 코스 카드 표시 정보

| 항목 | 출처 | 비고 |
|------|------|------|
| 대표 이미지 | `CourseImageFile.awsKey` (코스당 1개) | 없으면 null |
| 코스 제목 | `Course.title` | |
| 생성자 닉네임 | `Member.nickname` | CURATED는 null → FE에서 "빵긋 에디터" 고정 표시 |
| 가게 수 | 활성 `CourseStore` 개수 | |
| 지역 | `CourseStore → Store.region2nd` (구 단위, 중복 제거) | |
| 배지 | `Course.badge` | CURATED 전용 (예: "에디터 픽") |

예시 카드:
```
[대표이미지]
소금빵 순례
by 빵긋 에디터  |  가게 5개
마포구 · 성동구
[에디터 픽]
```

### 5-3. 큐레이션 코스

| 항목 | 내용 |
|------|------|
| 제공 주체 | 운영팀 (어드민 등록) |
| 구성 요소 | 코스 제목, 배지, 대표 이미지, 빵집 목록(순서 있음) |
| 사용자 액션 | 상세 보기 → **나만의 코스로 편집(포크)** → 새 FORKED 코스로 저장 |
| 노출 위치 | 코스 탭 > 코스 큐레이션 서브탭 |

### 5-4. 찜한가게 (관심 빵집 위시리스트)

커스텀 코스 생성의 재료가 되는 빵집 보관함.

| 항목 | 내용 |
|------|------|
| 역할 | 나중에 방문하고 싶은 빵집을 모아두는 위시리스트 |
| 추가 방법 | 빵집 상세페이지 "찜하기" 버튼 |
| 노출 위치 | 바텀 네비게이션 > 찜한가게 탭 |
| 활용 | 코스 생성 시 빵집 선택 재료 (찜한가게 탭) |

### 5-5. 커스텀 코스

| 항목 | 내용 |
|------|------|
| 제공 주체 | 사용자 |
| 구성 요소 | 코스 이름, 대표 이미지(선택), 빵집 목록(순서 조정 가능), 가게별 메모·방문시간(선택), 공개 여부 |
| 생성 플로우 | ① 코스 정보 입력(제목·공개여부) → courseId 발급 → ② 코스 편집 화면에서 가게 추가(auto-save) |
| 빵집 추가 경로 | ① 찜한가게 탭에서 선택 ② 지역·메뉴별 랭킹 탭에서 탐색 |
| 편집 | 코스 상세 더보기(…) → 편집 |

---

## 6. UX 플로우

### 코스 탭 화면 구조
```
코스 탭
  ├── [코스 큐레이션] 서브탭 — 큐레이션 코스 목록
  └── [나의 코스] 서브탭 — 내가 생성한 CUSTOM + FORKED 코스 목록
        우하단 [코스 생성] FAB (로그인 필요)
```

---

### 코스 상세

```
코스 카드 탭
  → 코스 상세
      - 지도 경로 미리보기 (가게별 위경도 마커 + 연결 폴리라인)
      - 빵집 리스트 + 가게 간 도보 시간 (walkingMinutesToNext)
      - [찜] 버튼 (로그인 필요, 토글) + 찜한 사람 수
      - [나만의 코스로 편집하기] 버튼 — 공개 코스에서만 노출
```

> ⚠️ **코스 좋아요 없음** — `course_likes` 테이블은 DB에 존재하지만 현재 미사용. 향후 기능.

**[나만의 코스로 편집하기] = 포크**
- 해당 코스의 빵집 목록(순서·메모·방문시간 포함)을 복사해 새 **FORKED** 코스로 생성
- 포크 제목: `"copy of {원본 제목}"` (자동 설정, 이후 수정 가능)
- 포크 초기 공개 여부: **비공개** (`isPublic=false`)
- 원본 코스 ID는 `sourceCourseId`에 기록 (원본 추적용)
- 이미지는 복사하지 않음

> **FORKED 코스 수정 제약**: 제목·가게 구성·순서·메모·방문시간이 원본과 동일하면 수정 불가 (`COURSE_FORK_NOT_MODIFIED`)

```
코스 상세 → [나만의 코스로 편집하기]
  → POST /courses/{courseId}/fork
  → 새 FORKED 코스 생성 (원본 빵집 목록 pre-populated)
  → [나의 코스] 탭에 추가됨
```

---

### 커스텀 코스 생성 — FAB 진입

```
나의 코스 서브탭 → [코스 생성] FAB

  코스 정보 입력 화면
    → 코스 제목 입력 (필수, max 100자)
    → 공개 여부 선택 (공개 / 비공개)
    → [저장] → POST /courses (title, isPublic) → courseId 반환
    → 코스 편집 화면으로 이동 (auto-save)

  코스 편집 화면 (auto-save)
    → [가게 추가] → POST /courses/{id}/stores
    → 가게 제거 → DELETE /courses/{id}/stores/{storeId}
    → 가게 순서·도보시간 조정 → PATCH /courses/{id}/stores/reorder (드롭 완료 시)
    → 가게별 메모·방문시간 → PATCH /courses/{id}/stores/{storeId}
    → 대표 이미지 → presigned URL → S3 업로드 → POST /courses/{id}/images
```

---

### 코스 편집 (내 코스) — auto-save 방식

별도 "저장" 버튼 없음. 각 액션이 API를 즉시 호출해 서버에 반영된다.

| 액션 | 호출 API |
|------|---------|
| 가게 추가 | `POST /courses/{id}/stores` |
| 가게 제거 | `DELETE /courses/{id}/stores/{storeId}` |
| 메모·방문시간 수정 | `PATCH /courses/{id}/stores/{storeId}` |
| 드래그로 순서+도보시간 변경 | `PATCH /courses/{id}/stores/reorder` |
| 코스 제목·공개여부 수정 | `PATCH /courses/{id}` |
| 코스 삭제 | `DELETE /courses/{id}` (확인 후) |
| 대표 이미지 업로드 | presigned URL → S3 → `POST /courses/{id}/images` |
| 대표 이미지 삭제 | `DELETE /courses/{id}/images` |

---

## 7. 빵집 상세페이지 버튼 배치

```
[ 빵알람 받기 ]   ← Primary CTA (기존, 큰 버튼)
[ 찜하기 ]        ← Secondary CTA (작은 버튼 or 아이콘)
```

---

## 8. 데이터 모델 (현행 BE 스키마)

```
courses 테이블
  - id
  - type: CURATED | CUSTOM | FORKED
  - title (max 100자)
  - member_id (FK, CUSTOM·FORKED는 필수 / CURATED는 null)
  - is_public (CURATED는 true 고정 / FORKED 생성 시 false)
  - like_count (현재 미사용, 향후 기능)
  - badge (max 30자, CURATED 전용)
  - source_course_id (FK, FORKED일 때 원본 코스 ID)
  - pin_order (핀 순서 1~3, 어드민 설정, nullable)
  - deleted_at (soft delete)
  - created_at, updated_at

course_stores 테이블
  - id (PK — reorder 시 이 ID 사용)
  - course_id (FK)
  - store_id (FK)
  - visit_order (방문 순서, 1부터 시작)
  - memo (TEXT, nullable)
  - visit_time (TIME, nullable)
  - walking_minutes_to_next (다음 가게까지 도보 분, nullable)
  - deleted_at (soft delete)
  - UNIQUE (course_id, store_id), (course_id, visit_order)

course_image_files 테이블
  - course_id (FK, 코스당 1개)
  - display_order (항상 1)
  - aws_key / mime_type / name

saved_courses 테이블 (코스 찜)
  - member_id (FK)
  - course_id (FK)
  - UNIQUE (member_id, course_id)

course_likes 테이블 (현재 미사용 — 향후 기능)
  - member_id, course_id

saved_stores 테이블 (찜한가게)
  - member_id, store_id
```

---

## 9. API 목록

### 9-1. 앱 API (`/courses`)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/courses` | 선택 | 공개 코스 목록 (pinOrder ASC → savedCount DESC) |
| GET | `/courses/my` | 필수 | 내 코스 목록 (CUSTOM + FORKED) |
| GET | `/courses/saved` | 필수 | 내가 찜한 코스 목록 |
| GET | `/courses/{courseId}` | 선택 | 코스 상세 (비공개는 본인만) |
| POST | `/courses` | 필수 | 코스 생성 (CUSTOM) |
| PATCH | `/courses/{courseId}` | 필수 | 코스 수정 (title?, isPublic?) |
| DELETE | `/courses/{courseId}` | 필수 | 코스 삭제 (soft delete) |
| POST | `/courses/{courseId}/images/presigned-url` | 필수 | 커버 이미지 presigned URL 발급 |
| POST | `/courses/{courseId}/images` | 필수 | 이미지 등록 (기존 이미지 있으면 교체) |
| DELETE | `/courses/{courseId}/images` | 필수 | 이미지 삭제 |
| POST | `/courses/{courseId}/stores` | 필수 | 가게 추가 |
| PATCH | `/courses/{courseId}/stores/{storeId}` | 필수 | 가게 메모·방문시간 수정 |
| DELETE | `/courses/{courseId}/stores/{storeId}` | 필수 | 가게 제거 (soft delete) |
| PATCH | `/courses/{courseId}/stores/reorder` | 필수 | 가게 순서+도보시간 변경 |
| POST | `/courses/{courseId}/save` | 필수 | 코스 찜 |
| DELETE | `/courses/{courseId}/save` | 필수 | 코스 찜 해제 |
| POST | `/courses/{courseId}/fork` | 필수 | 공개 코스 포크 → FORKED 코스 생성 |

### 9-2. 어드민 API (`/api/admin/courses`)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/admin/courses` | CURATED 코스 목록 (최신순) |
| GET | `/api/admin/courses/{courseId}` | 코스 상세 + 가게 목록 |
| POST | `/api/admin/courses` | CURATED 코스 생성 |
| PATCH | `/api/admin/courses/{courseId}` | 코스 제목·배지 수정 |
| DELETE | `/api/admin/courses/{courseId}` | 코스 soft delete |
| POST | `/api/admin/courses/{courseId}/images/presigned-url` | 커버 이미지 presigned URL |
| POST | `/api/admin/courses/{courseId}/images` | 이미지 등록 |
| DELETE | `/api/admin/courses/{courseId}/images` | 이미지 삭제 |
| POST | `/api/admin/courses/{courseId}/stores` | 가게 추가 |
| PATCH | `/api/admin/courses/{courseId}/stores/{storeId}` | 가게 메모·방문시간 수정 |
| DELETE | `/api/admin/courses/{courseId}/stores/{storeId}` | 가게 제거 |
| PATCH | `/api/admin/courses/{courseId}/stores/reorder` | 가게 순서+도보시간 변경 |
| POST | `/api/admin/courses/{courseId}/pin` | 홈 핀 등록 (최대 3개, 순위 중복 불가) |
| DELETE | `/api/admin/courses/{courseId}/pin` | 홈 핀 해제 |

---

## 10. 주요 요청/응답 DTO

### CourseSummaryResponse (목록 카드)
```json
{
  "id": 1,
  "title": "소금빵 순례",
  "coverImageKey": "courses/1/cover.jpg",
  "storeCount": 5,
  "regions": ["마포구", "성동구"],
  "courseType": "CURATED",
  "authorName": null,        // CURATED는 null → FE에서 "빵긋 에디터" 표시
  "badge": "에디터 픽"
}
```

### CourseDetailResponse (상세)
```json
{
  "id": 1,
  "title": "소금빵 순례",
  "coverImageKey": "courses/1/cover.jpg",
  "storeCount": 3,
  "regions": ["마포구"],
  "courseType": "CURATED",
  "authorName": null,
  "badge": "에디터 픽",
  "isSaved": true,
  "savedCount": 42,
  "courseStores": [
    {
      "courseStoreId": 10,          // course_stores.id (reorder 시 사용)
      "visitOrder": 1,
      "storeId": 100,
      "storeName": "르뺑",
      "latitude": 37.55,
      "longitude": 126.92,
      "region": "마포구",
      "memo": "오전에 방문 추천",
      "visitTime": "10:00:00",
      "walkingMinutesToNext": 5
    }
  ]
}
```

### CourseStoreAddRequest
```json
{
  "storeId": 100,               // 필수
  "memo": "오전에 방문 추천",    // 선택
  "visitTime": "10:00:00",       // 선택
  "walkingMinutesToNext": 5      // 선택
}
```

### CourseStoreReorderRequest
```json
{
  "courseStoreOrders": [
    { "courseStoreId": 10, "walkingMinutesToNext": 5 },
    { "courseStoreId": 12, "walkingMinutesToNext": 8 },
    { "courseStoreId": 11, "walkingMinutesToNext": null }
  ]
}
```
> `courseStoreId`는 `course_stores.id` (가게 ID가 아님). 배열 순서가 새 `visitOrder`.

### AdminCoursePinRequest
```json
{ "pinOrder": 1 }   // 1~3, 중복 불가
```

---

## 11. 핀(Pin) 기능

어드민이 CURATED 코스를 홈 화면 등 주요 노출 위치에 고정하는 기능.

- 최대 **3개** 동시 핀
- `pinOrder` 1~3, 동일 순위에 두 코스 핀 불가
- 이미 핀된 코스를 다시 핀하려 하면 오류
- 큐레이션 목록 정렬: `pinOrder ASC (null은 후순위)` → `savedCount DESC`

---

## 12. 에러 코드

| 코드 | HTTP | 설명 |
|------|------|------|
| `COURSE404_1` | 404 | 코스를 찾을 수 없음 |
| `COURSE404_2` | 404 | 코스 가게를 찾을 수 없음 |
| `COURSE404_3` | 404 | 코스 이미지를 찾을 수 없음 |
| `COURSE404_4` | 404 | 찜한 코스를 찾을 수 없음 |
| `COURSE403_1` | 403 | 코스 권한 없음 (비공개 코스 타인 접근 / 타인 코스 수정·삭제) |
| `COURSE400_1` | 400 | 이미 코스에 추가된 가게 |
| `COURSE400_2` | 400 | 이미 찜한 코스 |
| `COURSE400_3` | 400 | 비공개 코스는 포크 불가 |
| `COURSE400_4` | 400 | 원본과 동일한 내용으로 FORKED 코스 수정 불가 |
| `COURSE400_5` | 400 | 이미 핀된 코스 |
| `COURSE400_6` | 400 | 핀되지 않은 코스를 핀 해제 시도 |
| `COURSE400_7` | 400 | 핀 최대 3개 초과 |
| `COURSE400_8` | 400 | 해당 순위에 이미 핀된 코스 있음 |

---

## 13. 추후 기능 (이번 범위 외)

| 기능 | 설명 |
|------|------|
| 코스 좋아요 | `course_likes` 테이블 활용, likeCount 토글 |
| 코스 공유 | 링크/SNS 공유, 딥링크 |
| 방문 기록 | 코스 진행 상태 트래킹 |
| 코스 실행 모드 | 지도에서 순서대로 네비게이션 |
| 입맛 추천 연동 | 취향 기반 빵집 우선 노출 |

---

## 14. 미결 사항

| # | 질문 | 상태 |
|---|------|------|
| 1 | 코스 내 빵집 수 제한 (예: 최대 10개) | 미결 |
| 2 | 커스텀 코스 개수 제한 (유저당 최대 N개) | 미결 |
| 3 | 큐레이션 목록 노출 범위: 현재 `isPublic=true` 전체 (FORKED 공개 코스도 포함 가능) — CURATED만 노출할지 확인 필요 | 미결 |
