package sweetgirl.dataops.snapshot.parser;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NaverPlaceParserTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final NaverPlaceParser naverPlaceParser = new NaverPlaceParser();

	private JsonNode apolloFixture() {
		// 실제 __APOLLO_STATE__ 구조 축소판 (표본 1,000 실측 기준) — 영업시간·영업상태는
		// ROOT_QUERY.placeDetail(인자).newBusinessHours(인자)[0] 아래에 살고, 참조({"__ref"})가 섞인다
		return objectMapper.readTree("""
				{
				  "ROOT_QUERY": {
				    "__typename": "Query",
				    "placeDetail({id:38427733})": {
				      "newBusinessHours({format:restaurant})": [
				        {
				          "businessHours": [{"day": "월", "businessHours": {"start": "09:30", "end": "20:00"}}],
				          "businessStatusDescription": {"__ref": "BusinessStatus:38427733"}
				        }
				      ]
				    }
				  },
				  "PlaceDetailBase:38427733": {
				    "__typename": "PlaceDetailBase",
				    "id": "38427733",
				    "name": "밀림 빵공장",
				    "phone": "02-123-4567",
				    "openingHours": null,
				    "homepages": {"etc": [{"url": "https://www.instagram.com/millim.flourforest"}]}
				  },
				  "BusinessStatus:38427733": {"__typename": "BusinessStatus", "status": "영업 중"},
				  "Menu:38427733_0": {
				    "__typename": "Menu",
				    "name": "소금빵",
				    "price": "3500",
				    "description": "매일 아침 구움",
				    "images": ["https://ldb-phinf.pstatic.net/20260505_257/salt.jpg"]
				  },
				  "Menu:38427733_1": {
				    "__typename": "Menu",
				    "name": "생과일 파르페",
				    "price": "12000",
				    "description": null,
				    "images": []
				  },
				  "PlaceDetail_BaeminMenu:1105447685": {
				    "__typename": "PlaceDetail_BaeminMenu",
				    "name": "[인기폭발]아메리카노",
				    "price": "1900",
				    "desc": "산미가 없고 고소하며 묵직한 원두",
				    "images": ["http://imagefarm.baemin.com/a.jpg"]
				  },
				  "PlaceDetail_BaeminMenu:1105447686": {
				    "__typename": "PlaceDetail_BaeminMenu",
				    "name": "소금빵 세트",
				    "price": "8900",
				    "desc": null,
				    "images": []
				  },
				  "PlaceDetail_BaeminMenuGroup:g0": {
				    "__typename": "PlaceDetail_BaeminMenuGroup",
				    "name": "대표메뉴",
				    "menus": [
				      {"__ref": "PlaceDetail_BaeminMenu:1105447685"},
				      {"__ref": "PlaceDetail_BaeminMenu:1105447686"}
				    ]
				  },
				  "PlaceDetail_BaeminMenuGroup:g1": {
				    "__typename": "PlaceDetail_BaeminMenuGroup",
				    "name": "음료",
				    "menus": [{"__ref": "PlaceDetail_BaeminMenu:1105447685"}]
				  }
				}""");
	}

	@Test
	void parse_개편된_PlaceMenuItem_메뉴를_읽는다() throws Exception {
		// 2026-09-20 실측 (96504) — 메뉴가 Menu 대신 PlaceMenuItem 으로 오고, 가격은 표시 문자열 객체, 이미지는 url 객체 배열이다
		JsonNode renewed = objectMapper.readTree("""
				{"ROOT_QUERY": {"__typename": "Query",
				  "placeDetail({\\"input\\":{\\"id\\":\\"1294146984\\"}})": {"placeMenus": {"items": [{"__ref": "PlaceMenuItem:a"}]}}},
				 "PlaceDetailBase:1294146984": {"__typename": "PlaceDetailBase", "id": "1294146984", "name": "베이글 가게"},
				 "PlaceMenuItem:a": {"__typename": "PlaceMenuItem", "id": "a", "name": "어니언크림치즈", "type": "normal",
				   "price": {"__typename": "PlaceMenuPrice", "priceType": "fixed", "displayText": "5,300원"},
				   "images": [{"__typename": "PlaceMenuImage", "url": "https://ldb-phinf.pstatic.net/onion.jpg"}],
				   "description": "베이글 속에 상큼한 양파크림치즈가 들어간 빵"},
				 "PlaceMenuItem:b": {"__typename": "PlaceMenuItem", "id": "b", "name": "시즌 케이크", "type": "normal",
				   "price": {"__typename": "PlaceMenuPrice", "priceType": "variable", "displayText": "변동"},
				   "images": [], "description": null}}
				""");

		ParsedPlace parsedPlace = naverPlaceParser.parse(renewed);

		assertThat(parsedPlace.menus()).extracting(ParsedMenu::name).containsExactlyInAnyOrder("어니언크림치즈", "시즌 케이크");
		ParsedMenu onion = parsedPlace.menus().stream().filter(m -> m.name().equals("어니언크림치즈")).findFirst().orElseThrow();
		assertThat(onion.price()).isEqualTo("5300");
		assertThat(onion.imageUrls()).containsExactly("https://ldb-phinf.pstatic.net/onion.jpg");
		assertThat(onion.description()).isEqualTo("베이글 속에 상큼한 양파크림치즈가 들어간 빵");
		ParsedMenu season = parsedPlace.menus().stream().filter(m -> m.name().equals("시즌 케이크")).findFirst().orElseThrow();
		assertThat(season.price()).isNull();
	}

	@Test
	void parse_placeDetail_이_null_이면_페이지_없음으로_본다() throws Exception {
		// 09-05 이브런치(1596902362) 실제 응답 모양 — 페이지가 삭제된 플레이스는 placeDetail 이 null 로 온다
		JsonNode gone = objectMapper.readTree("""
				{"ROOT_QUERY": {"__typename": "Query", "user": {"__typename": "UserResult"},
				  "placeDetail({\\"input\\":{\\"id\\":\\"1596902362\\"}})": null}}
				""");

		ParsedPlace parsedPlace = naverPlaceParser.parse(gone);

		assertThat(parsedPlace.pageMissing()).isTrue();
		assertThat(parsedPlace.menus()).isEmpty();
		assertThat(naverPlaceParser.parse(apolloFixture()).pageMissing()).isFalse();
	}

	@Test
	void parse_기본_필드를_추출한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.name()).isEqualTo("밀림 빵공장");
		assertThat(parsedPlace.phone()).isEqualTo("02-123-4567");
	}

	@Test
	void parse_ROOT_QUERY_동적_키_아래의_영업시간을_추출한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.businessHoursRaw())
			.contains("09:30")
			.contains("20:00")
			.doesNotContain("__ref");
	}

	@Test
	void parse_참조로_저장된_영업_상태를_해소한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.businessStatusDescription()).isEqualTo("영업 중");
	}

	@Test
	void parse_메뉴_엔티티를_전부_추출한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.menus()).hasSize(2);
		assertThat(parsedPlace.menus().get(0).name()).isEqualTo("소금빵");
		assertThat(parsedPlace.menus().get(0).price()).isEqualTo("3500");
		assertThat(parsedPlace.menus().get(0).imageUrls()).hasSize(1);
		assertThat(parsedPlace.menus().get(1).description()).isNull();
	}

	@Test
	void parse_배민_메뉴를_그룹명과_함께_추출한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.baeminMenus()).hasSize(2);
		ParsedMenu americano = parsedPlace.baeminMenus().stream()
			.filter(menu -> menu.name().contains("아메리카노")).findFirst().orElseThrow();
		assertThat(americano.price()).isEqualTo("1900");
		assertThat(americano.description()).isEqualTo("산미가 없고 고소하며 묵직한 원두");
		// "대표메뉴"와 "음료" 그룹에 중복 소속 — 실제 그룹명(음료)이 이긴다 (음료 제외 필터의 판별 입력)
		assertThat(americano.groupName()).isEqualTo("음료");
	}

	@Test
	void parse_배민_메뉴가_대표메뉴_그룹에만_있으면_그_이름을_쓴다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		ParsedMenu saltBreadSet = parsedPlace.baeminMenus().stream()
			.filter(menu -> menu.name().contains("소금빵")).findFirst().orElseThrow();
		assertThat(saltBreadSet.groupName()).isEqualTo("대표메뉴");
	}

	@Test
	void parse_네이버_일반_메뉴의_그룹명은_null_이다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.menus()).allSatisfy(menu -> assertThat(menu.groupName()).isNull());
	}

	@Test
	void parse_인스타_핸들을_추출한다() throws Exception {
		ParsedPlace parsedPlace = naverPlaceParser.parse(apolloFixture());

		assertThat(parsedPlace.instagramHandle()).isEqualTo("millim.flourforest");
	}

	@Test
	void parse_영업시간이_없으면_null_이다() throws Exception {
		JsonNode withoutHours = objectMapper.readTree("""
				{"PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "빵집", "newBusinessHours": []}}""");

		ParsedPlace parsedPlace = naverPlaceParser.parse(withoutHours);

		assertThat(parsedPlace.businessHoursRaw()).isNull();
		assertThat(parsedPlace.instagramHandle()).isNull();
		assertThat(parsedPlace.menus()).isEmpty();
		assertThat(parsedPlace.baeminMenus()).isEmpty();
	}

	@Test
	void 인자_없는_newBusinessHours_키도_영업시간을_찾는다() {
		// 2026-08 말부터 실제 페이지가 "newBusinessHours" (GraphQL 인자 없음) 로 내려온다 — 09-04 실측 최근 300건 중 267건
		JsonNode apolloState = objectMapper.readTree("""
				{
				  "ROOT_QUERY": {
				    "placeDetail({\\"input\\":{\\"id\\":\\"1\\"}})": {
				      "newBusinessHours": [
				        {
				          "businessHours": [{"day": "화", "businessHours": {"start": "10:00", "end": "20:00"}}],
				          "businessStatusDescription": {"status": "영업 중"}
				        }
				      ]
				    }
				  },
				  "PlaceDetailBase:1": {"__typename": "PlaceDetailBase", "name": "가게"}
				}
				""");

		ParsedPlace parsed = naverPlaceParser.parse(apolloState);

		assertThat(parsed.businessStatusDescription()).isEqualTo("영업 중");
		assertThat(parsed.businessHoursRaw()).contains("10:00");
	}
}
