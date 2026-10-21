package sweetgirl.croissant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.config.JacksonConfig;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.CourseRecommendationCommandService;

/**
 * 추천 기준선 재현 러너 (PR0). <b>같은 입력에 같은 후보 · 점수 · 순서가 나오는지</b>를 고정한다.
 *
 * <p>이것이 없으면 뒤따르는 어떤 비교도 뜻이 없다. 라벨 전환 · 가중치 조정 · 경로 개선이 무엇을 바꿨는지 알려면
 * "바꾸기 전"이 숫자로 박혀 있어야 한다. 실제로 이유 문장 프롬프트를 45회 깎고 나서야 그 실험이 결함 있는 재료
 * 위에서 돌았다는 것을 알았다(회원과 겹치는 빵 종류를 잘라 버리고 있었다) — 같은 일을 점수에서 반복하지 않으려는
 * 장치다.
 *
 * <p><b>서비스 함수를 그대로 부른다.</b> 후보 조회는 {@link RecommendationCandidateJdbcRepository#findAreaCandidates},
 * 코스 구성은 {@link CourseRecommendationCommandService#chainWithinHop} 이다. 평가용으로 따로 구현하면 두 구현이
 * 조용히 갈라져, 재고 있는 것이 서비스가 아니게 된다.
 *
 * <p><b>결과는 저장소에 넣지 않는다.</b> 코호트에 회원 id 가 들어가고 후보에 가게 목록이 들어간다. 기본 출력 위치는
 * {@code .claude/experiments/recommendation-eval/}(gitignore)이고 {@code EVAL_OUT} 으로 바꿀 수 있다.
 *
 * <p>실행: {@code ./gradlew test -PlabTests --tests "*RecommendationBaselineLabTest"}
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, JacksonConfig.class, RecommendationProperties.class,
	RecommendationCandidateJdbcRepository.class})
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=false",
	// 랩 스키마를 건드리지 않는다. 마이그레이션을 돌리면 랩이 운영 덤프 복제본이 아니게 되고, validate 를 켜면
	// 이 러너가 쓰지도 않는 표(세계관 등)가 없다고 기동이 막힌다 — 여기서 읽는 것은 가게 · 프로필 · 상권뿐이다
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.jpa.show-sql=false"
})
class RecommendationBaselineLabTest {

	private static final int CANDIDATE_LIMIT = 15;
	private static final int STORE_COUNT = 4;
	/** 층마다 이만큼. 결정적으로 고르므로 늘리면 앞의 것은 그대로 남는다 */
	private static final int MEMBERS_PER_STRATUM = 8;
	private static final int AREAS = 6;

	@Autowired
	private RecommendationCandidateJdbcRepository candidateRepository;
	@Autowired
	private CourseAreaRepository courseAreaRepository;
	@Autowired
	private UserTasteProfileRepository userTasteProfileRepository;
	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;
	@Autowired
	private RecommendationProperties recommendationProperties;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("같은 코호트·같은 설정이면 후보 id·점수·순서가 두 번 다 같다 — 이게 이후 모든 비교의 잣대다")
	void baseline_isReproducible() throws IOException {
		List<Long> cohort = cohort();
		List<CourseArea> areas = areas();
		assertThat(cohort).as("코호트가 비면 잴 것이 없다").isNotEmpty();
		assertThat(areas).as("상권이 비면 잴 것이 없다").isNotEmpty();

		List<Map<String, Object>> first = run(cohort, areas);
		List<Map<String, Object>> second = run(cohort, areas);

		// 같은 JVM 안에서 두 번 — SQL 정렬이 흔들리거나 동점 처리가 불안정하면 여기서 걸린다
		assertThat(second).as("같은 입력에 두 번 다른 결과가 나오면 비교를 시작할 수 없다").isEqualTo(first);

		Path out = outDir();
		Files.createDirectories(out);
		writeManifest(out, cohort, areas);
		Path resultPath = out.resolve("baseline-l0.jsonl");
		Path previous = out.resolve("baseline-l0.previous.jsonl");
		if (Files.exists(resultPath)) {
			Files.copy(resultPath, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		writeJsonl(resultPath, first);

		System.out.printf(Locale.ROOT, "%n기준선: 회원 %d명 × 상권 %d곳 = %d건%n남긴 곳: %s%n",
			cohort.size(), areas.size(), first.size(), resultPath);
		if (Files.exists(previous)) {
			List<String> before = Files.readAllLines(previous);
			List<String> now = Files.readAllLines(resultPath);
			long changed = before.size() == now.size()
				? java.util.stream.IntStream.range(0, now.size()).filter(i -> !before.get(i).equals(now.get(i))).count()
				: -1;
			System.out.printf("지난 실행과 견줌: %s%n", changed == 0 ? "같음"
				: changed < 0 ? "건수가 달라졌다(" + before.size() + " → " + now.size() + ")"
					: changed + "건 달라졌다 — 무엇을 바꿨는지 확인할 것");
		}
	}

	/**
	 * 층이 갈리게 고른다 — Spec 7.2 가 층별 보고를 요구한다. 랩에는 설문만 한 회원이 없어(축이 전부 행동 신호에서
	 * 나왔다) 층을 "축 없음 · 축 있고 문장 재료 없음 · 축 있고 재료 있음" 으로 잡는다.
	 *
	 * <p>member_id 순으로 앞에서 자른다. 무작위로 고르면 실행마다 코호트가 달라져 재현이 깨진다.
	 */
	private List<Long> cohort() {
		List<Long> members = new ArrayList<>();
		members.addAll(pick("p.axis_taste IS NULL"));
		members.addAll(pick("p.axis_taste IS NOT NULL AND cardinality(coalesce(p.top_kinds,'{}')) = 0"));
		members.addAll(pick("p.axis_taste IS NOT NULL AND cardinality(coalesce(p.top_kinds,'{}')) > 0"));
		return members;
	}

	private List<Long> pick(String where) {
		return jdbcTemplate.queryForList(
			"SELECT p.member_id FROM user_taste_profile p WHERE " + where + " ORDER BY p.member_id LIMIT :n",
			new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("n", MEMBERS_PER_STRATUM), Long.class);
	}

	/** 후보가 넉넉한 상권부터. 이름 순으로 자르면 후보가 0곳인 칸이 섞여 잴 것이 없어진다 */
	private List<CourseArea> areas() {
		List<Long> ids = jdbcTemplate.queryForList("""
				SELECT a.id FROM course_area a
				JOIN store s ON s.status = 'ACTIVE'
					AND ST_DWithin(s.location, ST_SetSRID(ST_MakePoint(a.center_lng, a.center_lat), 4326)::geography,
						a.radius_m)
				JOIN store_profile p ON p.store_id = s.id AND p.bakery_score >= 0.5
				WHERE a.is_active
				GROUP BY a.id
				HAVING count(*) >= 15
				ORDER BY count(*) DESC, a.id
				LIMIT :n
				""", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("n", AREAS), Long.class);
		return ids.stream().map(id -> courseAreaRepository.findById(id).orElseThrow()).toList();
	}

	/** 한 줄 = 회원 하나 × 상권 하나. 서비스가 새 추천을 만들 때와 같은 경로다(제외 목록은 비운다 — 빈 코스 기준) */
	private List<Map<String, Object>> run(List<Long> cohort, List<CourseArea> areas) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Long memberId : cohort) {
			TasteQuery taste = TasteQuery.of(userTasteProfileRepository.findById(memberId).orElse(null));
			for (CourseArea area : areas) {
				List<CandidateStoreRow> candidates = candidateRepository.findAreaCandidates(memberId, taste,
					area.getCenterLat(), area.getCenterLng(), area.getRadiusM(), area.getSido(), area.getRegion2nd(),
					area.getRegion3rd(), List.of(), CANDIDATE_LIMIT);
				List<CandidateStoreRow> course = CourseRecommendationCommandService.chainWithinHop(candidates,
					area.getMaxHopM(), STORE_COUNT);
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("memberId", memberId);
				row.put("stratum", stratumOf(taste));
				row.put("areaId", area.getId());
				row.put("candidates", candidates.stream().map(this::candidate).toList());
				row.put("course", course.stream().map(CandidateStoreRow::storeId).toList());
				rows.add(row);
			}
		}
		return rows;
	}

	private Map<String, Object> candidate(CandidateStoreRow row) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("storeId", row.storeId());
		// 소수 그대로 쓰면 자바·DB 가 마지막 자리에서 달라 보일 수 있다. 여섯 자리면 순위를 가르기 충분하다
		out.put("score", round(row.score()));
		out.put("tasteFit", round(row.tasteFit()));
		out.put("popularity", round(row.popularityScore()));
		out.put("distanceM", Math.round(row.distanceM()));
		return out;
	}

	private static double round(double value) {
		return Math.round(value * 1_000_000) / 1_000_000.0;
	}

	private static String stratumOf(TasteQuery taste) {
		if (!taste.hasAxis()) {
			return "NO_AXIS";
		}
		return taste.topKinds().isEmpty() ? "AXIS_ONLY" : "AXIS_AND_KINDS";
	}

	/**
	 * 무엇으로 잰 값인지 남긴다. 이 파일이 없으면 결과 JSONL 은 "언젠가 어떤 설정으로 돌린 숫자" 일 뿐이다.
	 * 점수 가중치 · 중심화 상수 · 문턱은 값이 바뀌면 결과가 바뀌므로 전부 적는다.
	 */
	private void writeManifest(Path out, List<Long> cohort, List<CourseArea> areas) throws IOException {
		RecommendationProperties.Score score = recommendationProperties.getScore();
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("generatedAt", LocalDateTime.now().toString());
		manifest.put("gitRevision", gitRevision());
		manifest.put("policy", "L0");
		manifest.put("candidateLimit", CANDIDATE_LIMIT);
		manifest.put("storeCount", STORE_COUNT);
		manifest.put("cohort", Map.of("members", cohort.size(), "membersPerStratum", MEMBERS_PER_STRATUM,
			"memberIds", cohort));
		manifest.put("areas", areas.stream().map(area -> Map.of("id", area.getId(), "name", area.getName(),
			"radiusM", area.getRadiusM(), "maxHopM", area.getMaxHopM())).toList());
		manifest.put("score", scoreSettings(score));
		manifest.put("dbSnapshot", dbSnapshot());
		Files.writeString(out.resolve("manifest.json"),
			objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardCharsets.UTF_8);
	}

	private Map<String, Object> scoreSettings(RecommendationProperties.Score score) {
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("cosineWeight", score.getCosineWeight());
		settings.put("tagWeight", score.getTagWeight());
		settings.put("priorWeight", score.getPriorWeight());
		settings.put("kindWeight", score.getKindWeight());
		settings.put("centerAxes", score.isCenterAxes());
		settings.put("centerTaste", score.getCenterTaste());
		settings.put("centerTexture", score.getCenterTexture());
		settings.put("centerFilling", score.getCenterFilling());
		settings.put("centerVibe", score.getCenterVibe());
		settings.put("minBakeryScore", score.getMinBakeryScore());
		settings.put("tasteCosineThreshold", score.getTasteCosineThreshold());
		settings.put("storeNotableTagAbove", score.getStoreNotableTagAbove());
		settings.put("memberNotableTagAbove", new java.util.TreeMap<>(score.getMemberNotableTagAbove()));
		return settings;
	}

	/** 랩 데이터가 언제 것인지 — 가게·프로필이 바뀌면 같은 코드로도 결과가 달라진다 */
	private Map<String, Object> dbSnapshot() {
		return jdbcTemplate.queryForObject("""
				SELECT (SELECT count(*) FROM store WHERE status = 'ACTIVE') AS active_stores,
					(SELECT count(*) FROM store_profile) AS store_profiles,
					(SELECT count(*) FROM user_taste_profile) AS taste_profiles,
					(SELECT max(computed_at)::text FROM store_profile) AS store_profile_computed_at,
					(SELECT max(computed_at)::text FROM user_taste_profile) AS taste_profile_computed_at
				""", new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(),
			RecommendationBaselineLabTest::readSnapshot);
	}

	private static Map<String, Object> readSnapshot(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("activeStores", rs.getLong("active_stores"));
		snapshot.put("storeProfiles", rs.getLong("store_profiles"));
		snapshot.put("tasteProfiles", rs.getLong("taste_profiles"));
		snapshot.put("storeProfileComputedAt", rs.getString("store_profile_computed_at"));
		snapshot.put("tasteProfileComputedAt", rs.getString("taste_profile_computed_at"));
		return snapshot;
	}

	private static String gitRevision() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
			String revision = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			return process.waitFor() == 0 ? revision : "unknown";
		} catch (IOException | InterruptedException e) {
			Thread.currentThread().interrupt();
			return "unknown";
		}
	}

	private void writeJsonl(Path path, List<Map<String, Object>> rows) throws IOException {
		List<String> lines = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			lines.add(objectMapper.writeValueAsString(row));
		}
		Files.write(path, lines, StandardCharsets.UTF_8);
	}

	private static Path outDir() {
		return Optional.ofNullable(System.getenv("EVAL_OUT"))
			.map(Path::of)
			.orElseGet(() -> Path.of(".claude", "experiments", "recommendation-eval"));
	}
}
