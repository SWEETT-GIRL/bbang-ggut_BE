package sweetgirl.dataops.autoapply;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyConfig {
	@Bean(destroyMethod = "close")
	public AutomaticApplyWriter automaticApplyWriter(@Value("${dataops.prod-db.url}") String url,
			@Value("${DATAOPS_AUTO_APPLY_DB_USERNAME:}") String username,
			@Value("${DATAOPS_AUTO_APPLY_DB_PASSWORD:}") String password, org.springframework.core.env.Environment environment) {
		if (environment.getProperty("dataops.api-key", "").isBlank())
			throw new IllegalStateException("Automatic apply requires dataops.api-key");
		for (String mode : new String[] {"apply", "replay", "runtime"}) {
			if (environment.getProperty("dataops.agent." + mode + ".enabled", Boolean.class, false))
				throw new IllegalStateException("Automatic live apply cannot run inside an agent approval process");
		}
		return new AutomaticApplyWriter(url, username, password);
	}
}
