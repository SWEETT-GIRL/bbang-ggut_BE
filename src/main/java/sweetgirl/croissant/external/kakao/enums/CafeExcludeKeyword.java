package sweetgirl.croissant.external.kakao.enums;

public enum CafeExcludeKeyword {
	가정생활("가정,생활");

	private final String keyword;

	CafeExcludeKeyword(String keyword) {
		this.keyword = keyword;
	}

	public String keyword() {
		return keyword;
	}
}
