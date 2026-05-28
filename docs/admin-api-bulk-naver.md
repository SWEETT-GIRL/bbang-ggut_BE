# 어드민 웹 통합 가이드 — 데이터 수집 파이프라인 API

빵긋 어드민에서 **Kakao 벌크 임포트** + **Naver Place 매칭/메뉴 크롤링** 을 컨트롤하기 위한 엔드포인트 모음.

- 베이스 URL: `http://localhost:8080` (로컬)
- 인증: JWT Bearer 헤더 (어드민 로그인 후 토큰)
- 응답 wrapper: 거의 모든 엔드포인트가 `{ success, code, message, data }` 형식
- 한 가지 예외: `/admin/images/presigned-url` 만 raw (어드민 웹에서 직접 안 씀)

---

## 1. 엔드포인트 요약표

| # | Method | Path | 용도 |
|---|---|---|---|
| **Kakao 벌크 임포트** | | | |
| 1-1 | `POST` | `/api/admin/stores/bulk-import` | 임포트 시작 |
| 1-2 | `GET`  | `/api/admin/stores/bulk-import/progress` | 진행상황 (폴링) |
| 1-3 | `GET`  | `/api/admin/stores/bulk-import/failed` | 실패한 동 목록 |
| 1-4 | `POST` | `/api/admin/stores/bulk-import/stop` | 중단 요청 |
| **Naver enrich — 상태 & 큐** | | | |
| 2-1 | `GET`  | `/api/admin/stores/naver-enrich-queue` | 미매칭 큐 목록 |
| 2-2 | `GET`  | `/api/admin/stores/naver-enrich-stats` | 매칭/대기/실패/running 카운트 |
| 2-3 | `GET`  | `/api/admin/stores/{id}/naver-enrich-item` | 단일 가게 큐 아이템 (Python `--store-id` 모드용) |
| **Naver enrich — 수동 액션** | | | |
| 2-4 | `PUT`  | `/api/admin/stores/{id}/naver-place-id` | 매칭 ID 저장 (수동 매칭) |
| 2-5 | `DELETE` | `/api/admin/stores/{id}/naver-place-id` | 매칭 초기화 (잘못 매칭됨) |
| 2-6 | `POST` | `/api/admin/stores/{id}/naver-enrich-failed` | 실패 마킹 (7일 throttle) |
| 2-7 | `POST` | `/api/admin/stores/{id}/naver-enrich-retry` | throttle 강제 해제 |
| **Naver enrich — 자동 실행 (subprocess)** | | | |
| 2-8 | `POST` | `/api/admin/stores/{id}/naver-enrich-now` | **단일 가게 즉시 enrich (sync, 최대 180s)** |
| 2-9 | `POST` | `/api/admin/stores/naver-enrich-start` | **일괄 enrich 백그라운드 시작** |
| 2-10 | `POST` | `/api/admin/stores/naver-enrich-stop` | **일괄 enrich 중단 (SIGTERM)** |
| **참고** | | | |
| 3-1 | `GET`  | `/api/admin/stores/{id}` | 가게 상세 (naverPlaceId 포함) |

---

## 2. Kakao 벌크 임포트

전국 3,557개 동에 대해 Kakao Local API 에서 빵집/카페를 끌어와 DB 에 적재. 백그라운드 비동기 실행.

### 2-1. 임포트 시작

```
POST /api/admin/stores/bulk-import
```

- 바디 없음
- 이미 실행 중이면 **409** `BULK_IMPORT_ALREADY_RUNNING`

#### Response 200

```json
{
  "success": true,
  "data": {
    "totalDongs": 3557,
    "pendingDongs": 3557,
    "completedDongs": 0
  }
}
```

#### Response 409 (이미 실행 중)

```json
{
  "success": false,
  "code": "BULK_IMPORT_ALREADY_RUNNING",
  "message": "이미 실행 중입니다."
}
```

---

### 2-2. 진행상황 조회 (폴링)

```
GET /api/admin/stores/bulk-import/progress
```

5~10초 간격으로 폴링. `running=true` 일 때만 폴링하면 효율적.

#### Response 200

```json
{
  "success": true,
  "data": {
    "running": true,
    "totalDongs": 3557,
    "completedDongs": 1024,
    "failedDongs": 12,
    "inProgressDongs": 1,
    "pendingDongs": 2520,
    "totalInserted": 47823
  }
}
```

| 필드 | 의미 |
|---|---|
| `running` | 오케스트레이터 실행 중 여부 |
| `totalDongs` | 전체 동 수 (고정 3557) |
| `completedDongs` | 성공 처리된 동 |
| `failedDongs` | 실패한 동 (수동 재시도 가능) |
| `inProgressDongs` | 처리 중인 동 (풀 사이즈 1이라 0 또는 1) |
| `pendingDongs` | 미처리 동 |
| `totalInserted` | 누적 store INSERT 개수 (dedup 제외) |

---

### 2-3. 실패한 동 목록

```
GET /api/admin/stores/bulk-import/failed
```

#### Response 200

```json
{
  "success": true,
  "data": [
    {
      "region1st": "서울특별시",
      "region2nd": "강남구",
      "region3rd": "역삼동",
      "errorMessage": "Kakao API timeout",
      "lastAttemptedAt": "2026-05-28T03:24:15"
    }
  ]
}
```

#### 재시도 방법

별도 retry 엔드포인트 없음. `POST /api/admin/stores/bulk-import` 다시 호출하면 PENDING + FAILED 동을 자동으로 다시 처리합니다.

---

### 2-4. 중단 요청

```
POST /api/admin/stores/bulk-import/stop
```

- 바디 없음
- 진행 중인 동은 마치고 종료 (즉시 중단 아님)

#### Response 200

```json
{ "success": true, "data": null }
```

응답 받자마자 progress 폴링 계속 → `running=false` 되면 "중단됨" 상태로 표시.

---

## 3. Naver Enrich

Kakao 임포트로 들어온 store 의 `naverPlaceId` 매칭 + 메뉴/이미지 보강.

> ⚠️ 실제 크롤링은 별도 Python 스크립트 (`scripts/naver_menu_scraper.py`) 가 담당. 어드민 웹은 **모니터링 + 수동 액션** 만.

### 큐 조건 (백엔드 쿼리)

```
ACTIVE status
AND naverPlaceId IS NULL
AND (naverMatchedAt IS NULL OR naverMatchedAt < (now - 7일))
```

### 상태 전이

```
미시도 (naverPlaceId NULL, naverMatchedAt NULL)
  ├─ 매칭 성공 → 매칭됨 (naverPlaceId 값, naverMatchedAt 값)
  └─ 매칭 실패 → 실패 throttle (naverPlaceId NULL, naverMatchedAt 값)
        └─ 7일 후 자동 재진입 OR 수동 retry
```

---

### 3-1. 매칭 큐 조회

```
GET /api/admin/stores/naver-enrich-queue?page=0&size=100
```

#### Query Params

| 이름 | 타입 | 기본값 |
|---|---|---|
| `page` | int | 0 |
| `size` | int | 100 |

#### Response 200

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 42,
        "name": "후와후와",
        "address": "서울 강남구 신사동 123-45",
        "kakaoPlaceId": "27194853",
        "lat": 37.521,
        "lng": 127.024,
        "existingMenuCount": 0,
        "lastAttemptedAt": null
      }
    ],
    "totalElements": 4823,
    "totalPages": 49,
    "number": 0,
    "size": 100,
    "first": true,
    "last": false,
    "empty": false
  }
}
```

| 필드 | 의미 |
|---|---|
| `existingMenuCount` | 0 → 메뉴 크롤링 대상 / >0 → ID 만 채우기 |
| `lastAttemptedAt` | null = 미시도 / 값 = 7일 throttle 중 |

---

### 3-2. 매칭 통계 (대시보드용)

```
GET /api/admin/stores/naver-enrich-stats
```

#### Response 200

```json
{
  "success": true,
  "data": {
    "running": false,
    "matched": 12304,
    "pending": 4536,
    "failed": 287,
    "total": 17127
  }
}
```

| 필드 | 정의 |
|---|---|
| `running` | 일괄 enrich 백그라운드 실행 중 여부 (`POST /naver-enrich-start` 후 true) |
| `matched` | naverPlaceId 가 있는 ACTIVE store |
| `pending` | naverPlaceId NULL + naverMatchedAt NULL (미시도) |
| `failed` | naverPlaceId NULL + naverMatchedAt 값 (실패 throttle 중) |
| `total` | ACTIVE store 전체 (matched + pending + failed + 약간의 race 차이) |

> `pending + failed` 가 큐 사이즈와 거의 일치 (`failed` 중 throttle 만료된 것은 큐에도 포함).
> `running=true` 일 때 5초 폴링으로 진행률 시각화 가능.

---

### 3-3. 매칭 ID 저장

```
PUT /api/admin/stores/{storeId}/naver-place-id
```

#### Request Body

```json
{ "naverPlaceId": "12345678" }
```

- `naverPlaceId`: `@NotBlank`

#### Response 200

```json
{ "success": true, "data": null }
```

#### 에러

| Status | Code | 상황 |
|---|---|---|
| 404 | `STORE_NOT_FOUND` | storeId 없음 |
| 500 | (DB unique) | 다른 store 가 이미 같은 naverPlaceId 사용 중 — 운영자 개입 필요 |

---

### 3-4. 매칭 초기화 (잘못 매칭됨)

```
DELETE /api/admin/stores/{storeId}/naver-place-id
```

- 바디 없음
- `naverPlaceId` + `naverMatchedAt` 둘 다 null 로 변경
- 다음 사이클에 큐 진입

#### Response 200

```json
{ "success": true, "data": null }
```

---

### 3-5. 실패 마킹

```
POST /api/admin/stores/{storeId}/naver-enrich-failed
```

- 바디 없음
- `naverMatchedAt = now` 만 기록 → 7일간 큐에서 제외
- 운영자가 "이 가게는 네이버에 없음" 수동 마킹 시 사용 (또는 크롤러 자동 호출)

#### Response 200

```json
{ "success": true, "data": null }
```

---

### 3-6. 재시도 (throttle 해제)

```
POST /api/admin/stores/{storeId}/naver-enrich-retry
```

- 바디 없음
- `naverMatchedAt = null` 만 변경 (`naverPlaceId` 는 건드리지 않음)
- 실패 throttle 중인 가게를 즉시 큐에 다시 노출

#### Response 200

```json
{ "success": true, "data": null }
```

> ⚠️ `naverPlaceId` 가 이미 있는 store 에는 효과 없음. 그 경우엔 3-4 (DELETE) 를 써야 큐에 들어감.

---

### 3-7. 단일 가게 enrich-item 조회 (Python `--store-id` 용)

```
GET /api/admin/stores/{storeId}/naver-enrich-item
```

큐 아이템과 동일한 shape 으로 단일 가게 정보 반환. 어드민 웹은 보통 안 쓰지만 호환성을 위해 노출.

#### Response 200

```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "후와후와",
    "address": "서울 강남구 ...",
    "kakaoPlaceId": "27194853",
    "lat": 37.521,
    "lng": 127.024,
    "existingMenuCount": 0,
    "lastAttemptedAt": null
  }
}
```

---

### 3-8. ⭐ 단일 가게 즉시 enrich (Option A)

```
POST /api/admin/stores/{storeId}/naver-enrich-now
```

**어드민 웹의 행 단위 [Enrich] 버튼**. Python 크롤러 subprocess 를 띄워 매칭 + 메뉴 보강. **sync 응답** — 최대 180초 대기.

- 바디 없음
- 내부적으로: `python3 scripts/naver_menu_scraper.py --store-id N --server BACKEND_URL`
- 완료 후 DB 재조회한 결과 반환

#### Response 200 (매칭 성공)

```json
{
  "success": true,
  "data": {
    "matched": true,
    "naverPlaceId": "12345678",
    "naverMatchedAt": "2026-05-28T03:24:15Z",
    "menuCount": 14,
    "subprocessExitCode": 0,
    "message": "매칭 성공 (naverPlaceId=12345678, 메뉴 14개)"
  }
}
```

#### Response 200 (매칭 실패 — subprocess 정상 종료)

```json
{
  "success": true,
  "data": {
    "matched": false,
    "naverPlaceId": null,
    "naverMatchedAt": "2026-05-28T03:24:15Z",
    "menuCount": 0,
    "subprocessExitCode": 0,
    "message": "매칭 실패 또는 신뢰 매칭 못 찾음"
  }
}
```

#### Response 200 (타임아웃 — 비정상 종료)

```json
{
  "success": true,
  "data": {
    "matched": false,
    "naverPlaceId": null,
    "naverMatchedAt": null,
    "menuCount": 0,
    "subprocessExitCode": -1,
    "message": "타임아웃 (180초)"
  }
}
```

> ⚠️ subprocess 실패도 `success: true` 로 응답합니다 (DB 상태 자체는 유효함). `matched` boolean + `subprocessExitCode` 로 어드민 UI 에서 판단하세요.
> ⚠️ 백엔드 서버에 `python3` 과 `playwright` (+ Chromium) 가 깔려있어야 합니다.

---

### 3-9. ⭐ 일괄 enrich 시작 (Option B)

```
POST /api/admin/stores/naver-enrich-start
```

**대시보드의 [전체 매칭 시작] 버튼**. Python 크롤러를 `--loop` 모드로 백그라운드 실행. 큐가 빌 때까지 처리.

- 바디 없음
- 이미 실행 중이면 **409** `STORE409_2`

#### Response 200 (시작)

```json
{ "success": true, "data": null }
```

#### Response 409 (이미 실행 중)

```json
{
  "success": false,
  "code": "STORE409_2",
  "message": "네이버 일괄 매칭이 이미 실행 중입니다."
}
```

#### 진행상황 추적

별도 progress 엔드포인트 없음 — `GET /naver-enrich-stats` 의 `running` + `matched` 카운트가 증가하는 걸로 폴링. 5초 권장.

```typescript
// React Query 폴링 예시
const { data: stats } = useQuery({
  queryKey: ['naver-enrich-stats'],
  queryFn: fetchNaverEnrichStats,
  refetchInterval: (q) => q.state.data?.data?.running ? 5000 : 30000,
});
```

#### subprocess 로그

`/tmp/naver-enrich-bulk.log` 에 stdout/stderr 기록됨. 어드민 웹에서 직접 보지 않지만 디버깅용.

---

### 3-10. ⭐ 일괄 enrich 중단

```
POST /api/admin/stores/naver-enrich-stop
```

- 바디 없음
- 백그라운드 Python 프로세스에 SIGTERM 전송
- 현재 처리 중인 가게는 끝까지 마치고 종료
- 폴링 시 `running=false` 로 전환됨

#### Response 200

```json
{ "success": true, "data": null }
```

---

## 4. 참고: 가게 상세 (확장됨)

```
GET /api/admin/stores/{storeId}
```

기존 응답에 `naverPlaceId`, `naverMatchedAt` 추가됨.

#### Response 200 (발췌)

```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "후와후와",
    "address": "...",
    "kakaoPlaceId": "27194853",
    "naverPlaceId": "12345678",
    "naverMatchedAt": "2026-05-28T03:24:15Z",
    "coord": { "latitude": 37.521, "longitude": 127.024 },
    "status": "active",
    "photos": ["..."]
    // ... 기존 필드 동일
  }
}
```

`naverPlaceId`, `naverMatchedAt` 둘 다 null 가능.

---

## 5. TypeScript 타입

```typescript
// ────────── Wrapper ──────────
type ApiResponse<T> = {
  success: boolean;
  code: string;
  message: string;
  data: T | null;
};

type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
};

// ────────── Bulk import ──────────
type BulkImportStartResponse = {
  totalDongs: number;
  pendingDongs: number;
  completedDongs: number;
};

type BulkImportProgressResponse = {
  running: boolean;
  totalDongs: number;
  completedDongs: number;
  failedDongs: number;
  inProgressDongs: number;
  pendingDongs: number;
  totalInserted: number;
};

type FailedDongResponse = {
  region1st: string;
  region2nd: string;
  region3rd: string;
  errorMessage: string;
  lastAttemptedAt: string; // ISO LocalDateTime
};

// ────────── Naver enrich ──────────
type NaverEnrichQueueItem = {
  id: number;
  name: string;
  address: string;
  kakaoPlaceId: string | null;
  lat: number | null;
  lng: number | null;
  existingMenuCount: number;
  lastAttemptedAt: string | null; // ISO Instant
};

type NaverEnrichStatsResponse = {
  running: boolean;        // 일괄 enrich 백그라운드 실행 중
  matched: number;
  pending: number;
  failed: number;
  total: number;
};

type NaverPlaceIdUpdateRequest = {
  naverPlaceId: string;
};

type NaverEnrichRunResponse = {
  matched: boolean;
  naverPlaceId: string | null;
  naverMatchedAt: string | null;       // ISO Instant
  menuCount: number;
  subprocessExitCode: number;          // 0=정상, -1=타임아웃/오류
  message: string;
};

// ────────── Store detail (관련 부분) ──────────
type AdminStoreResponse = {
  id: number;
  name: string;
  area: string;
  desc: string | null;
  category: string | null;
  alarmCount: number;
  rating: number;
  address: string;
  phone: string | null;
  coord: { latitude: number; longitude: number } | null;
  kakaoPlaceId: string | null;
  naverPlaceId: string | null;       // ← 추가됨
  naverMatchedAt: string | null;     // ← 추가됨 (ISO Instant)
  status: 'active' | 'inactive';
  photos: string[];                  // S3 awsKey 배열
  mainPhotoIndex: number;
  archivedAt: string | null;
  createdAt: string | null;
  hours: Record<'mon'|'tue'|'wed'|'thu'|'fri'|'sat'|'sun', DayHoursResponse>;
};

type DayHoursResponse = {
  openHour: number;
  openMin: number;
  closeHour: number;
  closeMin: number;
  closed: boolean;
};
```

---

## 6. 어드민 웹 UI 흐름 제안

### 화면 A: 데이터 수집 대시보드

```
┌──────────────────────────────────────────────────────────────────┐
│  📦 Kakao 벌크 임포트                                              │
│  ─────────────────────────────────                                │
│  진행률: ████████░░░░░  1024 / 3557 (28.8%)                       │
│  • 완료: 1024  • 진행 중: 1  • 대기: 2520  • 실패: 12             │
│  • 누적 적재 store: 47,823                                        │
│                                                                  │
│  [▶ 시작/재시작] [⏸ 중단] [실패 동 12개 보기 ▾]                  │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│  🔍 Naver 매칭 (전체 17,127 stores 기준)                          │
│  ─────────────────────────────────                                │
│  [Donut chart: matched 72% · pending 26% · failed 2%]            │
│  • 매칭 완료: 12,304                                              │
│  • 대기 중:   4,536                                               │
│  • throttle: 287                                                  │
│                                                                  │
│  ⚡ 일괄 매칭: 진행 중 ████░░ 매칭 4,234 / 4,536 (회수율 93%)       │
│  [▶ 일괄 매칭 시작]  [⏸ 일괄 매칭 중단]  [매칭 큐 보기 →]         │
└──────────────────────────────────────────────────────────────────┘
```

#### 데이터 소스
- 위쪽 카드: `GET /api/admin/stores/bulk-import/progress` (5초 폴링, running=true 시)
- 아래쪽 카드: `GET /api/admin/stores/naver-enrich-stats` (running=true 면 5초, 아니면 30초)

#### 일괄 매칭 컨트롤
- **[▶ 일괄 매칭 시작]** → `POST /naver-enrich-start` → `running=true` 가 되면 진행 바 표시
- **[⏸ 일괄 매칭 중단]** → `POST /naver-enrich-stop` → 폴링 시 `running=false` 로 전환
- 409 (이미 실행 중) → 토스트 "이미 진행 중"
- 진행률 = `matched - 시작 시점의 matched` 로 누적 계산 (또는 단순히 matched 카운트가 올라가는 거 보여주기)

#### 폴링 전략 (React Query)

```typescript
const { data: progress } = useQuery({
  queryKey: ['bulk-import-progress'],
  queryFn: fetchBulkImportProgress,
  refetchInterval: (q) => q.state.data?.data?.running ? 5000 : false,
});

const { data: stats } = useQuery({
  queryKey: ['naver-enrich-stats'],
  queryFn: fetchNaverEnrichStats,
  refetchInterval: 30000,
});
```

---

### 화면 B: 실패한 동 리스트

```
┌────────────────────────────────────────────────────────────────┐
│  실패한 동 (12개)                                                │
│                                                                 │
│  | 시/도   | 시/군/구 | 동      | 에러 메시지              | 시각 │
│  | 서울    | 강남구   | 역삼동   | Kakao API timeout      | ... │
│  | 부산    | 해운대구 | 우동     | KAKAO_RATE_LIMITED     | ... │
│                                                                 │
│  ※ "벌크 임포트 시작" 다시 누르면 자동 재시도됩니다              │
└────────────────────────────────────────────────────────────────┘
```

---

### 화면 C: Naver 매칭 큐

```
┌──────────────────────────────────────────────────────────────────────┐
│  Naver 매칭 큐 (4,823건)            [페이지: ◀  1/49  ▶]              │
│                                                                      │
│  필터: ⚪ 미시도 만  ⚪ throttle 중 포함  ⚪ 메뉴 보유만               │
│                                                                      │
│  | ID  | 이름        | 주소         | 메뉴 | 상태       | 액션      │
│  | 42  | 후와후와     | 강남구 신사동 | 0    | 미시도      | [⋮]      │
│  | 51  | 르빵         | 마포구 연남동 | 12   | 미시도      | [⋮]      │
│  | 89  | 베이커블     | 서초구       | 3    | 5일 전 실패 | [⋮]      │
└──────────────────────────────────────────────────────────────────────┘
```

#### [⋮] 액션 메뉴

| 액션 | 호출 엔드포인트 | 동작 |
|---|---|---|
| **⚡ 즉시 매칭 실행 (Enrich)** | **`POST /api/admin/stores/{id}/naver-enrich-now`** | **버튼 한 번 → Python 크롤러 sync 실행 (최대 180s) → 결과 표시** |
| 가게 상세 보기 | `GET /api/admin/stores/{id}` | 모달로 표시 |
| 수동 매칭 (네이버 ID 입력) | `PUT /api/admin/stores/{id}/naver-place-id` | 모달 입력 → PUT → 큐에서 제거 |
| 매칭 불가 마킹 | `POST /api/admin/stores/{id}/naver-enrich-failed` | 7일 throttle |
| (throttle 중인 경우만) 재시도 | `POST /api/admin/stores/{id}/naver-enrich-retry` | 즉시 큐 복귀 |

#### 행 단위 [⚡ Enrich] 버튼 동작

```typescript
async function enrichStore(storeId: number) {
  setLoading(storeId, true);  // 행에 spinner
  try {
    const result = await callApi<NaverEnrichRunResponse>(
      `/api/admin/stores/${storeId}/naver-enrich-now`,
      { method: 'POST' }
    );
    if (result.matched) {
      toast.success(`매칭 성공: ${result.naverPlaceId} (메뉴 ${result.menuCount}개)`);
    } else {
      toast.error(result.message);
    }
    queryClient.invalidateQueries(['naver-enrich-queue']);
  } finally {
    setLoading(storeId, false);
  }
}
```

> UX 주의: 응답까지 10~30초 걸릴 수 있으니 로딩 spinner 필수.

---

### 화면 D: 가게 상세 (Naver 정보 패널 추가)

```
┌──────────────────────────────────────────────────────────────────┐
│  후와후와 (ID: 42)                                                │
│                                                                  │
│  주소: 서울 강남구 신사동 123-45                                  │
│  좌표: 37.521, 127.024                                            │
│  카카오 ID: 27194853                                              │
│                                                                  │
│  ─── 네이버 매칭 ───                                              │
│  네이버 Place ID: 12345678 ✓                                      │
│  마지막 시도:    2026-05-28 03:24                                 │
│  [네이버에서 보기] [매칭 초기화]  ← DELETE                        │
│                                                                  │
│  ─── 또는 미매칭 시 ───                                            │
│  네이버 Place ID: 없음                                            │
│  마지막 시도:    5일 전 실패 (throttle 중)                        │
│  [수동 매칭]    [즉시 재시도]  ← retry                            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. 매칭 상태 분기 (어드민 웹 로직)

```typescript
function getMatchStatus(store: { naverPlaceId: string | null; naverMatchedAt: string | null }) {
  if (store.naverPlaceId) return 'MATCHED';
  if (store.naverMatchedAt) return 'FAILED_THROTTLED';
  return 'PENDING';
}

function isThrottleActive(naverMatchedAt: string | null): boolean {
  if (!naverMatchedAt) return false;
  const matchedTime = new Date(naverMatchedAt).getTime();
  const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000;
  return Date.now() - matchedTime < SEVEN_DAYS;
}
```

---

## 8. 에러 처리 공통 패턴

```typescript
async function callApi<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${getToken()}`,
      ...init?.headers,
    },
  });
  const body: ApiResponse<T> = await res.json();
  if (!body.success) {
    throw new ApiError(body.code, body.message);
  }
  return body.data as T;
}
```

#### 주요 에러 코드

| Code | 의미 | 권장 처리 |
|---|---|---|
| `STORE_NOT_FOUND` | 가게 없음 | "가게가 없습니다" 토스트 |
| `BULK_IMPORT_ALREADY_RUNNING` | 이미 임포트 중 | "이미 진행 중입니다" 모달 + progress 화면 이동 |
| `MENU_NOT_FOUND` | 메뉴 없음 | "메뉴가 없습니다" |
| `MENU_CATEGORY_NOT_FOUND` | MenuKind 에 없는 카테고리 | 카테고리 입력 검증 |

---

## 9. 운영 주의사항

1. **벌크 임포트는 매우 오래 걸림** — 약 16~20시간 예상. 어드민에서 시작만 누르고 닫아도 백엔드는 계속 돔.
2. **Kakao API 일일 쿼터** — 무료 한도 초과 시 동마다 실패. 실패 동 리스트에서 패턴 확인 후 다음날 재시도.
3. **Naver 크롤링은 어드민에서 직접 안 함** — Python 스크립트를 운영자가 별도로 실행. 어드민은 모니터링 + 수동 보정만.
4. **실패 throttle 기간**: 현재 코드 상 **7일** 고정. 변경 시 `AdminStoreService.NAVER_RETRY_AFTER_DAYS` 수정.
5. **인증** — 모든 엔드포인트 어드민 권한 필요 가정 (`SecurityConfig` 확인 필요).

---

## 10. 사전 작업 (BE 측 — 어드민 웹 작업 전 완료 필요)

- [x] `store` 테이블에 `naver_place_id`, `naver_matched_at` 컬럼 추가 (이미 적용됨, ddl-auto=update)
- [x] 신규 엔드포인트 10개 배포 (Naver enrich 9개 + AdminStoreResponse 확장)
- [ ] **백엔드 서버에 Python + Playwright + Chromium 설치 필요** (Option A/B subprocess 용)
  ```bash
  pip3 install requests playwright
  python3 -m playwright install chromium
  ```
- [ ] Swagger UI (`/swagger-ui/index.html`) 에서 새 엔드포인트 응답 sample 검토
- [ ] 어드민 웹 빌드용 OpenAPI spec export (`/v3/api-docs`)

---

## 11. Python 환경 (Option A/B 자동 실행에 필수)

`naver-enrich-now` (단일) 와 `naver-enrich-start` (일괄) 는 백엔드가 Python subprocess 를 띄우는 구조. 따라서 **백엔드 서버 자체에 Python 환경이 깔려있어야 작동**합니다.

### 로컬 dev 환경

```bash
cd /Users/danalim/bbanggut/bbang-ggut_BE
pip3 install requests playwright
python3 -m playwright install chromium
```

설치 확인:
```bash
which python3
python3 -c "import playwright; print('OK')"
```

### 설정 (`application.properties`)

```properties
naver.crawler.python=python3                   # 시스템 python 바이너리 경로
naver.crawler.script=scripts/naver_menu_scraper.py
naver.crawler.backend-url=http://localhost:8080
naver.crawler.single-timeout-seconds=180       # 단일 enrich 타임아웃
naver.crawler.bulk-batch=50                    # 일괄 enrich 배치 크기
```

### 운영 환경 (선택)

- 백엔드와 같은 Docker 이미지에 Python + Chromium 포함 → 이미지 크기 ~300MB 증가
- 또는 Python 워커를 별도 컨테이너로 분리 (orchestrator 가 RPC 로 호출하는 식으로 리팩토링 필요)
- MVP 단계에서는 로컬/dev 환경에서만 작동시키고, prod 에서는 Option A/B 비활성화하는 것도 옵션
