# PRD: 빵지순례 코스 기능

**작성일:** 2026-06-17  
**최종 수정:** 2026-06-23  
**상태:** 구현 진행 중 (feat/461-BG-157-빵지순례-코스-CRUD)

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

## 4. 기능 범위

### 4-1. 코스 탭 서브탭 구성

| 서브탭 | 표시 내용 |
|--------|-----------|
| 코스 큐레이션 | 운영팀이 등록한 CURATED 코스 전체 |
| 나의 코스 | 내가 생성한 CUSTOM 코스 목록 (로그인 필요) |

> ⚠️ **"저장한 코스" 서브탭 없음** — 찜(SavedCourse)은 별도 탭이 아닌 기능으로 제공.  
> 코스 찜 여부는 코스 상세 내 찜 버튼으로만 토글한다.

### 4-2. 코스 카드 표시 정보

| 항목 | 출처 |
|------|------|
| 대표 이미지 | `CourseImageFile` (displayOrder 가장 낮은 것) |
| 코스 제목 | `Course.title` |
| 생성자 닉네임 | `Member.nickname` (CURATED는 "빵긋 에디터" 고정) |
| 가게 수 | 활성 `CourseStore` 개수 |
| 지역 | `CourseStore → Store.regionDepth2` (구 단위, 중복 제거, 최대 2개 노출) |
| 배지 | `Course.badge` (CURATED 전용, 예: "에디터 픽") |

예시 카드:
```
[대표이미지]
소금빵 순례
by 빵긋 에디터  |  가게 5개
마포구 · 성동구
[에디터 픽]
```

### 4-3. 큐레이션 코스

| 항목 | 내용 |
|------|------|
| 제공 주체 | 운영팀 (관리자 등록) |
| 구성 요소 | 코스 제목, 배지, 대표 이미지, 빵집 목록(순서 있음) |
| 사용자 액션 | 상세 보기 → **나만의 코스로 편집(포크)** → 새 CUSTOM 코스로 저장 |
| 노출 위치 | 코스 탭 > 코스 큐레이션 서브탭 |

### 4-4. 찜한가게 (관심 빵집 위시리스트)

커스텀 코스 생성의 재료가 되는 빵집 보관함.

| 항목 | 내용 |
|------|------|
| 역할 | 나중에 방문하고 싶은 빵집을 모아두는 위시리스트 |
| 추가 방법 | 빵집 상세페이지 "찜하기" 버튼 |
| 노출 위치 | 바텀 네비게이션 > 찜한가게 탭 |
| 활용 | 코스 생성 시 빵집 선택 재료 (찜한가게 탭) |

### 4-5. 커스텀 코스

| 항목 | 내용 |
|------|------|
| 제공 주체 | 사용자 |
| 구성 요소 | 코스 이름, 대표 이미지(선택), 빵집 목록(순서 조정 가능), 가게별 메모·방문시간(선택), 공개 여부 |
| 생성 플로우 | **2단계** — ① 가게 구성 → ② 코스 정보 입력(제목·이미지·공개여부) |
| 빵집 추가 경로 | ① 찜한가게 탭에서 선택 ② 지역·메뉴별 랭킹 탭에서 탐색 |
| 편집 | 코스 상세 더보기(…) → 편집 |

---

## 5. 커스텀 코스 빵집 탐색 방식

코스 생성 화면의 "가게 추가" 영역에서 두 탭으로 탐색한다.

```
[찜한가게 탭]
  → 내가 찜한 빵집 목록에서 선택

[지역·메뉴별 랭킹 탭]
  → 검색창 + 지역 / 메뉴(카테고리) 필터
  → 해당 조건의 빵집을 랭킹 점수 순으로 표시
```

---

## 6. UX 플로우 전체

### 코스 탭 화면 구조
```
코스 탭
  ├── [코스 큐레이션] 서브탭 — 큐레이션 코스 목록
  └── [나의 코스] 서브탭 — 내가 생성한 커스텀 코스 목록
        우하단 [코스 생성] FAB (로그인 필요)
```

---

### 코스 상세

```
코스 카드 탭
  → 코스 상세
      - 지도 경로 미리보기 (가게별 위경도 마커 + 연결 폴리라인)
      - 빵집 리스트 + 가게 간 거리 (Haversine)
      - [찜] 버튼 (로그인 필요, 토글)
      - [나만의 코스로 편집하기] 버튼 — CURATED 코스에서만 노출
```

> ⚠️ **코스 좋아요 없음** — `course_likes` 테이블은 DB에 존재하지만 현재 미사용. 향후 기능.

**[나만의 코스로 편집하기] = 포크**
- 해당 CURATED 코스의 빵집 목록을 복사해 새 CUSTOM 코스 생성
- 원본과 독립된 코스로 저장 (이미지는 복사하지 않음)

```
코스 상세 → [나만의 코스로 편집하기]
  → 새 CUSTOM 코스 생성 (원본 빵집 목록 pre-populated)
  → [나의 코스] 탭에 추가됨
```

---

### 커스텀 코스 생성 — FAB 진입 (트리플 방식)

```
나의 코스 서브탭 → [코스 생성] FAB

  코스 정보 입력 화면
    → 코스 제목 입력
    → 공개 여부 선택 (공개 / 비공개)
    → [저장] → POST /courses (title, isPublic) → courseId 반환
    → 코스 편집 화면으로 이동 (auto-save)

  코스 편집 화면 (auto-save)
    → [가게 추가] → POST /courses/{id}/stores (즉시 반영)
    → 가게 제거 → DELETE /courses/{id}/stores/{storeId} (즉시 반영)
    → 가게 순서 조정 → PATCH /courses/{id}/stores/reorder (드롭 완료 시)
    → 가게별 메모·방문시간 → PATCH /courses/{id}/stores/{storeId} (즉시 반영)
    → 대표 이미지 → presigned URL → S3 업로드 → POST /courses/{id}/images
```

> 가게 구성 선(先) 선택 후 코스 생성하는 2단계 플로우는 제거.

---

### 코스 상세 편집 (내 코스) — 즉시 반영(auto-save) 방식

별도 "저장" 버튼 없음. 각 액션이 API를 즉시 호출해 서버에 반영된다.

| 액션 | 호출 API |
|------|---------|
| 가게 추가 | `POST /courses/{id}/stores` |
| 가게 제거 | `DELETE /courses/{id}/stores/{storeId}` |
| 메모·방문시간 수정 | `PATCH /courses/{id}/stores/{storeId}` |
| 드래그로 순서 변경 | `PATCH /courses/{id}/stores/reorder` (드롭 완료 시점에 전체 순서 전송) |
| 코스 제목·공개여부 수정 | `PATCH /courses/{id}` |
| 코스 삭제 | `DELETE /courses/{id}` (확인 후) |

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
  - type: CURATED | CUSTOM
  - title (max 100자)
  - member_id (FK, CUSTOM일 때 필수 / CURATED면 null)
  - is_public
  - like_count (현재 미사용, 향후 기능)
  - badge (max 30자, CURATED 전용)
  - deleted_at (soft delete)

course_stores 테이블 (구 course_stops)
  - course_id (FK)
  - store_id (FK)
  - visit_order (방문 순서)
  - memo (nullable)
  - visit_time (TIME, nullable)
  - deleted_at (soft delete)
  - UNIQUE (course_id, store_id), (course_id, visit_order)

course_image_files 테이블
  - course_id (FK)
  - display_order
  - aws_key / mime_type / name
  - processing_status ('DONE' 인 것만 조회)

saved_courses 테이블 (코스 찜)
  - member_id (FK)
  - course_id (FK)
  - UNIQUE (member_id, course_id)

course_likes 테이블 (현재 미사용 — 향후 기능)
  - member_id, course_id

saved_stores 테이블 (구 wished_stores, 찜한가게)
  - member_id, store_id
```

---

## 9. API 목록 (이번 구현 범위)

| Method | Path | 인증 | 설명 |
|--------|------|------|------|
| GET | `/courses` | 선택 | 큐레이션 코스 목록 |
| GET | `/courses/my` | 필수 | 내 커스텀 코스 목록 |
| GET | `/courses/{courseId}` | 선택 | 코스 상세 |
| POST | `/courses` | 필수 | 내 코스 생성 |
| PATCH | `/courses/{courseId}` | 필수 | 내 코스 수정 (title, isPublic) |
| DELETE | `/courses/{courseId}` | 필수 | 내 코스 삭제 |
| POST | `/courses/{courseId}/images/presigned-url` | 필수 | 커버 이미지 presigned URL |
| POST | `/courses/{courseId}/images` | 필수 | 이미지 등록 |
| POST | `/courses/{courseId}/stores` | 필수 | 가게 추가 (즉시 반영) |
| PATCH | `/courses/{courseId}/stores/{storeId}` | 필수 | 메모·방문시간 수정 (즉시 반영) |
| DELETE | `/courses/{courseId}/stores/{storeId}` | 필수 | 가게 제거 (즉시 반영) |
| PATCH | `/courses/{courseId}/stores/reorder` | 필수 | 가게 순서 변경 (드롭 완료 시 전체 순서 전송) |
| GET | `/saved-stores` | 필수 | 내 찜한가게 목록 (코스 편집 가게 추가 모달용) |
| POST | `/saved-stores/{storeId}` | 필수 | 가게 찜 |
| DELETE | `/saved-stores/{storeId}` | 필수 | 가게 찜 해제 |
| POST | `/courses/{courseId}/save` | 필수 | 코스 찜 |
| DELETE | `/courses/{courseId}/save` | 필수 | 코스 찜 해제 |
| POST | `/courses/{courseId}/fork` | 필수 | 큐레이션→커스텀 포크 |

---

## 10. 추후 기능 (이번 범위 외)

| 기능 | 설명 |
|------|------|
| 코스 좋아요 | `course_likes` 테이블 활용, likeCount 토글 |
| 코스 공유 | 링크/SNS 공유, 딥링크 |
| 방문 기록 | 코스 진행 상태 트래킹 |
| 코스 실행 모드 | 지도에서 순서대로 네비게이션 |
| 입맛 추천 연동 | 취향 기반 빵집 우선 노출 |

---

## 11. 미결 사항

| # | 질문 | 상태 |
|---|------|------|
| 1 | 큐레이션 코스 관리자 등록 — 관리자 웹 대시보드 연동 여부 | 미결 |
| 2 | 코스 내 빵집 수 제한 (예: 최대 10개) | 미결 |
| 3 | 커스텀 코스 개수 제한 (유저당 최대 N개) | 미결 |
| 4 | 지역 표시 단위: `Store.regionDepth2` (구) 사용 확정 여부 | 미결 |
| 5 | CURATED 코스 편집 권한 — 어드민 전용 확정 | 어드민만 가능 (이번 구현에서 일반 사용자 편집 불가) |
| 6 | 코스 좋아요 기능 — 이번 구현 제외 확정 | **제외** (향후 기능) |
