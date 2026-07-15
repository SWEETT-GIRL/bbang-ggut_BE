package sweetgirl.croissant.external.slack.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class SlackConfig {

	@Bean
	public RestClient slackRestClient() {
		return RestClient.builder().build();
	}
}
