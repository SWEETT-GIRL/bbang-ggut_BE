package sweetgirl.croissant.personalization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

/**
 * 신호 적재를 실제 표에 넣고 확인한다 — 유니크 제약(NULLS NOT DISTINCT)과 ON CONFLICT 는 H2 에 없어 랩 PG 로 돈다.
 * 픽스처는 2020-01(실데이터 없는 범위)에 넣고, 기록은 랩의 실데이터 범위로 대조한다. 트랜잭션은 테스트마다 롤백된다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, PersonalizationProperties.class, UserInteractionIngestJdbcRepository.class,
	UserInteractionIngestCommandService.class})
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
class UserInteractionIngestJdbcRepositoryLabTest {

	private static final LocalDate DAY = LocalDate.of(2020, 1, 1);
	private static final LocalDateTime BASE = DAY.atTime(10, 0);

	@Autowired
	private UserInteractionIngestCommandService ingestCommandService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("저장·계획·조회(전환 판정)·코스 제거·추천 반응이 종류별 행으로 들어가고, 같은 범위를 다시 돌리면 0 건이다")
	void ingest_writesEachSourceOnce() {
		Long memberId = jdbcTemplate.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
		List<Long> stores = jdbcTemplate.queryForList(
			"SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id LIMIT 2", Long.class);
		Long store1 = stores.get(0);
		Long store2 = stores.get(1);
		// 조회: store1 은 같은 날 두 번(한 행) + 다음 날 한 번, store2 는 한 번, 익명 조회는 안 센다
		insertView(memberId, store1, BASE);
		insertView(memberId, store1, BASE.plusMinutes(2));
		insertView(memberId, store1, BASE.plusDays(1));
		insertView(memberId, store2, BASE);
		insertView(null, store1, BASE);
		// store1 은 조회 5분 뒤 저장(창 안 → converted), store2 는 60분 뒤 계획(창 밖)
		jdbcTemplate.update("INSERT INTO saved_stores (member_id, store_id, created_at) VALUES (?, ?, ?)",
			memberId, store1, BASE.plusMinutes(5));
		jdbcTemplate.update("INSERT INTO store_plan (member_id, store_id, memo, created_at) VALUES (?, ?, '', ?)",
			memberId, store2, BASE.plusMinutes(60));
		// 코스에 담았다가 뺌(코스 주인 기준)
		String courseType = jdbcTemplate.queryForObject("SELECT type FROM courses LIMIT 1", String.class);
		Long courseId = jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, member_id, is_public, created_at)
				VALUES (?, '적재 랩', ?, false, ?) RETURNING id
				""", Long.class, courseType, memberId, BASE);
		jdbcTemplate.update("""
				INSERT INTO course_stores (course_id, store_id, visit_order, created_at, deleted_at)
				VALUES (?, ?, 1, ?, ?)
				""", courseId, store1, BASE.plusMinutes(10), BASE.plusHours(2));
		// 추천 묶음(가게 둘)을 두 번 닫고(DISMISS) 한 곳은 체크 해제
		Long recommendationId = jdbcTemplate.queryForObject("""
				INSERT INTO recommendation (member_id, course_id, mode, profile_version, provider, result, created_at)
				VALUES (?, ?, 'AREA', 0, 'rule', CAST(? AS jsonb), ?) RETURNING id
				""", Long.class, memberId, courseId,
			"{\"stores\":[{\"storeId\":" + store1 + "},{\"storeId\":" + store2 + "}]}", BASE.plusHours(3));
		jdbcTemplate.update(
			"INSERT INTO recommendation_feedback (recommendation_id, action, created_at) VALUES (?, 'DISMISS', ?)",
			recommendationId, BASE.plusHours(3).plusMinutes(1));
		jdbcTemplate.update(
			"INSERT INTO recommendation_feedback (recommendation_id, action, created_at) VALUES (?, 'DISMISS', ?)",
			recommendationId, BASE.plusHours(4));
		jdbcTemplate.update("""
				INSERT INTO recommendation_feedback (recommendation_id, store_id, action, created_at)
				VALUES (?, ?, 'STORE_UNCHECK', ?)
				""", recommendationId, store2, BASE.plusHours(3).plusMinutes(2));
		// 그 묶음에서 store1 을 담았다 — 닫았어도 store1 은 RECO_DISMISSED 가 아니다
		jdbcTemplate.update("""
				INSERT INTO course_stores (course_id, store_id, visit_order, created_at, recommendation_id)
				VALUES (?, ?, 2, ?, ?)
				""", courseId, store1, BASE.plusHours(5), recommendationId);
		// 큐레이션 코스(주인 없음)에서 뺀 가게는 누구의 신호도 아니다 — NOT NULL 위반으로 배치가 터지지 않아야 한다(PR #747)
		Long curatedId = jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, member_id, is_public, created_at)
				VALUES ('CURATED', '큐레이션 랩', NULL, true, ?) RETURNING id
				""", Long.class, BASE);
		jdbcTemplate.update("""
				INSERT INTO course_stores (course_id, store_id, visit_order, created_at, deleted_at)
				VALUES (?, ?, 1, ?, ?)
				""", curatedId, store2, BASE, BASE.plusHours(1));

		InteractionIngestResult first = ingestCommandService.ingest(DAY, DAY.plusDays(2));

		assertThat(first.byType())
			.containsEntry(InteractionType.SAVE, 1)
			.containsEntry(InteractionType.PLAN, 1)
			.containsEntry(InteractionType.STORE_VIEW, 3)
			.containsEntry(InteractionType.COURSE_REMOVE, 1)
			.containsEntry(InteractionType.RECO_DISMISSED, 1) // 두 번 닫아도 묶음당 한 번, 담은 store1 은 빼고 store2 만
			.containsEntry(InteractionType.RECO_UNCHECK, 1)
			.containsEntry(InteractionType.RECORD_STORE, 0)
			.containsEntry(InteractionType.RECORD_BREAD, 0);
		assertThat(first.inserted()).isEqualTo(8);
		List<Map<String, Object>> views = jdbcTemplate.queryForList("""
				SELECT store_id, converted, occurred_at FROM user_store_interaction
				WHERE member_id = ? AND type = 'STORE_VIEW' ORDER BY occurred_at, store_id
				""", memberId);
		assertThat(views).hasSize(3);
		assertThat(views.get(0).get("store_id")).isEqualTo(store1); // 같은 날 첫 조회 시각으로 한 행
		assertThat(views.get(0).get("converted")).isEqualTo(true);
		assertThat(views.get(1).get("store_id")).isEqualTo(store2);
		assertThat(views.get(1).get("converted")).isEqualTo(false);
		assertThat(views.get(2).get("converted")).isEqualTo(false); // 다음 날 조회
		Integer recoContext = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM user_store_interaction WHERE member_id = ? AND context = 'RECO'", Integer.class,
			memberId);
		assertThat(recoContext).isEqualTo(2);
		Integer ownerless = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM user_store_interaction WHERE member_id IS NULL", Integer.class);
		assertThat(ownerless).isZero();

		InteractionIngestResult again = ingestCommandService.ingest(DAY, DAY.plusDays(2));

		assertThat(again.inserted()).isZero(); // 유니크 제약 + ON CONFLICT — 재실행 안전
	}

	@Test
	@DisplayName("기록은 가게 단위와 빵 단위(메뉴 연결 태그만) 두 신호 — 랩 실데이터 범위와 수가 맞는다")
	void ingest_recordsMatchSource() {
		LocalDate from = LocalDate.of(2026, 8, 1);
		LocalDate to = LocalDate.of(2026, 9, 1);
		Integer expectedStores = jdbcTemplate.queryForObject("""
				SELECT count(DISTINCT (member_id, store_id, created_at)) FROM bread_record
				WHERE deleted_at IS NULL AND store_id IS NOT NULL AND created_at >= ? AND created_at < ?
				""", Integer.class, from.atStartOfDay(), to.atStartOfDay());
		Integer expectedBreads = jdbcTemplate.queryForObject("""
				SELECT count(DISTINCT (br.member_id, br.store_id, t.menu_id, br.created_at))
				FROM bread_record_tag t JOIN bread_record br ON br.id = t.record_id
				WHERE br.deleted_at IS NULL AND t.menu_id IS NOT NULL AND br.created_at >= ? AND br.created_at < ?
				""", Integer.class, from.atStartOfDay(), to.atStartOfDay());
		assertThat(expectedStores).isPositive();
		assertThat(expectedBreads).isPositive();

		InteractionIngestResult result = ingestCommandService.ingest(from, to);

		assertThat(result.byType())
			.containsEntry(InteractionType.RECORD_STORE, expectedStores)
			.containsEntry(InteractionType.RECORD_BREAD, expectedBreads);
	}

	@Test
	@DisplayName("담기 백필은 until 전 코스 가게를 코스 주인의 COURSE_ADD 로 — 추천에서 담은 것은 RECO 맥락")
	void backfillCourseAdds_attributesToOwner() {
		Long memberId = jdbcTemplate.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
		Long store = jdbcTemplate.queryForObject("SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id LIMIT 1",
			Long.class);
		String courseType = jdbcTemplate.queryForObject("SELECT type FROM courses LIMIT 1", String.class);
		Long courseId = jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, member_id, is_public, created_at)
				VALUES (?, '백필 랩', ?, false, ?) RETURNING id
				""", Long.class, courseType, memberId, BASE);
		Long recommendationId = jdbcTemplate.queryForObject("""
				INSERT INTO recommendation (member_id, course_id, mode, profile_version, provider, result, created_at)
				VALUES (?, ?, 'AREA', 0, 'rule', CAST('{"stores":[]}' AS jsonb), ?) RETURNING id
				""", Long.class, memberId, courseId, BASE);
		jdbcTemplate.update("""
				INSERT INTO course_stores (course_id, store_id, visit_order, created_at, recommendation_id)
				VALUES (?, ?, 1, ?, ?)
				""", courseId, store, BASE, recommendationId);
		// 큐레이션 코스(주인 없음)의 담기는 백필에서 빠진다 — 있어도 NOT NULL 위반으로 터지지 않는다(PR #747)
		Long curatedId = jdbcTemplate.queryForObject("""
				INSERT INTO courses (type, title, member_id, is_public, created_at)
				VALUES ('CURATED', '큐레이션 랩', NULL, true, ?) RETURNING id
				""", Long.class, BASE);
		jdbcTemplate.update(
			"INSERT INTO course_stores (course_id, store_id, visit_order, created_at) VALUES (?, ?, 1, ?)",
			curatedId, store, BASE);
		Integer before = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM course_stores cs JOIN courses c ON c.id = cs.course_id
				WHERE c.member_id IS NOT NULL AND cs.created_at < ?
				""", Integer.class, DAY.plusDays(1).atStartOfDay());

		int inserted = ingestCommandService.backfillCourseAddsByOwner(DAY.plusDays(1));

		assertThat(inserted).isEqualTo(before); // 2020-01-02 전에 담긴 건(주인 있는 코스) 이 픽스처뿐이거나, 있으면 전부
		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT type, context FROM user_store_interaction
				WHERE member_id = ? AND store_id = ? AND occurred_at = ?
				""", memberId, store, BASE);
		assertThat(row.get("type")).isEqualTo("COURSE_ADD");
		assertThat(row.get("context")).isEqualTo("RECO");
	}

	private void insertView(Long memberId, Long storeId, LocalDateTime at) {
		jdbcTemplate.update("""
				INSERT INTO request_log
				(member_id, http_method, endpoint, response_status, store_id, event_type, session_id, created_at)
				VALUES (?, 'GET', '/api/v1/stores/x', 200, ?, 'STORE_VIEW', 'lab-ingest', ?)
				""", memberId, storeId, at);
	}
}
