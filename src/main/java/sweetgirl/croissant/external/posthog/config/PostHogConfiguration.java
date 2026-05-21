package sweetgirl.croissant.external.posthog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;

import jakarta.annotation.PreDestroy;

@Configuration
public class PostHogConfiguration {

	@Value("${posthog.api-key}")
	private String apiKey;

	@Value("${posthog.host:https://us.i.posthog.com}")
	private String host;

	private PostHogInterface postHogClient;

	@Bean
	public PostHogInterface postHog() {
		PostHogConfig config = PostHogConfig
			.builder(apiKey)
			.host(host)
			.build();

		this.postHogClient = PostHog.with(config);
		return this.postHogClient;
	}

	@PreDestroy
	public void onDestroy() {
		if (postHogClient != null) {
			postHogClient.flush();
			postHogClient.close();
		}
	}

}
