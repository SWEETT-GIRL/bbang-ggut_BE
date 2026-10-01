package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBreadAggregationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyReportBreadAggregationTest {

	private final String schema = "monthly_bread_" + UUID.randomUUID().toString().replace("-", "");
	private JdbcTemplate rootJdbc;
	private JdbcTemplate jdbc;
	private LocalContainerEntityManagerFactoryBean factory;
	private EntityManager entityManager;
	private MonthlyReportRepository repository;
	private MonthlyReportBreadAggregationService aggregation;

	@BeforeAll
	void setUp() {
		var source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		rootJdbc = new JdbcTemplate(source);
		rootJdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		jdbc = new JdbcTemplate(source);
		new ResourceDatabasePopulator(new ClassPathResource("monthly-report-bread-aggregation.sql"),
			new ClassPathResource("db/migration/V20261001131708__create_monthly_report_member.sql"),
			new ClassPathResource("db/migration/V20261001140644__add_monthly_report_appearance.sql"),
			new ClassPathResource("db/migration/V20261001170000__add_bread_photo_reference.sql"))
			.execute(source);
		factory = new LocalContainerEntityManagerFactoryBean();
		factory.setDataSource(source);
		// 빵로그 엔티티가 참조하는 회원·가게 등도 매핑하되 테스트용 원천 테이블만 생성한다.
		factory.setPackagesToScan("sweetgirl.croissant");
		factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.default_schema", schema,
			"hibernate.physical_naming_strategy",
			"org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
		factory.afterPropertiesSet();
		entityManager = factory.getObject().createEntityManager();
		var repositories = new JpaRepositoryFactory(entityManager);
		repository = repositories.getRepository(MonthlyReportRepository.class);
		aggregation = new MonthlyReportBreadAggregationService(
			repositories.getRepository(BreadRecordTagRepository.class));
	}

	@AfterAll
	void cleanUp() {
		if (entityManager != null) {
			entityManager.close();
		}
		if (factory != null) {
			factory.destroy();
		}
		if (rootJdbc != null) {
			rootJdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void aggregatesRealTagsThenKeepsPublishedCountsAndPhotoChoiceAfterDeletion() throws Exception {
		// 같은 로그의 다른 종류가 최고였던 빵이어도 소금빵 사진의 우선순위에는 영향을 주지 않는다.
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (1, 0, '베이글')");
		var result = aggregation.aggregate(123L, MONTH);
		assertThat(result.total()).isEqualTo(9);
		assertThat(result.uncategorized()).isEqualTo(2);
		assertThat(result.breads()).containsExactly(
			new MonthlyReportResponse.Bread("소금빵", 5L, "first-photo-640"),
			new MonthlyReportResponse.Bread("베이글", 1L, "old-salt"),
			new MonthlyReportResponse.Bread("식빵", 1L, "old-salt"));
		assertThat(aggregation.aggregate(789L, MONTH).breads()).isEmpty();
		assertThat(aggregation.aggregate(123L, "2025-10").total()).isZero();
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (1, 1, '소금빵')");
		result = aggregation.aggregate(123L, MONTH);
		assertThat(result.breads().get(0).imageKey()).isEqualTo("old-salt");
		// 같은 종류에 최고였던 빵이 여럿이면 그중 최신 기록을 사용한다. 다른 이름의 태그도 분류로 묶는다.
		jdbc.update("INSERT INTO bread_record_loved_bread VALUES (2, 0, '버터소금빵')");
		result = aggregation.aggregate(123L, MONTH);
		assertThat(result.breads().get(0).imageKey()).isEqualTo("newer-salt");
		jdbc.update("UPDATE bread_record SET loved_bread = '소금빵' WHERE id = 3");
		assertThat(aggregation.aggregate(123L, MONTH).breads().get(0).imageKey()).isEqualTo("first-photo-640");
		jdbc.update("UPDATE bread_record SET loved_bread = NULL WHERE id = 3");

		var reader = mock(MemberReader.class);
		given(reader.getMember(123L)).willReturn(new Member("현재 닉네임"));
		var notifications = mock(MonthlyReportNotificationService.class);
		var mapper = new ObjectMapper();
		try (var validation = Validation.buildDefaultValidatorFactory()) {
			var codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
			var publication = new MonthlyReportPublicationService(reader, repository, codec, notifications);
			var generation = new MonthlyReportGenerationService(repository, codec, aggregation, publication);
			var draft = codec.read(PAYLOAD, 123L, MONTH);
			var metrics = mapper.createObjectNode().put("records", 12);
			entityManager.getTransaction().begin();
			assertThat(generation.publishFromDraft(draft, metrics, "test-v1")).isTrue();
			entityManager.getTransaction().commit();
			assertThat(metrics.has("breadAggregation")).isFalse();
			var snapshot = repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow();
			String originalPayload = snapshot.getPayload();
			assertThat(codec.read(originalPayload, 123L, MONTH).breads()).isEqualTo(result.breads());
			assertThat(codec.read(originalPayload, 123L, MONTH).summary().breads()).isEqualTo(9);
			assertThat(mapper.readTree(snapshot.getRawMetrics()).at("/breadAggregation/uncategorizedTags").asLong())
				.isEqualTo(2);

			// 이전 소금빵 사진이 남아 있어도 발행 사진을 교체하거나 통계를 다시 계산하지 않는다.
			jdbc.update("UPDATE bread_record SET deleted_at = CURRENT_TIMESTAMP WHERE id = 2");
			jdbc.update("UPDATE bread_record_tag SET bread_category = '쿠키' WHERE id = 1");
			entityManager.clear();
			var query = new MonthlyReportQueryService(reader, repository, codec);
			var visible = query.getReport(123L, MONTH);
			assertThat(visible.breads().get(0)).isEqualTo(new MonthlyReportResponse.Bread("소금빵", 5L, null));
			assertThat(visible.summary().breads()).isEqualTo(9);
			assertThat(visible.nickname()).isEqualTo("현재 닉네임");
			entityManager.getTransaction().begin();
			assertThat(generation.publishFromDraft(draft, metrics, "test-v2")).isFalse();
			entityManager.getTransaction().commit();
			assertThat(repository.findById(new MonthlyReportMemberId(123L, MONTH)).orElseThrow().getPayload())
				.isEqualTo(originalPayload);
			verify(notifications, times(1)).recordPublished(any(), any(), any());
		}
	}
}
