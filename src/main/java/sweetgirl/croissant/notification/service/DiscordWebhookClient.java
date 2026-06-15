package sweetgirl.croissant.notification.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.notification.config.DiscordNotificationProperties;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;

@Slf4j
@Component
public class DiscordWebhookClient {

	private static final int MAX_TITLE_LENGTH = 256;
	private static final int MAX_DESCRIPTION_LENGTH = 4096;
	private static final int MAX_FIELD_NAME_LENGTH = 256;
	private static final int MAX_FIELD_VALUE_LENGTH = 1024;
	private static final int MAX_FIELD_COUNT = 25;

	private final DiscordNotificationProperties properties;
	private final RestClient restClient;

	public DiscordWebhookClient(DiscordNotificationProperties properties) {
		this.properties = properties;
		this.restClient = RestClient.builder().build();
	}

	public void send(OperationalNotificationEvent event) {
		String webhookUrl = webhookUrl(event.channel());
		if (!properties.isEnabled() || webhookUrl == null || webhookUrl.isBlank()) {
			return;
		}

		try {
			restClient.post()
				.uri(webhookUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.body(toPayload(event))
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			log.warn("Discord 운영 알림 전송 실패(channel={}): {}", event.channel(), e.getMessage());
		}
	}

	private String webhookUrl(Channel channel) {
		return switch (channel) {
			case REPORT -> properties.getReportWebhookUrl();
			case ABUSE -> properties.getAbuseWebhookUrl();
		};
	}

	private Map<String, Object> toPayload(OperationalNotificationEvent event) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", properties.getUsername());
		payload.put("embeds", List.of(toEmbed(event)));
		return payload;
	}

	private Map<String, Object> toEmbed(OperationalNotificationEvent event) {
		Map<String, Object> embed = new LinkedHashMap<>();
		embed.put("title", truncate(event.title(), MAX_TITLE_LENGTH));
		embed.put("description", truncate(event.description(), MAX_DESCRIPTION_LENGTH));
		embed.put("color", event.color());
		embed.put("timestamp", event.timestamp().toString());
		embed.put("fields", toFields(event.fields()));
		return embed;
	}

	private List<Map<String, Object>> toFields(List<OperationalNotificationEvent.Field> fields) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (OperationalNotificationEvent.Field field : fields.stream().limit(MAX_FIELD_COUNT).toList()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", truncate(field.name(), MAX_FIELD_NAME_LENGTH));
			item.put("value", truncate(field.value(), MAX_FIELD_VALUE_LENGTH));
			item.put("inline", field.inline());
			result.add(item);
		}
		return result;
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength - 3) + "...";
	}
}
