package sweetgirl.croissant.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.review.entity.Review;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:review-repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ReviewRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void setUpSchema() {
		execute("DROP TABLE IF EXISTS member_block CASCADE");
		execute("DROP TABLE IF EXISTS review_texture_tag CASCADE");
		execute("DROP TABLE IF EXISTS review CASCADE");
		execute("DROP TABLE IF EXISTS menu CASCADE");
		execute("DROP TABLE IF EXISTS menu_category CASCADE");
		execute("DROP TABLE IF EXISTS store CASCADE");
		execute("DROP TABLE IF EXISTS member CASCADE");
		execute("""
				CREATE TABLE member (
					id BIGINT PRIMARY KEY,
					nickname VARCHAR(100) NOT NULL,
					pending_nickname VARCHAR(100),
					nickname_drawn_at TIMESTAMP,
					nickname_normalized VARCHAR(100),
					nickname_changed_at TIMESTAMP,
					deleted_at TIMESTAMP,
					agree_to_terms BOOLEAN NOT NULL DEFAULT TRUE,
					agree_to_marketing BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_night_marketing BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_privacy BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_age_over14 BOOLEAN NOT NULL DEFAULT FALSE,
					agree_to_location BOOLEAN NOT NULL DEFAULT FALSE,
					terms_version VARCHAR(20),
					privacy_version VARCHAR(20),
					terms_agreed_at TIMESTAMP,
					marketing_agreed_at TIMESTAMP,
					member_type VARCHAR(20) NOT NULL DEFAULT 'USER',
					registered_at TIMESTAMP,
					previous_member_id BIGINT,
					invite_code VARCHAR(8),
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE store (
					id BIGINT PRIMARY KEY,
					name VARCHAR(100) NOT NULL,
					address VARCHAR(255) NOT NULL,
					region_1st VARCHAR(50),
					region_2nd VARCHAR(50),
					region_3rd VARCHAR(50),
					location VARCHAR(255),
					phone VARCHAR(20),
					status VARCHAR(50) NOT NULL,
					kakao_place_id VARCHAR(50),
					naver_place_id VARCHAR(50),
					naver_matched_at TIMESTAMP,
					naver_enrich_attempts INTEGER NOT NULL DEFAULT 0,
					source VARCHAR(50) NOT NULL,
					category VARCHAR(20),
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE menu_category (
					id BIGINT PRIMARY KEY,
					name VARCHAR(50) NOT NULL
				)
				""");
		execute("""
				CREATE TABLE menu (
					id BIGINT PRIMARY KEY,
					store_id BIGINT NOT NULL,
					menu_category_id BIGINT NOT NULL,
					name VARCHAR(100) NOT NULL,
					normalized_name VARCHAR(100) NOT NULL,
					created_by_member_id BIGINT,
					price INTEGER,
					recommend_count INTEGER NOT NULL DEFAULT 0,
					like_count INTEGER NOT NULL DEFAULT 0,
					deleted_at TIMESTAMP,
					description VARCHAR(255),
					display_order INTEGER,
					is_bakery_item BOOLEAN,
					classified_at TIMESTAMP,
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE review (
					id BIGINT PRIMARY KEY,
					menu_id BIGINT NOT NULL,
					member_id BIGINT NOT NULL,
					rating DECIMAL(2, 1) NOT NULL,
					repurchase_intent VARCHAR(50) NOT NULL,
					sweetness VARCHAR(50) NOT NULL,
					filling_amount VARCHAR(50) NOT NULL,
					content VARCHAR(1000) NOT NULL,
					like_count INTEGER NOT NULL DEFAULT 0,
					recommend_count INTEGER NOT NULL DEFAULT 0,
					comment_count INTEGER NOT NULL DEFAULT 0,
					deleted_at TIMESTAMP,
					hidden_at TIMESTAMP,
					hidden_reason VARCHAR(500),
					hidden_by BIGINT,
					created_at TIMESTAMP NOT NULL,
					updated_at TIMESTAMP
				)
				""");
		execute("""
				CREATE TABLE review_texture_tag (
					review_id BIGINT NOT NULL,
					texture_tag VARCHAR(50) NOT NULL
				)
				""");
		execute("""
				CREATE TABLE member_block (
					id BIGINT PRIMARY KEY,
					blocker_id BIGINT NOT NULL,
					blocked_member_id BIGINT NOT NULL,
					created_at TIMESTAMP NOT NULL
				)
				""");
	}

	@Test
	@DisplayName("차단한 작성자의 리뷰는 목록에서 숨겨지고 차단 해제 후 다시 보인다")
	void findVisibleActiveReviewsOrderByRecent_blockedAuthorExcludedAndUnblockRestores() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		List<Review> blockedResult = reviewRepository.findVisibleActiveReviewsOrderByRecent(
			fixture.menuId(),
			false,
			fixture.viewerId(),
			PageRequest.of(0, 10));

		assertThat(blockedResult)
			.extracting(Review::getContent)
			.containsExactly("보이는 리뷰");

		execute("DELETE FROM member_block WHERE id = ?", fixture.memberBlockId());
		entityManager.flush();
		entityManager.clear();

		List<Review> unblockedResult = reviewRepository.findVisibleActiveReviewsOrderByRecent(
			fixture.menuId(),
			false,
			fixture.viewerId(),
			PageRequest.of(0, 10));

		assertThat(unblockedResult)
			.extracting(Review::getContent)
			.containsExactlyInAnyOrder("보이는 리뷰", "숨겨질 리뷰");
	}

	@Test
	@DisplayName("차단한 작성자의 리뷰는 요약 통계 기준인 평균 평점과 리뷰 수에서 제외된다")
	void summaryStats_blockedAuthorExcludedFromRatingAndCount() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		assertThat(reviewRepository.countVisibleActiveByMenuId(fixture.menuId(), fixture.viewerId()))
			.isEqualTo(1L);
		assertThat(reviewRepository.findVisibleActiveRatingsByMenuId(fixture.menuId(), fixture.viewerId()))
			.containsExactly(BigDecimal.valueOf(5.0));
	}

	@Test
	@DisplayName("비로그인 사용자는 차단 관계와 무관하게 기존 리뷰 조회 결과를 유지한다")
	void findVisibleActiveReviewsOrderByRecent_guestKeepsExistingReviewLookup() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		List<Review> result = reviewRepository.findVisibleActiveReviewsOrderByRecent(
			fixture.menuId(),
			false,
			null,
			PageRequest.of(0, 10));

		assertThat(result)
			.extracting(Review::getContent)
			.containsExactlyInAnyOrder("보이는 리뷰", "숨겨질 리뷰");
		assertThat(reviewRepository.countVisibleActiveByMenuId(fixture.menuId(), null)).isEqualTo(2L);
		assertThat(reviewRepository.findVisibleActiveRatingsByMenuId(fixture.menuId(), null))
			.containsExactlyInAnyOrder(BigDecimal.valueOf(5.0), BigDecimal.valueOf(1.0));
	}

	@Test
	@DisplayName("차단한 작성자의 리뷰 상세는 숨겨지고 비로그인 상세 조회는 유지된다")
	void findVisibleActiveByIdWithDetails_blockedAuthorExcludedFromDetail() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		assertThat(reviewRepository.findVisibleActiveByIdWithDetails(
			fixture.blockedReviewId(),
			fixture.viewerId()))
			.isEmpty();
		assertThat(reviewRepository.findVisibleActiveByIdWithDetails(fixture.blockedReviewId(), null))
			.isPresent();
	}

	@Test
	@DisplayName("내가 쓴 리뷰 목록은 차단 관계와 무관하게 유지된다")
	void findActiveByMemberIdOrderByRecent_myReviewsIgnoreBlockState() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		List<Review> result = reviewRepository.findActiveByMemberIdOrderByRecent(
			fixture.blockedAuthorId(),
			PageRequest.of(0, 10));

		assertThat(result)
			.extracting(Review::getContent)
			.containsExactly("숨겨질 리뷰");
	}

	@Test
	@DisplayName("공개 프로필 리뷰 목록은 대상 회원의 활성 리뷰를 최신순으로 반환한다")
	void findVisibleActiveByMemberIdOrderByRecent_returnsTargetReviewsRecentFirst() {
		saveMember(1L, "조회자");
		saveMember(2L, "작성자");
		saveMenu();
		saveReview(1L, 1L, 2L, BigDecimal.valueOf(4.0), "이전 리뷰", "2026-06-05 09:00:00");
		saveReview(2L, 1L, 2L, BigDecimal.valueOf(5.0), "최신 리뷰", "2026-06-05 10:00:00");
		entityManager.flush();
		entityManager.clear();

		List<Review> result = reviewRepository.findVisibleActiveByMemberIdOrderByRecent(
			2L,
			null,
			PageRequest.of(0, 10));

		assertThat(result)
			.extracting(Review::getContent)
			.containsExactly("최신 리뷰", "이전 리뷰");
	}

	@Test
	@DisplayName("공개 프로필 리뷰 목록은 조회자가 차단한 작성자이면 빈 목록을 반환한다")
	void findVisibleActiveByMemberIdOrderByRecent_blockedAuthorReturnsEmpty() {
		Fixture fixture = fixtureWithBlockedAuthorReview();

		List<Review> blockedResult = reviewRepository.findVisibleActiveByMemberIdOrderByRecent(
			fixture.blockedAuthorId(),
			fixture.viewerId(),
			PageRequest.of(0, 10));
		List<Review> guestResult = reviewRepository.findVisibleActiveByMemberIdOrderByRecent(
			fixture.blockedAuthorId(),
			null,
			PageRequest.of(0, 10));

		assertThat(blockedResult).isEmpty();
		assertThat(guestResult)
			.extracting(Review::getContent)
			.containsExactly("숨겨질 리뷰");
	}

	private Fixture fixtureWithBlockedAuthorReview() {
		saveMember(1L, "조회자");
		saveMember(2L, "보이는 작성자");
		saveMember(3L, "차단한 작성자");
		saveMenu();
		saveReview(1L, 1L, 2L, BigDecimal.valueOf(5.0), "보이는 리뷰", "2026-06-05 10:00:00");
		saveReview(2L, 1L, 3L, BigDecimal.valueOf(1.0), "숨겨질 리뷰", "2026-06-05 09:00:00");
		execute("""
				INSERT INTO member_block (id, blocker_id, blocked_member_id, created_at)
				VALUES (?, ?, ?, TIMESTAMP '2026-06-05 11:00:00')
				""", 1L, 1L, 3L);
		entityManager.flush();
		entityManager.clear();

		return new Fixture(1L, 3L, 1L, 2L, 1L);
	}

	private void saveMember(Long id, String nickname) {
		execute("""
				INSERT INTO member (
					id, nickname, agree_to_terms, agree_to_marketing, agree_to_night_marketing,
					agree_to_privacy, agree_to_age_over14, agree_to_location, terms_agreed_at, created_at
				)
				VALUES (?, ?, TRUE, FALSE, FALSE, TRUE, TRUE, FALSE,
					TIMESTAMP '2026-06-05 08:00:00', TIMESTAMP '2026-06-05 08:00:00')
				""", id, nickname);
	}

	private void saveMenu() {
		execute("""
				INSERT INTO store (
					id, name, address, status, source, naver_enrich_attempts, created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, TIMESTAMP '2026-06-05 08:00:00')
				""", 1L, "단팥당", "서울 마포구", "ACTIVE", "ADMIN_REGISTERED", 0);
		execute("INSERT INTO menu_category (id, name) VALUES (?, ?)", 1L, "소금빵");
		execute("""
				INSERT INTO menu (
					id, store_id, menu_category_id, name, normalized_name, price, recommend_count, created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, TIMESTAMP '2026-06-05 08:00:00')
				""", 1L, 1L, 1L, "소금빵", "소금빵", 3000, 0);
	}

	private void saveReview(Long id, Long menuId, Long memberId, BigDecimal rating, String content, String createdAt) {
		execute("""
				INSERT INTO review (
					id, menu_id, member_id, rating, repurchase_intent, sweetness, filling_amount,
					content, like_count, comment_count, created_at
				)
				VALUES (?, ?, ?, ?, 'yes', 'medium', 'full', ?, 0, 0, ?)
				""", id, menuId, memberId, rating, content, createdAt);
	}

	private void execute(String sql, Object... parameters) {
		var query = entityManager.createNativeQuery(sql);
		for (int i = 0; i < parameters.length; i++) {
			query.setParameter(i + 1, parameters[i]);
		}
		query.executeUpdate();
	}

	private record Fixture(
			Long viewerId,
			Long blockedAuthorId,
			Long menuId,
			Long blockedReviewId,
			Long memberBlockId) {
	}
}
