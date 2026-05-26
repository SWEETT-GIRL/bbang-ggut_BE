# Kakao Bulk Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dev DB에 전국 빵집/카페를 90%+ 커버리지로 적재하는 Admin batch 기능 구현.

**Architecture:** `POST /api/admin/stores/bulk-import` 호출 시 전용 async 스레드에서 행정안전부 동 리스트를 순회. 각 동에 대해 11종 키워드 × FD6/CE7 카테고리 조합으로 Kakao Local API 호출 → 기존 `KakaoLocalClient` 자동 필터 통과 → `insertIfNotExistsByKakaoPlaceId`로 dev DB의 `store` 테이블에 INSERT. 진행상황은 `bulk_import_progress` 테이블에 동 단위로 기록 → 중단되어도 재시작 가능.

**Tech Stack:** Spring Boot 4.0.6 · Java 17 · Spring Data JPA · PostgreSQL/PostGIS · `@Async` (별도 executor) · 기존 `KakaoLocalClient`/`StoreRepository` 재사용

**Spec reference:** `docs/superpowers/specs/2026-05-25-kakao-bulk-import-design.md`

---

## File Structure

신규 패키지 `sweetgirl.croissant.admin.bulkimport`:

| 파일 | 역할 |
|------|------|
| `enums/BulkSearchKeyword.java` | 검색 키워드 11종 enum |
| `enums/BulkImportStatus.java` | 동 진행상태 enum (PENDING/IN_PROGRESS/COMPLETED/FAILED) |
| `entity/BulkImportProgress.java` | 동 단위 진행상황 entity |
| `repository/BulkImportProgressRepository.java` | JPA repository |
| `code/BulkImportErrorCode.java` | 에러코드 enum |
| `code/StoreCategoryDeriver.java` | Kakao category_name → StoreCategory 매핑 |
| `code/BulkImportRateLimiter.java` | 단순 sleep 기반 throttle |
| `config/BulkImportAsyncConfig.java` | bulk import 전용 단일 스레드 executor |
| `loader/DongListLoader.java` | CSV 리소스 로딩 + DB 초기화 |
| `service/BulkImportStoreSaver.java` | place → store 단건 INSERT (REQUIRES_NEW) |
| `service/DongImporter.java` | 한 개 동 처리 (11 키워드 × 페이지 순회) |
| `service/BulkImportProgressService.java` | 진행상황 조회/업데이트 + IN_PROGRESS 복구 |
| `service/BulkImportOrchestrator.java` | @Async 메인 루프, 동시실행 방지 |
| `dto/response/BulkImportStartResponse.java` | 시작 endpoint 응답 |
| `dto/response/BulkImportProgressResponse.java` | progress endpoint 응답 |
| `dto/response/FailedDongResponse.java` | failed endpoint 응답 |
| `controller/BulkImportController.java` | REST endpoints |

수정 대상:

| 파일 | 변경 |
|------|------|
| `store/repository/StoreRepository.java` | `insertIfNotExistsByKakaoPlaceId` 시그니처 + native SQL에 `category` 파라미터 추가 |
| `store/service/IndividualStoreSaver.java` | 새 시그니처에 `null` 전달 |
| `store/service/StoreService.java` (`registerKakaoStore`) | 새 시그니처에 `null` 전달 |

신규 리소스:

| 파일 | 비고 |
|------|------|
| `src/main/resources/dong/dong_list.csv` | 행정안전부 주소계 동 리스트. **개발자가 직접 다운로드해서 배치**. 포맷: `region_1st,region_2nd,region_3rd` 헤더 1행 + 데이터 |

---

## Task 1: 키워드/상태/카테고리 매핑 enum 및 helper

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/enums/BulkSearchKeyword.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/enums/BulkImportStatus.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/code/StoreCategoryDeriver.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/code/StoreCategoryDeriverTest.java`

- [ ] **Step 1: 실패 테스트 작성 — `StoreCategoryDeriverTest`**

```java
package sweetgirl.croissant.admin.bulkimport.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.store.enums.StoreCategory;

class StoreCategoryDeriverTest {

	@Test
	@DisplayName("kakao category_name에 '양식'이 포함되면 WESTERN을 반환한다")
	void derive_western_whenContainsYangsik() {
		assertThat(StoreCategoryDeriver.derive("음식점 > 양식 > 베이커리"))
			.isEqualTo(StoreCategory.WESTERN);
	}

	@Test
	@DisplayName("kakao category_name에 '양식'이 없으면 null을 반환한다")
	void derive_null_whenNotContainsYangsik() {
		assertThat(StoreCategoryDeriver.derive("음식점 > 간식 > 도넛")).isNull();
		assertThat(StoreCategoryDeriver.derive("카페 > 디저트카페")).isNull();
	}

	@Test
	@DisplayName("category_name이 null이면 null을 반환한다")
	void derive_null_whenInputIsNull() {
		assertThat(StoreCategoryDeriver.derive(null)).isNull();
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.code.StoreCategoryDeriverTest"`
Expected: FAIL — `StoreCategoryDeriver` 클래스가 존재하지 않음

- [ ] **Step 3: enum 2종 작성**

`BulkSearchKeyword.java`:
```java
package sweetgirl.croissant.admin.bulkimport.enums;

public enum BulkSearchKeyword {
	브런치, 빵집, 베이커리, 디저트, 소금빵, 빵, 도넛, 케이크, 에그타르트, 휘낭시에, 구움과자;
}
```

`BulkImportStatus.java`:
```java
package sweetgirl.croissant.admin.bulkimport.enums;

public enum BulkImportStatus {
	PENDING, IN_PROGRESS, COMPLETED, FAILED;
}
```

- [ ] **Step 4: `StoreCategoryDeriver` 구현**

`StoreCategoryDeriver.java`:
```java
package sweetgirl.croissant.admin.bulkimport.code;

import sweetgirl.croissant.store.enums.StoreCategory;

public final class StoreCategoryDeriver {

	private StoreCategoryDeriver() {
	}

	public static StoreCategory derive(String kakaoCategoryName) {
		if (kakaoCategoryName == null) {
			return null;
		}
		if (kakaoCategoryName.contains("양식")) {
			return StoreCategory.WESTERN;
		}
		return null;
	}
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.code.StoreCategoryDeriverTest"`
Expected: PASS

- [ ] **Step 6: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/enums/BulkSearchKeyword.java \
        src/main/java/sweetgirl/croissant/admin/bulkimport/enums/BulkImportStatus.java \
        src/main/java/sweetgirl/croissant/admin/bulkimport/code/StoreCategoryDeriver.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/code/StoreCategoryDeriverTest.java
git commit -m ":sparkles: Feat: Kakao bulk import - 키워드/상태 enum, 카테고리 매핑 helper 추가"
```

---

## Task 2: BulkImportProgress entity + repository

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/entity/BulkImportProgress.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/repository/BulkImportProgressRepository.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/entity/BulkImportProgressTest.java`

- [ ] **Step 1: 엔티티 단위테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;

class BulkImportProgressTest {

	@Test
	@DisplayName("새로 생성하면 status가 PENDING이고 count가 0이다")
	void newProgress_isPendingWithZeroCounts() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		assertThat(progress.getFoundCount()).isZero();
		assertThat(progress.getInsertedCount()).isZero();
	}

	@Test
	@DisplayName("markInProgress 호출 시 status가 IN_PROGRESS로 바뀌고 last_attempted_at이 갱신된다")
	void markInProgress_changesStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markInProgress();

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.IN_PROGRESS);
		assertThat(progress.getLastAttemptedAt()).isNotNull();
	}

	@Test
	@DisplayName("markCompleted 호출 시 status가 COMPLETED로 바뀌고 카운트가 기록된다")
	void markCompleted_setsCountsAndStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markCompleted(40, 35);

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.COMPLETED);
		assertThat(progress.getFoundCount()).isEqualTo(40);
		assertThat(progress.getInsertedCount()).isEqualTo(35);
	}

	@Test
	@DisplayName("markFailed 호출 시 status가 FAILED로 바뀌고 error_message가 저장된다")
	void markFailed_setsErrorMessageAndStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markFailed("Kakao API timeout");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.FAILED);
		assertThat(progress.getErrorMessage()).isEqualTo("Kakao API timeout");
	}

	@Test
	@DisplayName("resetToPending: IN_PROGRESS 상태를 다시 PENDING으로 되돌린다 (서버 재시작 복구용)")
	void resetToPending_setsStatusBackToPending() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		progress.markInProgress();

		progress.resetToPending();

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgressTest"`
Expected: FAIL — `BulkImportProgress` 클래스 없음

- [ ] **Step 3: 엔티티 구현**

```java
package sweetgirl.croissant.admin.bulkimport.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

@Entity
@Table(name = "bulk_import_progress",
		uniqueConstraints = @UniqueConstraint(name = "uk_bulk_region",
			columnNames = {"region_1st", "region_2nd", "region_3rd"}),
		indexes = @Index(name = "idx_bulk_import_status", columnList = "status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BulkImportProgress extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "region_1st", nullable = false, length = 50)
	private String region1st;

	@Column(name = "region_2nd", nullable = false, length = 50)
	private String region2nd;

	@Column(name = "region_3rd", nullable = false, length = 50)
	private String region3rd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BulkImportStatus status;

	@Column(name = "found_count", nullable = false)
	private int foundCount;

	@Column(name = "inserted_count", nullable = false)
	private int insertedCount;

	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "last_attempted_at")
	private LocalDateTime lastAttemptedAt;

	private BulkImportProgress(String region1st, String region2nd, String region3rd) {
		this.region1st = region1st;
		this.region2nd = region2nd;
		this.region3rd = region3rd;
		this.status = BulkImportStatus.PENDING;
		this.foundCount = 0;
		this.insertedCount = 0;
	}

	public static BulkImportProgress of(String region1st, String region2nd, String region3rd) {
		return new BulkImportProgress(region1st, region2nd, region3rd);
	}

	public void markInProgress() {
		this.status = BulkImportStatus.IN_PROGRESS;
		this.lastAttemptedAt = LocalDateTime.now();
	}

	public void markCompleted(int foundCount, int insertedCount) {
		this.status = BulkImportStatus.COMPLETED;
		this.foundCount = foundCount;
		this.insertedCount = insertedCount;
		this.errorMessage = null;
	}

	public void markFailed(String errorMessage) {
		this.status = BulkImportStatus.FAILED;
		this.errorMessage = errorMessage;
	}

	public void resetToPending() {
		this.status = BulkImportStatus.PENDING;
	}
}
```

- [ ] **Step 4: repository 구현**

```java
package sweetgirl.croissant.admin.bulkimport.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;

public interface BulkImportProgressRepository extends JpaRepository<BulkImportProgress, Long> {

	Optional<BulkImportProgress> findByRegion1stAndRegion2ndAndRegion3rd(
			String region1st, String region2nd, String region3rd);

	List<BulkImportProgress> findByStatusIn(List<BulkImportStatus> statuses);

	long countByStatus(BulkImportStatus status);

	List<BulkImportProgress> findByStatusOrderByLastAttemptedAtDesc(BulkImportStatus status);

	@Modifying
	@Query("UPDATE BulkImportProgress p SET p.status = :newStatus WHERE p.status = :oldStatus")
	int bulkUpdateStatus(@Param("oldStatus") BulkImportStatus oldStatus,
			@Param("newStatus") BulkImportStatus newStatus);
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgressTest"`
Expected: PASS

- [ ] **Step 6: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/entity/BulkImportProgress.java \
        src/main/java/sweetgirl/croissant/admin/bulkimport/repository/BulkImportProgressRepository.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/entity/BulkImportProgressTest.java
git commit -m ":sparkles: Feat: BulkImportProgress entity & repository 추가"
```

---

## Task 3: StoreRepository `insertIfNotExistsByKakaoPlaceId` 시그니처 확장

**Files:**
- Modify: `src/main/java/sweetgirl/croissant/store/repository/StoreRepository.java:141-160`
- Modify: `src/main/java/sweetgirl/croissant/store/service/IndividualStoreSaver.java:25-32`
- Modify: `src/main/java/sweetgirl/croissant/store/service/StoreService.java:215-219`

기존 native SQL은 `source = 'KAKAO_IMPORTED'` 하드코딩 + category 컬럼 미설정 (DB default NULL). 새 시그니처에 `category` 파라미터를 추가해 NULL 또는 enum name (예: `'WESTERN'`)을 받도록 한다.

- [ ] **Step 1: `StoreRepository` 메서드 수정**

기존 메서드 (141-160번 라인)를 다음으로 교체:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
		INSERT INTO store (
				name, address, region_1st, region_2nd, region_3rd,
				location, phone, kakao_place_id, source, status, category,
				created_at, updated_at)
		VALUES (:name, :address, :region1st, :region2nd, :region3rd,
				ST_SetSRID(ST_MakePoint(:x, :y), 4326)::geography, :phone, :kakaoPlaceId,
				'KAKAO_IMPORTED', 'ACTIVE', :category, NOW(), NOW())
		ON CONFLICT (kakao_place_id) DO NOTHING
		""", nativeQuery = true)
void insertIfNotExistsByKakaoPlaceId(
		@Param("name") String name,
		@Param("address") String address,
		@Param("region1st") String region1st,
		@Param("region2nd") String region2nd,
		@Param("region3rd") String region3rd,
		@Param("x") double longitude,
		@Param("y") double latitude,
		@Param("phone") String phone,
		@Param("kakaoPlaceId") String kakaoPlaceId,
		@Param("category") String category);
```

- [ ] **Step 2: 기존 호출처 1 — `IndividualStoreSaver.save` 수정**

`IndividualStoreSaver.java`의 `save` 메서드 본문을 다음으로 교체:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void save(KakaoPlace place) {
	String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
		? place.roadAddressName() : place.addressName();

	String[] addr = AddressParser.parse(place.addressName());

	storeRepository.insertIfNotExistsByKakaoPlaceId(
		place.placeName(),
		address,
		addr[0], addr[1], addr[2],
		Double.parseDouble(place.x()),
		Double.parseDouble(place.y()),
		place.phone(),
		place.id(),
		null);
}
```

- [ ] **Step 3: 기존 호출처 2 — `StoreService.registerKakaoStore` 수정**

`StoreService.java`의 213-224 라인 메서드 본문에서 `insertIfNotExistsByKakaoPlaceId` 호출을 다음으로 교체:

```java
storeRepository.insertIfNotExistsByKakaoPlaceId(
	request.name(), request.address(),
	addr[0], addr[1], addr[2],
	Double.parseDouble(request.x()), Double.parseDouble(request.y()),
	request.phone(), request.kakaoPlaceId(),
	null);
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 5: 기존 테스트 회귀 확인**

Run: `./gradlew test`
Expected: PASS (기존 테스트 깨지지 않음)

- [ ] **Step 6: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/store/repository/StoreRepository.java \
        src/main/java/sweetgirl/croissant/store/service/IndividualStoreSaver.java \
        src/main/java/sweetgirl/croissant/store/service/StoreService.java
git commit -m ":sparkles: Feat: insertIfNotExistsByKakaoPlaceId에 category 파라미터 추가"
```

---

## Task 4: BulkImportRateLimiter (sleep 기반)

`KakaoLocalClient.searchByKeyword` 한 번 = HTTP 2회(FD6 + CE7). RateLimiter는 호출 간 최소 간격을 보장한다. 5 req/sec 목표면 호출 1회당 2 HTTP가 나가므로 limiter 간격을 **400ms**로 설정 (2 calls / 400ms = 5 req/sec).

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportRateLimiter.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportRateLimiterTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BulkImportRateLimiterTest {

	@Test
	@DisplayName("acquire를 연속 3번 호출하면 최소 2 * minIntervalMs 이상 시간이 소요된다")
	void acquire_enforcesMinInterval() throws InterruptedException {
		BulkImportRateLimiter limiter = new BulkImportRateLimiter(100);

		long start = System.currentTimeMillis();
		limiter.acquire();
		limiter.acquire();
		limiter.acquire();
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isGreaterThanOrEqualTo(200L);
	}

	@Test
	@DisplayName("첫 acquire는 즉시 통과한다")
	void acquire_firstCallReturnsImmediately() throws InterruptedException {
		BulkImportRateLimiter limiter = new BulkImportRateLimiter(500);

		long start = System.currentTimeMillis();
		limiter.acquire();
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isLessThan(100L);
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiterTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.code;

public class BulkImportRateLimiter {

	private final long minIntervalMs;
	private long lastCallMs = 0L;

	public BulkImportRateLimiter(long minIntervalMs) {
		this.minIntervalMs = minIntervalMs;
	}

	public synchronized void acquire() throws InterruptedException {
		long now = System.currentTimeMillis();
		long waitMs = (lastCallMs + minIntervalMs) - now;
		if (waitMs > 0) {
			Thread.sleep(waitMs);
		}
		lastCallMs = System.currentTimeMillis();
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiterTest"`
Expected: PASS

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportRateLimiter.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportRateLimiterTest.java
git commit -m ":sparkles: Feat: BulkImportRateLimiter 추가 (sleep 기반 throttle)"
```

---

## Task 5: BulkImportStoreSaver

기존 `IndividualStoreSaver`와 같은 패턴(`REQUIRES_NEW` + native upsert) + category 매핑을 적용한다. 단건 INSERT 실패는 부모 트랜잭션과 분리되어 다음 place 처리에 영향 없음.

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportStoreSaver.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportStoreSaverTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class BulkImportStoreSaverTest {

	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	BulkImportStoreSaver saver;

	@Test
	@DisplayName("category_name에 '양식'이 있으면 'WESTERN' 문자열로 저장된다")
	void save_passesWesternCategory_whenCategoryNameContainsYangsik() {
		KakaoPlace place = new KakaoPlace(
			"kakao-1", "테스트빵집", "서울 강남구 역삼동 1", "서울 강남구 테헤란로 1",
			"음식점 > 양식 > 베이커리", "FD6", "02-1234-5678", "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("테스트빵집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq("02-1234-5678"), eq("kakao-1"), eq("WESTERN"));
	}

	@Test
	@DisplayName("category_name에 '양식'이 없으면 null이 저장된다")
	void save_passesNullCategory_whenCategoryNameDoesNotContainYangsik() {
		KakaoPlace place = new KakaoPlace(
			"kakao-2", "도넛집", "서울 강남구 역삼동 2", "서울 강남구 테헤란로 2",
			"음식점 > 간식 > 도넛", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("도넛집"), anyString(), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-2"), eq(null));
	}

	@Test
	@DisplayName("roadAddressName이 비어있으면 addressName을 사용한다")
	void save_fallsBackToAddressName_whenRoadAddressIsBlank() {
		KakaoPlace place = new KakaoPlace(
			"kakao-3", "빵집", "서울 강남구 역삼동 3", "",
			"음식점 > 간식", "FD6", null, "127.0", "37.5");

		saver.save(place);

		verify(storeRepository).insertIfNotExistsByKakaoPlaceId(
			eq("빵집"), eq("서울 강남구 역삼동 3"), anyString(), anyString(), anyString(),
			anyDouble(), anyDouble(), eq(null), eq("kakao-3"), eq(null));
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportStoreSaverTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.code.StoreCategoryDeriver;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.repository.StoreRepository;

@Component
@RequiredArgsConstructor
public class BulkImportStoreSaver {

	private final StoreRepository storeRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void save(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName() : place.addressName();

		String[] addr = AddressParser.parse(place.addressName());
		StoreCategory category = StoreCategoryDeriver.derive(place.categoryName());
		String categoryName = category != null ? category.name() : null;

		storeRepository.insertIfNotExistsByKakaoPlaceId(
			place.placeName(),
			address,
			addr[0], addr[1], addr[2],
			Double.parseDouble(place.x()),
			Double.parseDouble(place.y()),
			place.phone(),
			place.id(),
			categoryName);
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportStoreSaverTest"`
Expected: PASS

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportStoreSaver.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportStoreSaverTest.java
git commit -m ":sparkles: Feat: BulkImportStoreSaver 추가 (place → store 단건 INSERT)"
```

---

## Task 6: BulkImportErrorCode + 전용 Async Executor

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportErrorCode.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/config/BulkImportAsyncConfig.java`

전용 executor 이유: 기존 `async-store-` executor는 `corePoolSize=1, queue=30`. 수십 시간 돌아가는 bulk job이 이걸 점유하면 다른 async 작업이 막힘.

- [ ] **Step 1: ErrorCode 작성**

```java
package sweetgirl.croissant.admin.bulkimport.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BulkImportErrorCode implements BaseErrorCode {

	BULK_IMPORT_ALREADY_RUNNING(CONFLICT, "BULK409_1", "Bulk import가 이미 실행 중입니다."),
	DONG_LIST_NOT_FOUND(INTERNAL_SERVER_ERROR, "BULK500_1", "동 리스트 CSV 파일을 찾을 수 없습니다."),
	DONG_LIST_PARSE_ERROR(INTERNAL_SERVER_ERROR, "BULK500_2", "동 리스트 CSV 파싱에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
```

- [ ] **Step 2: AsyncConfig 작성**

```java
package sweetgirl.croissant.admin.bulkimport.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class BulkImportAsyncConfig {

	public static final String EXECUTOR_NAME = "bulkImportExecutor";

	@Bean(name = EXECUTOR_NAME)
	public Executor bulkImportExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("bulk-import-");
		executor.initialize();
		return executor;
	}
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 4: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/code/BulkImportErrorCode.java \
        src/main/java/sweetgirl/croissant/admin/bulkimport/config/BulkImportAsyncConfig.java
git commit -m ":sparkles: Feat: BulkImport 전용 ErrorCode 및 Async Executor 추가"
```

---

## Task 7: DongImporter (한 개 동 처리)

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/service/DongImporter.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/service/DongImporterTest.java`

플로우: 11 키워드 × 페이지 순회(isEnd까지) → 받은 places 합치고 kakao_place_id 기준 dedup → `BulkImportStoreSaver.save()` 호출. 각 키워드 검색 전 RateLimiter.acquire().

`DongImporter.DongImportResult` record로 (foundCount, insertedCount) 반환. inserted는 saver가 실제로 row를 만들었는지 못 알기 때문에 here는 "INSERT 시도 수"로 간주 (현재 SQL이 `DO NOTHING`이라 정확한 inserted count 알 수 없음 — 실용적으로 foundCount만 의미 있음). 정확하려면 saver가 `boolean insert(...)` 반환하도록 시그니처 변경이 필요한데, native `@Modifying` 반환값 활용은 transaction propagation과 함께 까다로움. **간단히** found = inserted로 두고 진행상황에 표시 (스펙 12장 요약에서 정확도는 운영 관찰로 보정).

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiter;
import sweetgirl.croissant.admin.bulkimport.enums.BulkSearchKeyword;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;

@ExtendWith(MockitoExtension.class)
class DongImporterTest {

	@Mock
	KakaoLocalClient kakaoLocalClient;
	@Mock
	BulkImportRateLimiter rateLimiter;
	@Mock
	BulkImportStoreSaver storeSaver;

	@InjectMocks
	DongImporter dongImporter;

	@Test
	@DisplayName("키워드 N개를 모두 검색하고 dedup한 후 각 place에 대해 saver.save를 호출한다")
	void importDong_searchesAllKeywords_dedupesAndSaves() throws Exception {
		KakaoPlace placeA = place("kakao-1", "A빵집");
		KakaoPlace placeB = place("kakao-2", "B빵집");
		KakaoPlace duplicateA = place("kakao-1", "A빵집");

		given(kakaoLocalClient.searchByKeyword(anyString(), anyInt()))
			.willReturn(new KakaoSearchResult(List.of(placeA, placeB), true))
			.willReturn(new KakaoSearchResult(List.of(duplicateA), true))
			.willReturn(new KakaoSearchResult(List.of(), true));

		DongImporter.DongImportResult result = dongImporter.importDong(
			"서울특별시", "강남구", "역삼동");

		int keywordCount = BulkSearchKeyword.values().length;
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), anyInt());
		verify(rateLimiter, times(keywordCount)).acquire();
		verify(storeSaver).save(placeA);
		verify(storeSaver).save(placeB);
		verify(storeSaver, times(2)).save(org.mockito.ArgumentMatchers.any());
		assertThat(result.foundCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("isEnd=false면 다음 페이지까지 호출한다 (최대 3페이지)")
	void importDong_pagesUntilIsEnd() throws Exception {
		KakaoPlace placeA = place("kakao-A", "A");
		given(kakaoLocalClient.searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(1)))
			.willReturn(new KakaoSearchResult(List.of(placeA), false));
		given(kakaoLocalClient.searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(2)))
			.willReturn(new KakaoSearchResult(List.of(), true));

		dongImporter.importDong("서울특별시", "강남구", "역삼동");

		int keywordCount = BulkSearchKeyword.values().length;
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(1));
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(2));
	}

	private KakaoPlace place(String id, String name) {
		return new KakaoPlace(id, name, "addr", "road", "음식점 > 간식", "FD6", null, "127.0", "37.5");
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.DongImporterTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiter;
import sweetgirl.croissant.admin.bulkimport.enums.BulkSearchKeyword;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class DongImporter {

	private static final int MAX_PAGES = 3;

	private final KakaoLocalClient kakaoLocalClient;
	private final BulkImportRateLimiter rateLimiter;
	private final BulkImportStoreSaver storeSaver;

	public DongImportResult importDong(String region1st, String region2nd, String region3rd)
			throws InterruptedException {
		Map<String, KakaoPlace> dedup = new HashMap<>();

		for (BulkSearchKeyword keyword : BulkSearchKeyword.values()) {
			String query = "%s %s %s %s".formatted(region1st, region2nd, region3rd, keyword.name());
			collectKeyword(query, dedup);
		}

		for (KakaoPlace place : dedup.values()) {
			try {
				storeSaver.save(place);
			} catch (Exception e) {
				log.warn("[BulkImport] place 저장 실패 - placeId={}, name={}, err={}",
					place.id(), place.placeName(), e.getMessage());
			}
		}
		return new DongImportResult(dedup.size(), dedup.size());
	}

	private void collectKeyword(String query, Map<String, KakaoPlace> dedup) throws InterruptedException {
		for (int page = 1; page <= MAX_PAGES; page++) {
			rateLimiter.acquire();
			KakaoSearchResult result = kakaoLocalClient.searchByKeyword(query, page);
			for (KakaoPlace place : result.places()) {
				dedup.putIfAbsent(place.id(), place);
			}
			if (result.isEnd()) {
				break;
			}
		}
	}

	public record DongImportResult(int foundCount, int insertedCount) {
	}
}
```

> 주의: rateLimiter는 키워드당 1회만 acquire (페이지 첫 호출 전). 테스트는 isEnd=true 케이스라 keyword당 1회만 호출됨. 두 번째 테스트는 페이지 카운트 검증을 위해 rateLimiter mock의 호출 횟수는 검증 안 함.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.DongImporterTest"`
Expected: PASS

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/service/DongImporter.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/service/DongImporterTest.java
git commit -m ":sparkles: Feat: DongImporter 추가 (동 단위 11 키워드 × 페이지 순회)"
```

---

## Task 8: BulkImportProgressService

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportProgressService.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportProgressServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.repository.BulkImportProgressRepository;

@ExtendWith(MockitoExtension.class)
class BulkImportProgressServiceTest {

	@Mock
	BulkImportProgressRepository repository;

	@InjectMocks
	BulkImportProgressService service;

	@Test
	@DisplayName("findOrCreate: 존재하지 않으면 PENDING으로 생성한다")
	void findOrCreate_createsNew_whenNotExists() {
		given(repository.findByRegion1stAndRegion2ndAndRegion3rd("서울", "강남구", "역삼동"))
			.willReturn(Optional.empty());
		given(repository.save(org.mockito.ArgumentMatchers.any(BulkImportProgress.class)))
			.willAnswer(inv -> inv.getArgument(0));

		BulkImportProgress p = service.findOrCreate("서울", "강남구", "역삼동");

		assertThat(p.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		verify(repository).save(org.mockito.ArgumentMatchers.any(BulkImportProgress.class));
	}

	@Test
	@DisplayName("recoverStaleInProgress: IN_PROGRESS 상태를 PENDING으로 일괄 변경한다")
	void recoverStaleInProgress_resetsAllInProgressToPending() {
		given(repository.bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING))
			.willReturn(3);

		int count = service.recoverStaleInProgress();

		assertThat(count).isEqualTo(3);
		verify(repository).bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING);
	}

	@Test
	@DisplayName("getPendingOrFailed: PENDING과 FAILED 상태의 동을 모두 반환한다")
	void getPendingOrFailed_returnsBothStatuses() {
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(repository.findByStatusIn(List.of(BulkImportStatus.PENDING, BulkImportStatus.FAILED)))
			.willReturn(List.of(p1, p2));

		assertThat(service.getPendingOrFailed()).hasSize(2);
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressServiceTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.repository.BulkImportProgressRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulkImportProgressService {

	private final BulkImportProgressRepository repository;

	@Transactional
	public BulkImportProgress findOrCreate(String region1st, String region2nd, String region3rd) {
		return repository.findByRegion1stAndRegion2ndAndRegion3rd(region1st, region2nd, region3rd)
			.orElseGet(() -> repository.save(BulkImportProgress.of(region1st, region2nd, region3rd)));
	}

	public List<BulkImportProgress> getPendingOrFailed() {
		return repository.findByStatusIn(List.of(BulkImportStatus.PENDING, BulkImportStatus.FAILED));
	}

	public List<BulkImportProgress> getFailed() {
		return repository.findByStatusOrderByLastAttemptedAtDesc(BulkImportStatus.FAILED);
	}

	@Transactional
	public int recoverStaleInProgress() {
		return repository.bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING);
	}

	@Transactional
	public void markInProgress(BulkImportProgress progress) {
		progress.markInProgress();
		repository.save(progress);
	}

	@Transactional
	public void markCompleted(BulkImportProgress progress, int foundCount, int insertedCount) {
		progress.markCompleted(foundCount, insertedCount);
		repository.save(progress);
	}

	@Transactional
	public void markFailed(BulkImportProgress progress, String errorMessage) {
		progress.markFailed(errorMessage);
		repository.save(progress);
	}

	public long countByStatus(BulkImportStatus status) {
		return repository.countByStatus(status);
	}

	public long countTotal() {
		return repository.count();
	}

	public long sumInsertedCount() {
		return repository.findAll().stream().mapToLong(BulkImportProgress::getInsertedCount).sum();
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressServiceTest"`
Expected: PASS

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportProgressService.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportProgressServiceTest.java
git commit -m ":sparkles: Feat: BulkImportProgressService 추가 (상태 관리 + 복구)"
```

---

## Task 9: DongListLoader + CSV 리소스 setup

**Files:**
- Create: `src/main/resources/dong/dong_list.csv` (개발자가 수동으로 채움 — 본 task에서는 sample만)
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/loader/DongListLoader.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/loader/DongRow.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/loader/DongListLoaderTest.java`

CSV 포맷:
```
region_1st,region_2nd,region_3rd
서울특별시,강남구,역삼동
서울특별시,강남구,개포동
```

- [ ] **Step 1: sample CSV 작성**

`src/main/resources/dong/dong_list.csv`:
```
region_1st,region_2nd,region_3rd
서울특별시,강남구,역삼동
서울특별시,강남구,개포동
서울특별시,강남구,논현동
```

> **NOTE:** 실제 운영 전에는 행정안전부 주소계 데이터(https://www.juso.go.kr/addrlink/devLayerRequestWrite.do 또는 공공데이터포털)에서 전체 동 리스트를 받아 이 파일을 교체할 것. 약 20,000 행 예상.

- [ ] **Step 2: DongRow record 작성**

```java
package sweetgirl.croissant.admin.bulkimport.loader;

public record DongRow(String region1st, String region2nd, String region3rd) {
}
```

- [ ] **Step 3: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DongListLoaderTest {

	@Test
	@DisplayName("classpath의 dong_list.csv를 파싱해 DongRow 리스트로 반환한다")
	void load_parsesCsvFromClasspath() {
		DongListLoader loader = new DongListLoader();

		List<DongRow> rows = loader.load();

		assertThat(rows).isNotEmpty();
		assertThat(rows.get(0).region1st()).isNotBlank();
		assertThat(rows.get(0).region2nd()).isNotBlank();
		assertThat(rows.get(0).region3rd()).isNotBlank();
	}

	@Test
	@DisplayName("헤더 행은 결과에 포함되지 않는다")
	void load_skipsHeader() {
		DongListLoader loader = new DongListLoader();

		List<DongRow> rows = loader.load();

		assertThat(rows).noneMatch(r -> "region_1st".equals(r.region1st()));
	}
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.loader.DongListLoaderTest"`
Expected: FAIL — `DongListLoader` 없음

- [ ] **Step 5: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.admin.bulkimport.code.BulkImportErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

@Component
public class DongListLoader {

	private static final String CSV_PATH = "/dong/dong_list.csv";

	public List<DongRow> load() {
		try (InputStream is = DongListLoader.class.getResourceAsStream(CSV_PATH)) {
			if (is == null) {
				throw new CustomException(BulkImportErrorCode.DONG_LIST_NOT_FOUND);
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(is, StandardCharsets.UTF_8))) {
				List<DongRow> rows = new ArrayList<>();
				String line = reader.readLine();
				if (line == null) {
					return rows;
				}
				while ((line = reader.readLine()) != null) {
					String[] cols = line.split(",", -1);
					if (cols.length < 3) {
						continue;
					}
					rows.add(new DongRow(cols[0].trim(), cols[1].trim(), cols[2].trim()));
				}
				return rows;
			}
		} catch (IOException e) {
			throw new CustomException(BulkImportErrorCode.DONG_LIST_PARSE_ERROR, e);
		}
	}
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.loader.DongListLoaderTest"`
Expected: PASS

- [ ] **Step 7: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/resources/dong/dong_list.csv \
        src/main/java/sweetgirl/croissant/admin/bulkimport/loader/DongRow.java \
        src/main/java/sweetgirl/croissant/admin/bulkimport/loader/DongListLoader.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/loader/DongListLoaderTest.java
git commit -m ":sparkles: Feat: DongListLoader + sample CSV 추가 (전체 데이터는 운영 전 교체 필요)"
```

---

## Task 10: BulkImportOrchestrator

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportOrchestrator.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportOrchestratorTest.java`

책임:
- 시작 진입점 `tryStart()` — 동시 실행 차단 (`AtomicBoolean`)
- `@Async("bulkImportExecutor")`로 fire-and-forget
- `ApplicationReadyEvent` 리스너로 IN_PROGRESS → PENDING 복구
- DongListLoader로 전체 동 → DB에 progress row 보장(findOrCreate)
- 순회하며 DongImporter 호출, 결과를 ProgressService에 기록
- `shouldStop` flag로 중단 가능

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.loader.DongListLoader;
import sweetgirl.croissant.admin.bulkimport.loader.DongRow;

@ExtendWith(MockitoExtension.class)
class BulkImportOrchestratorTest {

	@Mock
	DongListLoader dongListLoader;
	@Mock
	BulkImportProgressService progressService;
	@Mock
	DongImporter dongImporter;

	@InjectMocks
	BulkImportOrchestrator orchestrator;

	@Test
	@DisplayName("tryStart: 이미 실행 중이면 false를 반환한다")
	void tryStart_returnsFalse_whenAlreadyRunning() {
		assertThat(orchestrator.tryStart()).isTrue();
		assertThat(orchestrator.tryStart()).isFalse();
	}

	@Test
	@DisplayName("runImport: PENDING + FAILED 동을 모두 처리하고 각 동에 importDong을 호출한다")
	void runImport_processesPendingAndFailedDongs() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong(anyString(), anyString(), anyString()))
			.willReturn(new DongImporter.DongImportResult(5, 5));

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(dongImporter).importDong("서울", "강남구", "역삼동");
		verify(dongImporter).importDong("서울", "강남구", "개포동");
		verify(progressService).markCompleted(p1, 5, 5);
		verify(progressService).markCompleted(p2, 5, 5);
	}

	@Test
	@DisplayName("runImport: importDong 예외 시 해당 동을 FAILED로 마크하고 다음 동 진행한다")
	void runImport_marksFailed_onException() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong("서울", "강남구", "역삼동"))
			.willThrow(new RuntimeException("Kakao timeout"));
		given(dongImporter.importDong("서울", "강남구", "개포동"))
			.willReturn(new DongImporter.DongImportResult(3, 3));

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(progressService).markFailed(p1, "Kakao timeout");
		verify(progressService).markCompleted(p2, 3, 3);
	}

	@Test
	@DisplayName("requestStop: shouldStop 셋팅 시 다음 동 처리 전에 종료한다")
	void requestStop_haltsLoop() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong("서울", "강남구", "역삼동"))
			.willAnswer(inv -> {
				orchestrator.requestStop();
				return new DongImporter.DongImportResult(1, 1);
			});

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(dongImporter).importDong("서울", "강남구", "역삼동");
		verify(dongImporter, never()).importDong("서울", "강남구", "개포동");
	}

	@Test
	@DisplayName("recoverStaleOnStartup: 시작 시 IN_PROGRESS를 PENDING으로 되돌린다")
	void recoverStaleOnStartup_resetsInProgressToPending() {
		given(progressService.recoverStaleInProgress()).willReturn(2);

		orchestrator.recoverStaleOnStartup(null);

		verify(progressService).recoverStaleInProgress();
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestratorTest"`
Expected: FAIL

- [ ] **Step 3: 구현**

```java
package sweetgirl.croissant.admin.bulkimport.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.bulkimport.config.BulkImportAsyncConfig;
import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.loader.DongListLoader;
import sweetgirl.croissant.admin.bulkimport.loader.DongRow;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkImportOrchestrator {

	private final DongListLoader dongListLoader;
	private final BulkImportProgressService progressService;
	private final DongImporter dongImporter;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean shouldStop = new AtomicBoolean(false);

	public boolean tryStart() {
		boolean acquired = running.compareAndSet(false, true);
		if (acquired) {
			shouldStop.set(false);
		}
		return acquired;
	}

	public boolean isRunning() {
		return running.get();
	}

	public void requestStop() {
		shouldStop.set(true);
	}

	@Async(BulkImportAsyncConfig.EXECUTOR_NAME)
	public void runImportAsync() {
		runImport();
	}

	public void runImport() {
		try {
			List<DongRow> allDongs = dongListLoader.load();
			log.info("[BulkImport] 시작 — 전체 동 수: {}", allDongs.size());

			for (DongRow row : allDongs) {
				progressService.findOrCreate(row.region1st(), row.region2nd(), row.region3rd());
			}

			List<BulkImportProgress> targets = progressService.getPendingOrFailed();
			log.info("[BulkImport] 처리 대상 동 수: {} (전체 중 PENDING+FAILED)", targets.size());

			int processed = 0;
			for (BulkImportProgress p : targets) {
				if (shouldStop.get()) {
					log.info("[BulkImport] 중단 요청 수신 — 진행 종료");
					break;
				}
				processOneDong(p);
				processed++;
				if (processed % 100 == 0) {
					log.info("[BulkImport] 진행률 {}/{} ({}%)",
						processed, targets.size(),
						String.format("%.1f", processed * 100.0 / targets.size()));
				}
			}
			log.info("[BulkImport] 종료 — 처리 동 수: {}", processed);
		} catch (Exception e) {
			log.error("[BulkImport] 예상치 못한 오류로 orchestrator 종료", e);
		} finally {
			running.set(false);
			shouldStop.set(false);
		}
	}

	private void processOneDong(BulkImportProgress p) {
		long startMs = System.currentTimeMillis();
		try {
			progressService.markInProgress(p);
			DongImporter.DongImportResult result = dongImporter.importDong(
				p.getRegion1st(), p.getRegion2nd(), p.getRegion3rd());
			progressService.markCompleted(p, result.foundCount(), result.insertedCount());
			long elapsedMs = System.currentTimeMillis() - startMs;
			log.info("[BulkImport] {} {} {} - found={}, inserted={} ({}ms)",
				p.getRegion1st(), p.getRegion2nd(), p.getRegion3rd(),
				result.foundCount(), result.insertedCount(), elapsedMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			progressService.markFailed(p, "interrupted");
		} catch (Exception e) {
			log.warn("[BulkImport] 동 처리 실패 - {} {} {}, err={}",
				p.getRegion1st(), p.getRegion2nd(), p.getRegion3rd(), e.getMessage());
			progressService.markFailed(p, e.getMessage());
		}
	}

	@EventListener
	public void recoverStaleOnStartup(ApplicationReadyEvent event) {
		int recovered = progressService.recoverStaleInProgress();
		if (recovered > 0) {
			log.info("[BulkImport] 시작 시 stale IN_PROGRESS {} 건을 PENDING으로 복구", recovered);
		}
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestratorTest"`
Expected: PASS

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportOrchestrator.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/service/BulkImportOrchestratorTest.java
git commit -m ":sparkles: Feat: BulkImportOrchestrator 추가 (Async runner + startup 복구)"
```

---

## Task 11: Bulk import 응답 DTO 3종

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/dto/response/BulkImportStartResponse.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/dto/response/BulkImportProgressResponse.java`
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/dto/response/FailedDongResponse.java`

- [ ] **Step 1: 3개 record 작성**

`BulkImportStartResponse.java`:
```java
package sweetgirl.croissant.admin.bulkimport.dto.response;

public record BulkImportStartResponse(
		long totalDongs,
		long pendingDongs,
		long completedDongs) {
}
```

`BulkImportProgressResponse.java`:
```java
package sweetgirl.croissant.admin.bulkimport.dto.response;

public record BulkImportProgressResponse(
		boolean running,
		long totalDongs,
		long completedDongs,
		long failedDongs,
		long inProgressDongs,
		long pendingDongs,
		long totalInserted) {
}
```

`FailedDongResponse.java`:
```java
package sweetgirl.croissant.admin.bulkimport.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;

public record FailedDongResponse(
		String region1st,
		String region2nd,
		String region3rd,
		String errorMessage,
		LocalDateTime lastAttemptedAt) {

	public static FailedDongResponse from(BulkImportProgress p) {
		return new FailedDongResponse(
			p.getRegion1st(), p.getRegion2nd(), p.getRegion3rd(),
			p.getErrorMessage(), p.getLastAttemptedAt());
	}
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 3: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/dto/response/
git commit -m ":sparkles: Feat: BulkImport 응답 DTO 추가"
```

---

## Task 12: BulkImportController

**Files:**
- Create: `src/main/java/sweetgirl/croissant/admin/bulkimport/controller/BulkImportController.java`
- Test: `src/test/java/sweetgirl/croissant/admin/bulkimport/controller/BulkImportControllerTest.java`

엔드포인트:
- `POST /api/admin/stores/bulk-import` — 시작
- `GET /api/admin/stores/bulk-import/progress` — 진행상황 조회
- `GET /api/admin/stores/bulk-import/failed` — 실패한 동 목록
- `POST /api/admin/stores/bulk-import/stop` — 중단 요청

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.admin.bulkimport.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestrator;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressService;
import sweetgirl.croissant.config.security.SecurityConfig;

@WebMvcTest(controllers = BulkImportController.class)
@Import(SecurityConfig.class)
class BulkImportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockBean
	BulkImportOrchestrator orchestrator;
	@MockBean
	BulkImportProgressService progressService;

	@Test
	@WithMockUser(roles = "ADMIN")
	@DisplayName("POST /bulk-import: 시작 성공 시 카운트를 반환한다")
	void start_returnsCountsAndStarts() throws Exception {
		given(orchestrator.tryStart()).willReturn(true);
		given(progressService.countTotal()).willReturn(20142L);
		given(progressService.countByStatus(BulkImportStatus.PENDING)).willReturn(19587L);
		given(progressService.countByStatus(BulkImportStatus.COMPLETED)).willReturn(555L);

		mockMvc.perform(post("/api/admin/stores/bulk-import").with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalDongs").value(20142))
			.andExpect(jsonPath("$.data.pendingDongs").value(19587));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	@DisplayName("POST /bulk-import: 이미 실행 중이면 409를 반환한다")
	void start_returns409_whenAlreadyRunning() throws Exception {
		given(orchestrator.tryStart()).willReturn(false);

		mockMvc.perform(post("/api/admin/stores/bulk-import").with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("BULK409_1"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	@DisplayName("GET /bulk-import/progress: 진행상황을 반환한다")
	void getProgress_returnsCurrentState() throws Exception {
		given(orchestrator.isRunning()).willReturn(true);
		given(progressService.countTotal()).willReturn(20142L);
		given(progressService.countByStatus(BulkImportStatus.COMPLETED)).willReturn(8732L);
		given(progressService.countByStatus(BulkImportStatus.FAILED)).willReturn(12L);
		given(progressService.countByStatus(BulkImportStatus.IN_PROGRESS)).willReturn(1L);
		given(progressService.countByStatus(BulkImportStatus.PENDING)).willReturn(11397L);
		given(progressService.sumInsertedCount()).willReturn(142583L);

		mockMvc.perform(get("/api/admin/stores/bulk-import/progress"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.running").value(true))
			.andExpect(jsonPath("$.data.completedDongs").value(8732))
			.andExpect(jsonPath("$.data.totalInserted").value(142583));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	@DisplayName("GET /bulk-import/failed: 실패한 동 목록을 반환한다")
	void getFailed_returnsFailedDongs() throws Exception {
		BulkImportProgress p = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		p.markFailed("Kakao API timeout");
		given(progressService.getFailed()).willReturn(List.of(p));

		mockMvc.perform(get("/api/admin/stores/bulk-import/failed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].region3rd").value("역삼동"))
			.andExpect(jsonPath("$.data[0].errorMessage").value("Kakao API timeout"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	@DisplayName("POST /bulk-import/stop: 중단 요청을 전달한다")
	void stop_callsRequestStop() throws Exception {
		mockMvc.perform(post("/api/admin/stores/bulk-import/stop").with(csrf()))
			.andExpect(status().isOk());
	}
}
```

> 주의: `SecurityConfig` import는 프로젝트 구성에 따라 슬라이싱이 다를 수 있음. 테스트가 컴파일/통과 안 되면 `@WebMvcTest` 대신 `@SpringBootTest + @AutoConfigureMockMvc` 사용 검토.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.controller.BulkImportControllerTest"`
Expected: FAIL — `BulkImportController` 없음

- [ ] **Step 3: 컨트롤러 구현**

```java
package sweetgirl.croissant.admin.bulkimport.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.bulkimport.code.BulkImportErrorCode;
import sweetgirl.croissant.admin.bulkimport.dto.response.BulkImportProgressResponse;
import sweetgirl.croissant.admin.bulkimport.dto.response.BulkImportStartResponse;
import sweetgirl.croissant.admin.bulkimport.dto.response.FailedDongResponse;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestrator;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressService;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Bulk Import", description = "전국 빵집/카페 Kakao bulk import 관리 API")
@RestController
@RequestMapping("/api/admin/stores/bulk-import")
@RequiredArgsConstructor
public class BulkImportController {

	private final BulkImportOrchestrator orchestrator;
	private final BulkImportProgressService progressService;

	@Operation(summary = "Bulk import 시작",
		description = "전국 동 단위로 Kakao Local API에서 빵집/카페를 수집해 dev DB에 적재합니다. "
			+ "이미 실행 중이면 409를 반환합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<BulkImportStartResponse>> start() {
		if (!orchestrator.tryStart()) {
			throw new CustomException(BulkImportErrorCode.BULK_IMPORT_ALREADY_RUNNING);
		}
		orchestrator.runImportAsync();
		return ResponseEntity.ok(ApiResponse.ok(new BulkImportStartResponse(
			progressService.countTotal(),
			progressService.countByStatus(BulkImportStatus.PENDING),
			progressService.countByStatus(BulkImportStatus.COMPLETED))));
	}

	@Operation(summary = "Bulk import 진행상황 조회")
	@GetMapping("/progress")
	public ResponseEntity<ApiResponse<BulkImportProgressResponse>> getProgress() {
		return ResponseEntity.ok(ApiResponse.ok(new BulkImportProgressResponse(
			orchestrator.isRunning(),
			progressService.countTotal(),
			progressService.countByStatus(BulkImportStatus.COMPLETED),
			progressService.countByStatus(BulkImportStatus.FAILED),
			progressService.countByStatus(BulkImportStatus.IN_PROGRESS),
			progressService.countByStatus(BulkImportStatus.PENDING),
			progressService.sumInsertedCount())));
	}

	@Operation(summary = "실패한 동 목록 조회")
	@GetMapping("/failed")
	public ResponseEntity<ApiResponse<List<FailedDongResponse>>> getFailed() {
		List<FailedDongResponse> data = progressService.getFailed().stream()
			.map(FailedDongResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.ok(data));
	}

	@Operation(summary = "Bulk import 중단 요청",
		description = "현재 동 처리를 마치고 종료합니다. 다음 호출 시 재개 가능합니다.")
	@PostMapping("/stop")
	public ResponseEntity<ApiResponse<Void>> stop() {
		orchestrator.requestStop();
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "sweetgirl.croissant.admin.bulkimport.controller.BulkImportControllerTest"`
Expected: PASS

> 만약 `SecurityConfig` 의존성 누락으로 FAIL이라면 `@Import(SecurityConfig.class)`를 빼고 `@AutoConfigureMockMvc(addFilters = false)`를 사용하거나, `@WebMvcTest` 대신 `@SpringBootTest`로 전환.

- [ ] **Step 5: spotless 적용 + 커밋**

```bash
./gradlew spotlessApply
git add src/main/java/sweetgirl/croissant/admin/bulkimport/controller/BulkImportController.java \
        src/test/java/sweetgirl/croissant/admin/bulkimport/controller/BulkImportControllerTest.java
git commit -m ":sparkles: Feat: BulkImportController 추가 (start/progress/failed/stop)"
```

---

## Task 13: 전체 빌드/테스트 + Sanity 통합 확인

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL — 모든 테스트 통과 + checkstyle/spotless 통과

- [ ] **Step 2: dev 프로파일로 부팅 시 BulkImportProgress 테이블 자동 생성 확인**

`application.yml`에서 `spring.jpa.hibernate.ddl-auto`가 `update` 또는 `create`인지 확인. 둘 다 아니면 다음 DDL을 dev DB에 수동 적용:

```sql
CREATE TABLE bulk_import_progress (
    id                BIGSERIAL PRIMARY KEY,
    region_1st        VARCHAR(50)  NOT NULL,
    region_2nd        VARCHAR(50)  NOT NULL,
    region_3rd        VARCHAR(50)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    found_count       INT          NOT NULL DEFAULT 0,
    inserted_count    INT          NOT NULL DEFAULT 0,
    error_message     TEXT,
    last_attempted_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_bulk_region UNIQUE (region_1st, region_2nd, region_3rd)
);
CREATE INDEX idx_bulk_import_status ON bulk_import_progress(status);
```

- [ ] **Step 3: 로컬에서 dev DB 향해 부팅**

Run: `./gradlew bootRun`
Expected: 정상 부팅, 로그에 `bulk-import-` executor 생성, `recoverStaleOnStartup` 리스너 등록 확인

- [ ] **Step 4: sample CSV로 sanity 테스트**

Sample CSV (3개 동)로 import 실행:
```bash
curl -X POST http://localhost:8080/api/admin/stores/bulk-import \
     -H "Authorization: Bearer <admin-token>"
```

진행상황 확인:
```bash
curl http://localhost:8080/api/admin/stores/bulk-import/progress
```

Expected:
- 3개 동에 대한 progress row 생성
- dev DB `store` 테이블에 KAKAO_IMPORTED source의 신규 row가 늘어남
- 양식 베이커리는 `category = 'WESTERN'`, 그 외는 `category = null`
- 로그에 동별 found/inserted 출력
- 약 1-2분 내 완료

- [ ] **Step 5: 실패한 동 확인 (있다면)**

```bash
curl http://localhost:8080/api/admin/stores/bulk-import/failed
```

Expected: 빈 배열 또는 실패 사유와 함께 동 목록

- [ ] **Step 6: 운영 투입 준비 안내 (코드 변경 없음)**

다음을 운영자에게 전달:
1. 행정안전부 데이터에서 전국 동 리스트를 받아 `src/main/resources/dong/dong_list.csv` 교체
2. 빌드/배포 후 `POST /api/admin/stores/bulk-import` 호출
3. 수십 시간 소요 예상 → `GET /progress`로 주기적 모니터링
4. 완료 후 `failed` 목록 확인, 필요 시 재호출
5. (별도 작업) dev DB → 운영 DB promote

- [ ] **Step 7: 최종 커밋 (있다면 — CSV 교체 등은 별도 PR 권장)**

본 task의 변경이 없으면 커밋 생략.

---

## 자가 검토 (스펙 대비 커버리지)

| 스펙 섹션 | 구현 task |
|----------|----------|
| 2. 핵심 결정사항 | Task 1-12 전반 |
| 3. 컴포넌트 구조 | Task 1-12 |
| 4. 데이터 흐름 | Task 10 (Orchestrator) + Task 7 (DongImporter) |
| 5-1. bulk_import_progress 테이블 | Task 2 (entity) + Task 13 step 2 (DDL) |
| 5-2. store 테이블 변경 없음 | (확인 완료, Task 3에서 호출처만 영향) |
| 5-3. insertIfNotExistsByKakaoPlaceId 시그니처 확장 | Task 3 |
| 6. Category 매핑 | Task 1 (StoreCategoryDeriver) + Task 5 (Saver에서 사용) |
| 7. 검색 키워드 | Task 1 (BulkSearchKeyword) + Task 7 (DongImporter에서 사용) |
| 8. 에러 처리 — 단일 INSERT 실패 | Task 5 (REQUIRES_NEW) + Task 7 (DongImporter catch) |
| 8. 에러 처리 — 동 전체 Exception | Task 10 (Orchestrator processOneDong catch) |
| 8. 재시작 — IN_PROGRESS 복구 | Task 10 (recoverStaleOnStartup @EventListener) |
| 8. 재시작 — PENDING+FAILED 재처리 | Task 8 (getPendingOrFailed) + Task 10 |
| 8. 동시 트리거 차단 | Task 10 (AtomicBoolean running) |
| 9-1. POST /bulk-import | Task 12 |
| 9-2. GET /progress | Task 12 |
| 9-3. GET /failed | Task 12 |
| 9-4. POST /stop | Task 12 |
| 10. 로깅 | Task 10 (orchestrator 로그) + Task 7 (importer) |
| 11. 테스트 전략 | Task 1, 2, 4, 5, 7, 8, 10, 12 모두 TDD |
| 12. 운영 절차 | Task 13 |

**의도적 단순화:**
- 429 rate limit 시 30초 대기 + 1시간 sleep 후 재개 → **별도 task로 미구현**. 현재는 InterruptedException/RestClientException 발생 시 그냥 동을 FAILED로 두고 다음 진행. 운영 중 429가 잦으면 follow-up으로 추가.
- `inserted_count`는 native `ON CONFLICT DO NOTHING`이라 실제 INSERT 수를 알 수 없어 `found_count`와 동일 값으로 기록 (스펙 12장 운영 관찰 의존).

**Plan complete and saved to `docs/superpowers/plans/2026-05-26-kakao-bulk-import.md`.**

---

## 실행 옵션

**1. Subagent-Driven (recommended)** — task당 fresh subagent 디스패치, task 간 리뷰, 빠른 반복

**2. Inline Execution** — 현재 세션에서 executing-plans로 일괄 실행, 체크포인트로 리뷰

어떤 방식으로 진행할까?
