package sweetgirl.croissant.admin.bulkimport.enums;

public enum BulkSearchKeyword {
	// trusted = true: Kakao 검색 결과를 신뢰해서 categoryName 화이트리스트 우회. "음식점 > 양식"
	//                 처럼 subcategory 가 명확하지 않은(generic) 양식 카테고리까지 통과.
	//                 단, "양식 > 피자/파스타/스테이크/햄버거" 등 명시적 sub 카테고리는 여전히 제외.
	브런치(true),
	샌드위치(true),
	빵집(true),
	베이커리(true),
	소금빵(true),
	에그타르트(true),
	휘낭시에(true),
	디저트(false),
	도넛(false),
	케이크(false);

	private final boolean trusted;

	BulkSearchKeyword(boolean trusted) {
		this.trusted = trusted;
	}

	public boolean isTrusted() {
		return trusted;
	}
}
