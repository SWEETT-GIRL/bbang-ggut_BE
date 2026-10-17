package sweetgirl.croissant.searchmiss.service;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.slack.client.SlackWebhookClient;
import sweetgirl.croissant.external.slack.config.SlackProperties;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

/**
 * 판정 리포트를 Slack 실패 검색어 채널로 순서대로 보낸다.
 *
 * <p>운영 알림 이벤트({@code OperationalNotificationEvent})는 비동기라 메시지가 여러 개면 순서가 뒤섞이고, 한꺼번에 몰리면 웹훅
 * 속도 제한(초당 1건 안팎)에 걸린다. 리포트는 새벽 배치 스레드에서 보내므로 직접, 차례로, 사이를 두고 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissTriageReportSender {

	private static final long DEFAULT_PAUSE_MILLIS = 1_100L;

	private final SlackWebhookClient slackWebhookClient;
	private final SlackProperties slackProperties;

	/** 메시지 사이 간격. 테스트에서만 줄인다 */
	private long pauseMillis = DEFAULT_PAUSE_MILLIS;

	public void send(List<SlackMessage> messages) {
		if (!slackProperties.isEnabled()) {
			return;
		}
		String webhookUrl = slackProperties.getSearchMissWebhookUrl();
		for (int index = 0; index < messages.size(); index++) {
			if (index > 0 && !pause()) {
				log.warn("[검색어 판정] 리포트 발송이 중단됐다 sent={}/{}", index, messages.size());
				return;
			}
			slackWebhookClient.send(webhookUrl, withTitlePrefix(messages.get(index)));
		}
	}

	private SlackMessage withTitlePrefix(SlackMessage message) {
		String prefix = slackProperties.getTitlePrefix();
		if (prefix == null || prefix.isBlank()) {
			return message;
		}
		return new SlackMessage(message.color(), prefix.strip() + " " + message.title(), message.description(),
			message.timestamp(), message.fields());
	}

	private boolean pause() {
		try {
			Thread.sleep(pauseMillis);
			return true;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
