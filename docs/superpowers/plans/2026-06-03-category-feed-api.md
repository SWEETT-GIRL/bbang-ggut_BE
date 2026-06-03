# 카테고리 순위 피드 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 블로그 점수(`docs/reports/*_v3.json`)를 `bbang_score` 스키마에 적재하고, `GET /feed/categories?region=공덕동`으로 카테고리별 가게 랭킹(점수 배지 포함)을 앱에 제공한다.

**Architecture:** 운영 PG(`soboro`)와 같은 인스턴스에 `bbang_score` 스키마를 분리. Python 로더가 최신 v3 JSON을 그 스키마에 upsert하고 region별 active run을 1개로 유지. Spring(단일 datasource, JPA)이 active run을 읽어 카테고리별 top10을 구성하고, 운영 스키마의 `store_image_file`에서 썸네일을 배치 조인한다. 카테고리 노출 순서는 JSON이 제공한 강도순(이 동네 유명 카테고리 순)을 그대로 보존한다.

**Tech Stack:** Java 17 · Spring Boot · JPA/Hibernate · PostgreSQL · Python 3 (psycopg2) · JUnit5/Mockito/AssertJ

---

## 스펙 대비 보정 사항 (읽고 시작)

- spec(§4.3·§6)은 "카테고리 표준 순서(enum.order)"라 적었으나, 실제 `score_v3`는 `categories`를 **강도순으로 정렬**해 내보내고 사용자 참조 화면도 그 순서다. → **JSON 순서를 보존**한다(각 카테고리의 `category_order`를 적재). enum은 라벨↔코드 매핑에만 쓰고 정렬엔 쓰지 않는다.
- `SecurityConfig`에 이미 `.requestMatchers("/**").permitAll()`이 있어 `/feed/**`는 **자동 공개**. SecurityConfig 수정 불필요.
- `bread_category` 컬럼에는 **JSON의 한글 라벨**을 그대로 저장한다(로더는 Java enum을 모름). Java `BreadCategoryGroup.fromLabel()`이 코드로 매핑한다.
- `application-local.properties`는 `ddl-auto=validate` → bbang_score 테이블이 **앱 기동 전에 존재**해야 한다. 로더가 `CREATE ... IF NOT EXISTS`로 먼저 만든다(Task 6 → Task 8 순서). CI는 `build -x test`라 부팅/DB 접근이 없어 영향 없음.

## 카테고리 라벨 ↔ 코드 (확정)

`scripts/blog_score/dictionaries.py`의 `GROUP_MEMBERS` 키 = JSON `categories` 키 = 아래 10개.

| 한글 라벨(JSON 키) | 코드 |
|---|---|
| 소금빵 | `SALT_BREAD` |
| 크루아상·페이스트리 | `CROISSANT` |
| 식사빵·하드빵 | `HARD_BREAD` |
| 베이글 | `BAGEL` |
| 조리빵·샌드위치 | `SAVORY` |
| 케이크 | `CAKE` |
| 타르트·파이 | `TART` |
| 구움과자 | `BAKED_SWEET` |
| 도넛·디저트 | `DONUT` |
| 단과자·크림빵 | `SWEET_BUN` |

## File Structure

```
src/main/java/sweetgirl/croissant/feed/
├── controller/FeedController.java              GET /feed/categories
├── service/CategoryFeedService.java            run조회 → 그룹핑/top10 → 썸네일조인 → 조립
├── repository/BlogCrawlRunRepository.java       findByRegion3rdAndIsActiveTrue
├── repository/StoreCategoryScoreRepository.java findByRunIdOrderByCategoryOrderAscNormalizedDesc
├── entity/BlogCrawlRun.java                     @Table(schema="bbang_score", name="blog_crawl_run")
├── entity/StoreCategoryScore.java               @Table(schema="bbang_score", name="store_category_score")
├── enums/BreadCategoryGroup.java                라벨↔코드
└── dto/response/{CategoryFeedResponse,CategoryBlock,FeedStore}.java

src/test/java/sweetgirl/croissant/feed/
├── enums/BreadCategoryGroupTest.java
├── service/CategoryFeedServiceTest.java
└── controller/FeedControllerTest.java

scripts/blog_score/
├── load_to_db.py                                DDL + JSON→DB upsert + active 토글
└── tests/test_load_to_db.py                     transform 순수함수 단위테스트
```

재사용: `StoreImageFileRepository.findThumbnailsByStoreIdIn(List<Long>)` → `StoreImageThumbnailProjection(storeId, awsKey)`. `scan_placeid_mismatch.db_config()`(application-local.properties 파싱).

---

### Task 1: BreadCategoryGroup enum (라벨↔코드)

**Files:**
- Create: `src/main/java/sweetgirl/croissant/feed/enums/BreadCategoryGroup.java`
- Test: `src/test/java/sweetgirl/croissant/feed/enums/BreadCategoryGroupTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.feed.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class BreadCategoryGroupTest {

	@Test
	void fromLabel_returnsMatchingGroup() {
		assertThat(BreadCategoryGroup.fromLabel("소금빵")).contains(BreadCategoryGroup.SALT_BREAD);
		assertThat(BreadCategoryGroup.fromLabel("단과자·크림빵")).contains(BreadCategoryGroup.SWEET_BUN);
	}

	@Test
	void fromLabel_returnsEmpty_whenUnknown() {
		assertThat(BreadCategoryGroup.fromLabel("없는카테고리")).isEqualTo(Optional.empty());
	}

	@Test
	void code_isEnumName() {
		assertThat(BreadCategoryGroup.CROISSANT.getCode()).isEqualTo("CROISSANT");
		assertThat(BreadCategoryGroup.CROISSANT.getLabel()).isEqualTo("크루아상·페이스트리");
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*BreadCategoryGroupTest" --no-daemon`
Expected: FAIL (BreadCategoryGroup 클래스 없음 → 컴파일 에러)

- [ ] **Step 3: enum 구현**

```java
package sweetgirl.croissant.feed.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BreadCategoryGroup {

	SALT_BREAD("소금빵"),
	CROISSANT("크루아상·페이스트리"),
	HARD_BREAD("식사빵·하드빵"),
	BAGEL("베이글"),
	SAVORY("조리빵·샌드위치"),
	CAKE("케이크"),
	TART("타르트·파이"),
	BAKED_SWEET("구움과자"),
	DONUT("도넛·디저트"),
	SWEET_BUN("단과자·크림빵");

	private final String label;

	public String getCode() {
		return name();
	}

	public static Optional<BreadCategoryGroup> fromLabel(String label) {
		return Arrays.stream(values())
			.filter(group -> group.label.equals(label))
			.findFirst();
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*BreadCategoryGroupTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/feed/enums/BreadCategoryGroup.java src/test/java/sweetgirl/croissant/feed/enums/BreadCategoryGroupTest.java
git commit -m "feat(feed): BreadCategoryGroup enum 라벨↔코드 매핑"
```

---

### Task 2: JPA 엔티티 + 리포지토리 (bbang_score 스키마)

**Files:**
- Create: `src/main/java/sweetgirl/croissant/feed/entity/BlogCrawlRun.java`
- Create: `src/main/java/sweetgirl/croissant/feed/entity/StoreCategoryScore.java`
- Create: `src/main/java/sweetgirl/croissant/feed/repository/BlogCrawlRunRepository.java`
- Create: `src/main/java/sweetgirl/croissant/feed/repository/StoreCategoryScoreRepository.java`

> 엔티티는 읽기 전용 조회에만 쓰므로 setter/빌더 없이 getter만. `run_id`는 단순 Long(연관관계 매핑 안 함 — 지연로딩/조인 회피).

- [ ] **Step 1: BlogCrawlRun 엔티티 작성**

```java
package sweetgirl.croissant.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "bbang_score", name = "blog_crawl_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlogCrawlRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "region_3rd", nullable = false, length = 50)
	private String region3rd;

	@Column(name = "run_key", nullable = false, length = 64)
	private String runKey;

	@Column(name = "is_active", nullable = false)
	private boolean active;
}
```

- [ ] **Step 2: StoreCategoryScore 엔티티 작성**

```java
package sweetgirl.croissant.feed.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(schema = "bbang_score", name = "store_category_score")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreCategoryScore {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "run_id", nullable = false)
	private Long runId;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "bread_category", nullable = false, length = 64)
	private String breadCategory;

	@Column(name = "category_order", nullable = false)
	private int categoryOrder;

	@Column(nullable = false, precision = 5, scale = 1)
	private BigDecimal normalized;

	@Column(name = "mention_count", nullable = false)
	private int mentionCount;

	@Column(name = "recent_count", nullable = false)
	private int recentCount;

	@Column(name = "review_count")
	private Integer reviewCount;

	@Column(precision = 3, scale = 2)
	private BigDecimal rating;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 300)
	private String address;
}
```

- [ ] **Step 3: 리포지토리 작성**

`BlogCrawlRunRepository.java`:
```java
package sweetgirl.croissant.feed.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.feed.entity.BlogCrawlRun;

public interface BlogCrawlRunRepository extends JpaRepository<BlogCrawlRun, Long> {

	Optional<BlogCrawlRun> findByRegion3rdAndActiveTrue(String region3rd);
}
```

`StoreCategoryScoreRepository.java`:
```java
package sweetgirl.croissant.feed.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.feed.entity.StoreCategoryScore;

public interface StoreCategoryScoreRepository extends JpaRepository<StoreCategoryScore, Long> {

	List<StoreCategoryScore> findByRunIdOrderByCategoryOrderAscNormalizedDesc(Long runId);
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/feed/entity/ src/main/java/sweetgirl/croissant/feed/repository/
git commit -m "feat(feed): bbang_score 엔티티/리포지토리 추가"
```

---

### Task 3: 응답 DTO (record)

**Files:**
- Create: `src/main/java/sweetgirl/croissant/feed/dto/response/FeedStore.java`
- Create: `src/main/java/sweetgirl/croissant/feed/dto/response/CategoryBlock.java`
- Create: `src/main/java/sweetgirl/croissant/feed/dto/response/CategoryFeedResponse.java`

- [ ] **Step 1: FeedStore 작성**

```java
package sweetgirl.croissant.feed.dto.response;

public record FeedStore(
		Long storeId,
		String name,
		String thumbnailUrl,
		int score,
		int mentionCount,
		Integer reviewCount,
		Double rating) {
}
```

- [ ] **Step 2: CategoryBlock 작성**

```java
package sweetgirl.croissant.feed.dto.response;

import java.util.List;

public record CategoryBlock(
		String code,
		String label,
		int storeCount,
		List<FeedStore> stores) {
}
```

- [ ] **Step 3: CategoryFeedResponse 작성**

```java
package sweetgirl.croissant.feed.dto.response;

import java.util.List;

public record CategoryFeedResponse(
		String region,
		String run,
		List<CategoryBlock> categories) {
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/feed/dto/response/
git commit -m "feat(feed): 카테고리 피드 응답 DTO 추가"
```

---

### Task 4: CategoryFeedService (그룹핑·top10·빈카테고리 제외·썸네일 조인)

**Files:**
- Create: `src/main/java/sweetgirl/croissant/feed/service/CategoryFeedService.java`
- Test: `src/test/java/sweetgirl/croissant/feed/service/CategoryFeedServiceTest.java`

규칙: active run 없으면 `categories: []`, `run: null`. 카테고리 순서 = `categoryOrder` 오름차순. 카테고리당 `normalized` 내림차순 최대 10개. 알 수 없는 라벨은 제외. `score = normalized` 반올림(int). 썸네일은 전체 storeId 배치 조회 후 storeId당 첫 awsKey.

- [ ] **Step 1: 실패 테스트 작성**

```java
package sweetgirl.croissant.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.feed.dto.response.CategoryFeedResponse;
import sweetgirl.croissant.feed.entity.BlogCrawlRun;
import sweetgirl.croissant.feed.entity.StoreCategoryScore;
import sweetgirl.croissant.feed.repository.BlogCrawlRunRepository;
import sweetgirl.croissant.feed.repository.StoreCategoryScoreRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class CategoryFeedServiceTest {

	@Mock
	BlogCrawlRunRepository blogCrawlRunRepository;
	@Mock
	StoreCategoryScoreRepository storeCategoryScoreRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;

	@InjectMocks
	CategoryFeedService categoryFeedService;

	private static StoreCategoryScore score(Long storeId, String label, int order, double normalized) {
		StoreCategoryScore s = new StoreCategoryScore();
		ReflectionTestUtils.setField(s, "storeId", storeId);
		ReflectionTestUtils.setField(s, "breadCategory", label);
		ReflectionTestUtils.setField(s, "categoryOrder", order);
		ReflectionTestUtils.setField(s, "normalized", BigDecimal.valueOf(normalized));
		ReflectionTestUtils.setField(s, "mentionCount", 5);
		ReflectionTestUtils.setField(s, "recentCount", 2);
		ReflectionTestUtils.setField(s, "reviewCount", 100);
		ReflectionTestUtils.setField(s, "rating", BigDecimal.valueOf(4.5));
		ReflectionTestUtils.setField(s, "name", "가게" + storeId);
		return s;
	}

	private static StoreImageThumbnailProjection thumb(Long storeId, String awsKey) {
		return new StoreImageThumbnailProjection() {
			public Long getStoreId() {
				return storeId;
			}

			public String getAwsKey() {
				return awsKey;
			}
		};
	}

	@Test
	void getFeed_returnsEmpty_whenNoActiveRun() {
		given(blogCrawlRunRepository.findByRegion3rdAndActiveTrue("없는동")).willReturn(Optional.empty());

		CategoryFeedResponse result = categoryFeedService.getFeed("없는동");

		assertThat(result.run()).isNull();
		assertThat(result.categories()).isEmpty();
	}

	@Test
	void getFeed_groupsByCategory_ordersByCategoryOrderThenScore() {
		BlogCrawlRun run = new BlogCrawlRun();
		ReflectionTestUtils.setField(run, "id", 7L);
		ReflectionTestUtils.setField(run, "runKey", "RUN1");
		given(blogCrawlRunRepository.findByRegion3rdAndActiveTrue("공덕동")).willReturn(Optional.of(run));
		given(storeCategoryScoreRepository.findByRunIdOrderByCategoryOrderAscNormalizedDesc(7L))
			.willReturn(List.of(
				score(1L, "베이글", 0, 90.0),
				score(2L, "베이글", 0, 70.0),
				score(3L, "소금빵", 1, 55.0)));
		given(storeImageFileRepository.findThumbnailsByStoreIdIn(List.of(1L, 2L, 3L)))
			.willReturn(List.of(thumb(1L, "k1.jpg")));

		CategoryFeedResponse result = categoryFeedService.getFeed("공덕동");

		assertThat(result.run()).isEqualTo("RUN1");
		assertThat(result.categories()).hasSize(2);
		assertThat(result.categories().get(0).code()).isEqualTo("BAGEL");
		assertThat(result.categories().get(0).storeCount()).isEqualTo(2);
		assertThat(result.categories().get(0).stores().get(0).storeId()).isEqualTo(1L);
		assertThat(result.categories().get(0).stores().get(0).score()).isEqualTo(90);
		assertThat(result.categories().get(0).stores().get(0).thumbnailUrl()).isEqualTo("k1.jpg");
		assertThat(result.categories().get(0).stores().get(1).thumbnailUrl()).isNull();
		assertThat(result.categories().get(1).code()).isEqualTo("SALT_BREAD");
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*CategoryFeedServiceTest" --no-daemon`
Expected: FAIL (CategoryFeedService 없음 → 컴파일 에러)

- [ ] **Step 3: 서비스 구현**

```java
package sweetgirl.croissant.feed.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.feed.dto.response.CategoryBlock;
import sweetgirl.croissant.feed.dto.response.CategoryFeedResponse;
import sweetgirl.croissant.feed.dto.response.FeedStore;
import sweetgirl.croissant.feed.entity.BlogCrawlRun;
import sweetgirl.croissant.feed.entity.StoreCategoryScore;
import sweetgirl.croissant.feed.enums.BreadCategoryGroup;
import sweetgirl.croissant.feed.repository.BlogCrawlRunRepository;
import sweetgirl.croissant.feed.repository.StoreCategoryScoreRepository;
import sweetgirl.croissant.store.dto.projection.StoreImageThumbnailProjection;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryFeedService {

	private static final int MAX_STORES_PER_CATEGORY = 10;

	private final BlogCrawlRunRepository blogCrawlRunRepository;
	private final StoreCategoryScoreRepository storeCategoryScoreRepository;
	private final StoreImageFileRepository storeImageFileRepository;

	public CategoryFeedResponse getFeed(String region) {
		Optional<BlogCrawlRun> activeRun = blogCrawlRunRepository.findByRegion3rdAndActiveTrue(region);
		if (activeRun.isEmpty()) {
			return new CategoryFeedResponse(region, null, List.of());
		}
		BlogCrawlRun run = activeRun.get();

		List<StoreCategoryScore> rows =
			storeCategoryScoreRepository.findByRunIdOrderByCategoryOrderAscNormalizedDesc(run.getId());

		Map<Long, String> thumbnails = loadThumbnails(rows);

		// 라벨별로 묶음 (입력이 categoryOrder→normalized desc 정렬이라 순서 보존)
		Map<String, List<StoreCategoryScore>> byCategory = new LinkedHashMap<>();
		for (StoreCategoryScore row : rows) {
			byCategory.computeIfAbsent(row.getBreadCategory(), key -> new java.util.ArrayList<>()).add(row);
		}

		List<CategoryBlock> blocks = new java.util.ArrayList<>();
		for (Map.Entry<String, List<StoreCategoryScore>> entry : byCategory.entrySet()) {
			Optional<BreadCategoryGroup> group = BreadCategoryGroup.fromLabel(entry.getKey());
			if (group.isEmpty()) {
				continue; // 알 수 없는 라벨 방어
			}
			List<StoreCategoryScore> stores = entry.getValue();
			List<FeedStore> feedStores = stores.stream()
				.limit(MAX_STORES_PER_CATEGORY)
				.map(row -> toFeedStore(row, thumbnails))
				.toList();
			blocks.add(new CategoryBlock(
				group.get().getCode(), group.get().getLabel(), stores.size(), feedStores));
		}

		return new CategoryFeedResponse(region, run.getRunKey(), blocks);
	}

	private Map<Long, String> loadThumbnails(List<StoreCategoryScore> rows) {
		List<Long> storeIds = rows.stream().map(StoreCategoryScore::getStoreId).distinct().toList();
		if (storeIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> map = new java.util.HashMap<>();
		for (StoreImageThumbnailProjection thumb : storeImageFileRepository.findThumbnailsByStoreIdIn(storeIds)) {
			map.putIfAbsent(thumb.getStoreId(), thumb.getAwsKey()); // storeId당 첫 awsKey
		}
		return map;
	}

	private FeedStore toFeedStore(StoreCategoryScore row, Map<Long, String> thumbnails) {
		return new FeedStore(
			row.getStoreId(),
			row.getName(),
			thumbnails.get(row.getStoreId()),
			row.getNormalized().setScale(0, java.math.RoundingMode.HALF_UP).intValue(),
			row.getMentionCount(),
			row.getReviewCount(),
			row.getRating() == null ? null : row.getRating().doubleValue());
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*CategoryFeedServiceTest" --no-daemon`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/feed/service/CategoryFeedService.java src/test/java/sweetgirl/croissant/feed/service/CategoryFeedServiceTest.java
git commit -m "feat(feed): CategoryFeedService — 카테고리 그룹핑/top10/썸네일 조인"
```

---

### Task 5: FeedController + MockMvc 테스트

**Files:**
- Create: `src/main/java/sweetgirl/croissant/feed/controller/FeedController.java`
- Test: `src/test/java/sweetgirl/croissant/feed/controller/FeedControllerTest.java`

- [ ] **Step 1: 실패 테스트 작성 (standalone MockMvc — security/JWT 우회)**

```java
package sweetgirl.croissant.feed.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.feed.dto.response.CategoryBlock;
import sweetgirl.croissant.feed.dto.response.CategoryFeedResponse;
import sweetgirl.croissant.feed.dto.response.FeedStore;
import sweetgirl.croissant.feed.service.CategoryFeedService;

@ExtendWith(MockitoExtension.class)
class FeedControllerTest {

	@Mock
	CategoryFeedService categoryFeedService;

	@InjectMocks
	FeedController feedController;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(feedController).build();
	}

	@Test
	void getCategories_returnsFeed() throws Exception {
		CategoryFeedResponse response = new CategoryFeedResponse("공덕동", "RUN1", List.of(
			new CategoryBlock("BAGEL", "베이글", 1,
				List.of(new FeedStore(1L, "가게1", "k1.jpg", 87, 5, 100, 4.5)))));
		given(categoryFeedService.getFeed("공덕동")).willReturn(response);

		mockMvc.perform(get("/feed/categories").param("region", "공덕동"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.region").value("공덕동"))
			.andExpect(jsonPath("$.data.categories[0].code").value("BAGEL"))
			.andExpect(jsonPath("$.data.categories[0].stores[0].score").value(87));
	}

	@Test
	void getCategories_returns400_whenRegionMissing() throws Exception {
		mockMvc.perform(get("/feed/categories"))
			.andExpect(status().isBadRequest());
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*FeedControllerTest" --no-daemon`
Expected: FAIL (FeedController 없음)

- [ ] **Step 3: 컨트롤러 구현**

```java
package sweetgirl.croissant.feed.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.feed.dto.response.CategoryFeedResponse;
import sweetgirl.croissant.feed.service.CategoryFeedService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Feed", description = "홈 카테고리 순위 피드 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/feed")
public class FeedController {

	private final CategoryFeedService categoryFeedService;

	@Operation(summary = "카테고리별 순위 피드", description = "지역(region_3rd)의 카테고리별 가게 랭킹을 반환합니다.")
	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<CategoryFeedResponse>> getCategories(
			@Parameter(description = "지역 동명 (예: 공덕동)") @RequestParam String region) {
		return ResponseEntity.ok(ApiResponse.ok(categoryFeedService.getFeed(region)));
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*FeedControllerTest" --no-daemon`
Expected: PASS (2 tests)

> `region` 누락 시 standalone MockMvc는 `MissingServletRequestParameterException`을 400으로 처리한다. 만약 400이 아닌 500이 나오면, 테스트의 두 번째 케이스를 standalone 기본 동작에 맞게 두고(이미 400) 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/sweetgirl/croissant/feed/controller/FeedController.java src/test/java/sweetgirl/croissant/feed/controller/FeedControllerTest.java
git commit -m "feat(feed): GET /feed/categories 엔드포인트"
```

---

### Task 6: Python 로더 `load_to_db.py` (DDL + JSON→DB + active 토글)

**Files:**
- Create: `scripts/blog_score/load_to_db.py`
- Test: `scripts/blog_score/tests/test_load_to_db.py`

> DB I/O와 순수 변환을 분리한다. `build_rows(report: dict)` 는 JSON → (run_key, [score_row...]) 순수함수 → 단위테스트. DB 적재는 `load(region)` 가 담당(수동 실행으로 검증).

- [ ] **Step 1: transform 실패 테스트 작성**

```python
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from blog_score.load_to_db import build_rows  # noqa: E402


def _report():
    return {
        "run": "20260603T112728-v3",
        "region": "공덕동",
        "categories": {
            "크루아상·페이스트리": [
                {"store_id": 32, "name": "비파티세리", "address": "주소", "normalized": 50.0,
                 "mention_count": 17, "recent_count": 17, "review_count": 3008, "rating": 4.48},
            ],
            "베이글": [
                {"store_id": 3304, "name": "아이엠베이글", "address": "주소2", "normalized": 86.6,
                 "mention_count": 17, "recent_count": 8, "review_count": 3712, "rating": 4.57},
            ],
            "단과자·크림빵": [],
        },
    }


def test_build_rows_run_key():
    run_key, rows = build_rows(_report())
    assert run_key == "20260603T112728-v3"


def test_build_rows_category_order_follows_json_order():
    _, rows = build_rows(_report())
    # 크루아상이 0, 베이글이 1
    croissant = [r for r in rows if r["store_id"] == 32][0]
    bagel = [r for r in rows if r["store_id"] == 3304][0]
    assert croissant["category_order"] == 0
    assert bagel["category_order"] == 1
    assert croissant["bread_category"] == "크루아상·페이스트리"


def test_build_rows_skips_empty_categories():
    _, rows = build_rows(_report())
    assert all(r["bread_category"] != "단과자·크림빵" for r in rows)
    assert len(rows) == 2
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd scripts && .venv/bin/python -m pytest blog_score/tests/test_load_to_db.py -v`
Expected: FAIL (build_rows ImportError)

- [ ] **Step 3: 로더 구현**

```python
#!/usr/bin/env python3
"""v3 리포트 JSON → bbang_score 스키마 적재 (파이프라인 §6 publish 단계).

사용:
    .venv/bin/python -m blog_score.load_to_db --region 공덕동
동작: 최신 {slug}_blog_score_*_v3.json 로드 → run upsert → store_category_score 교체 적재
      → 해당 region의 active run을 이 run 하나로 토글. 멱등.
운영DB 무손상: bbang_score 스키마에만 write.
"""
import argparse
import glob
import json
import sys
from pathlib import Path

import psycopg2

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from blog_score.regions import slug_for  # noqa: E402
from scan_placeid_mismatch import db_config  # noqa: E402

REPORTS_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "reports"

DDL = """
CREATE SCHEMA IF NOT EXISTS bbang_score;

CREATE TABLE IF NOT EXISTS bbang_score.blog_crawl_run (
    id          BIGSERIAL PRIMARY KEY,
    region_3rd  VARCHAR(50)  NOT NULL,
    run_key     VARCHAR(64)  NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (region_3rd, run_key)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_run_per_region
    ON bbang_score.blog_crawl_run (region_3rd) WHERE is_active;

CREATE TABLE IF NOT EXISTS bbang_score.store_category_score (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT       NOT NULL REFERENCES bbang_score.blog_crawl_run(id),
    store_id       BIGINT       NOT NULL,
    bread_category VARCHAR(64)  NOT NULL,
    category_order INT          NOT NULL,
    normalized     NUMERIC(5,1) NOT NULL,
    mention_count  INT          NOT NULL DEFAULT 0,
    recent_count   INT          NOT NULL DEFAULT 0,
    review_count   INT,
    rating         NUMERIC(3,2),
    name           VARCHAR(200) NOT NULL,
    address        VARCHAR(300),
    UNIQUE (run_id, store_id, bread_category)
);
CREATE INDEX IF NOT EXISTS idx_scs_run_cat_norm
    ON bbang_score.store_category_score (run_id, category_order, normalized DESC);
"""


def latest_report(slug: str) -> Path:
    matches = sorted(glob.glob(str(REPORTS_DIR / f"{slug}_blog_score_*_v3.json")))
    if not matches:
        sys.exit(f"✗ 리포트 없음: {slug}_blog_score_*_v3.json")
    return Path(matches[-1])


def build_rows(report: dict) -> tuple[str, list[dict]]:
    """JSON → (run_key, score rows). 빈 카테고리 제외, category_order = JSON 순서."""
    run_key = report["run"]
    rows: list[dict] = []
    for order, (label, stores) in enumerate(report["categories"].items()):
        for s in stores:
            rows.append({
                "store_id": s["store_id"],
                "bread_category": label,
                "category_order": order,
                "normalized": s["normalized"],
                "mention_count": s.get("mention_count", 0),
                "recent_count": s.get("recent_count", 0),
                "review_count": s.get("review_count"),
                "rating": s.get("rating"),
                "name": s["name"],
                "address": s.get("address"),
            })
    return run_key, rows


def load(region: str) -> None:
    slug = slug_for(region)
    report = json.loads(latest_report(slug).read_text(encoding="utf-8"))
    run_key, rows = build_rows(report)

    conn = psycopg2.connect(**db_config(), connect_timeout=20)
    try:
        with conn:
            cur = conn.cursor()
            cur.execute(DDL)
            # run upsert
            cur.execute(
                """INSERT INTO bbang_score.blog_crawl_run (region_3rd, run_key)
                   VALUES (%s, %s)
                   ON CONFLICT (region_3rd, run_key) DO UPDATE SET run_key = EXCLUDED.run_key
                   RETURNING id""",
                (region, run_key))
            run_id = cur.fetchone()[0]
            # 점수 교체 적재
            cur.execute("DELETE FROM bbang_score.store_category_score WHERE run_id = %s", (run_id,))
            cur.executemany(
                """INSERT INTO bbang_score.store_category_score
                   (run_id, store_id, bread_category, category_order, normalized,
                    mention_count, recent_count, review_count, rating, name, address)
                   VALUES (%(run_id)s, %(store_id)s, %(bread_category)s, %(category_order)s,
                           %(normalized)s, %(mention_count)s, %(recent_count)s,
                           %(review_count)s, %(rating)s, %(name)s, %(address)s)""",
                [{**r, "run_id": run_id} for r in rows])
            # active 토글: 같은 region 전부 false → 이 run만 true
            cur.execute(
                "UPDATE bbang_score.blog_crawl_run SET is_active = FALSE WHERE region_3rd = %s", (region,))
            cur.execute(
                "UPDATE bbang_score.blog_crawl_run SET is_active = TRUE WHERE id = %s", (run_id,))
        print(f"✓ {region} 적재: run_id={run_id}, {len(rows)}행 (run_key={run_key})")
    finally:
        conn.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="v3 리포트 → bbang_score 적재")
    ap.add_argument("--region", required=True, help="region_3rd 동명 (예: 공덕동)")
    args = ap.parse_args()
    load(args.region)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd scripts && .venv/bin/python -m pytest blog_score/tests/test_load_to_db.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add scripts/blog_score/load_to_db.py scripts/blog_score/tests/test_load_to_db.py
git commit -m "feat(blog_score): v3 리포트 → bbang_score 적재 로더"
```

---

### Task 7: 실데이터 적재 + 엔드투엔드 수동 검증

**Files:** (코드 변경 없음 — 부트스트랩/검증)

- [ ] **Step 1: 망원동·공덕동 적재 (스키마·테이블 자동 생성됨)**

Run:
```bash
cd scripts
.venv/bin/python -m blog_score.load_to_db --region 망원동
.venv/bin/python -m blog_score.load_to_db --region 공덕동
```
Expected: `✓ 공덕동 적재: run_id=..., N행 ...`

- [ ] **Step 2: 앱 기동 (ddl-auto=validate가 bbang_score 테이블 검증 통과해야 함)**

Run: `./gradlew bootRun` (local 프로파일)
Expected: 정상 기동 (validate 에러 없음). 기동 실패 시 Step 1을 먼저 했는지 확인.

- [ ] **Step 3: API 응답 확인**

Run: `curl "http://localhost:8080/feed/categories?region=공덕동" | python3 -m json.tool`
Expected: `data.categories`에 크루아상·페이스트리(1)·식사빵·하드빵(6)·구움과자(6)… 순서로 노출, 각 store에 `score`·`thumbnailUrl`. 빈 카테고리(단과자·크림빵·타르트·파이) 제외. → spec/참조 목록과 카테고리·개수 일치 확인.

- [ ] **Step 4: 빈 지역 확인**

Run: `curl "http://localhost:8080/feed/categories?region=역삼동" | python3 -m json.tool`
Expected: `{"data":{"region":"역삼동","run":null,"categories":[]}}`

- [ ] **Step 5: 검증 기록 커밋 (선택 — 변경 없으면 생략)**

검증만 했고 코드 변경이 없으면 커밋 없음.

---

### Task 8: 전체 빌드/테스트 + PR

**Files:** (없음)

- [ ] **Step 1: CI 동일 빌드**

Run: `./gradlew build -x test --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: feed 테스트 전체 통과**

Run: `./gradlew test --tests "*feed*" --no-daemon`
Expected: PASS (BreadCategoryGroupTest, CategoryFeedServiceTest, FeedControllerTest)

- [ ] **Step 3: spotless/checkstyle**

Run: `./gradlew spotlessApply checkstyleMain --no-daemon`
Expected: 포맷 적용 후 위반 0. 변경 생기면 커밋.

- [ ] **Step 4: 푸시 + PR**

```bash
git push -u origin feat-category-feed-impl
gh pr create --base main --head feat-category-feed-impl \
  --title "feat: 카테고리 순위 피드 API (점수 서빙 경로)" \
  --body "spec: docs/superpowers/specs/2026-06-03-category-feed-api-design.md"
```

---

## 의존성/주의

- **기동 순서**: bbang_score 테이블은 로더(Task 7 Step 1)가 만든다. `ddl-auto=validate`라 앱 기동 전 반드시 1회 적재 필요.
- **운영DB 무손상**: 로더는 `bbang_score` 스키마에만 INSERT/UPDATE/DELETE. 운영 테이블은 Spring이 read-only 조인(`findThumbnailsByStoreIdIn`)만.
- **CI 영향 없음**: CI는 `build -x test` (부팅/DB 접근 없음) → 신규 엔티티는 컴파일만 통과하면 됨.
- **썸네일 다중**: `findThumbnailsByStoreIdIn`은 가게당 여러 이미지를 반환할 수 있어 storeId당 첫 awsKey만 사용(`putIfAbsent`).
