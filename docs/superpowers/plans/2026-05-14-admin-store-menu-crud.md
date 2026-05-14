# Admin Store & Menu CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sweetgirl.croissant.admin` 패키지에 어드민 웹(`bbang-ggut_admin_web`) API 계약에 맞는 가게·메뉴 CRUD 엔드포인트 7개를 구현한다.

**Architecture:** 별도 `admin` 패키지(controller/service/dto)를 신설하고, 기존 `store`/`menu` 도메인의 엔티티·레포지토리를 재사용한다. Store soft-delete는 `StoreStatus.DELETED` 매핑, Menu soft-delete는 `deletedAt` 매핑. 어드민 웹 타입 계약(`Bakery`, `MenuItem`)에 맞게 응답 필드를 변환한다.

**Tech Stack:** Spring Boot 4, JPA, Mockito (unit tests), JTS GeometryFactory (PostGIS Point 생성)

**Spec:** `docs/superpowers/specs/2026-05-14-admin-store-menu-crud-design.md`

---

## 파일 맵

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `store/entity/Store.java` | `update()` 메서드 추가 |
| `menu/entity/Menu.java` | `update()`, `softDelete()` 메서드 추가 |
| `store/repository/StoreRepository.java` | `Page<Store>` 반환 admin 조회 메서드 3개 추가 |
| `store/repository/StoreBusinessHourRepository.java` | `deleteByStoreId()` 추가 |
| `store/repository/SubscribedStoreRepository.java` | `countByStoreId()`, `countByStoreIdIn()` 추가 |
| `store/repository/StoreImageFileRepository.java` | `findByStoreIdIn()` 추가 |
| `menu/repository/MenuRepository.java` | `findByStoreIdIncludingDeleted()` 추가 |

### 신규
| 파일 | 역할 |
|------|------|
| `admin/dto/request/BusinessHourRequest.java` | 요일별 영업시간 DTO |
| `admin/dto/request/AdminStoreCreateRequest.java` | 가게 생성 요청 |
| `admin/dto/request/AdminStoreUpdateRequest.java` | 가게 수정 요청 |
| `admin/dto/request/AdminMenuCreateRequest.java` | 메뉴 생성 요청 |
| `admin/dto/request/AdminMenuUpdateRequest.java` | 메뉴 수정 요청 |
| `admin/dto/response/AdminStoreResponse.java` | 가게 응답 (Bakery 타입) |
| `admin/dto/response/AdminMenuResponse.java` | 메뉴 응답 (MenuItem 타입) |
| `admin/service/AdminStoreService.java` | 가게 CRUD 비즈니스 로직 |
| `admin/service/AdminMenuService.java` | 메뉴 CRUD 비즈니스 로직 |
| `admin/controller/AdminStoreController.java` | `/api/admin/stores` 엔드포인트 |
| `admin/controller/AdminMenuController.java` | `/api/admin/stores/{id}/menus`, `/api/admin/menus/{id}` |

### 테스트
| 파일 | 역할 |
|------|------|
| `admin/service/AdminStoreServiceTest.java` | AdminStoreService 단위 테스트 |
| `admin/service/AdminMenuServiceTest.java` | AdminMenuService 단위 테스트 |

---

## Task 1: 엔티티 Update 메서드 추가

**Files:**
- Modify: `src/main/java/sweetgirl/croissant/store/entity/Store.java`
- Modify: `src/main/java/sweetgirl/croissant/menu/entity/Menu.java`

- [ ] **Step 1: Store에 update() 메서드 추가**

`Store.java`의 `@Builder` 생성자 아래에 추가:

```java
public void update(String name, String address, Point location, District district,
		String phone, StoreStatus status) {
	if (name != null) {
		this.name = name;
	}
	if (address != null) {
		this.address = address;
	}
	if (location != null) {
		this.location = location;
	}
	if (district != null) {
		this.district = district;
	}
	if (phone != null) {
		this.phone = phone;
	}
	if (status != null) {
		this.status = status;
	}
}
```

`Store.java` import에 추가:
```java
import sweetgirl.croissant.store.enums.District;
```

- [ ] **Step 2: Menu에 update(), softDelete() 메서드 추가**

`Menu.java`의 `decrementRecommendCount()` 아래에 추가:

```java
public void update(String name, Integer price, String description, MenuCategory menuCategory) {
	if (name != null) {
		this.name = name;
	}
	if (price != null) {
		this.price = price;
	}
	if (description != null) {
		this.description = description;
	}
	if (menuCategory != null) {
		this.menuCategory = menuCategory;
	}
}

public void softDelete() {
	this.deletedAt = LocalDateTime.now();
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/store/entity/Store.java \
        src/main/java/sweetgirl/croissant/menu/entity/Menu.java
git commit -m "feat: add update/softDelete methods to Store and Menu entities"
```

---

## Task 2: Repository 추가

**Files:**
- Modify: `src/main/java/sweetgirl/croissant/store/repository/StoreRepository.java`
- Modify: `src/main/java/sweetgirl/croissant/store/repository/StoreBusinessHourRepository.java`
- Modify: `src/main/java/sweetgirl/croissant/store/repository/SubscribedStoreRepository.java`
- Modify: `src/main/java/sweetgirl/croissant/store/repository/StoreImageFileRepository.java`
- Modify: `src/main/java/sweetgirl/croissant/menu/repository/MenuRepository.java`

- [ ] **Step 1: StoreRepository에 Page 반환 admin 조회 메서드 추가**

`StoreRepository.java`에 추가 (기존 메서드 아래):

```java
import org.springframework.data.domain.Page;

Page<Store> findAll(Pageable pageable); // JpaRepository에서 이미 상속, 명시적 선언 불필요

Page<Store> findByNameContainingIgnoreCase(String name, Pageable pageable);

Page<Store> findByStatus(StoreStatus status, Pageable pageable);

Page<Store> findByNameContainingIgnoreCaseAndStatus(String name, StoreStatus status, Pageable pageable);
```

> `findAll(Pageable)` 는 이미 `JpaRepository`에서 제공되므로 추가하지 않아도 된다. 나머지 3개만 추가.

- [ ] **Step 2: StoreBusinessHourRepository에 deleteByStoreId 추가**

```java
void deleteByStoreId(Long storeId);
```

- [ ] **Step 3: SubscribedStoreRepository에 배치 count 메서드 추가**

```java
long countByStoreId(Long storeId);

@Query("SELECT ss.store.id, COUNT(ss) FROM SubscribedStore ss "
		+ "WHERE ss.store.id IN :storeIds GROUP BY ss.store.id")
List<Object[]> countByStoreIdIn(@Param("storeIds") List<Long> storeIds);
```

import 추가:
```java
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 4: StoreImageFileRepository에 배치 조회 추가**

```java
List<StoreImageFile> findByStoreIdIn(List<Long> storeIds);
```

- [ ] **Step 5: MenuRepository에 soft-delete 포함 전체 조회 추가**

```java
@Query("""
		SELECT m FROM Menu m
		JOIN FETCH m.menuCategory
		WHERE m.store.id = :storeId
		ORDER BY m.createdAt DESC
		""")
List<Menu> findByStoreIdIncludingDeleted(@Param("storeId") Long storeId);
```

- [ ] **Step 6: 빌드 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/store/repository/ \
        src/main/java/sweetgirl/croissant/menu/repository/MenuRepository.java
git commit -m "feat: add admin query methods to repositories"
```

---

## Task 3: Admin DTOs

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/dto/request/BusinessHourRequest.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/request/AdminStoreCreateRequest.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/request/AdminStoreUpdateRequest.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/request/AdminMenuCreateRequest.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/request/AdminMenuUpdateRequest.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/response/AdminStoreResponse.java`
- Create: `src/main/java/sweetgirl/croissant/admin/dto/response/AdminMenuResponse.java`

- [ ] **Step 1: BusinessHourRequest 생성**

```java
package sweetgirl.croissant.admin.dto.request;

public record BusinessHourRequest(
		int openHour,
		int openMin,
		int closeHour,
		int closeMin,
		boolean closed) {
}
```

- [ ] **Step 2: AdminStoreCreateRequest 생성**

```java
package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminStoreCreateRequest(
		@NotBlank String name,
		@NotBlank String address,
		@NotNull CoordRequest coord,
		String phone,
		String kakaoPlaceId,
		@NotBlank String status,
		Map<String, BusinessHourRequest> hours) {

	public record CoordRequest(double latitude, double longitude) {
	}
}
```

- [ ] **Step 3: AdminStoreUpdateRequest 생성**

```java
package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

public record AdminStoreUpdateRequest(
		String name,
		String address,
		CoordRequest coord,
		String phone,
		String status,
		String archivedAt,
		Map<String, BusinessHourRequest> hours) {

	public record CoordRequest(double latitude, double longitude) {
	}
}
```

> **archivedAt 처리 규칙:** `archivedAt`이 non-null non-empty string이면 → `StoreStatus.DELETED`. `status: "active"`이면 → `StoreStatus.ACTIVE`. 어드민 웹의 `restore({ archivedAt: null })` 호출은 `status: "active"`를 포함한 전체 수정 폼을 통해 대신 처리한다.

- [ ] **Step 4: AdminMenuCreateRequest 생성**

```java
package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminMenuCreateRequest(
		Long bakeryId,
		@NotBlank String name,
		String price,
		String desc,
		String category,
		String status) {
}
```

- [ ] **Step 5: AdminMenuUpdateRequest 생성**

```java
package sweetgirl.croissant.admin.dto.request;

public record AdminMenuUpdateRequest(
		String name,
		String price,
		String desc,
		String category,
		String status) {
}
```

- [ ] **Step 6: AdminStoreResponse 생성**

```java
package sweetgirl.croissant.admin.dto.response;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreStatus;

public record AdminStoreResponse(
		Long id,
		String name,
		String area,
		String desc,
		String category,
		long alarmCount,
		int rating,
		String address,
		String phone,
		CoordResponse coord,
		String kakaoPlaceId,
		String status,
		List<String> photos,
		int mainPhotoIndex,
		String archivedAt,
		String createdAt,
		Map<String, DayHoursResponse> hours) {

	public record CoordResponse(double latitude, double longitude) {
	}

	public record DayHoursResponse(int openHour, int openMin, int closeHour, int closeMin, boolean closed) {
	}

	private static final Map<DayOfWeek, String> DAY_KEYS = Map.of(
		DayOfWeek.MONDAY, "mon",
		DayOfWeek.TUESDAY, "tue",
		DayOfWeek.WEDNESDAY, "wed",
		DayOfWeek.THURSDAY, "thu",
		DayOfWeek.FRIDAY, "fri",
		DayOfWeek.SATURDAY, "sat",
		DayOfWeek.SUNDAY, "sun");

	public static AdminStoreResponse of(Store store, List<StoreBusinessHour> businessHours,
			List<StoreImageFile> photos, long alarmCount) {
		String area = store.getDistrict() != null ? store.getDistrict().getCity() : null;
		String statusStr = store.getStatus() == StoreStatus.ACTIVE ? "active" : "inactive";
		String archivedAt = store.getStatus() == StoreStatus.DELETED && store.getUpdatedAt() != null
			? store.getUpdatedAt().toString() : null;

		Map<String, DayHoursResponse> hoursMap = businessHours.stream()
			.filter(bh -> DAY_KEYS.containsKey(bh.getDayOfWeek()))
			.collect(Collectors.toMap(
				bh -> DAY_KEYS.get(bh.getDayOfWeek()),
				bh -> new DayHoursResponse(
					bh.getOpenTime() != null ? bh.getOpenTime().getHour() : 0,
					bh.getOpenTime() != null ? bh.getOpenTime().getMinute() : 0,
					bh.getCloseTime() != null ? bh.getCloseTime().getHour() : 0,
					bh.getCloseTime() != null ? bh.getCloseTime().getMinute() : 0,
					bh.isClosed())));

		List<String> photoUrls = photos.stream()
			.map(StoreImageFile::getAwsKey)
			.toList();

		return new AdminStoreResponse(
			store.getId(),
			store.getName(),
			area,
			null,
			null,
			alarmCount,
			0,
			store.getAddress(),
			store.getPhone(),
			store.getLocation() != null
				? new CoordResponse(store.getLocation().getY(), store.getLocation().getX()) : null,
			store.getKakaoPlaceId(),
			statusStr,
			photoUrls,
			0,
			archivedAt,
			store.getCreatedAt() != null ? store.getCreatedAt().toString() : null,
			hoursMap);
	}
}
```

import 추가 필요: `sweetgirl.croissant.store.enums.District` (District는 Store.getDistrict()의 타입으로 이미 처리됨)

- [ ] **Step 7: AdminMenuResponse 생성**

```java
package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;

public record AdminMenuResponse(
		Long id,
		Long bakeryId,
		String name,
		String price,
		String desc,
		int likes,
		String category,
		List<String> photos,
		Integer order,
		String status,
		String createdAt) {

	public static AdminMenuResponse of(Menu menu, List<MenuImageFile> images) {
		String priceStr = menu.getPrice() != null ? menu.getPrice() + "원" : null;
		String statusStr = menu.getDeletedAt() == null ? "active" : "inactive";
		List<String> photoUrls = images.stream()
			.map(MenuImageFile::getAwsKey)
			.toList();
		return new AdminMenuResponse(
			menu.getId(),
			menu.getStore().getId(),
			menu.getName(),
			priceStr,
			menu.getDescription(),
			menu.getRecommendCount(),
			menu.getMenuCategory().getName(),
			photoUrls,
			null,
			statusStr,
			menu.getCreatedAt() != null ? menu.getCreatedAt().toString() : null);
	}
}
```

> `MenuCategory.name`이 String이지만 `MenuKind` enum과 값이 일치하므로 직접 반환한다.

- [ ] **Step 8: 빌드 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/admin/
git commit -m "feat: add admin request/response DTOs"
```

---

## Task 4: AdminStoreService

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/service/AdminStoreService.java`
- Create: `src/test/java/sweetgirl/croissant/admin/service/AdminStoreServiceTest.java`

- [ ] **Step 1: 테스트 파일 작성 (실패 상태)**

```java
package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminStoreServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@InjectMocks
	AdminStoreService adminStoreService;

	@Test
	@DisplayName("createStore: 가게를 저장하고 영업시간을 저장한다")
	void createStore_savesStoreAndBusinessHours() {
		Store saved = mock(Store.class);
		given(saved.getId()).willReturn(1L);
		given(saved.getName()).willReturn("소금빵집");
		given(saved.getAddress()).willReturn("서울시 강남구 테헤란로 1");
		given(saved.getStatus()).willReturn(StoreStatus.ACTIVE);
		given(saved.getCreatedAt()).willReturn(null);
		given(saved.getUpdatedAt()).willReturn(null);
		given(storeRepository.save(any(Store.class))).willReturn(saved);
		given(storeBusinessHourRepository.saveAll(anyList())).willReturn(Collections.emptyList());
		given(subscribedStoreRepository.countByStoreId(1L)).willReturn(0L);
		given(storeImageFileRepository.findByStoreId(1L)).willReturn(Collections.emptyList());
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(1L)).willReturn(Collections.emptyList());

		AdminStoreCreateRequest request = new AdminStoreCreateRequest(
			"소금빵집",
			"서울시 강남구 테헤란로 1",
			new AdminStoreCreateRequest.CoordRequest(37.5, 127.0),
			"02-1234-5678",
			null,
			"active",
			Map.of("mon", new BusinessHourRequest(9, 0, 21, 0, false)));

		AdminStoreResponse response = adminStoreService.createStore(request);

		verify(storeRepository).save(any(Store.class));
		verify(storeBusinessHourRepository).saveAll(anyList());
		assertThat(response.name()).isEqualTo("소금빵집");
		assertThat(response.status()).isEqualTo("active");
	}

	@Test
	@DisplayName("getStores: keyword 없이 전체 목록을 페이지로 반환한다")
	void getStores_noFilters_returnsPage() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);
		given(store.getCreatedAt()).willReturn(null);
		given(store.getUpdatedAt()).willReturn(null);

		PageRequest pageable = PageRequest.of(0, 20);
		given(storeRepository.findAll(pageable))
			.willReturn(new PageImpl<>(List.of(store), pageable, 1));
		given(subscribedStoreRepository.countByStoreIdIn(List.of(1L)))
			.willReturn(List.of(new Object[] {1L, 5L}));
		given(storeImageFileRepository.findByStoreIdIn(List.of(1L))).willReturn(Collections.emptyList());
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(1L)).willReturn(Collections.emptyList());

		Page<AdminStoreResponse> result = adminStoreService.getStores(null, null, pageable);

		assertThat(result.getContent()).hasSize(1);
		assertThat(result.getContent().get(0).alarmCount()).isEqualTo(5L);
	}

	@Test
	@DisplayName("getStoreDetail: 없는 가게는 예외를 던진다")
	void getStoreDetail_notFound_throwsException() {
		given(storeRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminStoreService.getStoreDetail(999L))
			.isInstanceOf(CustomException.class);
	}

	@Test
	@DisplayName("updateStore: businessHours를 요청값으로 교체한다")
	void updateStore_replacesBusinessHours() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(1L)).willReturn(Collections.emptyList());
		given(storeImageFileRepository.findByStoreId(1L)).willReturn(Collections.emptyList());
		given(subscribedStoreRepository.countByStoreId(1L)).willReturn(0L);
		given(store.getStatus()).willReturn(StoreStatus.ACTIVE);
		given(store.getCreatedAt()).willReturn(null);
		given(store.getUpdatedAt()).willReturn(null);

		AdminStoreUpdateRequest request = new AdminStoreUpdateRequest(
			"새이름", null, null, null, "active", null,
			Map.of("mon", new BusinessHourRequest(9, 0, 21, 0, false)));

		adminStoreService.updateStore(1L, request);

		verify(storeBusinessHourRepository).deleteByStoreId(1L);
		verify(storeBusinessHourRepository).saveAll(anyList());
	}
}
```

- [ ] **Step 2: 테스트가 컴파일 실패 확인 (AdminStoreService 없으므로)**

```bash
./gradlew compileTestJava 2>&1 | grep "error:"
```

Expected: `error: cannot find symbol` (AdminStoreService)

- [ ] **Step 3: AdminStoreService 구현**

```java
package sweetgirl.croissant.admin.service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.request.BusinessHourRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.District;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStoreService {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private static final Map<String, DayOfWeek> KEY_TO_DAY = Map.of(
		"mon", DayOfWeek.MONDAY,
		"tue", DayOfWeek.TUESDAY,
		"wed", DayOfWeek.WEDNESDAY,
		"thu", DayOfWeek.THURSDAY,
		"fri", DayOfWeek.FRIDAY,
		"sat", DayOfWeek.SATURDAY,
		"sun", DayOfWeek.SUNDAY);

	private final StoreRepository storeRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;

	@Transactional
	public AdminStoreResponse createStore(AdminStoreCreateRequest request) {
		Point location = toPoint(request.coord().latitude(), request.coord().longitude());
		District district = District.fromAddress(request.address()).orElse(null);

		Store store = Store.builder()
			.name(request.name())
			.address(request.address())
			.location(location)
			.phone(request.phone())
			.kakaoPlaceId(request.kakaoPlaceId())
			.source(StoreSource.ADMIN_REGISTERED)
			.build();
		store = storeRepository.save(store);

		saveBusinessHours(store, request.hours());

		return toResponse(store);
	}

	public Page<AdminStoreResponse> getStores(String keyword, String statusStr, Pageable pageable) {
		StoreStatus status = statusStr != null ? toStoreStatus(statusStr) : null;
		Page<Store> stores;

		if (keyword != null && status != null) {
			stores = storeRepository.findByNameContainingIgnoreCaseAndStatus(keyword, status, pageable);
		} else if (keyword != null) {
			stores = storeRepository.findByNameContainingIgnoreCase(keyword, pageable);
		} else if (status != null) {
			stores = storeRepository.findByStatus(status, pageable);
		} else {
			stores = storeRepository.findAll(pageable);
		}

		List<Long> storeIds = stores.getContent().stream().map(Store::getId).toList();
		Map<Long, Long> alarmCountMap = getAlarmCountMap(storeIds);
		Map<Long, List<StoreImageFile>> photoMap = getPhotoMap(storeIds);
		Map<Long, List<StoreBusinessHour>> hoursMap = getHoursMap(storeIds);

		return stores.map(store -> AdminStoreResponse.of(
			store,
			hoursMap.getOrDefault(store.getId(), Collections.emptyList()),
			photoMap.getOrDefault(store.getId(), Collections.emptyList()),
			alarmCountMap.getOrDefault(store.getId(), 0L)));
	}

	public AdminStoreResponse getStoreDetail(Long storeId) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		return toResponse(store);
	}

	@Transactional
	public void updateStore(Long storeId, AdminStoreUpdateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		Point newLocation = request.coord() != null
			? toPoint(request.coord().latitude(), request.coord().longitude()) : null;
		District newDistrict = request.address() != null
			? District.fromAddress(request.address()).orElse(null) : null;
		StoreStatus newStatus = resolveStatus(request);

		store.update(request.name(), request.address(), newLocation, newDistrict, request.phone(), newStatus);

		if (request.hours() != null) {
			storeBusinessHourRepository.deleteByStoreId(storeId);
			saveBusinessHours(store, request.hours());
		}
	}

	private AdminStoreResponse toResponse(Store store) {
		List<StoreBusinessHour> hours = storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(store.getId());
		List<StoreImageFile> photos = storeImageFileRepository.findByStoreId(store.getId());
		long alarmCount = subscribedStoreRepository.countByStoreId(store.getId());
		return AdminStoreResponse.of(store, hours, photos, alarmCount);
	}

	private void saveBusinessHours(Store store, Map<String, BusinessHourRequest> hours) {
		if (hours == null || hours.isEmpty()) {
			return;
		}
		List<StoreBusinessHour> entities = hours.entrySet().stream()
			.filter(e -> KEY_TO_DAY.containsKey(e.getKey()))
			.map(e -> {
				BusinessHourRequest bh = e.getValue();
				DayOfWeek day = KEY_TO_DAY.get(e.getKey());
				return StoreBusinessHour.builder()
					.store(store)
					.dayOfWeek(day)
					.openTime(bh.closed() ? null : LocalTime.of(bh.openHour(), bh.openMin()))
					.closeTime(bh.closed() ? null : LocalTime.of(bh.closeHour(), bh.closeMin()))
					.isClosed(bh.closed())
					.build();
			})
			.toList();
		storeBusinessHourRepository.saveAll(entities);
	}

	private StoreStatus resolveStatus(AdminStoreUpdateRequest request) {
		if ("active".equals(request.status())) {
			return StoreStatus.ACTIVE;
		}
		if ("inactive".equals(request.status())) {
			return StoreStatus.DELETED;
		}
		if (request.archivedAt() != null && !request.archivedAt().isBlank()) {
			return StoreStatus.DELETED;
		}
		return null;
	}

	private Map<Long, Long> getAlarmCountMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return subscribedStoreRepository.countByStoreIdIn(storeIds).stream()
			.collect(Collectors.toMap(
				row -> (Long) row[0],
				row -> (Long) row[1]));
	}

	private Map<Long, List<StoreImageFile>> getPhotoMap(List<Long> storeIds) {
		if (storeIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return storeImageFileRepository.findByStoreIdIn(storeIds).stream()
			.collect(Collectors.groupingBy(img -> img.getStore().getId()));
	}

	private Map<Long, List<StoreBusinessHour>> getHoursMap(List<Long> storeIds) {
		return storeIds.stream()
			.collect(Collectors.toMap(
				id -> id,
				id -> storeBusinessHourRepository.findByStoreIdOrderByDayOfWeekAsc(id)));
	}

	private Point toPoint(double latitude, double longitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}

	private StoreStatus toStoreStatus(String statusStr) {
		return "active".equals(statusStr) ? StoreStatus.ACTIVE : StoreStatus.DELETED;
	}
}
```

> `StoreImageFile.getStore()` 호출 시 LAZY 로딩 이슈가 발생할 수 있다. `findByStoreIdIn` 결과에서 `store.getId()`는 프록시로 호출 가능하므로 문제없다.

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew test --tests "sweetgirl.croissant.admin.service.AdminStoreServiceTest"
```

Expected: 4 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/admin/service/AdminStoreService.java \
        src/test/java/sweetgirl/croissant/admin/service/AdminStoreServiceTest.java
git commit -m "feat: implement AdminStoreService with unit tests"
```

---

## Task 5: AdminMenuService

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/service/AdminMenuService.java`
- Create: `src/test/java/sweetgirl/croissant/admin/service/AdminMenuServiceTest.java`

- [ ] **Step 1: 테스트 파일 작성**

```java
package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminMenuServiceTest {

	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	AdminMenuService adminMenuService;

	@Test
	@DisplayName("createMenu: 가격을 Integer로 파싱해 메뉴를 저장한다")
	void createMenu_parsesPrice_savesMenu() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn("소금빵");
		given(menuCategoryRepository.findByName(MenuKind.소금빵)).willReturn(Optional.of(category));

		Menu saved = mock(Menu.class);
		given(saved.getId()).willReturn(10L);
		given(saved.getName()).willReturn("소금빵");
		given(saved.getPrice()).willReturn(3000);
		given(saved.getDescription()).willReturn(null);
		given(saved.getRecommendCount()).willReturn(0);
		given(saved.getDeletedAt()).willReturn(null);
		given(saved.getStore()).willReturn(store);
		given(saved.getMenuCategory()).willReturn(category);
		given(saved.getCreatedAt()).willReturn(null);
		given(menuRepository.save(any(Menu.class))).willReturn(saved);
		given(menuImageFileRepository.findImagesByMenuId(10L)).willReturn(Collections.emptyList());

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "소금빵", "3000원", null, "소금빵", "active");

		AdminMenuResponse response = adminMenuService.createMenu(1L, request);

		assertThat(response.price()).isEqualTo("3000원");
		assertThat(response.bakeryId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("createMenu: 유효하지 않은 카테고리면 예외를 던진다")
	void createMenu_invalidCategory_throwsException() {
		Store store = mock(Store.class);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));

		AdminMenuCreateRequest request = new AdminMenuCreateRequest(
			1L, "테스트빵", "1000원", null, "존재하지않는카테고리", "active");

		assertThatThrownBy(() -> adminMenuService.createMenu(1L, request))
			.isInstanceOf(CustomException.class);
	}

	@Test
	@DisplayName("getStoreMenus: soft-delete 된 메뉴도 포함해 반환한다")
	void getStoreMenus_includesDeletedMenus() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(storeRepository.existsById(1L)).willReturn(true);

		MenuCategory category = mock(MenuCategory.class);
		given(category.getName()).willReturn("소금빵");

		Menu activeMenu = mock(Menu.class);
		given(activeMenu.getId()).willReturn(1L);
		given(activeMenu.getName()).willReturn("소금빵");
		given(activeMenu.getPrice()).willReturn(3000);
		given(activeMenu.getDescription()).willReturn(null);
		given(activeMenu.getRecommendCount()).willReturn(0);
		given(activeMenu.getDeletedAt()).willReturn(null);
		given(activeMenu.getStore()).willReturn(store);
		given(activeMenu.getMenuCategory()).willReturn(category);
		given(activeMenu.getCreatedAt()).willReturn(null);

		Menu deletedMenu = mock(Menu.class);
		given(deletedMenu.getId()).willReturn(2L);
		given(deletedMenu.getName()).willReturn("삭제된빵");
		given(deletedMenu.getPrice()).willReturn(2000);
		given(deletedMenu.getDescription()).willReturn(null);
		given(deletedMenu.getRecommendCount()).willReturn(0);
		given(deletedMenu.getDeletedAt()).willReturn(LocalDateTime.now());
		given(deletedMenu.getStore()).willReturn(store);
		given(deletedMenu.getMenuCategory()).willReturn(category);
		given(deletedMenu.getCreatedAt()).willReturn(null);

		given(menuRepository.findByStoreIdIncludingDeleted(1L)).willReturn(List.of(activeMenu, deletedMenu));
		given(menuImageFileRepository.findThumbnailsByMenuIdIn(List.of(1L, 2L))).willReturn(Collections.emptyList());

		List<AdminMenuResponse> result = adminMenuService.getStoreMenus(1L);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).status()).isEqualTo("active");
		assertThat(result.get(1).status()).isEqualTo("inactive");
	}

	@Test
	@DisplayName("updateMenu: status가 inactive면 softDelete를 호출한다")
	void updateMenu_inactiveStatus_callsSoftDelete() {
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(1L);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		AdminMenuUpdateRequest request = new AdminMenuUpdateRequest(
			null, null, null, null, "inactive");

		adminMenuService.updateMenu(1L, request);

		verify(menu).softDelete();
	}

	@Test
	@DisplayName("updateMenu: 없는 메뉴는 예외를 던진다")
	void updateMenu_notFound_throwsException() {
		given(menuRepository.findById(999L)).willReturn(Optional.empty());

		AdminMenuUpdateRequest request = new AdminMenuUpdateRequest(
			"이름", null, null, null, "active");

		assertThatThrownBy(() -> adminMenuService.updateMenu(999L, request))
			.isInstanceOf(CustomException.class);
	}
}
```

- [ ] **Step 2: 테스트가 컴파일 실패 확인**

```bash
./gradlew compileTestJava 2>&1 | grep "error:"
```

Expected: `error: cannot find symbol` (AdminMenuService)

- [ ] **Step 3: AdminMenuService 구현**

```java
package sweetgirl.croissant.admin.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.projection.MenuImageThumbnailProjection;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMenuService {

	private final MenuRepository menuRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;

	@Transactional
	public AdminMenuResponse createMenu(Long storeId, AdminMenuCreateRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		MenuCategory menuCategory = resolveCategory(request.category());
		Integer price = parsePrice(request.price());

		Menu menu = Menu.builder()
			.store(store)
			.menuCategory(menuCategory)
			.name(request.name())
			.price(price)
			.description(request.desc())
			.build();
		menu = menuRepository.save(menu);

		List<MenuImageFile> images = menuImageFileRepository.findImagesByMenuId(menu.getId());
		return AdminMenuResponse.of(menu, images);
	}

	public List<AdminMenuResponse> getStoreMenus(Long storeId) {
		if (!storeRepository.existsById(storeId)) {
			throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
		}
		List<Menu> menus = menuRepository.findByStoreIdIncludingDeleted(storeId);
		if (menus.isEmpty()) {
			return Collections.emptyList();
		}

		List<Long> menuIds = menus.stream().map(Menu::getId).toList();
		Map<Long, List<String>> photoMap = buildPhotoMap(menuIds);

		return menus.stream()
			.map(menu -> {
				List<MenuImageFile> images = photoMap.getOrDefault(menu.getId(), Collections.emptyList())
					.stream()
					.map(awsKey -> {
						MenuImageFile f = new MenuImageFile() {
						};
						return f;
					})
					.toList();
				return AdminMenuResponse.of(menu, Collections.emptyList());
			})
			.toList();
	}

	@Transactional
	public void updateMenu(Long menuId, AdminMenuUpdateRequest request) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if ("inactive".equals(request.status())) {
			menu.softDelete();
			return;
		}

		MenuCategory newCategory = request.category() != null ? resolveCategory(request.category()) : null;
		Integer newPrice = request.price() != null ? parsePrice(request.price()) : null;
		menu.update(request.name(), newPrice, request.desc(), newCategory);
	}

	private MenuCategory resolveCategory(String categoryName) {
		if (categoryName == null || categoryName.isBlank()) {
			return null;
		}
		try {
			MenuKind kind = MenuKind.valueOf(categoryName);
			return menuCategoryRepository.findByName(kind)
				.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND));
		} catch (IllegalArgumentException e) {
			throw new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND);
		}
	}

	private Integer parsePrice(String priceStr) {
		if (priceStr == null || priceStr.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(priceStr.replace("원", "").trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Map<Long, List<String>> buildPhotoMap(List<Long> menuIds) {
		return menuImageFileRepository.findThumbnailsByMenuIdIn(menuIds).stream()
			.collect(Collectors.groupingBy(
				MenuImageThumbnailProjection::getMenuId,
				Collectors.mapping(MenuImageThumbnailProjection::getAwsKey, Collectors.toList())));
	}
}
```

> **주의:** `getStoreMenus`의 photo 로딩을 단순화하기 위해 `MenuImageThumbnailProjection`의 AWS key를 사용한다. `AdminMenuResponse.of(menu, Collections.emptyList())`를 직접 호출하면 photos가 빈 배열이 된다. 아래 Step 3-fix에서 수정한다.

- [ ] **Step 3-fix: getStoreMenus photo 로딩 수정**

`getStoreMenus` 메서드를 아래로 교체:

```java
public List<AdminMenuResponse> getStoreMenus(Long storeId) {
	if (!storeRepository.existsById(storeId)) {
		throw new StoreException(StoreErrorCode.STORE_NOT_FOUND);
	}
	List<Menu> menus = menuRepository.findByStoreIdIncludingDeleted(storeId);
	if (menus.isEmpty()) {
		return Collections.emptyList();
	}

	List<Long> menuIds = menus.stream().map(Menu::getId).toList();
	Map<Long, List<String>> photoMap = buildPhotoMap(menuIds);

	return menus.stream()
		.map(menu -> {
			List<String> photoUrls = photoMap.getOrDefault(menu.getId(), Collections.emptyList());
			List<MenuImageFile> fakeImages = photoUrls.stream()
				.map(url -> MenuImageFile.builder().awsKey(url).build())
				.toList();
			return AdminMenuResponse.of(menu, fakeImages);
		})
		.toList();
}
```

> `MenuImageFile.builder()` 사용을 위해 `MenuImageFile` 엔티티에 `@Builder`가 있어야 한다. 없으면 `AdminMenuResponse.of`를 오버로드해서 `List<String>` 직접 받도록 수정한다 (아래 Step 3-fix2).

- [ ] **Step 3-fix2: MenuImageFile에 @Builder 없으면 AdminMenuResponse.of 오버로드 추가**

`MenuImageFile` 에 `@Builder` 없으면, `AdminMenuResponse.java`에 추가:

```java
public static AdminMenuResponse of(Menu menu, List<String> photoUrls) {
	String priceStr = menu.getPrice() != null ? menu.getPrice() + "원" : null;
	String statusStr = menu.getDeletedAt() == null ? "active" : "inactive";
	return new AdminMenuResponse(
		menu.getId(),
		menu.getStore().getId(),
		menu.getName(),
		priceStr,
		menu.getDescription(),
		menu.getRecommendCount(),
		menu.getMenuCategory().getName(),
		photoUrls,
		null,
		statusStr,
		menu.getCreatedAt() != null ? menu.getCreatedAt().toString() : null);
}
```

그리고 `getStoreMenus`를:

```java
return menus.stream()
	.map(menu -> AdminMenuResponse.of(
		menu,
		photoMap.getOrDefault(menu.getId(), Collections.emptyList())))
	.toList();
```

그리고 `createMenu`의 return도:
```java
List<String> photoUrls = menuImageFileRepository.findImagesByMenuId(menu.getId()).stream()
	.map(MenuImageFile::getAwsKey)
	.toList();
return AdminMenuResponse.of(menu, photoUrls);
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew test --tests "sweetgirl.croissant.admin.service.AdminMenuServiceTest"
```

Expected: 5 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/admin/service/AdminMenuService.java \
        src/main/java/sweetgirl/croissant/admin/dto/response/AdminMenuResponse.java \
        src/test/java/sweetgirl/croissant/admin/service/AdminMenuServiceTest.java
git commit -m "feat: implement AdminMenuService with unit tests"
```

---

## Task 6: Admin Controllers

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/controller/AdminStoreController.java`
- Create: `src/main/java/sweetgirl/croissant/admin/controller/AdminMenuController.java`

- [ ] **Step 1: AdminStoreController 생성**

```java
package sweetgirl.croissant.admin.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminStoreCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminStoreUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminStoreResponse;
import sweetgirl.croissant.admin.service.AdminStoreService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Store", description = "어드민 가게 관리 API")
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

	private final AdminStoreService adminStoreService;

	@Operation(summary = "가게 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<AdminStoreResponse>> createStore(
			@Valid @RequestBody AdminStoreCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.createStore(request)));
	}

	@Operation(summary = "가게 목록 조회", description = "keyword, status 필터 + 페이징 지원")
	@GetMapping
	public ResponseEntity<ApiResponse<Page<AdminStoreResponse>>> getStores(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(
			ApiResponse.ok(adminStoreService.getStores(keyword, status, PageRequest.of(page, size))));
	}

	@Operation(summary = "가게 상세 조회")
	@GetMapping("/{storeId}")
	public ResponseEntity<ApiResponse<AdminStoreResponse>> getStoreDetail(@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminStoreService.getStoreDetail(storeId)));
	}

	@Operation(summary = "가게 수정 (archive/restore 포함)")
	@PutMapping("/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@PathVariable Long storeId,
			@RequestBody AdminStoreUpdateRequest request) {
		adminStoreService.updateStore(storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
```

- [ ] **Step 2: AdminMenuController 생성**

```java
package sweetgirl.croissant.admin.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuCreateRequest;
import sweetgirl.croissant.admin.dto.request.AdminMenuUpdateRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuResponse;
import sweetgirl.croissant.admin.service.AdminMenuService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Menu", description = "어드민 메뉴 관리 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMenuController {

	private final AdminMenuService adminMenuService;

	@Operation(summary = "메뉴 생성")
	@PostMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<AdminMenuResponse>> createMenu(
			@PathVariable Long storeId,
			@Valid @RequestBody AdminMenuCreateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.createMenu(storeId, request)));
	}

	@Operation(summary = "가게별 메뉴 목록 조회 (soft-delete 포함)")
	@GetMapping("/stores/{storeId}/menus")
	public ResponseEntity<ApiResponse<List<AdminMenuResponse>>> getStoreMenus(
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(adminMenuService.getStoreMenus(storeId)));
	}

	@Operation(summary = "메뉴 수정 (status: inactive 시 soft delete)")
	@PutMapping("/menus/{menuId}")
	public ResponseEntity<ApiResponse<Void>> updateMenu(
			@PathVariable Long menuId,
			@RequestBody AdminMenuUpdateRequest request) {
		adminMenuService.updateMenu(menuId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
```

- [ ] **Step 3: 전체 빌드 및 테스트 실행**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASSED

- [ ] **Step 4: spotless 포맷 적용**

```bash
./gradlew spotlessApply
```

- [ ] **Step 5: 최종 빌드 확인**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/admin/controller/
git commit -m "feat: add AdminStoreController and AdminMenuController"
```

---

## 수동 검증 체크리스트

로컬 서버 기동 후 Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 확인:

- [ ] `POST /api/admin/stores` — 가게 생성 후 응답에 `id`, `status: "active"`, `hours` 포함
- [ ] `GET /api/admin/stores` — 응답 `data.content` 배열에 가게 목록
- [ ] `GET /api/admin/stores/{id}` — `alarmCount`, `coord`, `hours` 정상 반환
- [ ] `PUT /api/admin/stores/{id}` — `{ "archivedAt": "2024-01-01T00:00:00" }` 전송 시 status → DELETED
- [ ] `POST /api/admin/stores/{storeId}/menus` — `"price": "3000원"` 포함 응답 확인
- [ ] `GET /api/admin/stores/{storeId}/menus` — soft-delete된 메뉴 `status: "inactive"`로 포함
- [ ] `PUT /api/admin/menus/{id}` — `{ "status": "inactive" }` 전송 시 해당 메뉴 `deletedAt` 설정
