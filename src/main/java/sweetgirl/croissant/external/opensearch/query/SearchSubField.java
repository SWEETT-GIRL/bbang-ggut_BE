package sweetgirl.croissant.external.opensearch.query;

/**
 * store-index.json이 각 필드에 정의한 멀티필드(서브필드) 접미사.
 *
 * <p>예: {@code storeName}(BASE), {@code storeName.edge}(EDGE). 매칭 프로파일이 필드×서브필드별 부스트를
 * 선언할 때 사용한다. enum 선언 순서 = 쿼리 필드 나열 순서(결정성).
 */
public enum SearchSubField {

	BASE(""),
	KEYWORD(".keyword"),
	NORI(".nori"),
	EDGE(".edge"),
	NGRAM(".ngram");

	private final String suffix;

	SearchSubField(String suffix) {
		this.suffix = suffix;
	}

	public String field(String basePath) {
		return basePath + suffix;
	}
}
