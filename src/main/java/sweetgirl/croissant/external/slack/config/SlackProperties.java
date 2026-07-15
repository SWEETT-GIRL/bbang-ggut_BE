package sweetgirl.croissant.external.slack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "slack.notification")
public class SlackProperties {

	private boolean enabled = true;
	private String reportWebhookUrl;
	private String abuseWebhookUrl;
}
