package sweetgirl.croissant.global.logging;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;

public class DiscordWebhookAppender extends AppenderBase<ILoggingEvent> {

	private static final int MAX_DESCRIPTION_LENGTH = 2000;
	private static final int MAX_STACK_LINES = 20;
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneId.of("Asia/Seoul"));

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
			sendToDiscord(buildPayload(event));
		} catch (Exception e) {
			addError("Discord 웹훅 전송 실패", e);
		}
	}

	private String buildPayload(ILoggingEvent event) {
		String timestamp = FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp()));
		String loggerName = simpleClassName(event.getLoggerName());
		String message = event.getFormattedMessage();
		String stackTrace = formatStackTrace(event.getThrowableProxy());

		String description = "**" + message + "**";
		if (stackTrace != null && !stackTrace.isBlank()) {
			description += "\n```\n" + stackTrace + "\n```";
		}
		if (description.length() > MAX_DESCRIPTION_LENGTH) {
			description = description.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
		}

		return "{\"embeds\":[{"
			+ "\"title\":\"\\uD83D\\uDD34 ERROR\","
			+ "\"description\":\"" + escapeJson(description) + "\","
			+ "\"color\":15158332,"
			+ "\"fields\":["
			+ "{\"name\":\"Logger\",\"value\":\"" + escapeJson(loggerName) + "\",\"inline\":true},"
			+ "{\"name\":\"Time\",\"value\":\"" + timestamp + "\",\"inline\":true}"
			+ "]}]}";
	}

	private String simpleClassName(String loggerName) {
		int lastDot = loggerName.lastIndexOf('.');
		return lastDot >= 0 ? loggerName.substring(lastDot + 1) : loggerName;
	}

	private String formatStackTrace(IThrowableProxy proxy) {
		if (proxy == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append("\n");

		StackTraceElementProxy[] elements = proxy.getStackTraceElementProxyArray();
		int limit = Math.min(elements.length, MAX_STACK_LINES);
		for (int i = 0; i < limit; i++) {
			sb.append("\t").append(elements[i].getSTEAsString()).append("\n");
		}
		if (elements.length > limit) {
			sb.append("\t... ").append(elements.length - limit).append(" more");
		}
		return sb.toString();
	}

	private String escapeJson(String value) {
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}

	private void sendToDiscord(String payload) throws Exception {
		URL url = new URL(webhookUrl);
		HttpURLConnection conn = (HttpURLConnection)url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);
		conn.setConnectTimeout(3000);
		conn.setReadTimeout(3000);

		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload.getBytes(StandardCharsets.UTF_8));
		}

		int status = conn.getResponseCode();
		if (status < 200 || status >= 300) {
			addError("Discord 웹훅 응답 오류: HTTP " + status);
		}
		conn.disconnect();
	}
}
