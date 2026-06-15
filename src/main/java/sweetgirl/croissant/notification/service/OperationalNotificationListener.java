package sweetgirl.croissant.notification.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;

@Component
@RequiredArgsConstructor
public class OperationalNotificationListener {

	private final DiscordWebhookClient discordWebhookClient;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void handle(OperationalNotificationEvent event) {
		discordWebhookClient.send(event);
	}
}
