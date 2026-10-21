package sweetgirl.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 같은 JAR 안의 WAS 배치 설정·마이그레이션과 섞이지 않는지 본다.
 */
class DataopsConfigTest {

	@Test
	@DisplayName("모든 진입점이 dataops.properties 를 읽는다 — WAS 배치의 application.properties 를 읽지 않는다")
	void entryPointsUseDataopsConfigName() {
		Map<String, Object> properties = DataopsApplication.defaultProperties(Map.of("dataops.agent.apply.enabled", "true"));

		assertThat(properties)
			.containsEntry("spring.config.name", "dataops")
			.containsEntry("dataops.agent.apply.enabled", "true");
	}

	@Test
	@DisplayName("Flyway 는 dataops 마이그레이션만 읽는다 — 기본 위치(db/migration)는 core 의 WAS 마이그레이션이다")
	void flywayReadsOnlyDataopsMigrations() throws IOException {
		Properties properties = load("dataops.properties");
		String location = properties.getProperty("spring.flyway.locations");
		Resource[] migrations = new PathMatchingResourcePatternResolver()
			.getResources(location.replace("classpath:", "classpath*:") + "/*.sql");

		assertThat(location).isEqualTo("classpath:db/dataops-migration");
		assertThat(migrations).extracting(Resource::getFilename)
			.contains("V20260801100000__create_store_sync_state.sql")
			.noneMatch(name -> name.contains("user_report_api"));
	}

	@Test
	@DisplayName("Redis 헬스는 끈다 — 쓰지 않는 Redis 때문에 기동 대기(health)가 DOWN 이 되지 않게")
	void redisHealthIsDisabled() throws IOException {
		assertThat(load("dataops.properties").getProperty("management.health.redis.enabled")).isEqualTo("false");
	}

	@Test
	@DisplayName("운영 프로필은 dataops 스키마를 주 DB 로 쓰고 API 키를 필수로 받는다")
	void prodProfileUsesDataopsSchemaAndRequiresKey() throws IOException {
		Properties properties = load("dataops-prod.properties");

		assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DATAOPS_DB_URL}");
		assertThat(properties.getProperty("spring.flyway.default-schema")).isEqualTo("dataops");
		assertThat(properties.getProperty("dataops.api-key")).isEqualTo("${DATAOPS_API_KEY}");
		assertThat(properties.getProperty("dataops.serving.write-enabled")).isNull();
	}

	/** 테스트 리소스의 같은 이름 파일이 클래스패스에서 가리므로 배포 파일을 경로로 읽는다 */
	private Properties load(String name) throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(Path.of("src/main/resources", name))) {
			properties.load(input);
		}
		return properties;
	}
}
