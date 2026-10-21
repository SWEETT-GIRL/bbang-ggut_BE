package sweetgirl.croissant.external.slack.dto;

import java.time.Instant;
import java.util.List;

/**
 * 슬랙 Incoming Webhook 으로 전송할 리치 메시지 모델.
 *
 * <p>notification 등 도메인 계층은 이 DTO 로 변환해 {@code SlackWebhookClient} 에 전달하며,
 * 실제 Block Kit 페이로드 구성은 클라이언트가 담당한다.
 */
public record SlackMessage(
		int color,
		String title,
		String description,
		Instant timestamp,
		List<Field> fields) {

	public SlackMessage {
		fields = fields == null ? List.of() : List.copyOf(fields);
	}

	public record Field(String name, String value, boolean inline) {
	}
}
