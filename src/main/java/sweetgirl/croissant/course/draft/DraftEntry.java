package sweetgirl.croissant.course.draft;

/**
 * Redis 에 들어 있는 초안 하나. Hash 값은 {@code seq:updatedAt:content} — 앞 둘은 숫자라 content 에 ':' 가 있어도 갈라진다.
 * 빈 content 는 "지우기 예약"이다.
 */
public record DraftEntry(DraftField field, String content, long seq, long updatedAt) {

	public String encode() {
		return seq + ":" + updatedAt + ":" + content;
	}

	public static DraftEntry decode(String fieldKey, String value) {
		int first = value.indexOf(':');
		int second = value.indexOf(':', first + 1);
		if (first <= 0 || second < 0) {
			throw new IllegalArgumentException("초안 값 형식이 아니다: " + value);
		}
		return new DraftEntry(
			DraftField.parse(fieldKey),
			value.substring(second + 1),
			Long.parseLong(value.substring(0, first)),
			Long.parseLong(value.substring(first + 1, second)));
	}

	public boolean isDeletion() {
		return content.isBlank();
	}
}
