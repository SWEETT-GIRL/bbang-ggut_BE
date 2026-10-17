package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.slack.client.SlackWebhookClient;
import sweetgirl.croissant.external.slack.config.SlackProperties;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

@ExtendWith(MockitoExtension.class)
class SearchMissTriageReportSenderTest {

	private static final String WEBHOOK = "https://hooks.slack.test/search-miss";

	@Mock
	private SlackWebhookClient slackWebhookClient;

	private SlackProperties slackProperties;
	private SearchMissTriageReportSender sender;

	@BeforeEach
	void setUp() {
		slackProperties = new SlackProperties();
		slackProperties.setEnabled(true);
		slackProperties.setSearchMissWebhookUrl(WEBHOOK);
		sender = new SearchMissTriageReportSender(slackWebhookClient, slackProperties);
		ReflectionTestUtils.setField(sender, "pauseMillis", 0L);
	}

	private static SlackMessage message(String title) {
		return new SlackMessage(1, title, "설명", Instant.EPOCH, List.of());
	}

	@Test
	@DisplayName("여러 메시지를 실패 검색어 채널로 순서대로 보낸다")
	void send_inOrder() {
		sender.send(List.of(message("1/2"), message("2/2")));

		ArgumentCaptor<SlackMessage> captor = ArgumentCaptor.forClass(SlackMessage.class);
		InOrder order = inOrder(slackWebhookClient);
		order.verify(slackWebhookClient, times(2)).send(eq(WEBHOOK), captor.capture());
		assertThat(captor.getAllValues()).extracting(SlackMessage::title).containsExactly("1/2", "2/2");
	}

	@Test
	@DisplayName("환경 표시가 있으면 제목 앞에 붙인다 — dev 와 prod 가 같은 웹훅을 써도 가른다")
	void send_withTitlePrefix() {
		slackProperties.setTitlePrefix("[DEV]");

		sender.send(List.of(message("🥯 [검색어 판정] 완료")));

		ArgumentCaptor<SlackMessage> captor = ArgumentCaptor.forClass(SlackMessage.class);
		verify(slackWebhookClient).send(eq(WEBHOOK), captor.capture());
		assertThat(captor.getValue().title()).isEqualTo("[DEV] 🥯 [검색어 판정] 완료");
	}

	@Test
	@DisplayName("Slack 알림이 꺼져 있으면 보내지 않는다")
	void send_disabled() {
		slackProperties.setEnabled(false);

		sender.send(List.of(message("완료")));

		verify(slackWebhookClient, never()).send(any(), any());
	}
}
