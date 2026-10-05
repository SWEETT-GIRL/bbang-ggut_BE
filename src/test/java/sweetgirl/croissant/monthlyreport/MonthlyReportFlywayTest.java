package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Tag("lab")
@EnabledIfEnvironmentVariable(named = "MONTHLY_REPORT_TEST_JDBC_URL", matches = ".+")
class MonthlyReportFlywayTest {
	@TempDir
	Path scripts;

	@Test
	void migratesAllReportsAfterOctoberSecondEvenWithoutOutOfOrder() throws Exception {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("MONTHLY_REPORT_TEST_JDBC_URL"),
			System.getenv("MONTHLY_REPORT_TEST_DB_USER"), System.getenv("MONTHLY_REPORT_TEST_DB_PASSWORD"));
		JdbcTemplate jdbc = new JdbcTemplate(source);
		String schema = "monthly_flyway_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE SCHEMA " + schema);
		source.setSchema(schema);
		try {
			new ResourceDatabasePopulator(new ClassPathResource("monthly-report-bread-aggregation.sql"))
				.execute(source);
			jdbc.execute("CREATE TABLE notification (id bigint PRIMARY KEY)");
			List<String> migrations = List.of(
				"V20261005222045__create_monthly_report_member.sql",
				"V20261005222046__add_monthly_report_appearance.sql",
				"V20261005222047__add_bread_photo_reference.sql",
				"V20261005222048__link_monthly_report_bread_photos.sql",
				"V20261005222049__create_monthly_report_source_history.sql",
				"V20261005222050__track_interaction_ingest_days.sql",
				"V20261005222051__create_monthly_report_jobs.sql",
				"V20261005222052__monthly_report_assets.sql",
				"V20261005222053__monthly_report_push.sql",
				"V20261005222054__monthly_report_admin.sql",
				"V20261005222055__monthly_report_single_member_run.sql");
			for (String migration : migrations) {
				try (InputStream sql = new ClassPathResource("db/migration/" + migration).getInputStream()) {
					Files.copy(sql, scripts.resolve(migration));
				}
			}
			Flyway flyway = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
				.locations("filesystem:" + scripts).baselineOnMigrate(true).baselineVersion("20261002150000")
				.outOfOrder(false).load();
			assertThat(flyway.migrate().migrationsExecuted).isEqualTo(migrations.size());
			flyway.validate();
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_report_history_coverage", Integer.class))
				.isEqualTo(1);
		} finally {
			jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}
}
