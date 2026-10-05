package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAutoPreparationScheduler;
import sweetgirl.croissant.monthlyreport.config.MonthlyReportAutoPreparationProperties;

class MonthlyReportAutoPreparationTest {
	@Test
	void monthBoundaryUsesKoreaTimeIncludingNewYear() {
		assertThat(MonthlyReportAutoPreparationScheduler.previousMonth(Instant.parse("2026-09-30T14:59:59Z")))
			.isEqualTo("2026-08");
		assertThat(MonthlyReportAutoPreparationScheduler.previousMonth(Instant.parse("2026-09-30T15:00:00Z")))
			.isEqualTo("2026-09");
		assertThat(MonthlyReportAutoPreparationScheduler.previousMonth(Instant.parse("2026-12-31T15:00:00Z")))
			.isEqualTo("2026-12");
	}

	@Test
	void disabledByDefaultAndEnabledRequiresValidAssetVersion() {
		MonthlyReportAutoPreparationProperties properties = new MonthlyReportAutoPreparationProperties();
		MonthlyReportAdminService reports = mock(MonthlyReportAdminService.class);
		new MonthlyReportAutoPreparationScheduler(properties, reports).prepare();
		verifyNoInteractions(reports);
		try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
			assertThat(factory.getValidator().validate(properties)).isEmpty();
			properties.setEnabled(true);
			for (String invalid : new String[] {"", "../v1", "V1", "a".repeat(61)}) {
				properties.setAssetVersion(invalid);
				assertThat(factory.getValidator().validate(properties)).isNotEmpty();
			}
			properties.setAssetVersion("v1");
			assertThat(factory.getValidator().validate(properties)).isEmpty();
		}
	}

	@Test
	void enabledSchedulerOnlyRegistersDraftAndCanRecoverAfterFailure() {
		MonthlyReportAutoPreparationProperties properties = new MonthlyReportAutoPreparationProperties();
		properties.setEnabled(true);
		properties.setAssetVersion("v1");
		MonthlyReportAdminService reports = mock(MonthlyReportAdminService.class);
		doThrow(new IllegalStateException("DB unavailable")).doNothing()
			.when(reports).prepareAutomatically(anyString(), eq("v1"));
		MonthlyReportAutoPreparationScheduler scheduler = new MonthlyReportAutoPreparationScheduler(properties,
			reports);
		scheduler.prepare();
		scheduler.prepare();
		verify(reports, times(2)).prepareAutomatically(
			MonthlyReportAutoPreparationScheduler.previousMonth(Instant.now()), "v1");
		verifyNoMoreInteractions(reports);
	}
}
