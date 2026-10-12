package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.exception.RewardException;

/**
 * 같은 회원의 적립·차감이 나란히 들어와도 한 번만 반영되는지를 실제 DB 잠금으로 확인한다.
 *
 * <p>목으로는 볼 수 없는 것들이다 — 유니크 충돌을 DB 가 판정하는가, 지갑 잠금이 상한 판정과 지급 사이를 막는가.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:crumb-grant-concurrency-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CrumbGrantConcurrencyTest {

	private static final int THREADS = 8;
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CrumbGrantService crumbGrantService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
		jdbcTemplate.update("DELETE FROM crumb_ledger");
		jdbcTemplate.update("DELETE FROM member_crumb_wallet");
	}

	@Test
	@DisplayName("같은 멱등 키로 동시에 적립해도 원장은 한 줄, 잔액은 한 번만 오른다")
	void sameKeyGrantsOnce() throws Exception {
		long memberId = 101L;

		List<GrantResult> results = runTogether(() -> crumbGrantService.grant(
			memberId, CrumbReason.RECORD, 10, "record:1", RewardRefType.RECORD, 1L));

		assertThat(results).filteredOn(GrantResult::isGranted).hasSize(1);
		assertThat(ledgerRows(memberId)).isEqualTo(1);
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(10);
	}

	@Test
	@DisplayName("상한이 3 인 적립이 서로 다른 키로 동시에 들어와도 3 번까지만 준다")
	void dailyCapHoldsUnderRace() throws Exception {
		long memberId = 102L;
		List<Callable<GrantResult>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			long recordId = index + 1L;
			tasks.add(() -> crumbGrantService.grantWithDailyCap(
				memberId, CrumbReason.RECORD, 10, "record:" + recordId, RewardRefType.RECORD, recordId, 3, TODAY));
		}

		List<GrantResult> results = runTogether(tasks);

		assertThat(results).filteredOn(GrantResult::isGranted).hasSize(3);
		assertThat(results).filteredOn(GrantResult::isCapped).hasSize(THREADS - 3);
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(30);
	}

	@Test
	@DisplayName("한 번만 살 수 있는 잔액에서 동시에 쓰면 하나만 성공한다")
	void spendOnceWhenBalanceAllowsOne() throws Exception {
		long memberId = 103L;
		crumbGrantService.grantByAdmin(memberId, 600, "admin:seed", "테스트 준비");
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			String key = "wake:friend" + index;
			tasks.add(() -> {
				try {
					return crumbGrantService.spend(memberId, CrumbReason.WAKE_FRIEND, 600, key).spent();
				} catch (RewardException insufficient) {
					return false;
				}
			});
		}

		List<Boolean> results = runTogether(tasks);

		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(crumbGrantService.balanceOf(memberId)).isZero();
	}

	private <T> List<T> runTogether(Callable<T> task) throws Exception {
		List<Callable<T>> tasks = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			tasks.add(task);
		}
		return runTogether(tasks);
	}

	/** 모든 스레드를 한 출발선에 세웠다가 동시에 푼다. */
	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		CountDownLatch startLine = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> task : tasks) {
			futures.add(executor.submit(() -> {
				startLine.await();
				return task.call();
			}));
		}
		startLine.countDown();
		List<T> results = new ArrayList<>();
		for (Future<T> future : futures) {
			results.add(future.get(30, TimeUnit.SECONDS));
		}
		return results;
	}

	private int ledgerRows(long memberId) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM crumb_ledger WHERE member_id = ?", Integer.class, memberId);
	}
}
