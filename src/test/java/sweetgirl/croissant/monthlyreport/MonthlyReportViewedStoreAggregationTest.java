package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportViewedStoreAggregationService;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestDayRepository;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestJdbcRepository;
import sweetgirl.croissant.personalization.repository.UserStoreInteractionRepository;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
class MonthlyReportViewedStoreAggregationTest {
	private final String schema = "monthly_views_" + UUID.randomUUID().toString().replace("-", "");
	private final LocalDateTime from = LocalDateTime.of(2025, 12, 1, 0, 0);
	private final LocalDateTime until = from.plusMonths(1);
	private JdbcTemplate jdbc;
	private LocalContainerEntityManagerFactoryBean factory;
	private EntityManager entityManager;
	private UserInteractionIngestJdbcRepository ingest;
	private MonthlyReportViewedStoreAggregationService service;
	private TransactionTemplate transactions;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		new ResourceDatabasePopulator(new ClassPathResource(
			"db/migration/V20261005222050__track_interaction_ingest_days.sql")).execute(source);
		jdbc.execute("""
				CREATE TABLE user_store_interaction (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
					member_id bigint, store_id bigint, menu_id bigint, type varchar(24), context varchar(24),
					converted boolean, occurred_at timestamp, created_at timestamp DEFAULT now())
				""");
		factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setPackagesToScan("sweetgirl.croissant.personalization.entity");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		entityManager = factory.getObject().createEntityManager();
		JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
		service = new MonthlyReportViewedStoreAggregationService(
			repositories.getRepository(UserInteractionIngestDayRepository.class),
			repositories.getRepository(UserStoreInteractionRepository.class));
		ingest = new UserInteractionIngestJdbcRepository(new NamedParameterJdbcTemplate(source));
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
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

	@Test
	void incompleteMonthFailsButCompletedEmptyMonthReturnsZero() {
		assertIncomplete();
		assertThat(ingest.isDayComplete(from.toLocalDate())).isFalse();
		ingest.recordCompletedDays(from, until.minusDays(1));
		assertThat(ingest.isDayComplete(from.toLocalDate())).isTrue();
		assertIncomplete();
		ingest.recordCompletedDays(until.minusDays(1), until);
		assertThat(service.aggregate(123L, "2025-12")).isZero();
		ingest.recordCompletedDays(from, until);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interaction_ingest_day", Long.class)).isEqualTo(31);
	}

	@Test
	void countsDistinctStoresByEventTimeAndOwner() {
		ingest.recordCompletedDays(from, until);
		jdbc.update("""
				INSERT INTO user_store_interaction (member_id, store_id, type, occurred_at) VALUES
				(123, 11, 'STORE_VIEW', '2025-12-01'), (123, 11, 'STORE_VIEW', '2025-12-31'),
				(123, 12, 'STORE_VIEW', '2025-12-31 23:59:59.999999'),
				(123, 13, 'STORE_VIEW', '2026-01-01'), (123, 14, 'STORE_VIEW', '2025-11-30 23:59:59'),
				(456, 15, 'STORE_VIEW', '2025-12-01'), (123, 16, 'SAVE', '2025-12-01'),
				(123, NULL, 'STORE_VIEW', '2025-12-01'), (NULL, 17, 'STORE_VIEW', '2025-12-01')
				""");
		assertThat(service.aggregate(123L, "2025-12")).isEqualTo(2);
		assertThat(service.aggregate(456L, "2025-12")).isEqualTo(1);
	}

	@Test
	void completionRollsBackWithIngestTransaction() {
		transactions.executeWithoutResult(status -> {
			ingest.recordCompletedDays(from, until);
			status.setRollbackOnly();
		});
		assertIncomplete();
	}

	private void assertIncomplete() {
		assertThatThrownBy(() -> service.aggregate(123L, "2025-12")).isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorCode", MonthlyReportErrorCode.VIEW_INGEST_INCOMPLETE);
	}
}
