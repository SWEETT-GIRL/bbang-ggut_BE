package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportSourceType;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBreadAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportRecordAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportSourceReader;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTownAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportViewedStoreAggregationService;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
class MonthlyReportSourceHistoryTest {

	private final String schema = "monthly_history_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate jdbc;
	private LocalContainerEntityManagerFactoryBean factory;
	private EntityManager entityManager;
	private MonthlyReportSourceHistoryRepository repository;
	private MonthlyReportSourceReader reader;
	private TransactionTemplate transactions;
	private long baselineId;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
		new ResourceDatabasePopulator(new ClassPathResource("monthly-report-bread-aggregation.sql"),
			new ClassPathResource("db/migration/V20261001131708__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261001140644__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261001170000__add_bread_photo_reference.sql"),
			new ClassPathResource("db/migration/V20261001170100__link_monthly_report_bread_photos.sql"))
			.execute(source);
		jdbc.update("""
				INSERT INTO saved_stores (id, member_id, store_id, created_at) VALUES
				(1, 123, 11, '2025-12-01'), (2, 123, 12, '2025-11-01')
				""");
		jdbc.update("INSERT INTO store_plan (member_id, store_id, created_at) VALUES (123, 11, '2025-12-01')");
		jdbc.update("INSERT INTO courses VALUES (201, 123, '2025-12-01', NULL)");
		jdbc.update("""
				INSERT INTO course_stores VALUES
				(201, 201, 11, '2025-12-01', NULL), (202, 201, 13, '2025-12-01', NULL)
				""");
		ResourceDatabasePopulator historyMigration = new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261001190000__create_monthly_report_source_history.sql"));
		historyMigration.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
		transactions.executeWithoutResult(status -> historyMigration.execute(source));
		factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setPackagesToScan("sweetgirl.croissant");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		entityManager = factory.getObject().createEntityManager();
		repository = new JpaRepositoryFactory(entityManager).getRepository(MonthlyReportSourceHistoryRepository.class);
		reader = new MonthlyReportSourceReader(repository, new ObjectMapper());
		baselineId = jdbc.queryForObject("SELECT MAX(id) FROM monthly_report_source_history", Long.class);
	}

	@AfterEach
	void cleanUp() {
		if (entityManager != null) {
			entityManager.close();
		}
		if (factory != null) {
			factory.destroy();
		}
		if (jdbc != null) {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void historyBeforeMonthEnd() {
		jdbc.update("UPDATE monthly_report_history_coverage SET covered_from = '2025-12-01'");
		jdbc.update("UPDATE monthly_report_source_history SET recorded_at = '2025-12-31 23:00:00'");
	}

	private void changesAfterMonthEnd() {
		jdbc.update("UPDATE monthly_report_source_history SET recorded_at = '2026-01-01 00:10:00' WHERE id > ?",
			baselineId);
		entityManager.clear();
	}

	@Test
	void rejectsMonthsBeforeCoverageRatherThanInventingMissingHistory() {
		assertThatThrownBy(() -> reader.read(123L, MONTH)).isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorCode", MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
	}

	@Test
	void retainsMonthEndRecordsTagsPlansAndCoursesAfterEditsAndDeletion() {
		historyBeforeMonthEnd();
		MonthlyReportSourceSnapshot before = reader.read(123L, MONTH);
		assertThat(before.records()).hasSize(3);
		assertThat(before.savedStoreIds()).containsExactly(11L);
		assertThat(before.plannedStoreIds()).containsExactlyInAnyOrder(11L, 13L);
		assertThat(before.courses()).isEqualTo(1);
		transactions.executeWithoutResult(status -> {
			jdbc.update("UPDATE bread_record SET eaten_at = '2026-01-01', store_id = 99 WHERE id = 1");
			jdbc.update("DELETE FROM bread_record_tag WHERE record_id = 2");
			jdbc.update("DELETE FROM bread_record_photo WHERE record_id = 2");
			jdbc.update("UPDATE bread_record SET deleted_at = '2026-01-01 00:10:00' WHERE id = 2");
			jdbc.update("DELETE FROM saved_stores WHERE id = 1");
			jdbc.update("DELETE FROM store_plan WHERE member_id = 123");
			jdbc.update("UPDATE courses SET deleted_at = '2026-01-01 00:10:00' WHERE id = 201");
		});
		changesAfterMonthEnd();
		assertThat(reader.read(123L, MONTH)).isEqualTo(before);
		MonthlyReportSourceSnapshot january = reader.read(123L, "2026-01");
		assertThat(january.records()).hasSize(2);
		assertThat(january.records().get(0).storeId()).isEqualTo(99);
		assertThat(january.savedStoreIds()).isEmpty();
		assertThat(january.plannedStoreIds()).isEmpty();
		assertThat(january.courses()).isZero();
	}

	@Test
	void capturesMenuClassificationAndLovedBreadChangesWithoutChangingOldMonth() {
		historyBeforeMonthEnd();
		jdbc.update("UPDATE menu SET menu_category_id = 2 WHERE id = 1");
		jdbc.update("UPDATE menu_category SET name = '베이글' WHERE id = 2");
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (2, 0, '버터소금빵')");
		changesAfterMonthEnd();
		assertThat(reader.read(123L, MONTH).tags().stream().filter(tag -> tag.id() == 2).findFirst().orElseThrow()
			.menuKind()).isEqualTo(MenuKind.소금빵);
		assertThat(reader.read(123L, MONTH).records().stream().filter(record -> record.id() == 2)
			.findFirst().orElseThrow().lovedBreads()).isEmpty();
		assertThat(repository.findChildState("BREAD_TAG", Set.of(2L), LocalDateTime.of(2026, 2, 1, 0, 0)))
			.singleElement().satisfies(history -> assertThat(history.getPayload()).contains("베이글"));
		assertThat(repository.findOwnerState(123L, LocalDateTime.of(2026, 2, 1, 0, 0)).stream()
			.filter(history -> history.getSourceType() == MonthlyReportSourceType.BREAD_RECORD
				&& history.getSourceKey().equals("2"))
			.findFirst().orElseThrow().getPayload()).contains("버터소금빵");
	}

	@Test
	void movesCompositePlanKeysAndCourseOwnershipWithoutResurrectingOldOwner() {
		historyBeforeMonthEnd();
		jdbc.update("UPDATE store_plan SET member_id = 456 WHERE member_id = 123");
		jdbc.update("UPDATE courses SET member_id = 456 WHERE id = 201");
		changesAfterMonthEnd();
		assertThat(reader.read(123L, MONTH).courses()).isEqualTo(1);
		assertThat(reader.read(456L, MONTH).courses()).isZero();
		LocalDateTime januaryEnd = LocalDateTime.of(2026, 2, 1, 0, 0);
		assertThat(repository.findOwnerState(123L, januaryEnd))
			.noneMatch(history -> history.getSourceType() == MonthlyReportSourceType.STORE_PLAN
				|| history.getSourceType() == MonthlyReportSourceType.COURSE);
		assertThat(repository.findOwnerState(456L, januaryEnd).stream()
			.filter(history -> history.getSourceType() == MonthlyReportSourceType.STORE_PLAN
				|| history.getSourceType() == MonthlyReportSourceType.COURSE))
			.hasSize(2);
	}

	@Test
	void publishesHistoricalCountsAfterSourceDeletionAndHidesDeletedPhoto() {
		jdbc.update("UPDATE bread_record SET region_key = '서울_성동구_성수동' WHERE id = 3");
		baselineId = jdbc.queryForObject("SELECT MAX(id) FROM monthly_report_source_history", Long.class);
		historyBeforeMonthEnd();
		transactions.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM bread_record_tag WHERE record_id = 3");
			jdbc.update("DELETE FROM bread_record_photo WHERE record_id = 3");
			jdbc.update("UPDATE bread_record SET deleted_at = '2026-01-01 00:10:00' WHERE id = 3");
			jdbc.update("DELETE FROM saved_stores WHERE id = 1");
			jdbc.update("DELETE FROM store_plan WHERE member_id = 123");
			jdbc.update("UPDATE courses SET deleted_at = '2026-01-01 00:10:00' WHERE id = 201");
		});
		changesAfterMonthEnd();
		JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
		MonthlyReportRepository reports = repositories.getRepository(MonthlyReportRepository.class);
		MemberReader memberReader = mock(MemberReader.class);
		given(memberReader.getMember(123L)).willReturn(MonthlyReportFixtures.eligibleMember("현재 이름"));
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(new ObjectMapper(),
				validation.getValidator());
			MonthlyReportPublicationService publication = new MonthlyReportPublicationService(memberReader,
				reports, codec, mock(MonthlyReportNotificationService.class));
			MonthlyReportViewedStoreAggregationService views = mock(MonthlyReportViewedStoreAggregationService.class);
			given(views.aggregate(123L, MONTH)).willReturn(2L);
			MonthlyReportGenerationService generation = new MonthlyReportGenerationService(reports, codec,
				new MonthlyReportBreadAggregationService(reader),
				new MonthlyReportRecordAggregationService(reader, reports, codec), publication, reader,
				views,
				new MonthlyReportTownAggregationService(), new MonthlyReportActivityPolicy());
			entityManager.getTransaction().begin();
			assertThat(generation.publishFromDraft(codec.read(PAYLOAD, 123L, MONTH),
				new ObjectMapper().createObjectNode().put("test", true), "history-test-v1")).isTrue();
			entityManager.getTransaction().commit();
			entityManager.clear();
			MonthlyReportQueryService query = new MonthlyReportQueryService(memberReader, reports, codec,
				repositories.getRepository(BreadRecordTagRepository.class));
			MonthlyReportResponse report = query.getReport(123L, MONTH);
			assertThat(report.schemaVersion()).isEqualTo(2);
			assertThat(report.activity().totalScore()).isEqualTo(36);
			assertThat(report.activity().abilities().record().score()).isEqualTo(21);
			assertThat(report.activity().type()).isEqualTo("record");
			assertThat(report.summary()).isEqualTo(new MonthlyReportResponse.Summary(3L, 9L, 3L));
			assertThat(report.stores().viewed()).isEqualTo(2);
			assertThat(report.towns()).containsExactly(new MonthlyReportResponse.Town("성수동", "서울 성동구"));
			assertThat(report.stores().saved()).isEqualTo(1);
			assertThat(report.stores().planned()).isEqualTo(2);
			assertThat(report.stores().courses()).isEqualTo(1);
			assertThat(report.breads().get(0)).isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, null));
		}
	}

	@Test
	void appliesRemovalAndReadditionBeforeMonthEndAndLeavesOldMonthUntouched() {
		historyBeforeMonthEnd();
		transactions.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM saved_stores WHERE id = 1");
			jdbc.update(
				"INSERT INTO saved_stores (id, member_id, store_id, created_at) VALUES (3, 123, 11, '2025-12-31')");
			jdbc.update("DELETE FROM store_plan WHERE member_id = 123");
			jdbc.update("INSERT INTO store_plan (member_id, store_id, created_at) VALUES (123, 14, '2025-12-31')");
		});
		jdbc.update("UPDATE monthly_report_source_history SET recorded_at = '2025-12-31 23:59:59' WHERE id > ?",
			baselineId);
		entityManager.clear();
		MonthlyReportSourceSnapshot source = reader.read(123L, MONTH);
		assertThat(source.savedStoreIds()).containsExactly(11L);
		assertThat(source.plannedStoreIds()).containsExactlyInAnyOrder(11L, 13L, 14L);
		jdbc.update("DELETE FROM saved_stores WHERE id = 3");
		entityManager.clear();
		assertThat(reader.read(123L, MONTH)).isEqualTo(source);
	}

	@Test
	void rollsBackSourceAndHistoryTogetherAndDoesNotStoreImageKeys() {
		long count = repository.count();
		transactions.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM saved_stores WHERE id = 1");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monthly_report_source_history", Long.class))
				.isEqualTo(count + 1);
			status.setRollbackOnly();
		});
		assertThat(repository.count()).isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_stores WHERE id = 1", Long.class)).isEqualTo(1);
		assertThat(
			jdbc.queryForList("SELECT payload::text FROM monthly_report_source_history WHERE payload IS NOT NULL",
				String.class))
			.noneMatch(payload -> payload.contains("old-salt") || payload.contains("awsKey"));
	}
}
