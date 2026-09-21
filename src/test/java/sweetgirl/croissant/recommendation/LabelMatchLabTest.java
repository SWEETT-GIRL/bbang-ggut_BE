package sweetgirl.croissant.recommendation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.config.JacksonConfig;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.dto.response.TasteLabels;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.service.QuizAxisRule;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.config.RecommendationProperties.Score.TasteSource;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.StoreLabelStatsRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.LabelCenterParams;

/**
 * 라벨 매칭이 추천을 실제로 바꾸는지 랩(운영 덤프 복제본)에서 잰다.
 *
 * <p><b>Flyway 를 끈다.</b> 랩의 {@code store_recommendation_feature} 는 dataops 가 Flyway 밖에서 만들어 둔 것이라,
 * 마이그레이션을 돌리면 {@code CREATE TABLE} 이 already exists 로 깨지고 {@code flyway_schema_history} 에 실패가
 * 남아 이후 마이그레이션이 전부 막힌다. 읽기만 하는 측정이라 스키마를 건드릴 이유가 없다.
 *
 * <p><b>합성 회원으로 잰다.</b> 랩에 검사를 푼 회원이 0명이라 실회원으로는 못 잰다. 대신 맛 4 × 식감 3 × 속 2 =
 * 24명을 만들어 <b>변수를 취향 하나로</b> 통제한다 — 실회원은 신호량·태그·종류가 제각각이라 무엇이 순위를
 * 바꿨는지 가릴 수 없다. DB 에는 쓰지 않는다(리포지토리가 {@link TasteQuery} 를 직접 받는다).
 *
 * <p><b>두 갈래로 잰다.</b>
 * <ul>
 *   <li><b>갈리나</b> — 같은 상권에서 회원을 바꿀 때 상위 4곳이 얼마나 달라지나(개인화)</li>
 *   <li><b>밀리나</b> — 24명의 상위 4곳을 합쳤을 때 한 가게가 몇 명에게 겹쳐 나오나(동질화)</li>
 * </ul>
 * 갈리나만 보면 "그 차원이 일을 안 한다" 로 읽히는데, 실제로는 전원의 순위를 같은 방향으로 밀고 있을 수 있다.
 * 중심화 내적에서 회원이 한 차원에서 다 같은 값이어도 그 항은 {@code k·(s−c)} 라 가게마다 다르게 더해진다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, JacksonConfig.class})
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.jpa.show-sql=false"
})
class LabelMatchLabTest {

	/** 코스 한 벌의 가게 수 — 상위 몇 곳이 갈리는지가 사용자가 실제로 보는 차이다. */
	private static final int COURSE_SIZE = 4;
	private static final int CANDIDATE_LIMIT = 500;
	private static final Path OUT = Path.of(".claude/experiments/recommendation-eval/label-match.md");

	private static final String READING_GUIDE = String.join("\n",
		"",
		"## 읽는 법",
		"",
		"- **서로 다른 코스** — 24명이 받은 상위 4곳 집합의 가짓수. 24면 전원이 다른 코스, 1이면 전원 같다.",
		"- **한 가게 최대 노출** — 한 가게가 몇 명에게 나왔나. 24면 전원에게 나온 가게가 있다는 뜻이고,",
		"  그건 개인화가 아니라 공통 편향이다.",
		"- **전원 공통 가게** — 24명 모두의 코스에 든 가게 수.",
		"",
		"갈리는 것(가짓수↑)과 밀리는 것(최대 노출↓)이 같이 좋아져야 개인화가 실제로 는 것이다.",
		"가짓수만 늘고 최대 노출이 그대로면 코스 네 곳 중 한 곳만 바뀐 것일 수 있다.",
		"");

	/** 라벨이 많은 상권부터 — 라벨이 얇은 곳은 어느 쪽이든 인기순이라 비교가 안 된다. */
	private static final List<Area> AREAS = List.of(
		new Area("연남동", 37.559800, 126.920940, 1200),
		new Area("서교동", 37.552490, 126.923160, 1200),
		new Area("중구(대구)", 35.866090, 128.599530, 1200));

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	private record Area(String name, double lat, double lng, int radiusM) {
	}

	/** 합성 회원 — 검사 답 하나로 축과 라벨을 함께 만든다(실제 제출 경로와 같은 규칙). */
	private record Member(String name, TasteQuery axisOnly, TasteQuery labelOnly) {
	}

	@Test
	@DisplayName("[측정] 라벨 매칭이 추천을 바꾸나 — 갈리나 · 밀리나")
	void measureLabelMatch() throws IOException {
		Assumptions.assumeTrue(labelRows() > 0, "랩에 가게 라벨이 없다");
		List<Member> members = syntheticMembers();

		StringBuilder report = new StringBuilder();
		report.append("# 라벨 매칭 측정 (랩)\n\n")
			.append("합성 회원 ").append(members.size()).append("명 × 상권 ").append(AREAS.size())
			.append("곳 · 코스 ").append(COURSE_SIZE).append("곳 · 후보 상한 ").append(CANDIDATE_LIMIT).append("\n\n")
			.append("가게 라벨 ").append(labelRows()).append("행\n\n")
			.append("| 상권 | 판 | 서로 다른 코스 | 한 가게 최대 노출 | 전원 공통 가게 |\n")
			.append("|---|---|---|---|---|\n");

		for (Area area : AREAS) {
			appendRow(report, area, "AXIS", measure(area, members, TasteSource.AXIS));
			appendRow(report, area, "LABEL", measure(area, members, TasteSource.LABEL));
		}
		report.append(READING_GUIDE);
		Files.createDirectories(OUT.getParent());
		Files.writeString(OUT, report.toString());
		System.out.println(report);
	}

	private void appendRow(StringBuilder report, Area area, String source, int[] result) {
		report.append("| ").append(area.name()).append(" | ").append(source)
			.append(" | ").append(result[0]).append(" | ").append(result[1])
			.append(" | ").append(result[2]).append(" |\n");
	}

	/** @return [서로 다른 코스 수, 한 가게 최대 노출, 전원 공통 가게 수] */
	private int[] measure(Area area, List<Member> members, TasteSource source) {
		RecommendationCandidateJdbcRepository repository = repositoryFor(source);
		Set<List<Long>> courses = new LinkedHashSet<>();
		Map<Long, Integer> exposure = new LinkedHashMap<>();
		for (Member member : members) {
			TasteQuery taste = source == TasteSource.LABEL ? member.labelOnly() : member.axisOnly();
			List<Long> course = repository
				.findAreaCandidates(1L, taste, area.lat(), area.lng(), area.radiusM(), null, null, null,
					List.of(), CANDIDATE_LIMIT)
				.stream()
				.sorted((left, right) -> Double.compare(right.score(), left.score()))
				.limit(COURSE_SIZE)
				.map(CandidateStoreRow::storeId)
				.toList();
			courses.add(course);
			course.forEach(storeId -> exposure.merge(storeId, 1, Integer::sum));
		}
		int maxExposure = exposure.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		long sharedByAll = exposure.values().stream().filter(count -> count == members.size()).count();
		return new int[] {courses.size(), maxExposure, (int)sharedByAll};
	}

	private RecommendationCandidateJdbcRepository repositoryFor(TasteSource source) {
		RecommendationProperties properties = new RecommendationProperties();
		properties.getScore().setTasteSource(source);
		NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
		return new RecommendationCandidateJdbcRepository(named, properties,
			new LabelCenterParams(new StoreLabelStatsRepository(named)), objectMapper);
	}

	/**
	 * 맛 4 × 식감 3 × 속 2 = 24명. 스타일은 트렌디로 고정해 변수를 줄인다.
	 *
	 * <p>같은 답에서 축과 라벨을 함께 만든다 — 축 판과 라벨 판이 <b>같은 사람</b>을 다르게 표현한 것이어야
	 * 두 판의 차이가 표현의 차이가 된다.
	 */
	private static List<Member> syntheticMembers() {
		List<QuizChoice> tastes = List.of(QuizChoice.SWEET, QuizChoice.PLAIN, QuizChoice.SALTY, QuizChoice.AROMA);
		List<QuizChoice> textures = List.of(QuizChoice.SOFT, QuizChoice.CRISP, QuizChoice.CHEWY);
		List<QuizChoice> fillings = List.of(QuizChoice.FULL, QuizChoice.SIMPLE);
		List<Member> members = new ArrayList<>();
		for (QuizChoice taste : tastes) {
			for (QuizChoice texture : textures) {
				for (QuizChoice filling : fillings) {
					members.add(memberOf(taste, texture, filling, QuizChoice.TRENDY));
				}
			}
		}
		return members;
	}

	private static Member memberOf(QuizChoice taste, QuizChoice texture, QuizChoice filling, QuizChoice vibe) {
		List<BbtiQuizAnswer> answers = List.of(
			new BbtiQuizAnswer(QuizAxis.TASTE, taste), new BbtiQuizAnswer(QuizAxis.TEXTURE, texture),
			new BbtiQuizAnswer(QuizAxis.FILLING, filling), new BbtiQuizAnswer(QuizAxis.VIBE, vibe));
		TasteAxis axis = QuizAxisRule.fromAnswers(answers, QuizAxisRule.answersPerAxis(answers));
		double[] labels = TasteLabels.of(List.of(taste, texture, filling, vibe)).values();
		String name = taste + "·" + texture + "·" + filling;
		// 태그·종류는 비운다 — 두 판의 차이가 취향 표현에서만 오게 한다
		return new Member(name,
			new TasteQuery(axis.getTaste().doubleValue(), axis.getTexture().doubleValue(),
				axis.getFilling().doubleValue(), axis.getVibe().doubleValue(), Map.of(), List.of(), 0, List.of()),
			new TasteQuery(null, null, null, null, Map.of(), List.of(), 0, List.of(), labels));
	}

	private long labelRows() {
		Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM store_recommendation_feature", Long.class);
		return rows == null ? 0 : rows;
	}
}
