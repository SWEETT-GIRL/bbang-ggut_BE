package sweetgirl.croissant.monthlyreport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "monthly-report.auto-preparation")
public class MonthlyReportAutoPreparationProperties {
	private boolean enabled = false;
	private String assetVersion = "";

	@AssertTrue(message = "자동 생성 활성화 시 유효한 이미지 버전을 지정해야 합니다")
	public boolean isConfigurationValid() {
		return !enabled || (assetVersion != null && assetVersion.matches("[a-z0-9][a-z0-9-]{0,59}"));
	}
}
