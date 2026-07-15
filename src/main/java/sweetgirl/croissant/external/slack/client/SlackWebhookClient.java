package sweetgirl.croissant.external.slack.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

/**
 * 슬랙 Incoming Webhook 전송 전용 클라이언트.
 *
 * <p>대상 웹훅 URL 과 {@link SlackMessage} 를 받아 Block Kit 페이로드를 전송한다.
 * 페이로드 구성은 {@link SlackBlockKitBuilder} 가, JSON 직렬화는 RestClient(Jackson) 가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackWebhookClient {

	private final RestClient slackRestClient;

	public void send(String webhookUrl, SlackMessage message) {
		if (webhookUrl == null || webhookUrl.isBlank()) {
			return;
		}

		try {
			slackRestClient.post()
				.uri(webhookUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.body(SlackBlockKitBuilder.build(message))
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			log.warn("Slack 웹훅 전송 실패: {}", e.getMessage());
		}
	}
}
