package sweetgirl.croissant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.admin.bulkimport.loader.DongListLoader;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestrator;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressService;
import sweetgirl.croissant.admin.bulkimport.service.DongImporter;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.breadranking.service.BreadCategorySeeder;

class V2RuntimeConfigTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(SchedulerConfig.class, BreadCategorySeeder.class)
		.withBean(BreadCategoryRepository.class, () -> mock(BreadCategoryRepository.class));

	@Test
	@DisplayName("기존 프로필은 설정을 추가하지 않아도 스케줄러와 기본 목록 시더를 유지한다")
	void existingProfilesKeepDefaults() {
		context.run(result -> {
			assertThat(result).hasSingleBean(SchedulerConfig.class);
			assertThat(result).hasSingleBean(BreadCategorySeeder.class);
		});
	}

	@Test
	@DisplayName("v2 프로필은 마이그레이션만 직접 돌리고 스케줄러·시더·데이터 보정은 막는다")
	void v2DisablesAutomaticWriters() throws IOException {
		Properties properties = new Properties();
		try (var input = new ClassPathResource("application-v2.properties").getInputStream()) {
			properties.load(input);
		}
		assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
		assertThat(properties.getProperty("monthly-report.auto-preparation.enabled")).isEqualTo("false");
		assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		context.withPropertyValues(
			"app.scheduling.enabled=" + properties.getProperty("app.scheduling.enabled"),
			"app.startup-data-maintenance.enabled=" + properties.getProperty("app.startup-data-maintenance.enabled"))
			.run(result -> {
				assertThat(result).doesNotHaveBean(SchedulerConfig.class);
				assertThat(result).doesNotHaveBean(BreadCategorySeeder.class);
			});

		BulkImportProgressService progress = mock(BulkImportProgressService.class);
		BulkImportOrchestrator orchestrator = new BulkImportOrchestrator(
			mock(DongListLoader.class), progress, mock(DongImporter.class));
		ReflectionTestUtils.setField(orchestrator, "startupDataMaintenanceEnabled", false);
		orchestrator.recoverStaleOnStartup(null);
		verify(progress, never()).recoverStaleInProgress();
	}

	@Test
	@DisplayName("기존 프로필의 시작 시 미완료 작업 복구를 유지한다")
	void existingStartupRecoveryIsUnchanged() {
		BulkImportProgressService progress = mock(BulkImportProgressService.class);
		BulkImportOrchestrator orchestrator = new BulkImportOrchestrator(
			mock(DongListLoader.class), progress, mock(DongImporter.class));
		orchestrator.recoverStaleOnStartup(null);
		verify(progress).recoverStaleInProgress();
	}
}
