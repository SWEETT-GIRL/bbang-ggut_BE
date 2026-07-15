package sweetgirl.croissant.global.logging;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import sweetgirl.croissant.external.slack.client.SlackBlockKitBuilder;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

/**
 * ERROR 레벨 로그를 슬랙 Incoming Webhook 으로 전송하는 logback 어펜더.
 *
 * <p>logback 이 직접 인스턴스화하므로 스프링 빈(RestClient 등)을 주입받지 못해 순수 JDK 로 전송한다.
 * 페이로드 구성은 운영 알림과 동일한 {@link SlackBlockKitBuilder} 를 재사용하고, JSON 직렬화는 Jackson 이 담당한다.
 */
public class SlackWebhookAppender extends AppenderBase<ILoggingEvent> {

	private static final int ERROR_COLOR = 0xE74C3C;
	private static final String ERROR_TITLE = "🔴 ERROR";
	private static final String LOGGER_FIELD_NAME = "Logger";
	private static final int MAX_STACK_LINES = 20;
	private static final int HTTP_TIMEOUT_MS = 3000;
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private String webhookUrl;

	public void setWebhookUrl(String webhookUrl) {
		this.webhookUrl = webhookUrl;
	}

	@Override
	protected void append(ILoggingEvent event) {
		if (webhookUrl == null || webhookUrl.isBlank()) {
			return;
		}
		if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) {
			return;
		}
		try {
			String payload = OBJECT_MAPPER.writeValueAsString(SlackBlockKitBuilder.build(toMessage(event)));
			sendToSlack(payload);
		} catch (Exception e) {
			addError("Slack 웹훅 전송 실패", e);
		}
	}

	SlackMessage toMessage(ILoggingEvent event) {
		String message = event.getFormattedMessage();
		String stackTrace = formatStackTrace(event.getThrowableProxy());

		String description = "*" + message + "*";
		if (stackTrace != null && !stackTrace.isBlank()) {
			description += "\n```\n" + stackTrace + "\n```";
		}

		List<SlackMessage.Field> fields = List.of(
			new SlackMessage.Field(LOGGER_FIELD_NAME, simpleClassName(event.getLoggerName()), true));

		return new SlackMessage(
			ERROR_COLOR,
			ERROR_TITLE,
			description,
			Instant.ofEpochMilli(event.getTimeStamp()),
			fields);
	}

	private String simpleClassName(String loggerName) {
		int lastDot = loggerName.lastIndexOf('.');
		return lastDot >= 0 ? loggerName.substring(lastDot + 1) : loggerName;
	}

	private String formatStackTrace(IThrowableProxy proxy) {
		if (proxy == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append("\n");

		StackTraceElementProxy[] elements = proxy.getStackTraceElementProxyArray();
		int limit = Math.min(elements.length, MAX_STACK_LINES);
		for (int i = 0; i < limit; i++) {
			builder.append("\t").append(elements[i].getSTEAsString()).append("\n");
		}
		if (elements.length > limit) {
			builder.append("\t... ").append(elements.length - limit).append(" more");
		}
		return builder.toString();
	}

	private void sendToSlack(String payload) throws Exception {
		URL url = new URL(webhookUrl);
		HttpURLConnection connection = (HttpURLConnection)url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setDoOutput(true);
		connection.setConnectTimeout(HTTP_TIMEOUT_MS);
		connection.setReadTimeout(HTTP_TIMEOUT_MS);

		try (OutputStream outputStream = connection.getOutputStream()) {
			outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
		}

		int status = connection.getResponseCode();
		if (status < 200 || status >= 300) {
			addError("Slack 웹훅 응답 오류: HTTP " + status);
		}
		connection.disconnect();
	}
}
