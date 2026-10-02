package sweetgirl.croissant.store;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 시/도·구·동 지역 표기를 사용자 노출용 문자열로 조합한다.
 * 시/도 풀네임("대전광역시")은 짧은 표기("대전")로 축약한다.
 */
public final class RegionFormatter {

	private static final Map<String, String> SIDO_SHORT = Map.ofEntries(
		Map.entry("서울특별시", "서울"),
		Map.entry("부산광역시", "부산"),
		Map.entry("대구광역시", "대구"),
		Map.entry("인천광역시", "인천"),
		Map.entry("광주광역시", "광주"),
		Map.entry("대전광역시", "대전"),
		Map.entry("울산광역시", "울산"),
		Map.entry("세종특별자치시", "세종"),
		Map.entry("경기도", "경기"),
		Map.entry("강원특별자치도", "강원"),
		Map.entry("강원도", "강원"),
		Map.entry("충청북도", "충북"),
		Map.entry("충청남도", "충남"),
		Map.entry("전북특별자치도", "전북"),
		Map.entry("전라북도", "전북"),
		Map.entry("전라남도", "전남"),
		Map.entry("경상북도", "경북"),
		Map.entry("경상남도", "경남"),
		Map.entry("제주특별자치도", "제주"));

	private RegionFormatter() {

	}

	/** 시/도 풀네임을 짧은 표기로 변환. 매핑에 없으면 원본을 그대로 반환. */
	public static String shortSido(String region1st) {
		if (region1st == null || region1st.isBlank()) {
			return null;
		}
		return SIDO_SHORT.getOrDefault(region1st, region1st);
	}

	/** 짧은 시/도 표기("서울", "경기")가 맞는지 — 랭킹 "내 지역"이 받는 값을 거른다. */
	public static boolean isShortSido(String sido) {
		return sido != null && SIDO_SHORT.containsValue(sido);
	}

	/** "대전 중구 은행동" — 시(축약)·구·동을 공백으로 연결. null/공백 조각은 건너뛴다. */
	public static String full(String region1st, String region2nd, String region3rd) {
		return join(shortSido(region1st), region2nd, region3rd);
	}

	/** "대전 중구" — 시(축약)·구를 공백으로 연결. */
	public static String cityDistrict(String region1st, String region2nd) {
		return join(shortSido(region1st), region2nd);
	}

	private static String join(String... parts) {
		String joined = Stream.of(parts)
			.filter(part -> part != null && !part.isBlank())
			.collect(Collectors.joining(" "));
		return joined.isBlank() ? null : joined;
	}
}
