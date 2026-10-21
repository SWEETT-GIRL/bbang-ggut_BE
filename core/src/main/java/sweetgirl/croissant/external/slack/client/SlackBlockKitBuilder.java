package sweetgirl.croissant.external.slack.client;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.external.slack.code.SlackBlockKitConstants;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

/**
 * {@link SlackMessage} 를 슬랙 Block Kit 페이로드(Map)로 변환한다.
 *
 * <p>블록 구조·색상 변환·길이 제한(슬랙 하드 리밋)을 이 클래스에 모아,
 * RestClient 기반 클라이언트와 logback 어펜더가 동일한 방식으로 페이로드를 구성한다.
 * (Map 을 JSON 으로 직렬화하는 일은 각 소비자의 Jackson 이 담당한다.)
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SlackBlockKitBuilder {

	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
		.ofPattern(SlackBlockKitConstants.TIMESTAMP_PATTERN)
		.withZone(ZoneId.of(SlackBlockKitConstants.TIMESTAMP_ZONE));

	public static Map<String, Object> build(SlackMessage message) {
		Map<String, Object> attachment = new LinkedHashMap<>();
		attachment.put("color", toHexColor(message.color()));
		attachment.put("blocks", toBlocks(message));

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("attachments", List.of(attachment));
		return payload;
	}

	private static List<Map<String, Object>> toBlocks(SlackMessage message) {
		List<Map<String, Object>> blocks = new ArrayList<>();
		blocks.add(headerBlock(truncate(message.title(), SlackBlockKitConstants.HEADER_TEXT_MAX)));
		blocks.add(sectionText(truncate(message.description(), SlackBlockKitConstants.SECTION_TEXT_MAX)));
		addFieldBlocks(blocks, message.fields());
		blocks.add(
			contextBlock(SlackBlockKitConstants.CLOCK_EMOJI + " " + TIMESTAMP_FORMATTER.format(message.timestamp())));
		return blocks;
	}

	private static void addFieldBlocks(List<Map<String, Object>> blocks, List<SlackMessage.Field> fields) {
		List<Map<String, Object>> inlineBuffer = new ArrayList<>();
		for (SlackMessage.Field field : fields.stream().limit(SlackBlockKitConstants.RENDERED_FIELDS_MAX).toList()) {
			String label = String.format(SlackBlockKitConstants.FIELD_LABEL_FORMAT, field.name(), field.value());
			String text = truncate(label, SlackBlockKitConstants.FIELD_TEXT_MAX);
			if (field.inline()) {
				inlineBuffer.add(mrkdwn(text));
				if (inlineBuffer.size() == SlackBlockKitConstants.FIELDS_PER_SECTION_MAX) {
					blocks.add(sectionFields(inlineBuffer));
					inlineBuffer = new ArrayList<>();
				}
			} else {
				if (!inlineBuffer.isEmpty()) {
					blocks.add(sectionFields(inlineBuffer));
					inlineBuffer = new ArrayList<>();
				}
				blocks.add(sectionText(text));
			}
		}
		if (!inlineBuffer.isEmpty()) {
			blocks.add(sectionFields(inlineBuffer));
		}
	}

	private static Map<String, Object> headerBlock(String text) {
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("type", SlackBlockKitConstants.TYPE_HEADER);
		header.put("text", plainText(text));
		return header;
	}

	private static Map<String, Object> sectionText(String text) {
		Map<String, Object> section = new LinkedHashMap<>();
		section.put("type", SlackBlockKitConstants.TYPE_SECTION);
		section.put("text", mrkdwn(text));
		return section;
	}

	private static Map<String, Object> sectionFields(List<Map<String, Object>> fields) {
		Map<String, Object> section = new LinkedHashMap<>();
		section.put("type", SlackBlockKitConstants.TYPE_SECTION);
		section.put("fields", List.copyOf(fields));
		return section;
	}

	private static Map<String, Object> contextBlock(String text) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("type", SlackBlockKitConstants.TYPE_CONTEXT);
		context.put("elements", List.of(mrkdwn(text)));
		return context;
	}

	private static Map<String, Object> plainText(String text) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("type", SlackBlockKitConstants.TYPE_PLAIN_TEXT);
		node.put("text", text);
		node.put("emoji", true);
		return node;
	}

	private static Map<String, Object> mrkdwn(String text) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("type", SlackBlockKitConstants.TYPE_MRKDWN);
		node.put("text", text);
		return node;
	}

	private static String toHexColor(int color) {
		return String.format(SlackBlockKitConstants.COLOR_FORMAT, color & 0xFFFFFF);
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || value.isBlank()) {
			return SlackBlockKitConstants.BLANK_PLACEHOLDER;
		}
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength - SlackBlockKitConstants.TRUNCATION_SUFFIX.length())
			+ SlackBlockKitConstants.TRUNCATION_SUFFIX;
	}
}
