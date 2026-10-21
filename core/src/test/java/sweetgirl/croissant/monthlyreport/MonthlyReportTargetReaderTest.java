package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
class MonthlyReportTargetReaderTest {
	private final String schema = "monthly_targets_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate jdbc;
	private LocalContainerEntityManagerFactoryBean factory;
	private EntityManager entityManager;
	private MonthlyReportTargetReader reader;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc.execute("""
				CREATE TABLE member (id bigint PRIMARY KEY, member_type varchar(20), created_at timestamp,
					deleted_at timestamp)
				""");
		jdbc.execute("""
				CREATE TABLE monthly_report_member (member_id bigint, year_month varchar(7), status varchar(16))
				""");
		jdbc.update("""
				INSERT INTO member VALUES (1, 'USER', '2025-12-01', NULL), (2, 'GUEST', '2025-12-01', NULL),
					(3, 'BLOG_BOT', '2025-12-01', NULL), (4, 'USER', '2025-12-01', '2026-01-01'),
					(5, 'USER', '2026-01-01', NULL), (6, 'USER', '2025-12-31 23:59:59.999999', NULL),
					(7, 'USER', '2025-12-01', NULL), (8, 'USER', '2025-12-01', NULL),
					(9, 'USER', '2025-12-01', NULL), (10, 'USER', '2025-12-01', NULL)
				""");
		jdbc.update("""
				INSERT INTO monthly_report_member VALUES (7, '2025-12', 'PUBLISHED'), (8, '2025-12', 'FAILED'),
					(9, '2025-12', 'WITHHELD'), (10, '2025-11', 'PUBLISHED')
				""");
		factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		factory.setPackagesToScan("sweetgirl.croissant");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		entityManager = factory.getObject().createEntityManager();
		reader = new MonthlyReportTargetReader(new JpaRepositoryFactory(entityManager)
			.getRepository(MemberRepository.class));
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
	void pagesNormalMembersIncludingNoActivityAndRetriesWhileExcludingPublished() {
		var first = reader.nextPage("2025-12", 0, 2);
		assertThat(first.getContent()).containsExactly(1L, 6L);
		assertThat(first.hasNext()).isTrue();
		// 첫 페이지를 발행해 대상 목록에서 사라져도 offset 이동 없이 다음 회원을 읽는다.
		jdbc.update("INSERT INTO monthly_report_member VALUES (1, '2025-12', 'PUBLISHED')");
		var next = reader.nextPage("2025-12", 6, 2);
		assertThat(next.getContent()).containsExactly(8L, 9L);
		assertThat(next.hasNext()).isTrue();
		var last = reader.nextPage("2025-12", 9, 2);
		assertThat(last.getContent()).containsExactly(10L);
		assertThat(last.hasNext()).isFalse();
		assertThat(reader.nextPage("2025-12", 10, 2).getContent()).isEmpty();
	}

	@Test
	void rejectsOpenMonthAndInvalidCursorOrPageSize() {
		assertThatThrownBy(() -> reader.nextPage(YearMonth.now(ZoneId.of("Asia/Seoul")).toString(), 0, 10))
			.isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorCode", MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		assertThatThrownBy(() -> reader.nextPage("2025-12", -1, 10)).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> reader.nextPage("2025-12", 0, 0)).isInstanceOf(CustomException.class);
		assertThatThrownBy(() -> reader.nextPage("2025-12", 0, 1001)).isInstanceOf(CustomException.class);
	}
}
