package sweetgirl.dataops.snapshot.parser;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class MenuEvidenceParserTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final MenuEvidenceParser parser = new MenuEvidenceParser(mapper);

	private String payload(String container) {
		var payload = mapper.createObjectNode();
		var root = mapper.createObjectNode();
		root.set("placeDetail({\"input\":{\"id\":\"123\"}})", mapper.readTree("{\"placeMenus\":" + container + "}"));
		payload.set("ROOT_QUERY", root);
		payload.set("Menu:a", mapper.readTree("{\"__typename\":\"Menu\",\"id\":\"a\",\"name\":\"소금빵\",\"recommend\":true}"));
		payload.set("Menu:orphan", mapper.readTree("{\"__typename\":\"Menu\",\"id\":\"orphan\",\"name\":\"고로케\",\"recommend\":true}"));
		return payload.toString();
	}

	@Test
	void complete_list_uses_target_refs_only() {
		var result = parser.parse(mapper.readTree(payload("{\"items\":[{\"__ref\":\"Menu:a\"}],\"total\":1}")), "123");
		assertThat(result.identityVerified()).isTrue();
		assertThat(result.menuComplete()).isTrue();
		assertThat(result.menuNames()).containsExactly("소금빵");
		assertThat(result.representativeNames()).containsExactly("소금빵");
	}

	@Test
	void missing_total_is_positive_only_not_complete() {
		var result = parser.parse(mapper.readTree(payload("{\"items\":[{\"__ref\":\"Menu:a\"}]}")), "123");
		assertThat(result.identityVerified()).isTrue();
		assertThat(result.menuComplete()).isFalse();
		assertThat(result.menuNames()).containsExactly("소금빵");
	}

	@Test
	void dangling_reference_pagination_and_count_mismatch_cannot_prove_absence() {
		for (String container : new String[]{
			"{\"items\":[{\"__ref\":\"missing\"}],\"total\":1}",
			"{\"items\":[{\"__ref\":\"Menu:a\"}],\"total\":2}",
			"{\"items\":[{\"__ref\":\"Menu:a\"}],\"total\":1,\"pageInfo\":{\"hasNextPage\":true}}",
			"{\"items\":[{\"__ref\":\"Menu:a\"},{\"__ref\":\"Menu:a\"}],\"total\":2}"}) {
			assertThat(parser.parse(mapper.readTree(payload(container)), "123").menuComplete()).isFalse();
		}
	}

	@Test
	void explicit_empty_complete_list_is_distinct_from_missing_menu_section() {
		assertThat(parser.parse(mapper.readTree(payload("{\"items\":[],\"total\":0}")), "123").menuComplete()).isTrue();
		assertThat(parser.parse(mapper.readTree(payload("null")), "123").menuComplete()).isFalse();
	}

	@Test
	void wrong_target_and_unscoped_cache_entities_provide_no_positive_evidence() {
		var result = parser.parse(mapper.readTree(payload("{\"items\":[{\"__ref\":\"Menu:a\"}],\"total\":1}")), "999");
		assertThat(result.identityVerified()).isFalse();
		assertThat(result.menuNames()).isEmpty();
		assertThat(result.representativeNames()).isEmpty();
		assertThat(parser.parse(mapper.readTree("{\"Menu:a\":{\"__typename\":\"Menu\",\"name\":\"소금빵\"}}"), "123"))
			.isEqualTo(MenuEvidenceParser.Evidence.unknown());
	}

	@Test
	void filtered_list_total_and_referenced_page_info_cannot_prove_full_inventory() {
		String filtered = payload("{\"items\":[],\"total\":0}").replace("placeMenus", "placeMenus({})");
		assertThat(parser.parse(mapper.readTree(filtered), "123").menuComplete()).isFalse();
		var paginated = (tools.jackson.databind.node.ObjectNode)mapper.readTree(
			payload("{\"items\":[],\"total\":0,\"pageInfo\":{\"__ref\":\"page\"}}"));
		paginated.set("page", mapper.readTree("{\"hasNextPage\":true}"));
		assertThat(parser.parse(paginated, "123").menuComplete()).isFalse();
	}

	@Test
	void recorded_real_place_menus_payload_proves_completeness() throws Exception {
		// 네이버가 09-06~09-20 사이에 메뉴 payload 모양을 바꿨다. 새 모양은 placeMenus 컨테이너에
		// menuCount 로 전체 수를 준다 — v1 은 total/totalCount 만 찾아 완전성이 영영 서지 않았다.
		// 이 fixture 는 09-20 이후 실제 수집분이다.
		try (var input = getClass().getResourceAsStream("/fixtures/apollo_state_place_menus_1439400670.json")) {
			var result = parser.parse(mapper.readTree(input.readAllBytes()), "1439400670");

			assertThat(result.identityVerified()).isTrue();
			assertThat(result.menuComplete()).isTrue();
			assertThat(result.menuNames()).hasSize(4);
		}
	}

	@Test
	void menu_count_is_accepted_only_when_it_matches_the_listed_items() {
		// 이름만 다를 뿐 "이 목록이 전부"라는 같은 주장이므로 total 과 똑같이 검사한다
		assertThat(parser.parse(mapper.readTree(payload(
			"{\"items\":[{\"__ref\":\"Menu:a\"}],\"menuCount\":1}")), "123").menuComplete()).isTrue();
		assertThat(parser.parse(mapper.readTree(payload(
			"{\"items\":[{\"__ref\":\"Menu:a\"}],\"menuCount\":5}")), "123").menuComplete()).isFalse();
		assertThat(parser.parse(mapper.readTree(payload(
			"{\"items\":[{\"__ref\":\"Menu:a\"}],\"menuCount\":\"1\"}")), "123").menuComplete()).isFalse();
	}

	@Test
	void recorded_real_menu_array_is_positive_evidence_without_claiming_completeness() throws Exception {
		try (var input = getClass().getResourceAsStream("/fixtures/apollo_state_lemoment_465987.json")) {
			var result = parser.parse(mapper.readTree(input), "2008297455");
			assertThat(result.identityVerified()).isTrue();
			assertThat(result.menuNames()).hasSize(11);
			assertThat(result.representativeNames()).hasSize(8);
			assertThat(result.menuComplete()).isFalse();
		}
	}
}
