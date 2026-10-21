package sweetgirl.croissant.searchmiss.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

class SearchMissTriagePropertiesTest {

	private static SearchMissTriageProperties bind(String autoApplyVerdicts) {
		Binder binder = new Binder(new MapConfigurationPropertySource(
			Map.of("search-miss.triage.auto-apply-verdicts", autoApplyVerdicts)));
		return binder.bindOrCreate("search-miss.triage", Bindable.of(SearchMissTriageProperties.class));
	}

	@Test
	@DisplayName("자동 적용 판정이 비어 있으면 shadow mode 다 — 배포 변수가 없을 때의 기본")
	void emptyMeansShadow() {
		SearchMissTriageProperties properties = bind("");

		assertThat(properties.isShadow()).isTrue();
		assertThat(properties.autoApplies(SearchMissVerdict.CORRECTED)).isFalse();
	}

	@Test
	@DisplayName("쉼표로 여러 판정을 켠다")
	void bindsCommaSeparatedVerdicts() {
		SearchMissTriageProperties properties = bind("CORRECTED,JUNK");

		assertThat(properties.getAutoApplyVerdicts())
			.containsExactlyInAnyOrder(SearchMissVerdict.CORRECTED, SearchMissVerdict.JUNK);
		assertThat(properties.autoApplies(SearchMissVerdict.NOW_HIT)).isFalse();
	}

	@Test
	@DisplayName("기본값 — 꺼짐, shadow, 하루 2,000건, 처리 후 90일 보관")
	void defaults() {
		SearchMissTriageProperties properties = new SearchMissTriageProperties();

		assertThat(properties.isEnabled()).isFalse();
		assertThat(properties.isShadow()).isTrue();
		assertThat(properties.getBatchSize()).isEqualTo(2000);
		assertThat(properties.getRetentionDays()).isEqualTo(90);
	}
}
