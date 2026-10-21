package sweetgirl.croissant.store;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 주소 문자열 → [시·도, 시·군·구, 읍·면·동].
 *
 * <p>시·도는 짧은 이름으로 접는다 — 검색이 `store.region_1st` 를 그대로 비교하는데, 같은 서울이
 * "서울"과 "서울특별시" 두 갈래로 저장되면 한쪽이 통째로 안 잡힌다(2026-09-10 실측: 17곳).
 *
 * <p>셋째 칸은 <b>읍·면·동일 때만</b> 채운다. 도로명 주소를 공백으로 잘라 세 번째를 그대로 쓰면
 * "아차산로9길" 같은 도로명이 동 자리에 앉는다. 그 상태로는 "성수" 같은 지역 검색에 걸리지 않는다.
 * 도로명뿐인 주소는 동을 비워 두고, 좌표로 채우는 건 {@code scripts/normalize_store_regions.py} 몫이다.
 */
public final class AddressParser {

	/** 행정구역 접미사가 붙은 것만 동으로 인정한다 — "아차산로9길"·"강남대로18길" 같은 도로명을 거른다 */
	private static final Pattern DONG = Pattern.compile(".*(읍|면|동|가|리)$");
	private static final Pattern ROAD = Pattern.compile(".*(로|길)[0-9]*(번길|가길)?$");

	/** 긴 이름 → 검색·표시에 쓰는 짧은 이름. store 에 이미 쌓인 값이 짧은 쪽이다 */
	private static final Map<String, String> SHORT_SIDO = Map.ofEntries(
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
		Map.entry("전라북도", "전북"),
		Map.entry("전북특별자치도", "전북"),
		Map.entry("전라남도", "전남"),
		Map.entry("경상북도", "경북"),
		Map.entry("경상남도", "경남"),
		Map.entry("제주특별자치도", "제주"));

	private AddressParser() {

	}

	public static String[] parse(String addressName) {
		if (addressName == null || addressName.isBlank()) {
			return new String[] {null, null, null};
		}
		List<String> parts = List.of(addressName.trim().split("\\s+"));
		String sido = parts.isEmpty() ? null : shortSido(parts.get(0));
		String sigungu = parts.size() > 1 ? parts.get(1) : null;
		return new String[] {sido, sigungu, dongOrNull(parts)};
	}

	private static String shortSido(String sido) {
		return SHORT_SIDO.getOrDefault(sido, sido);
	}

	/** 셋째 칸이 동이면 그것, 도로명이면 null. 지번 주소만 동을 얻는다 */
	private static String dongOrNull(List<String> parts) {
		if (parts.size() <= 2) {
			return null;
		}
		String third = parts.get(2);
		if (ROAD.matcher(third).matches()) {
			return null;
		}
		return DONG.matcher(third).matches() ? third : null;
	}
}
