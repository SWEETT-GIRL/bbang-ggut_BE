package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import sweetgirl.croissant.common.util.GeoDistance;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;

/**
 * 시드된 상권(도장 칸 48개) 전부로 빈 코스·기본 4곳 추천을 재현해 코스 불변 조건을 확인한다 — PR #735 리뷰가 머지 조건으로
 * 요구한 회귀 검증. 후보 SQL 과 선택 규칙(chainWithinHop)을 서비스와 같게 쓴다.
 * 실행: {@code ./gradlew test -PlabTests}.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, RecommendationCandidateJdbcRepository.class})
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
class CourseAreaSeedLabTest {

	private static final int CANDIDATE_LIMIT = 15;
	private static final int DEFAULT_STORE_COUNT = 4;

	@Autowired
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Autowired
	private CourseAreaRepository courseAreaRepository;

	@Test
	@DisplayName("시드 48칸: 빈 코스·4곳 추천이 모두 4곳이 나오고, 이웃 가게 사이 거리가 칸의 max_hop 을 넘지 않는다")
	void everySeededArea_buildsFourStopCourseWithinMaxHop() {
		List<CourseArea> areas = courseAreaRepository.findAll().stream().filter(CourseArea::isActive).toList();
		assertThat(areas).hasSizeGreaterThanOrEqualTo(59);
		// 도장 칸(passport_spot)은 48개 그대로, 동네 이름이 도장 라벨과 다른 행(세부 동네 11 + 이름 바꾼 대표 6)이 17개
		// 도장 칸은 (시·도, 라벨) 단위다 — "중구" 는 부산·대구·대전·울산·인천에 있다
		assertThat(areas.stream().map(area -> area.getSido() + "/" + area.getPassportSpot()).distinct().count())
			.isEqualTo(48);
		assertThat(areas.stream().filter(area -> !area.getName().equals(area.getPassportSpot())).count())
			.isGreaterThanOrEqualTo(17);

		for (CourseArea area : areas) {
			List<CandidateStoreRow> candidates = candidateRepository.findAreaCandidates(
				area.getCenterLat(), area.getCenterLng(), area.getRadiusM(), area.getSido(), area.getRegion2nd(),
				area.getRegion3rd(), List.of(), CANDIDATE_LIMIT);
			List<CandidateStoreRow> course = CourseRecommendationCommandService.chainWithinHop(candidates,
				area.getMaxHopM(), DEFAULT_STORE_COUNT);

			assertThat(course).as("%s %s 후보 %d", area.getSido(), area.getName(), candidates.size())
				.hasSize(DEFAULT_STORE_COUNT);
			for (int i = 0; i + 1 < course.size(); i++) {
				CandidateStoreRow from = course.get(i);
				CandidateStoreRow to = course.get(i + 1);
				int hop = GeoDistance.metersBetween(from.latitude(), from.longitude(), to.latitude(), to.longitude());
				assertThat(hop).as("%s %s 구간 %d", area.getSido(), area.getName(), i + 1)
					.isLessThanOrEqualTo(area.getMaxHopM());
			}
			if (area.getRegion2nd() == null) {
				// 시군구 칸은 반경만 본다 — 시군구 전체로 새지 않는다
				assertThat(candidates)
					.allSatisfy(row -> assertThat(row.distanceM()).isLessThanOrEqualTo(area.getRadiusM()));
			}
		}
	}
}
