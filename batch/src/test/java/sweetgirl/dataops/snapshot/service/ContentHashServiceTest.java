package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import tools.jackson.databind.ObjectMapper;

class ContentHashServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final ContentHashService contentHashService = new ContentHashService(new NaverPlaceParser(), objectMapper);

	private String hashOf(String apolloJson) {
		return contentHashService.canonicalHash(objectMapper.readTree(apolloJson));
	}

	private String payload(String statusJson, String hoursJson, String menusJson) {
		return payload(statusJson, hoursJson, menusJson,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.050000", "37.540000");
	}

	private String payload(String statusJson, String hoursJson, String menusJson,
			String address, String roadAddress, String lon, String lat) {
		return """
				{
				  "ROOT_QUERY": {
				    "placeDetail({id:1})": {
				      "newBusinessHours({format:restaurant})": [
				        {"businessHours": %s, "businessStatusDescription": %s}
				      ]
				    }
				  },
				  "PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "빵집", "phone": "02-1-2",
				    "address": "%s", "roadAddress": "%s", "coordinate": {"x": "%s", "y": "%s"}}
				  %s
				}""".formatted(hoursJson, statusJson, address, roadAddress, lon, lat, menusJson);
	}

	private static final String HOURS_MON =
		"[{\"day\": \"월\", \"businessHours\": {\"start\": \"09:00\", \"end\": \"20:00\"}}]";

	private static final String MENU_A = """
			, "Menu:1_0": {"__typename": "Menu", "name": "소금빵", "price": "2900", "description": null, "images": []}
			, "Menu:1_1": {"__typename": "Menu", "name": "크루아상", "price": "3400", "description": null, "images": []}""";

	@Test
	void 당일_영업상태가_달라도_해시는_같다() {
		String open = payload("{\"status\": \"영업 중\"}",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"09:00\", \"end\": \"20:00\"}}]", MENU_A);
		String closed = payload("{\"status\": \"영업 종료\", \"description\": \"09:00에 영업 시작\"}",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"09:00\", \"end\": \"20:00\"}}]", MENU_A);

		assertThat(hashOf(open)).isEqualTo(hashOf(closed));
	}

	@Test
	void 영업시간_배열_회전과_날짜_라벨에_불변이다() {
		String saturdayFirst = payload("null",
			"[{\"day\": \"토(8/1)\", \"businessHours\": {\"start\": \"10:00\", \"end\": \"20:00\"}},"
				+ "{\"day\": \"월\", \"businessHours\": {\"start\": \"09:00\", \"end\": \"20:00\"}}]",
			MENU_A);
		String mondayFirst = payload("null",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"09:00\", \"end\": \"20:00\"}},"
				+ "{\"day\": \"토\", \"businessHours\": {\"start\": \"10:00\", \"end\": \"20:00\"}}]",
			MENU_A);

		assertThat(hashOf(saturdayFirst)).isEqualTo(hashOf(mondayFirst));
	}

	@Test
	void 메뉴_키_순서_회전에_불변이다() {
		String rotated = payload("null", "[]",
			"""
					, "Menu:1_0": {"__typename": "Menu", "name": "크루아상", "price": "3400", "description": null, "images": []}
					, "Menu:1_1": {"__typename": "Menu", "name": "소금빵", "price": "2900", "description": null, "images": []}""");
		String original = payload("null", "[]", MENU_A);

		assertThat(hashOf(rotated)).isEqualTo(hashOf(original));
	}

	@Test
	void 메뉴_가격이_바뀌면_해시가_달라진다() {
		String repriced = payload("null", "[]", MENU_A.replace("2900", "3200"));
		String original = payload("null", "[]", MENU_A);

		assertThat(hashOf(repriced)).isNotEqualTo(hashOf(original));
	}

	@Test
	void 삼세대_해시는_영업시간을_보지_않는다() {
		// 영업시간은 RegularHoursTracker 가 정기 요일 기억과 따로 비교한다 — 해시에 넣으면 날짜 붙은 임시 일정이
		// 정기 값 자리에 들어가 매일 방문의 약 95%가 가짜 변경이었다 (표본 2차 61곳 중 58곳)
		String before = payload("null",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"06:00\", \"end\": \"18:00\"}}]", MENU_A);
		String after = payload("null",
			"[{\"day\": \"토(9/26)\", \"businessHours\": null, \"description\": \"휴무\"}]", MENU_A);

		assertThat(hashOf(before)).isEqualTo(hashOf(after));
	}

	@Test
	void 이세대_해시는_영업시간_변경에_민감하다_옛_세대_비교용() {
		NaverPlaceParser parser = new NaverPlaceParser();
		String before = payload("null",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"06:00\", \"end\": \"18:00\"}}]", MENU_A);
		String after = payload("null",
			"[{\"day\": \"월\", \"businessHours\": {\"start\": \"08:00\", \"end\": \"17:00\"}}]", MENU_A);

		assertThat(contentHashService.hashFor(parser.parse(objectMapper.readTree(before)), (short)2))
			.isNotEqualTo(contentHashService.hashFor(parser.parse(objectMapper.readTree(after)), (short)2));
	}

	@Test
	void 주소만_바뀌어도_해시가_달라진다() {
		// 1세대 해시는 주소를 안 봐서, 이름·전화·메뉴·영업시간이 그대로인 채 이전한 가게가 파이프라인에
		// 보이지 않았다 — 스냅샷이 저장되지 않아 재판정이 주소 제안을 만들 기회조차 없었다 (v3 P2-1)
		String before = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.050000", "37.540000");
		String moved = payload("null", HOURS_MON, MENU_A,
			"서울 광진구 구의동 9-9", "서울 광진구 구의로 20", "127.050000", "37.540000");

		assertThat(hashOf(before)).isNotEqualTo(hashOf(moved));
	}

	@Test
	void 좌표만_바뀌어도_해시가_달라진다() {
		String before = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.050000", "37.540000");
		String moved = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.070000", "37.560000");

		assertThat(hashOf(before)).isNotEqualTo(hashOf(moved));
	}

	@Test
	void 좌표_표현_차이는_해시를_흔들지_않는다() {
		// 같은 자리를 다른 정밀도로 보내는 경우 — 고정 자리수로 정규화한다
		String plain = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.05", "37.54");
		String padded = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.0500000", "37.5400000");

		assertThat(hashOf(plain)).isEqualTo(hashOf(padded));
	}

	@Test
	void 구세대_해시는_주소_변경에_불변이다() {
		// 세대 전환 직후의 재방문을 전부 "변경"으로 만들지 않기 위해 이 계산을 남겨 둔다
		NaverPlaceParser parser = new NaverPlaceParser();
		String before = payload("null", HOURS_MON, MENU_A,
			"서울 성동구 성수동 1-1", "서울 성동구 성수일로 10", "127.050000", "37.540000");
		String moved = payload("null", HOURS_MON, MENU_A,
			"서울 광진구 구의동 9-9", "서울 광진구 구의로 20", "127.070000", "37.560000");

		assertThat(contentHashService.hashFor(parser.parse(objectMapper.readTree(before)), (short)1))
			.isEqualTo(contentHashService.hashFor(parser.parse(objectMapper.readTree(moved)), (short)1));
	}
}
