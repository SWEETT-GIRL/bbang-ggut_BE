package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.request.ShopPurchaseRequest;
import sweetgirl.croissant.reward.dto.response.MyShopResponse;
import sweetgirl.croissant.reward.exception.RewardException;

/**
 * 구매가 한 덩어리로 되는지를 실제 DB 로 본다 — 값을 못 치르면 산 것도 남지 않고, 나란히 들어와도 한 번만 산다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:shop-purchase-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShopPurchaseIntegrationTest {

	private static final int THREADS = 6;

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private ShopCommandService shopCommandService;
	@Autowired
	private ShopQueryService shopQueryService;
	@Autowired
	private CrumbGrantService crumbGrantService;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);
	private Long memberId;

	@BeforeEach
	void setUp() {
		memberId = memberRepository.save(Member.registering("신난 두쫀쿠", java.time.LocalDateTime.now())).getId();
		jdbcTemplate.update("INSERT INTO shop_items (code, kind, pose, name, price, sort_order, is_new, active) "
			+ "VALUES ('prop-flag', 'PROP', NULL, '빵 깃발', 50, 110, FALSE, TRUE)");
		jdbcTemplate.update("INSERT INTO shop_items (code, kind, pose, name, price, sort_order, is_new, active) "
			+ "VALUES ('prop-lights', 'PROP', NULL, '별 전구', 80, 140, FALSE, TRUE)");
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
		jdbcTemplate.update("DELETE FROM member_shop_items");
		jdbcTemplate.update("DELETE FROM shop_items");
		jdbcTemplate.update("DELETE FROM crumb_ledger");
		jdbcTemplate.update("DELETE FROM member_crumb_wallet");
	}

	@Test
	@DisplayName("사면 잔액이 가격만큼 줄고 내 상점에 남는다")
	void purchase() {
		crumbGrantService.grantByAdmin(memberId, 100, "admin:seed", "테스트 준비");

		assertThat(shopCommandService.purchase(memberId, new ShopPurchaseRequest("prop-flag", null)).crumbs())
			.isEqualTo(50);

		assertThat(shopQueryService.getMyShop(memberId).owned())
			.containsExactly(new MyShopResponse.Owned(null, "prop-flag"));
	}

	@Test
	@DisplayName("부스러기가 모자라면 산 것으로도 남지 않는다 — 다음에 다시 살 수 있다")
	void insufficientRollsBackOwnership() {
		crumbGrantService.grantByAdmin(memberId, 60, "admin:seed", "테스트 준비");

		assertThatThrownBy(() -> shopCommandService.purchase(memberId, new ShopPurchaseRequest("prop-lights", null)))
			.isInstanceOf(RewardException.class)
			.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT);

		assertThat(shopQueryService.getMyShop(memberId).owned()).isEmpty();
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(60);
	}

	@Test
	@DisplayName("같은 품목을 다시 사려 하면 REWARD409_2 이고 잔액은 그대로다")
	void alreadyOwned() {
		crumbGrantService.grantByAdmin(memberId, 200, "admin:seed", "테스트 준비");
		shopCommandService.purchase(memberId, new ShopPurchaseRequest("prop-flag", null));

		assertThatThrownBy(() -> shopCommandService.purchase(memberId, new ShopPurchaseRequest("prop-flag", null)))
			.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ALREADY_OWNED);
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(150);
	}

	@Test
	@DisplayName("같은 구매가 나란히 들어와도 하나만 성공하고 한 번만 값을 치른다")
	void concurrentPurchaseOnce() throws Exception {
		crumbGrantService.grantByAdmin(memberId, 500, "admin:seed", "테스트 준비");
		CountDownLatch startLine = new CountDownLatch(1);
		List<Future<Boolean>> futures = new ArrayList<>();
		for (int index = 0; index < THREADS; index++) {
			Callable<Boolean> task = () -> {
				startLine.await();
				try {
					shopCommandService.purchase(memberId, new ShopPurchaseRequest("prop-flag", null));
					return true;
				} catch (RuntimeException rejected) {
					return false;
				}
			};
			futures.add(executor.submit(task));
		}
		startLine.countDown();
		int succeeded = 0;
		for (Future<Boolean> future : futures) {
			if (future.get(30, TimeUnit.SECONDS)) {
				succeeded++;
			}
		}

		assertThat(succeeded).isEqualTo(1);
		assertThat(crumbGrantService.balanceOf(memberId)).isEqualTo(450);
	}
}
