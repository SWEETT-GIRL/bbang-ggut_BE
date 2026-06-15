package sweetgirl.croissant.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "discord.notification")
public class DiscordNotificationProperties {

	private boolean enabled = true;
	private String reportWebhookUrl;
	private String abuseWebhookUrl;
	private String username = "bbang-ggut admin";
}
