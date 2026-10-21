package sweetgirl.croissant.external.slack.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 슬랙 Block Kit 페이로드 구성에 쓰이는 상수.
 *
 * <p>길이 제한(슬랙 하드 리밋)·렌더링 정책·Block Kit 타입 어휘·표현 리터럴을 모아,
 * 빌더의 매직 넘버/매직 스트링을 제거한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SlackBlockKitConstants {

	// 슬랙 API 하드 리밋 (초과 시 400)
	public static final int HEADER_TEXT_MAX = 150;
	public static final int SECTION_TEXT_MAX = 3000;
	public static final int FIELD_TEXT_MAX = 2000;
	public static final int FIELDS_PER_SECTION_MAX = 10;

	// 렌더링 정책 — 한 메시지에 표시할 최대 필드 수
	public static final int RENDERED_FIELDS_MAX = 25;

	// Block Kit 타입 어휘 (슬랙 스펙 고정값)
	public static final String TYPE_HEADER = "header";
	public static final String TYPE_SECTION = "section";
	public static final String TYPE_CONTEXT = "context";
	public static final String TYPE_PLAIN_TEXT = "plain_text";
	public static final String TYPE_MRKDWN = "mrkdwn";

	// 표현 리터럴
	public static final String TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
	public static final String TIMESTAMP_ZONE = "Asia/Seoul";
	public static final String CLOCK_EMOJI = "🕒";
	public static final String COLOR_FORMAT = "#%06X";
	public static final String BLANK_PLACEHOLDER = "-";
	public static final String TRUNCATION_SUFFIX = "...";
	public static final String FIELD_LABEL_FORMAT = "*%s*\n%s";
}
