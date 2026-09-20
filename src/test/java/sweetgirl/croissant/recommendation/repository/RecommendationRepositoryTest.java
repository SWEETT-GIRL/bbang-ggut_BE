package sweetgirl.croissant.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.enums.RecommendationMode;

/**
 * 재사용 조회의 키가 지켜지는지 — 같은 코스의 다른 회원 행은 잡히지 않고, 같은 키면 최신 한 건만 온다.
 * PostGIS 가 없어 H2 로 돈다(recommendation · course_area 는 공간 컬럼이 없다).
 */
@SpringBootTest(properties = {
	// DAY 는 H2 예약어라 recommendation.day 컬럼이 있는 표가 안 만들어진다 — 이 컨텍스트에서만 키워드에서 뺀다
	"spring.datasource.url=jdbc:h2:mem:recommendation-reuse-test;MODE=PostgreSQL;NON_KEYWORDS=DAY;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class RecommendationRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private RecommendationRepository recommendationRepository;
	@Autowired
	private EntityManager entityManager;

	private Member jimin;
	private Member sujin;
	private Course course;
	private CourseArea seongsu;

	@BeforeEach
	void setUp() {
		jimin = persist(new Member("지민"));
		sujin = persist(new Member("수진"));
		course = persist(Course.createCustom("성수 소금빵 투어", jimin, false));
		seongsu = persist(CourseArea.create("서울", "성수동", "성수동", null, 37.5446, 127.0557, 1200, 1200, null, null, 1));
		entityManager.flush();
	}

	private <T> T persist(T entity) {
		entityManager.persist(entity);
		return entity;
	}

	private Recommendation areaRecommendation(Member member, short day, int profileVersion, String title) {
		return persist(Recommendation.forArea(member.getId(), course.getId(), seongsu.getId(), day, profileVersion,
			Recommendation.RULE_PROVIDER, null, null, false, Map.of("title", title)));
	}

	@Test
	@DisplayName("같은 코스·상권·일차·버전이라도 다른 회원의 행은 잡히지 않는다 — 추천은 그 사람의 취향으로 만든 것이다")
	void findLatestAreaForReuse_isScopedToMember() {
		areaRecommendation(jimin, (short)1, 0, "지민 것");
		areaRecommendation(sujin, (short)1, 0, "수진 것");
		entityManager.flush();

		Optional<Recommendation> forJimin = recommendationRepository.findLatestAreaForReuse(jimin.getId(),
			course.getId(), seongsu.getId(), (short)1, 0);
		Optional<Recommendation> forSujin = recommendationRepository.findLatestAreaForReuse(sujin.getId(),
			course.getId(), seongsu.getId(), (short)1, 0);

		assertThat(forJimin).get().extracting(r -> r.getResult().get("title")).isEqualTo("지민 것");
		assertThat(forSujin).get().extracting(r -> r.getResult().get("title")).isEqualTo("수진 것");
	}

	@Test
	@DisplayName("같은 키가 여럿이면 최신 한 건, 일차나 버전이 다르면 없다")
	void findLatestAreaForReuse_latestOnlyAndKeyExact() {
		areaRecommendation(jimin, (short)1, 0, "옛것");
		Recommendation newest = areaRecommendation(jimin, (short)1, 0, "새것");
		entityManager.flush();

		assertThat(recommendationRepository.findLatestAreaForReuse(jimin.getId(), course.getId(), seongsu.getId(),
			(short)1, 0)).get().extracting(Recommendation::getId).isEqualTo(newest.getId());
		assertThat(recommendationRepository.findLatestAreaForReuse(jimin.getId(), course.getId(), seongsu.getId(),
			(short)2, 0)).isEmpty();
		assertThat(recommendationRepository.findLatestAreaForReuse(jimin.getId(), course.getId(), seongsu.getId(),
			(short)1, 1)).isEmpty();
	}

	@Test
	@DisplayName("앵커 재사용도 회원·앵커·일차·버전이 전부 같아야 한다")
	void findLatestAnchorForReuse_isScopedToMemberAndDay() {
		persist(Recommendation.forAnchor(jimin.getId(), course.getId(), 1042L, (short)1, 0, Map.of("title", "1일차")));
		persist(Recommendation.forAnchor(sujin.getId(), course.getId(), 1042L, (short)1, 0, Map.of("title", "수진")));
		entityManager.flush();

		assertThat(recommendationRepository.findLatestAnchorForReuse(jimin.getId(), course.getId(), 1042L, (short)1, 0))
			.get().extracting(r -> r.getResult().get("title")).isEqualTo("1일차");
		assertThat(recommendationRepository.findLatestAnchorForReuse(jimin.getId(), course.getId(), 1042L, (short)2, 0))
			.isEmpty();
		assertThat(recommendationRepository.findLatestAnchorForReuse(jimin.getId(), course.getId(), 999L, (short)1, 0))
			.isEmpty();
	}

	@Test
	@DisplayName("다시 추천 제외 재료: 같은 회원·코스·상권·일차의 상권 추천 전부 — 프로필 버전은 가리지 않고, 남·다른 일차는 뺀다")
	void findAllForExclusion_isScopedToMemberAreaDay_ignoresProfileVersion() {
		areaRecommendation(jimin, (short)1, 0, "첫 묶음");
		areaRecommendation(jimin, (short)1, 3, "프로필 바뀐 뒤");
		areaRecommendation(jimin, (short)2, 0, "다른 일차");
		areaRecommendation(sujin, (short)1, 0, "남의 추천");
		entityManager.flush();
		entityManager.clear();

		List<Recommendation> shown = recommendationRepository
			.findAllByMemberIdAndCourseIdAndModeAndCourseAreaIdAndDay(jimin.getId(), course.getId(),
				RecommendationMode.AREA, seongsu.getId(), (short)1);

		assertThat(shown).extracting(r -> r.getResult().get("title")).containsExactlyInAnyOrder("첫 묶음", "프로필 바뀐 뒤");
	}

	@Test
	@DisplayName("이 코스에서 추천받은 상권 행 — 회원 기준, 중복 없이")
	void findRecommendedAreaIds_distinctPerMemberCourse() {
		areaRecommendation(jimin, (short)1, 0, "a");
		areaRecommendation(jimin, (short)2, 0, "b");
		areaRecommendation(sujin, (short)1, 0, "남");
		entityManager.flush();
		entityManager.clear();

		assertThat(recommendationRepository.findRecommendedAreaIds(jimin.getId(), course.getId()))
			.containsExactly(seongsu.getId());
		assertThat(recommendationRepository.findRecommendedAreaIds(jimin.getId() + 999, course.getId())).isEmpty();
	}

	@Test
	@DisplayName("소유 확인: 남의 추천은 없는 것으로 본다")
	void findByIdAndMemberId() {
		Recommendation mine = areaRecommendation(jimin, (short)1, 0, "지민 것");
		entityManager.flush();

		assertThat(recommendationRepository.findByIdAndMemberId(mine.getId(), jimin.getId())).isPresent();
		assertThat(recommendationRepository.findByIdAndMemberId(mine.getId(), sujin.getId())).isEmpty();
	}

	@Test
	@DisplayName("이유 문장은 아직 룰 문장인 행에만 쓰인다 — 나란히 들어온 두 요청 중 하나만 이긴다 (#764)")
	void fillReasonsIfStillRule_onlyFirstWins() {
		Recommendation recommendation = areaRecommendation(jimin, (short)1, 1, "성수동 빵집 코스");
		entityManager.flush();

		int first = recommendationRepository.fillReasonsIfStillRule(recommendation.getId(),
			Map.of("title", "성수동 빵집 코스", "stores", List.of(Map.of("storeId", 1042, "reason", "첫 번째 문장"))),
			"gemini", "gemini-3.5-flash-lite", "reason-A", Recommendation.RULE_PROVIDER);
		int second = recommendationRepository.fillReasonsIfStillRule(recommendation.getId(),
			Map.of("title", "성수동 빵집 코스", "stores", List.of(Map.of("storeId", 1042, "reason", "두 번째 문장"))),
			"gemini", "gemini-3.5-flash-lite", "reason-A", Recommendation.RULE_PROVIDER);

		assertThat(first).isEqualTo(1);
		assertThat(second).isZero();
		entityManager.clear();
		Recommendation saved = recommendationRepository.findById(recommendation.getId()).orElseThrow();
		assertThat(saved.getProvider()).isEqualTo("gemini");
		assertThat(saved.getModel()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(saved.isFallback()).isFalse();
		assertThat(saved.getResult()).extracting("stores").asInstanceOf(LIST).hasSize(1);
		assertThat(saved.getResult().toString()).contains("첫 번째 문장").doesNotContain("두 번째 문장");
	}
}
