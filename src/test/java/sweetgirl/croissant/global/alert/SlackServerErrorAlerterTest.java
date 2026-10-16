package sweetgirl.croissant.global.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import sweetgirl.croissant.external.slack.client.SlackWebhookClient;
import sweetgirl.croissant.external.slack.config.SlackProperties;
import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.logging.filter.RequestIdFilter;

class SlackServerErrorAlerterTest {

	private static final String WEBHOOK = "https://hooks.slack.test/error";

	private final SlackWebhookClient slackWebhookClient = mock(SlackWebhookClient.class);
	private final SlackProperties slackProperties = new SlackProperties();

	@AfterEach
	void tearDown() {
		MDC.clear();
		SecurityContextHolder.clearContext();
	}

	private SlackServerErrorAlerter alerter(String webhookUrl) {
		// 동기 실행기 — 보낸 내용을 바로 확인한다
		return new SlackServerErrorAlerter(slackWebhookClient, slackProperties, Runnable::run, webhookUrl,
			Duration.ofMinutes(5));
	}

	@Test
	@DisplayName("메서드·경로·상태·예외·요청 id·회원 id 를 담아 장애 채널로 보낸다")
	void alert_sendsCompactMessage() {
		MDC.put(RequestIdFilter.MDC_REQUEST_ID, "req-1");
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
		slackProperties.setTitlePrefix("[PROD]");
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/stores/3/subscribe");

		boolean handled = alerter(WEBHOOK).alert(request, 500, new IllegalStateException("커넥션 없음"));

		ArgumentCaptor<SlackMessage> captor = ArgumentCaptor.forClass(SlackMessage.class);
		verify(slackWebhookClient).send(eq(WEBHOOK), captor.capture());
		SlackMessage message = captor.getValue();
		assertThat(handled).isTrue();
		assertThat(message.title()).isEqualTo("[PROD] 🔴 500 POST /stores/3/subscribe");
		assertThat(message.description()).isEqualTo("*IllegalStateException*: 커넥션 없음");
		assertThat(message.fields()).extracting(SlackMessage.Field::value).containsExactly("req-1", "7");
	}

	@Test
	@DisplayName("같은 오류는 쿨다운 동안 한 번만 보내고, 나머지는 맡았다고만 답한다")
	void alert_suppressesSameSignatureWithinCooldown() {
		SlackServerErrorAlerter alerter = alerter(WEBHOOK);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/stores/1");

		for (int i = 0; i < 50; i++) {
			assertThat(alerter.alert(request, 500, sameOrigin())).isTrue();
		}
		alerter.alert(request, 500, new IllegalArgumentException("다른 오류"));

		verify(slackWebhookClient, times(2)).send(eq(WEBHOOK), any());
	}

	@Test
	@DisplayName("웹훅 주소가 없으면 맡지 않는다 — 기존 로그 어펜더가 보내게 둔다")
	void alert_returnsFalseWhenWebhookMissing() {
		boolean handled = alerter("").alert(new MockHttpServletRequest("GET", "/x"), 500, new RuntimeException());

		assertThat(handled).isFalse();
		verify(slackWebhookClient, never()).send(any(), any());
	}

	@Test
	@DisplayName("서명: 일반 예외는 클래스+터진 자리, CustomException 은 클래스+에러 코드")
	void signature_distinguishesOriginAndErrorCode() {
		assertThat(SlackServerErrorAlerter.signature(sameOrigin()))
			.isEqualTo(SlackServerErrorAlerter.signature(sameOrigin()))
			.startsWith(IllegalStateException.class.getName() + "@");
		assertThat(SlackServerErrorAlerter.signature(
			new CustomException(GeneralErrorCode._INTERNAL_SERVER_ERROR, "값마다 다른 메시지 1")))
			.isEqualTo(SlackServerErrorAlerter.signature(
				new CustomException(GeneralErrorCode._INTERNAL_SERVER_ERROR, "값마다 다른 메시지 2")))
			.endsWith("#COMMON500");
	}

	private static IllegalStateException sameOrigin() {
		return new IllegalStateException("id=" + System.nanoTime());
	}
}
