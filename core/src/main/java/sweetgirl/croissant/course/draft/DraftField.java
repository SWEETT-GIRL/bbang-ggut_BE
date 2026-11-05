package sweetgirl.croissant.course.draft;

/** 초안 한 칸 — (정류장의 가게, 회원). Redis Hash 의 필드 이름 {@code {storeId}:{memberId}} 와 1:1. */
public record DraftField(long storeId, long memberId) {

	public String key() {
		return storeId + ":" + memberId;
	}

	public static DraftField parse(String key) {
		int separator = key.indexOf(':');
		if (separator <= 0 || separator == key.length() - 1) {
			throw new IllegalArgumentException("초안 필드 이름이 아니다: " + key);
		}
		return new DraftField(Long.parseLong(key.substring(0, separator)),
			Long.parseLong(key.substring(separator + 1)));
	}
}
