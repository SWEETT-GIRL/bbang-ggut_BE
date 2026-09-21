package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.entity.RecommendationFeedback;
import sweetgirl.croissant.recommendation.enums.FeedbackAction;
import sweetgirl.croissant.recommendation.enums.FeedbackReason;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationFeedbackRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.store.entity.StoreProfile;
import sweetgirl.croissant.store.enums.AxisSource;
import sweetgirl.croissant.store.repository.StoreProfileRepository;

/**
 * 랩 PG(운영 덤프 복제본)에서 추천 스키마(#659)를 검증한다.
 *
 * 하는 일 둘: (1) Flyway 로 V20260908162421 이후의 추천 마이그레이션(09-11 정리 · 09-13 만족도 포함)을 적용하고
 * Hibernate validate 가 엔티티와 실제 컬럼(jsonb · text[] ·
 * numeric · smallint)을 맞춰 보는지, (2) JSON · 배열 · 임베디드 축이 저장하고 읽었을 때 그대로인지.
 * H2 로는 jsonb · 배열 · PostGIS 를 검증할 수 없어 랩으로 분리했다. 트랜잭션은 테스트마다 롤백된다.
 *
 * <p>실행: 랩 PG(docker-compose.lab-postgres.yml)를 띄운 뒤 {@code ./gradlew test -PlabTests}
 * (기본 빌드·CI 에서는 'lab' 태그가 제외된다). 접속은 시스템 프로퍼티 lab.db.url / lab.db.user / lab.db.password 로 바꿀 수 있다.
 * 개발·운영 DB 터널(15433 · 15432)을 가리키게 하지 않는다 — Flyway 가 실제로 마이그레이션을 적용한다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=true",
	"spring.flyway.out-of-order=true",
	"spring.flyway.baseline-on-migrate=true",
	"spring.flyway.baseline-version=20260604.0",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false"
})
class RecommendationSchemaLabTest {

	private static final LocalDateTime COMPUTED_AT = LocalDateTime.of(2026, 9, 7, 4, 0);

	@Autowired
	private StoreProfileRepository storeProfileRepository;
	@Autowired
	private UserTasteProfileRepository userTasteProfileRepository;
	@Autowired
	private CourseAreaRepository courseAreaRepository;
	@Autowired
	private RecommendationRepository recommendationRepository;
	@Autowired
	private RecommendationFeedbackRepository recommendationFeedbackRepository;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long anyActiveStoreId() {
		return jdbcTemplate.queryForObject("SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id LIMIT 1",
			Long.class);
	}

	/** store_profile 이 적재된 랩(09-14)에서 왕복 테스트가 기존 행과 부딪히지 않게 — 프로필이 없는 ACTIVE 가게(11%)를 고른다. */
	private Long activeStoreWithoutProfileId() {
		return jdbcTemplate.queryForObject("""
				SELECT s.id FROM store s WHERE s.status = 'ACTIVE'
					AND NOT EXISTS (SELECT 1 FROM store_profile p WHERE p.store_id = s.id)
				ORDER BY s.id LIMIT 1
				""", Long.class);
	}

	private Long anyMemberId() {
		return jdbcTemplate.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
	}

	/** 코스가 있는 회원이어야 한다 — 첫 회원은 코스가 없을 수 있다. */
	private Long anyOwnedCourseId() {
		return jdbcTemplate.queryForObject(
			"SELECT id FROM courses WHERE member_id IS NOT NULL AND deleted_at IS NULL ORDER BY id LIMIT 1",
			Long.class);
	}

	private Long ownerOf(Long courseId) {
		return jdbcTemplate.queryForObject("SELECT member_id FROM courses WHERE id = ?", Long.class, courseId);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	@DisplayName("store_profile.signature_kinds: 기존 행은 빈 배열이고, 열을 빼고 넣는 적재도 빈 배열이 된다(NOT NULL DEFAULT '{}')")
	void storeProfile_signatureKindsDefaultsToEmpty() {
		Integer nullRows = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM store_profile WHERE signature_kinds IS NULL", Integer.class);
		assertThat(nullRows).isZero();

		Long storeId = activeStoreWithoutProfileId();
		jdbcTemplate.update("""
				INSERT INTO store_profile (store_id, tags, attrs, bakery_score, batch_version, computed_at)
				VALUES (?, '{}', '{}', 0.9, 'v5', now())
				""", storeId);
		String[] kinds = jdbcTemplate.queryForObject(
			"SELECT signature_kinds FROM store_profile WHERE store_id = ?",
			(rs, rowNum) -> (String[])rs.getArray(1).getArray(), storeId);
		assertThat(kinds).isEmpty();
	}

	@Test
	@DisplayName("store_profile: 축 4컬럼 · jsonb 태그 · text[] 종류가 그대로 돌아온다")
	void storeProfile_roundTrip() {
		Long storeId = activeStoreWithoutProfileId();
		TasteAxis axis = TasteAxis.of(
			new BigDecimal("0.810"), new BigDecimal("0.900"), new BigDecimal("0.900"), new BigDecimal("0.810"));
		storeProfileRepository.save(StoreProfile.builder()
			.storeId(storeId)
			.axis(axis)
			.axisSource(AxisSource.BOTH)
			.breadType(axis.typeCode())
			.tags(Map.of("웨이팅", 0.8, "특별한메뉴", 0.6))
			.attrs(Map.of("parking", true, "newOpening", true))
			.kinds(new String[] {"소금빵", "깜빠뉴"})
			.signatureMenus(new String[] {"소금빵"})
			.signatureKinds(new String[] {"소금빵", "크루아상"})
			.bakeryScore(new BigDecimal("0.920"))
			.reviewCount(143)
			.ratingCount(16)
			.reviewScore(new BigDecimal("4.87"))
			.description("1994년부터 같은 자리에서 굽습니다.")
			.storeStatus("ACTIVE")
			.batchVersion("v1")
			.computedAt(COMPUTED_AT)
			.build());
		flushAndClear();

		StoreProfile found = storeProfileRepository.findById(storeId).orElseThrow();

		assertThat(found.hasAxis()).isTrue();
		assertThat(found.getAxis()).isEqualTo(axis);
		assertThat(found.getAxisSource()).isEqualTo(AxisSource.BOTH);
		assertThat(found.getTags()).containsEntry("웨이팅", 0.8);
		assertThat(found.getAttrs()).containsEntry("newOpening", true);
		assertThat(found.getKinds()).containsExactly("소금빵", "깜빠뉴");
		assertThat(found.getSignatureKinds()).containsExactly("소금빵", "크루아상");
		assertThat(found.getBakeryScore()).isEqualByComparingTo("0.920");
		assertThat(found.getBatchVersion()).isEqualTo("v1");
		assertThat(found.getBreadType()).isEqualTo(axis.typeCode());
		assertThat(found.getReviewScore()).isEqualByComparingTo("4.87");
		assertThat(found.getRatingCount()).isEqualTo(16);
		assertThat(found.getDescription()).startsWith("1994년");
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("store_profile: 축이 없는 가게는 임베디드가 null 로 돌아온다 — 근거 없음")
	void storeProfile_withoutAxis_readsNullEmbedded() {
		Long storeId = activeStoreWithoutProfileId();
		storeProfileRepository.save(StoreProfile.builder()
			.storeId(storeId).bakeryScore(BigDecimal.ZERO).storeStatus("ACTIVE").batchVersion("v1")
			.computedAt(COMPUTED_AT).build());
		flushAndClear();

		StoreProfile found = storeProfileRepository.findById(storeId).orElseThrow();

		assertThat(found.getAxis()).isNull();
		assertThat(found.hasAxis()).isFalse();
		assertThat(found.getTags()).isEmpty();
	}

	@Test
	@DisplayName("user_taste_profile: 검사 응답 jsonb · top_kinds 배열 · version 이 그대로 돌아온다")
	void userTasteProfile_roundTrip() {
		Long memberId = anyMemberId();
		UserTasteProfile profile = UserTasteProfile.createEmpty(memberId, COMPUTED_AT);
		profile.recompute(
			TasteAxis.of(new BigDecimal("0.600"), new BigDecimal("0.400"), new BigDecimal("0.500"),
				new BigDecimal("0.200")),
			"PCLN", TasteTypeSource.SIGNAL, Map.of("머물기", 0.7), new String[] {"소금빵"}, new String[0],
			TasteUserType.PLANNER, 4, 2, COMPUTED_AT);
		profile.applyQuiz(List.of(Map.of("axis", "taste", "choice", "PLAIN")), (short)2, profile.getAxis(), "PFLN",
			COMPUTED_AT);
		userTasteProfileRepository.save(profile);
		flushAndClear();

		UserTasteProfile found = userTasteProfileRepository.findById(memberId).orElseThrow();

		assertThat(found.getVersion()).isEqualTo(3);
		assertThat(found.getTypeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(found.getTypeCode()).isEqualTo("PFLN");
		assertThat(found.getQuizAnswers()).containsExactly(Map.of("axis", "taste", "choice", "PLAIN"));
		assertThat(found.getTopKinds()).containsExactly("소금빵");
		assertThat(found.getUserType()).isEqualTo(TasteUserType.PLANNER);
	}

	@Test
	@DisplayName("course_area: 시군구·동 보조 컬럼까지 저장하고 시/도로 찾는다")
	void courseArea_roundTrip() {
		// 이름에 "(랩)" 을 붙인다 — 도장 칸 48개가 시드돼 있어 (sido, name) 이 겹치면 uq_course_area_sido_name 에 막힌다
		courseAreaRepository.save(CourseArea.create("서울", "성수동(랩)", "성수동(랩)", 5L, 37.5446, 127.0557,
			1200, 1200, "성동구", "성수동", 70));
		courseAreaRepository.save(CourseArea.create("경기", "수원시(랩)", "수원시(랩)", null, 37.2636, 127.0286,
			5000, 8000, "수원시", null, 10));
		flushAndClear();

		List<CourseArea> seoul = courseAreaRepository.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("서울");
		List<CourseArea> gyeonggi = courseAreaRepository
			.findAllBySidoAndIsActiveTrueOrderByDisplayOrderAscNameAsc("경기");

		assertThat(seoul).extracting(CourseArea::getName).contains("성수동(랩)");
		assertThat(seoul).filteredOn(area -> area.getName().equals("성수동(랩)"))
			.first()
			.satisfies(area -> {
				assertThat(area.getRegion3rd()).isEqualTo("성수동");
				assertThat(area.coversWholeDistrict()).isFalse();
			});
		assertThat(gyeonggi).filteredOn(area -> area.getName().equals("수원시(랩)"))
			.first()
			.satisfies(area -> assertThat(area.coversWholeDistrict()).isTrue());
	}

	@Test
	@DisplayName("recommendation: 결과 jsonb 와 재사용 키가 그대로 돌아온다")
	void recommendation_roundTrip() {
		Long courseId = anyOwnedCourseId();
		Long memberId = ownerOf(courseId);
		Recommendation recommendation = Recommendation.forAnchor(
			memberId, courseId, anyActiveStoreId(), (short)1, 1,
			Map.of("title", "성수 담백 산책", "totalDistanceM", 1150,
				"stores", List.of(Map.of("storeId", 1042, "order", 1, "reason", "담백 취향", "tasteFit", 0.71))));
		Long id = recommendationRepository.save(recommendation).getId();
		flushAndClear();

		Recommendation found = recommendationRepository.findById(id).orElseThrow();

		assertThat(found.isAnchor()).isTrue();
		assertThat(found.getProvider()).isEqualTo(Recommendation.RULE_PROVIDER);
		assertThat(found.getModel()).isNull();
		assertThat(found.isFallback()).isFalse();
		assertThat(found.getResult()).containsEntry("title", "성수 담백 산책");
		assertThat(found.getResult()).containsEntry("totalDistanceM", 1150);
		assertThat(found.getDay()).isEqualTo((short)1);
	}

	@Test
	@DisplayName("recommendation_feedback: 👎 와 이유 칩이 새 CHECK 를 지나 그대로 돌아온다")
	void recommendationFeedback_roundTrip() {
		Long courseId = anyOwnedCourseId();
		Long recommendationId = recommendationRepository.save(Recommendation.forArea(
			ownerOf(courseId), courseId, null, (short)1, 1, "gemini", "gemini-2.5-flash", "p1", false,
			Map.of("title", "성수 담백 산책"), "lab-session")).getId();
		entityManager.flush();
		entityManager.clear();
		assertThat(recommendationRepository.findById(recommendationId)).get()
			.extracting(Recommendation::getSessionId).isEqualTo("lab-session"); // session_id 왕복
		Long id = recommendationFeedbackRepository.save(RecommendationFeedback.of(
			recommendationId, null, FeedbackAction.DISLIKE, FeedbackReason.BAD_REASON)).getId();
		recommendationFeedbackRepository.save(RecommendationFeedback.of(recommendationId, null, FeedbackAction.LIKE));
		// 다시 받기(#661) — 서버가 남기는 RETRY 도 CHECK 를 지난다
		recommendationFeedbackRepository.save(RecommendationFeedback.of(recommendationId, null, FeedbackAction.RETRY));
		flushAndClear();

		RecommendationFeedback found = recommendationFeedbackRepository.findById(id).orElseThrow();

		assertThat(found.getAction()).isEqualTo(FeedbackAction.DISLIKE);
		assertThat(found.getReason()).isEqualTo(FeedbackReason.BAD_REASON);
		assertThat(found.getStoreId()).isNull();
	}
}
