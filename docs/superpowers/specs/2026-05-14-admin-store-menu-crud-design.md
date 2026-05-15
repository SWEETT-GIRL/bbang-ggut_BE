# Admin Store & Menu CRUD — Design Spec

**Date:** 2026-05-14
**Branch:** dev
**Issue:** Admin 가게/메뉴 CRUD API 구현

---

## 범위

1순위 — 가게 CRUD (4개 엔드포인트)
2순위 — 메뉴 CRUD (3개 엔드포인트)

어드민 웹(`bbang-ggut_admin_web`)의 API 계약(`Bakery`, `MenuItem` 타입)을 기준으로 맞춤.

---

## 패키지 구조

```
sweetgirl.croissant.admin
├── controller/
│   ├── AdminStoreController.java   @RequestMapping("/api/admin/stores")
│   └── AdminMenuController.java    @RequestMapping("/api/admin")
├── service/
│   ├── AdminStoreService.java
│   └── AdminMenuService.java
└── dto/
    ├── request/
    │   ├── AdminStoreCreateRequest.java
    │   ├── AdminStoreUpdateRequest.java
    │   ├── BusinessHourRequest.java      (WeeklyHours 내 각 요일 DTO)
    │   ├── AdminMenuCreateRequest.java
    │   └── AdminMenuUpdateRequest.java
    └── response/
        ├── AdminStoreResponse.java       (Bakery 타입 매핑)
        └── AdminMenuResponse.java        (MenuItem 타입 매핑)
```

---

## API 엔드포인트

### 1순위 — 가게 CRUD

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/admin/stores` | 가게 생성 |
| GET | `/api/admin/stores` | 가게 목록 조회 |
| GET | `/api/admin/stores/{id}` | 가게 상세 조회 |
| PUT | `/api/admin/stores/{id}` | 가게 수정 |

### 2순위 — 메뉴 CRUD

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/admin/stores/{storeId}/menus` | 메뉴 생성 |
| GET | `/api/admin/stores/{storeId}/menus` | 가게별 메뉴 목록 조회 |
| PUT | `/api/admin/menus/{id}` | 메뉴 수정 (soft delete 포함) |

---

## 요청/응답 계약

### AdminStoreCreateRequest / AdminStoreUpdateRequest

```json
{
  "name": "빵집이름",
  "address": "서울시 강남구...",
  "coord": { "latitude": 37.5172, "longitude": 127.0473 },
  "phone": "02-1234-5678",
  "kakaoPlaceId": "123456",
  "status": "active",
  "hours": {
    "mon": { "openHour": 9, "openMin": 0, "closeHour": 21, "closeMin": 0, "closed": false },
    "tue": { ... },
    "wed": { ... },
    "thu": { ... },
    "fri": { ... },
    "sat": { ... },
    "sun": { "openHour": 9, "openMin": 0, "closeHour": 21, "closeMin": 0, "closed": true }
  }
}
```

- `status: "active"` → `StoreStatus.ACTIVE`, `"inactive"` → `StoreStatus.DELETED`
- `archivedAt` PUT 요청 수신 시: null이면 ACTIVE, non-null이면 DELETED로 처리
- Create 시: `StoreSource.ADMIN_REGISTERED` 고정
- Create 시: `coord`에서 PostGIS Point 생성 (`GeometryFactory`), `District.fromAddress(address)`로 자동 설정
- Update 시: businessHours → 기존 전체 삭제 후 새로 insert (교체 방식)

### AdminStoreResponse (Bakery 타입)

```json
{
  "id": 1,
  "name": "빵집이름",
  "area": "강남구",
  "desc": null,
  "category": null,
  "alarmCount": 10,
  "rating": 0,
  "address": "서울시 강남구...",
  "phone": "02-1234-5678",
  "coord": { "latitude": 37.5172, "longitude": 127.0473 },
  "kakaoPlaceId": "123456",
  "status": "active",
  "photos": ["https://s3.../img.jpg"],
  "mainPhotoIndex": 0,
  "archivedAt": null,
  "createdAt": "2024-01-01T00:00:00",
  "hours": {
    "mon": { "openHour": 9, "openMin": 0, "closeHour": 21, "closeMin": 0, "closed": false },
    ...
  }
}
```

| 어드민 웹 필드 | BE 매핑 |
|---|---|
| `area` | `district.city` (district null이면 null) |
| `desc`, `category` | null (Store 엔티티에 필드 없음) |
| `rating` | 0 고정 |
| `alarmCount` | SubscribedStore 건수 (배치 조회) |
| `coord` | PostGIS Point — `.y`=latitude, `.x`=longitude |
| `status` | `ACTIVE`→`"active"`, `DELETED`→`"inactive"` |
| `archivedAt` | `status==DELETED ? updatedAt.toString() : null` |
| `photos` | StoreImageFile AWS key 목록 |
| `mainPhotoIndex` | 0 고정 |
| `hours` | StoreBusinessHour 목록 → WeeklyHours 변환 |

**WeeklyHours 변환:**
- `DayOfWeek.MONDAY` ↔ `"mon"`, `TUESDAY` ↔ `"tue"`, ..., `SUNDAY` ↔ `"sun"`
- `LocalTime` ↔ `openHour/openMin`, `closeHour/closeMin`
- `isClosed` ↔ `closed`

**목록 조회 쿼리 파라미터:**
- `keyword` (optional): name LIKE %keyword%
- `status` (optional): `active` | `inactive`
- `page` (default 0), `size` (default 20)
- 응답: `Page<AdminStoreResponse>` → 어드민 웹 `unwrapList`가 `.content` 읽음

### AdminMenuCreateRequest

```json
{
  "bakeryId": 1,
  "name": "소금빵",
  "price": "3000원",
  "desc": "설명",
  "category": "소금빵",
  "status": "active",
  "likes": 0,
  "photos": []
}
```

- `price`: `"3000원"` → `"원"` 제거 후 Integer 파싱
- `category`: MenuCategory.name으로 조회 → 없으면 에러

### AdminMenuUpdateRequest

```json
{
  "name": "소금빵",
  "price": "3000원",
  "desc": "설명",
  "category": "소금빵",
  "status": "active"
}
```

- `status: "inactive"` → `menu.softDelete()` (deletedAt = now)
- `status: "active"` → 필드 수정만, 삭제 복구 없음

### AdminMenuResponse (MenuItem 타입)

```json
{
  "id": 1,
  "bakeryId": 1,
  "name": "소금빵",
  "price": "3000원",
  "desc": "설명",
  "likes": 5,
  "category": "소금빵",
  "photos": [],
  "order": null,
  "status": "active",
  "createdAt": "2024-01-01T00:00:00"
}
```

| 어드민 웹 필드 | BE 매핑 |
|---|---|
| `bakeryId` | `menu.store.id` |
| `price` | `menu.price != null ? menu.price + "원" : null` |
| `desc` | `menu.description` |
| `likes` | `menu.recommendCount` |
| `category` | `menu.menuCategory.name` |
| `order` | null 고정 |
| `status` | `deletedAt == null ? "active" : "inactive"` |
| `photos` | MenuImageFile AWS key 목록 |

---

## 엔티티 변경

### Store
- `update(String name, String address, Point location, String phone, StoreStatus status)` 메서드 추가

### Menu
- `update(String name, Integer price, String description, MenuCategory menuCategory)` 메서드 추가
- `softDelete()` 메서드 추가: `deletedAt = LocalDateTime.now()`

---

## Repository 변경

### StoreRepository (추가)
```java
Page<Store> findAll(Pageable pageable);                                               // 이미 존재
Page<Store> findByNameContainingIgnoreCase(String name, Pageable pageable);
Page<Store> findByStatus(StoreStatus status, Pageable pageable);
Page<Store> findByNameContainingIgnoreCaseAndStatus(String name, StoreStatus status, Pageable pageable);
```

### StoreBusinessHourRepository (추가)
```java
void deleteByStoreId(Long storeId);
```

### SubscribedStoreRepository (추가)
```java
// store 목록의 alarmCount 배치 조회
@Query("SELECT ss.store.id, COUNT(ss) FROM SubscribedStore ss WHERE ss.store.id IN :storeIds GROUP BY ss.store.id")
List<Object[]> countByStoreIdIn(@Param("storeIds") List<Long> storeIds);
```

### MenuRepository (추가)
```java
List<Menu> findByStoreId(Long storeId);  // 기존에 있으면 재사용, 소프트 삭제 포함
```

### MenuCategoryRepository
- 기존 `findAll()` 재사용
- `findByName(String name)` 추가 필요

---

## 에러 코드

기존 코드 재사용:
- `StoreErrorCode.STORE_NOT_FOUND`
- `MenuErrorCode.MENU_NOT_FOUND`

신규 추가:
- `MenuErrorCode.MENU_CATEGORY_NOT_FOUND`

---

## 제외 범위 (이슈 외)
- DELETE 엔드포인트 (archive/restore via PUT은 포함)
- 사진 업로드 (`/api/admin/stores/{id}/photos`, `/api/admin/menus/{id}/photos`)
- 메뉴 순서 재정렬 (`/api/admin/stores/{storeId}/menus/reorder`)
- GET `/api/admin/menus` (전체 메뉴 조회)
- GET `/api/admin/menus/{id}` (단건 조회)
