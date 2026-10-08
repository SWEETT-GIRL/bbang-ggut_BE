package sweetgirl.croissant.breadlog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 실제 PostgreSQL에서 새 마이그레이션·부분 고유 인덱스·병합 쿼리·이력 payload를 검증한다. */
@Tag("lab")
@EnabledIfEnvironmentVariable(named = "TUTORIAL_TEST_JDBC_URL", matches = "jdbc:postgresql://localhost:5432/.+")
class TutorialRecordPostgresTest {
	private final String schema = "tutorial_test_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate jdbc;
	private NamedParameterJdbcTemplate named;

	@BeforeEach
	void setup() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TUTORIAL_TEST_JDBC_URL"),
			"sweetgirl", "soboro-lab");
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		named = new NamedParameterJdbcTemplate(source);
		jdbc.execute("CREATE TABLE member (id bigint PRIMARY KEY)");
		jdbc.execute("""
				CREATE TABLE bread_record (
					id bigint PRIMARY KEY, member_id bigint, store_id bigint, region_key varchar(100),
					created_at timestamp DEFAULT CURRENT_TIMESTAMP, eaten_at timestamp DEFAULT CURRENT_TIMESTAMP,
					deleted_at timestamp, is_public boolean NOT NULL DEFAULT false, loved_bread varchar(100))
				""");
		jdbc.execute("CREATE TABLE bread_record_loved_bread (record_id bigint, sort_order int, name varchar(100))");
		jdbc.execute("INSERT INTO member VALUES (1), (2)");
		ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261008110000__add_tutorial_bread_record.sql"));
		migration.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
		migration.execute(source);
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261008150000__add_managed_tutorial_templates.sql")).execute(source);
	}

	@AfterEach
	void cleanup() {
		if (jdbc != null) {
			// 이 테스트에서 생성한 UUID 스키마만 정리한다.
			jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}

	@Test
	void catalogMigrationSeedsOneRowAndReferencesStoredRevision() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM tutorial_template_catalog", Long.class)).isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update("UPDATE tutorial_template_catalog SET active_code='missing' WHERE id=1"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		jdbc.update(
			"INSERT INTO tutorial_template_revision (code, content, published, created_at) "
				+ "VALUES ('v1', '{}', true, CURRENT_TIMESTAMP)");
		jdbc.update("UPDATE tutorial_template_catalog SET active_code='v1' WHERE id=1");
		jdbc.update("UPDATE tutorial_template_catalog SET active_code=null WHERE id=1");
		assertThat(
			jdbc.queryForObject("SELECT published FROM tutorial_template_revision WHERE code='v1'", Boolean.class))
			.isTrue();
	}

	@Test
	void constraintsProtectUniquenessAndPrivacyAndPayloadKeepsType() {
		insertTutorial(10, 1);
		assertThatThrownBy(() -> insertTutorial(11, 1))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE bread_record SET is_public=true WHERE id=10"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE bread_record SET store_id=99 WHERE id=10"))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject(
			"SELECT monthly_report_record_payload(r)->>'recordType' FROM bread_record r WHERE id=10", String.class))
			.isEqualTo("TUTORIAL");
	}

	@Test
	void duplicateMergePreservesTargetReceiptEvenWhenTargetRecordWasDeleted() throws Exception {
		insertTutorial(10, 1);
		insertTutorial(20, 2);
		jdbc.update("UPDATE bread_record SET deleted_at=CURRENT_TIMESTAMP WHERE id=20");
		nativeQuery(BreadRecordRepository.class, "retireDuplicateTutorial");
		nativeQuery(MemberTutorialCompletionRepository.class, "removeMergedCompletion");
		nativeQuery(MemberTutorialCompletionRepository.class, "moveOwner");
		// 기존 moveOwner는 JPQL이므로 JDBC에서는 동일한 소유자 이관을 SQL로 실행한다.
		jdbc.update("UPDATE bread_record SET member_id=2 WHERE member_id=1");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM member_tutorial_completion", Long.class)).isEqualTo(1);
		assertThat(
			jdbc.queryForObject("SELECT record_id FROM member_tutorial_completion WHERE member_id=2", Long.class))
			.isEqualTo(20);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bread_record WHERE deleted_at IS NULL", Long.class))
			.isZero();
	}

	@Test
	void uncompletedTargetReceivesGuestRecordAndReceipt() throws Exception {
		insertTutorial(10, 1);
		nativeQuery(BreadRecordRepository.class, "retireDuplicateTutorial");
		nativeQuery(MemberTutorialCompletionRepository.class, "removeMergedCompletion");
		nativeQuery(MemberTutorialCompletionRepository.class, "moveOwner");
		jdbc.update("UPDATE bread_record SET member_id=2 WHERE member_id=1");
		assertThat(
			jdbc.queryForObject("SELECT record_id FROM member_tutorial_completion WHERE member_id=2", Long.class))
			.isEqualTo(10);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM bread_record WHERE member_id=2 AND deleted_at IS NULL",
			Long.class)).isEqualTo(1);
	}

	private void insertTutorial(long id, long memberId) {
		jdbc.update("INSERT INTO bread_record (id, member_id, record_type) VALUES (?, ?, 'TUTORIAL')", id, memberId);
		jdbc.update("INSERT INTO member_tutorial_completion VALUES (?, ?, 'bbangdaengi-v1', CURRENT_TIMESTAMP)",
			memberId, id);
	}

	private void nativeQuery(Class<?> repository, String method) throws Exception {
		Query query = repository.getMethod(method, Long.class, Long.class).getAnnotation(Query.class);
		assertThat(query.nativeQuery()).isTrue();
		named.update(query.value(), Map.of("guestId", 1L, "targetId", 2L));
	}
}
