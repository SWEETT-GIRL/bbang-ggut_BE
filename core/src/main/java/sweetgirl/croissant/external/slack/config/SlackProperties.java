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
	private String letterWebhookUrl;
	private String searchMissWebhookUrl;

	/** 제목 앞에 붙는 환경 표시. dev와 prod가 같은 웹훅을 쓸 때 어느 서버에서 온 건지 가른다. */
	private String titlePrefix;
}
