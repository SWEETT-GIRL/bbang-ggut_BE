package sweetgirl.croissant.external.opensearch.query;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 한 물리 필드에 대한 서브필드별 부스트 선언.
 *
 * <p>{@code nestedPath}가 있으면 nested 필드(searchRegions/menus), null이면 flat 필드다.
 * {@link #boostedFields()}가 {@code "searchRegions.name^4"} 형태의 multi_match 필드 문자열을 생성한다.
 * 서브필드 나열 순서는 {@link SearchSubField} 선언 순서를 따른다(결정성).
 */
public record FieldBoost(String path, String nestedPath, Map<SearchSubField, Float> boosts) {

	public static FieldBoost flat(String path, Map<SearchSubField, Float> boosts) {
		return new FieldBoost(path, null, boosts);
	}

	public static FieldBoost nested(String nestedPath, String path, Map<SearchSubField, Float> boosts) {
		return new FieldBoost(path, nestedPath, boosts);
	}

	public boolean isNested() {
		return nestedPath != null;
	}

	public List<String> boostedFields() {
		return Arrays.stream(SearchSubField.values())
			.filter(boosts::containsKey)
			.map(subField -> subField.field(path) + boostSuffix(boosts.get(subField)))
			.toList();
	}

	private static String boostSuffix(float boost) {
		if (boost == 1.0f) {
			return "";
		}
		if (boost == Math.floor(boost)) {
			return "^" + (int)boost;
		}
		return "^" + boost;
	}
}
