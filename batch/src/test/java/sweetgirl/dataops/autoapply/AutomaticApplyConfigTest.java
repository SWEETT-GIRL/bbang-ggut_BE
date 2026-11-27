package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AutomaticApplyConfigTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(AutomaticApplyConfig.class)
		.withPropertyValues("dataops.prod-db.url=jdbc:postgresql://127.0.0.1/unused",
			"DATAOPS_AUTO_APPLY_DB_USERNAME=test", "DATAOPS_AUTO_APPLY_DB_PASSWORD=test");

	@Test void enabled_without_api_key_fails_before_creating_writer() {
		context.withPropertyValues("dataops.auto-apply.enabled=true").run(application ->
			assertThat(application.getStartupFailure()).hasRootCauseMessage("Automatic apply requires dataops.api-key"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   "})
	void enabled_with_blank_api_key_fails(String key) {
		context.withPropertyValues("dataops.auto-apply.enabled=true", "dataops.api-key=" + key).run(application ->
			assertThat(application.getStartupFailure()).hasRootCauseMessage("Automatic apply requires dataops.api-key"));
	}

	@Test void disabled_local_mode_does_not_require_a_key_or_create_a_writer() {
		context.withPropertyValues("dataops.auto-apply.enabled=false").run(application ->
			assertThat(application).hasNotFailed().doesNotHaveBean(AutomaticApplyWriter.class));
	}

	@Test void enabled_with_a_key_creates_the_private_writer() {
		context.withPropertyValues("dataops.auto-apply.enabled=true", "dataops.api-key=test-key").run(application ->
			assertThat(application).hasNotFailed().hasSingleBean(AutomaticApplyWriter.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"apply", "replay", "runtime"})
	void agent_modes_cannot_start_the_live_writer(String mode) {
		context.withPropertyValues("dataops.auto-apply.enabled=true", "dataops.api-key=test-key",
			"dataops.agent." + mode + ".enabled=true").run(application ->
			assertThat(application.getStartupFailure())
				.hasRootCauseMessage("Automatic live apply cannot run inside an agent approval process"));
	}
}
