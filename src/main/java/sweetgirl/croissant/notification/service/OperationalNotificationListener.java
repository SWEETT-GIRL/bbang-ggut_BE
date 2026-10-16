package sweetgirl.croissant.notification.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.slack.client.SlackWebhookClient;
import sweetgirl.croissant.external.slack.config.SlackProperties;
import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;

@Component
@RequiredArgsConstructor
public class OperationalNotificationListener {

	private final SlackWebhookClient slackWebhookClient;
	private final SlackProperties properties;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handle(OperationalNotificationEvent event) {
		if (!properties.isEnabled()) {
			return;
		}
		slackWebhookClient.send(webhookUrl(event.channel()), toMessage(event));
	}

	private String webhookUrl(Channel channel) {
		return switch (channel) {
			case REPORT -> properties.getReportWebhookUrl();
			case ABUSE -> properties.getAbuseWebhookUrl();
			case LETTER -> properties.getLetterWebhookUrl();
			case SEARCH_MISS -> properties.getSearchMissWebhookUrl();
		};
	}

	private String title(String title) {
		String prefix = properties.getTitlePrefix();
		if (prefix == null || prefix.isBlank()) {
			return title;
		}
		return prefix.strip() + " " + title;
	}

	private SlackMessage toMessage(OperationalNotificationEvent event) {
		List<SlackMessage.Field> fields = event.fields().stream()
			.map(field -> new SlackMessage.Field(field.name(), field.value(), field.inline()))
			.toList();
		return new SlackMessage(
			event.color(),
			title(event.title()),
			event.description(),
			event.timestamp(),
			fields);
	}
}
