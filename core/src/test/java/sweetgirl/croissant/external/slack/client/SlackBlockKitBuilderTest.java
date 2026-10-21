package sweetgirl.croissant.external.slack.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.external.slack.code.SlackBlockKitConstants;
import sweetgirl.croissant.external.slack.dto.SlackMessage;

class SlackBlockKitBuilderTest {

	private static final Instant FIXED = Instant.parse("2026-07-15T05:03:11Z");

	@Test
	@DisplayName("build: color를 헥사로 변환하고 header/section/context 기본 블록을 만든다")
	void build_convertsColorAndBuildsBaseBlocks() {
		SlackMessage message = new SlackMessage(0x57F287, "제목", "설명", FIXED, List.of());

		Map<String, Object> payload = SlackBlockKitBuilder.build(message);

		assertThat(colorOf(payload)).isEqualTo("#57F287");
		List<Map<String, Object>> blocks = blocksOf(payload);
		assertThat(blocks).hasSize(3);
		assertThat(blocks.get(0)).containsEntry("type", "header");
		assertThat(textOf(blocks.get(0))).isEqualTo("제목");
		assertThat(blocks.get(1)).containsEntry("type", "section");
		assertThat(textOf(blocks.get(1))).isEqualTo("설명");
		assertThat(blocks.get(2)).containsEntry("type", "context");
		assertThat(contextTextOf(blocks.get(2))).isEqualTo("🕒 2026-07-15 14:03:11");
	}

	@Test
	@DisplayName("build: inline 필드는 한 section.fields로 묶고 non-inline은 개별 section으로 만든다")
	void build_groupsInlineFields_andNonInlineAsOwnSection() {
		SlackMessage message = new SlackMessage(0, "t", "d", FIXED, List.of(
			new SlackMessage.Field("A", "1", true),
			new SlackMessage.Field("B", "2", true),
			new SlackMessage.Field("C", "3", false)));

		List<Map<String, Object>> blocks = blocksOf(SlackBlockKitBuilder.build(message));

		// header, section(desc), section(fields: A,B), section(C), context
		assertThat(blocks).hasSize(5);
		Map<String, Object> inlineSection = blocks.get(2);
		assertThat(inlineSection).containsEntry("type", "section");
		List<Map<String, Object>> fields = fieldsOf(inlineSection);
		assertThat(fields).hasSize(2);
		assertThat(fields.get(0)).containsEntry("text", "*A*\n1");
		assertThat(fields.get(1)).containsEntry("text", "*B*\n2");
		assertThat(blocks.get(3)).containsEntry("type", "section");
		assertThat(textOf(blocks.get(3))).isEqualTo("*C*\n3");
	}

	@Test
	@DisplayName("build: 제목이 헤더 제한을 넘으면 잘라내고 말줄임표를 붙인다")
	void build_truncatesTitleToHeaderLimit() {
		SlackMessage message = new SlackMessage(0, "가".repeat(200), "d", FIXED, List.of());

		String headerText = textOf(blocksOf(SlackBlockKitBuilder.build(message)).get(0));

		assertThat(headerText).hasSize(SlackBlockKitConstants.HEADER_TEXT_MAX);
		assertThat(headerText).endsWith(SlackBlockKitConstants.TRUNCATION_SUFFIX);
	}

	@Test
	@DisplayName("build: 설명이 비어 있으면 '-' 로 대체한다")
	void build_usesPlaceholderForBlankDescription() {
		SlackMessage message = new SlackMessage(0, "t", "   ", FIXED, List.of());

		String sectionText = textOf(blocksOf(SlackBlockKitBuilder.build(message)).get(1));

		assertThat(sectionText).isEqualTo(SlackBlockKitConstants.BLANK_PLACEHOLDER);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> blocksOf(Map<String, Object> payload) {
		List<Map<String, Object>> attachments = (List<Map<String, Object>>)payload.get("attachments");
		return (List<Map<String, Object>>)attachments.get(0).get("blocks");
	}

	@SuppressWarnings("unchecked")
	private static String colorOf(Map<String, Object> payload) {
		List<Map<String, Object>> attachments = (List<Map<String, Object>>)payload.get("attachments");
		return (String)attachments.get(0).get("color");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> fieldsOf(Map<String, Object> block) {
		return (List<Map<String, Object>>)block.get("fields");
	}

	@SuppressWarnings("unchecked")
	private static String textOf(Map<String, Object> block) {
		return (String)((Map<String, Object>)block.get("text")).get("text");
	}

	@SuppressWarnings("unchecked")
	private static String contextTextOf(Map<String, Object> block) {
		List<Map<String, Object>> elements = (List<Map<String, Object>>)block.get("elements");
		return (String)elements.get(0).get("text");
	}
}
