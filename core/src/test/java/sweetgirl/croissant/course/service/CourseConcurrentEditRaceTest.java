package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

/**
 * 두 사람이 같은 코스를 동시에 고치는 경합을 실제 DB 로 재현한다 — 한쪽은 제목, 다른 쪽은 공개 범위.
 *
 * <p>고치기 전(941d0e27 이전) 코스 수정은 락 없이 읽었고 Course 엔티티에 {@code @DynamicUpdate} 가 없어
 * Hibernate 가 전 컬럼을 메모리 값으로 UPDATE 했다. 그래서 늦게 flush 한 트랜잭션이 먼저 커밋된 쪽의 컬럼을
 * 자기가 읽어 둔 옛 값으로 되돌렸다 — 에러는 하나도 없이 변경만 사라지는 유실 갱신이다.
 * 같은 시나리오 {@value #ROUNDS}회에서 198회가 유실됐다(제목 194회 · 공개 범위 4회, 오류 0건).
 *
 * <p>고친 뒤에는 {@code updateCourse} 가 {@code findWithLockByIdAndDeletedAtIsNull} 로 코스 행을 잠그고
 * 들어가 두 수정이 줄을 서고, {@code @DynamicUpdate} 로 바뀐 컬럼만 쓴다 — {@value #ROUNDS}회 중 0회 유실.
 * 이 테스트는 그 수치를 회귀로 고정한다: 한 회라도 제목이나 공개 범위가 사라지면 실패한다.
 *
 * <p>두 스레드는 저마다 별도 트랜잭션을 열고 {@link CyclicBarrier} 로 모였다가 함께 서비스를 부른다 — 순서대로 부르면
 * 경합이 안 생겨 락이 있든 없든 통과해 버린다. 결과는 트랜잭션 밖(자동 커밋 커넥션)에서 읽는다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-concurrent-edit-race-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CourseConcurrentEditRaceTest {

	/** 재현 횟수. 고치기 전 198/200 유실 → 고친 뒤 0/200. */
	static final int ROUNDS = 200;

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseCommandService courseCommandService;

	@Autowired
	private CourseRepository courseRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private final ExecutorService editors = Executors.newFixedThreadPool(2);

	private Long ownerId;
	private Long courseId;

	@BeforeEach
	void setUp() {
		transactionTemplate.executeWithoutResult(status -> {
			Member owner = new Member("방장");
			entityManager.persist(owner);
			ownerId = owner.getId();
			Course course = Course.createCustom("처음 제목", owner, false);
			entityManager.persist(course);
			courseId = course.getId();
		});
	}

	@AfterEach
	void tearDown() {
		editors.shutdownNow();
		jdbcTemplate.update("DELETE FROM courses");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("제목 수정과 공개 범위 전환이 200회 동시에 들어와도 둘 다 남고 version 은 매번 2 오른다")
	void updateCourse_titleAndVisibilityRace_noLostUpdateIn200Rounds() throws Exception {
		int titleLost = 0;
		int visibilityLost = 0;
		int versionMismatch = 0;
		int errors = 0;

		for (int round = 0; round < ROUNDS; round++) {
			String newTitle = "t-" + round;
			// 매 회 반대로 뒤집어 "안 바뀐 것"과 "되돌아간 것"을 구분한다
			CourseVisibility newVisibility = round % 2 == 0 ? CourseVisibility.PUBLIC : CourseVisibility.PRIVATE;
			long versionBefore = readVersion();

			CyclicBarrier bothInTransaction = new CyclicBarrier(2);
			Future<?> titleEdit = editors.submit(() -> inSeparateTransaction(bothInTransaction,
				() -> courseCommandService.updateCourse(ownerId, courseId, titleRequest(newTitle))));
			Future<?> visibilityEdit = editors.submit(() -> inSeparateTransaction(bothInTransaction,
				() -> courseCommandService.updateCourse(ownerId, courseId, visibilityRequest(newVisibility))));

			errors += failures(titleEdit) + failures(visibilityEdit);

			Map<String, Object> saved = jdbcTemplate.queryForMap(
				"SELECT title, visibility, is_public, version FROM courses WHERE id = ?", courseId);
			if (!newTitle.equals(saved.get("title"))) {
				titleLost++;
			}
			if (!newVisibility.name().equals(saved.get("visibility"))
				|| !Boolean.valueOf(newVisibility == CourseVisibility.PUBLIC).equals(saved.get("is_public"))) {
				visibilityLost++;
			}
			if (((Number)saved.get("version")).longValue() != versionBefore + 2) {
				versionMismatch++;
			}
		}

		assertThat(errors).as("%d회 중 예외로 끝난 수정", ROUNDS).isZero();
		assertThat(titleLost).as("%d회 중 제목이 유실된 횟수", ROUNDS).isZero();
		assertThat(visibilityLost).as("%d회 중 공개 범위가 유실된 횟수", ROUNDS).isZero();
		assertThat(versionMismatch).as("%d회 중 version 이 정확히 2 오르지 않은 횟수", ROUNDS).isZero();
	}

	/**
	 * 줄을 세우는 것이 코스 행 락임을 직접 본다 — 다른 트랜잭션이 코스 행을 잠근 동안 제목 수정은 들어가지 못하고,
	 * 그 트랜잭션이 커밋해야 끝난다.
	 */
	@Test
	@DisplayName("다른 트랜잭션이 코스 행을 잠근 동안 제목 수정은 기다리고, 풀리면 반영된다")
	void updateCourse_waitsForCourseRowLock() throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> holder = editors.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId).orElseThrow();
			locked.countDown();
			await(release);
		}));
		assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

		Future<?> titleEdit = editors.submit(
			() -> courseCommandService.updateCourse(ownerId, courseId, titleRequest("기다린 제목")));
		assertThat(titleEdit.isDone()).isFalse();
		// 락이 잡혀 있는 동안은 끝나지 않는다
		assertThatTimesOut(titleEdit);
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM courses WHERE id = ?", String.class, courseId))
			.isEqualTo("처음 제목");

		release.countDown();
		holder.get(5, TimeUnit.SECONDS);
		titleEdit.get(5, TimeUnit.SECONDS);
		assertThat(jdbcTemplate.queryForObject("SELECT title FROM courses WHERE id = ?", String.class, courseId))
			.isEqualTo("기다린 제목");
	}

	/** 트랜잭션을 먼저 열어 두고 상대도 열 때까지 모였다가 서비스를 부른다 — 둘이 같은 순간 락 조회에 닿게. */
	private void inSeparateTransaction(CyclicBarrier barrier, Runnable edit) {
		transactionTemplate.executeWithoutResult(status -> {
			try {
				barrier.await(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException("상대 스레드가 트랜잭션을 열지 못했다", e);
			}
			edit.run();
		});
	}

	private int failures(Future<?> edit) throws InterruptedException, TimeoutException {
		try {
			edit.get(15, TimeUnit.SECONDS);
			return 0;
		} catch (ExecutionException failed) {
			return 1;
		}
	}

	private long readVersion() {
		return jdbcTemplate.queryForObject("SELECT version FROM courses WHERE id = ?", Long.class, courseId);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void assertThatTimesOut(Future<?> future) throws Exception {
		try {
			future.get(500, TimeUnit.MILLISECONDS);
			throw new AssertionError("락이 잡혀 있는데 수정이 끝났다");
		} catch (TimeoutException stillWaiting) {
			// 기대한 대로 기다리는 중
		}
	}

	private static CourseUpdateRequest titleRequest(String title) {
		CourseUpdateRequest request = new CourseUpdateRequest();
		ReflectionTestUtils.setField(request, "title", title);
		return request;
	}

	private static CourseUpdateRequest visibilityRequest(CourseVisibility visibility) {
		CourseUpdateRequest request = new CourseUpdateRequest();
		ReflectionTestUtils.setField(request, "visibility", visibility);
		return request;
	}
}
