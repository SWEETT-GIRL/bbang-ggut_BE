package sweetgirl.croissant.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

class SlackWebhookAppenderTest {

	private final SlackWebhookAppender appender = new SlackWebhookAppender();

	@Test
	@DisplayName("toMessage: 스택트레이스가 없으면 제목·색상·Logger 필드를 매핑한다")
	void toMessage_mapsBasicFields_withoutStackTrace() {
		ILoggingEvent event = mock(ILoggingEvent.class);
		given(event.getFormattedMessage()).willReturn("에러 발생");
		given(event.getLoggerName()).willReturn("sweetgirl.croissant.store.StoreService");
		given(event.getTimeStamp()).willReturn(Instant.parse("2026-07-15T05:03:11Z").toEpochMilli());
		given(event.getThrowableProxy()).willReturn(null);

		SlackMessage message = appender.toMessage(event);

		assertThat(message.title()).isEqualTo("🔴 ERROR");
		assertThat(message.color()).isEqualTo(0xE74C3C);
		assertThat(message.description()).isEqualTo("*에러 발생*");
		assertThat(message.fields()).hasSize(1);
		assertThat(message.fields().get(0).name()).isEqualTo("Logger");
		assertThat(message.fields().get(0).value()).isEqualTo("StoreService");
	}

	@Test
	@DisplayName("toMessage: 스택트레이스가 있으면 설명에 코드블록으로 포함한다")
	void toMessage_includesStackTrace_whenThrowablePresent() {
		ILoggingEvent event = mock(ILoggingEvent.class);
		given(event.getFormattedMessage()).willReturn("NPE 발생");
		given(event.getLoggerName()).willReturn("X");
		given(event.getTimeStamp()).willReturn(0L);
		given(event.getThrowableProxy()).willReturn(new ThrowableProxy(new RuntimeException("boom")));

		SlackMessage message = appender.toMessage(event);

		assertThat(message.description())
			.contains("```")
			.contains("RuntimeException")
			.contains("boom");
	}
}
