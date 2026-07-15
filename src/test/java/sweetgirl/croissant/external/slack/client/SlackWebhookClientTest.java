package sweetgirl.croissant.external.slack.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import sweetgirl.croissant.external.slack.dto.SlackMessage;

class SlackWebhookClientTest {

	private static final String URL = "https://hooks.slack.com/services/T/B/xxx";
	private static final SlackMessage MESSAGE = new SlackMessage(0x57F287, "제목", "설명",
		Instant.parse("2026-07-15T05:03:11Z"), List.of());

	private MockRestServiceServer server;
	private SlackWebhookClient slackWebhookClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		slackWebhookClient = new SlackWebhookClient(builder.build());
	}

	@Test
	@DisplayName("send: 정상 URL이면 웹훅으로 POST 한다")
	void send_postsToWebhook() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess());

		slackWebhookClient.send(URL, MESSAGE);

		server.verify();
	}

	@Test
	@DisplayName("send: URL이 비어 있으면 전송하지 않는다")
	void send_skips_whenUrlBlank() {
		slackWebhookClient.send("   ", MESSAGE);
		slackWebhookClient.send(null, MESSAGE);

		server.verify();
	}

	@Test
	@DisplayName("send: 전송이 실패해도 예외를 던지지 않는다")
	void send_swallowsError() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatCode(() -> slackWebhookClient.send(URL, MESSAGE)).doesNotThrowAnyException();

		server.verify();
	}
}
