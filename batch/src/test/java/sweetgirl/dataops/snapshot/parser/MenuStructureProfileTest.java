package sweetgirl.dataops.snapshot.parser;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MenuStructureProfileTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private static final String ROOT = "placeDetail({\"input\":{\"id\":\"123\"}})";

	private ObjectNode definition() throws Exception {
		try (var input = getClass().getResourceAsStream(MenuStructureProfile.RESOURCE)) {
			return (ObjectNode)mapper.readTree(input);
		}
	}
	private MenuStructureProfile extended() throws Exception {
		var definition = definition();
		definition.put("version", "naver-menu-structure-v2");
		((ArrayNode)definition.path("entities")).add(mapper.readTree("""
			{"typename":"NextMenuItem","priceShape":"DISPLAY_TEXT_WON","imageShapes":["STRING","URL_OBJECT"]}
			"""));
		((ArrayNode)definition.path("containers")).add(mapper.readTree("""
			{"field":"catalogMenus","itemsField":"entries","totalFields":["itemCount"]}
			"""));
		return MenuStructureProfile.fromJson(definition);
	}
	private ObjectNode payload() {
		var payload = mapper.createObjectNode();
		payload.set("PlaceDetailBase:123", mapper.readTree("{\"id\":\"123\",\"name\":\"빵집\"}"));
		payload.putObject("ROOT_QUERY").set(ROOT, mapper.readTree("""
			{"catalogMenus":{"entries":[{"__ref":"NextMenuItem:a"}],"itemCount":1}}
			"""));
		payload.set("NextMenuItem:a", mapper.readTree("""
			{"__typename":"NextMenuItem","id":"a","name":"소금빵","price":{"displayText":"5,300원"},
			 "images":["first",{"url":"second"}],"recommend":true}
			"""));
		return payload;
	}
	private ResponseDiagnostics diagnostics(MenuStructureProfile profile) {
		return new ResponseDiagnostics(mapper, new NaverPlaceParser(profile), new BusinessHoursParser(),
			new MenuEvidenceParser(mapper, profile));
	}
	private ResponseDiagnostics.Report inspect(ObjectNode payload, MenuStructureProfile profile) {
		return diagnostics(profile).inspect(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload, null,
			"profile-test", "123", OffsetDateTime.now(), 1L));
	}

	@Test void approved_alias_is_consumed_by_parser_evidence_and_diagnostics_together() throws Exception {
		var profile = extended();
		var payload = payload();
		assertThat(new NaverPlaceParser().parse(payload).menus()).isEmpty();
		assertThat(new MenuEvidenceParser(mapper).parse(payload, "123").menuComplete()).isFalse();
		assertThat(inspect(payload, MenuStructureProfile.bundled()).menuAllowed()).isFalse();

		var parsed = new NaverPlaceParser(profile).parse(payload);
		assertThat(parsed.menus()).singleElement().satisfies(menu -> {
			assertThat(menu.name()).isEqualTo("소금빵");
			assertThat(menu.price()).isEqualTo("5300");
			assertThat(menu.imageUrls()).containsExactly("first", "second");
		});
		var evidence = new MenuEvidenceParser(mapper, profile).parse(payload, "123");
		assertThat(evidence.menuComplete()).isTrue();
		assertThat(evidence.menuNames()).containsExactly("소금빵");
		assertThat(evidence.representativeNames()).containsExactly("소금빵");
		assertThat(inspect(payload, profile).issues()).isEmpty();
	}

	@Test void added_alias_does_not_change_scalar_object_or_nonfixed_price_semantics() throws Exception {
		var profile = extended();
		var payload = payload();
		for (String price : List.of("\"5300\"", "5300", "true", "{\"displayText\":5300}")) {
			((ObjectNode)payload.path("NextMenuItem:a")).set("price", mapper.readTree(price));
			assertThat(new NaverPlaceParser(profile).parse(payload).menus().get(0).price()).isNull();
			assertThat(inspect(payload, profile).issues()).extracting(ResponseDiagnostics.Issue::code)
				.contains("PRICE_SHAPE_UNSUPPORTED");
		}
		for (String price : List.of("변동가", "문의", "5,000~6,000원")) {
			((ObjectNode)payload.path("NextMenuItem:a")).putObject("price").put("displayText", price);
			assertThat(new NaverPlaceParser(profile).parse(payload).menus().get(0).price()).isNull();
			assertThat(inspect(payload, profile).issues()).isEmpty();
		}
	}

	@Test void profile_alias_cannot_authenticate_wrong_target_or_incomplete_list() throws Exception {
		var profile = extended();
		for (String count : List.of("0", "2", "\"1\"", "null")) {
			var payload = payload();
			((ObjectNode)payload.path("ROOT_QUERY").path(ROOT).path("catalogMenus")).set("itemCount", mapper.readTree(count));
			assertThat(inspect(payload, profile).assessment()).isEqualTo("PARTIAL");
		}
		var filtered = payload();
		var root = (ObjectNode)filtered.path("ROOT_QUERY").path(ROOT);
		root.set("catalogMenus({\"page\":2})", root.remove("catalogMenus"));
		assertThat(inspect(filtered, profile).assessment()).isEqualTo("PARTIAL");
		var wrongTarget = payload();
		((ObjectNode)wrongTarget.path("PlaceDetailBase:123")).put("id", "999");
		assertThat(inspect(wrongTarget, profile).held()).isTrue();
		assertThat(inspect(wrongTarget, profile).targetVerified()).isFalse();
		var paginated = payload();
		((ObjectNode)paginated.path("ROOT_QUERY").path(ROOT).path("catalogMenus")).putObject("pageInfo").put("hasNextPage", true);
		assertThat(inspect(paginated, profile).assessment()).isEqualTo("PARTIAL");
		var repeated = payload();
		var container = (ObjectNode)repeated.path("ROOT_QUERY").path(ROOT).path("catalogMenus");
		((ArrayNode)container.path("entries")).add(container.path("entries").path(0));
		container.put("itemCount", 2);
		assertThat(inspect(repeated, profile).assessment()).isEqualTo("PARTIAL");
		var error = payload();
		error.putArray("errors").add("upstream failure");
		assertThat(inspect(error, profile).held()).isTrue();
	}

	@Test void image_shape_selection_is_shared_by_parser_and_diagnostics() throws Exception {
		var definition = definition();
		((ArrayNode)definition.path("entities")).add(mapper.readTree("""
			{"typename":"TextImageMenu","priceShape":"SCALAR","imageShapes":["STRING"]}
			"""));
		var profile = MenuStructureProfile.fromJson(definition);
		var payload = payload();
		var root = (ObjectNode)payload.path("ROOT_QUERY").path(ROOT);
		var container = (ObjectNode)root.remove("catalogMenus");
		container.set("items", container.remove("entries"));
		container.set("total", container.remove("itemCount"));
		root.set("placeMenus", container);
		var menu = (ObjectNode)payload.path("NextMenuItem:a");
		menu.put("__typename", "TextImageMenu");
		menu.put("price", "5300");
		assertThat(new NaverPlaceParser(profile).parse(payload).menus().get(0).imageUrls()).containsExactly("first");
		assertThat(inspect(payload, profile).issues()).extracting(ResponseDiagnostics.Issue::code)
			.containsExactly("IMAGE_SHAPE_UNSUPPORTED");
	}

	@Test void consumer_disagreement_is_configuration_failure_and_profile_digest_changes_runtime_version() throws Exception {
		var base = MenuStructureProfile.bundled();
		var profile = extended();
		assertThatThrownBy(() -> new ResponseDiagnostics(mapper, new NaverPlaceParser(profile),
			new BusinessHoursParser(), new MenuEvidenceParser(mapper)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profiles disagree");
		assertThat(profile.digest()).isNotEqualTo(base.digest());
		assertThat(diagnostics(profile).runtimeParserDigest()).isNotEqualTo(diagnostics(base).runtimeParserDigest());
		assertThat(new MenuEvidenceParser(mapper, profile).version()).isNotEqualTo(new MenuEvidenceParser(mapper).version());
		assertThat(new MenuEvidenceParser(mapper).version()).isEqualTo(new MenuEvidenceParser(mapper, MenuStructureProfile.fromJson(definition())).version());
	}

	@Test void existing_entity_and_container_contracts_cannot_be_reinterpreted() throws Exception {
		var scalarPlacePrice = definition();
		((ObjectNode)scalarPlacePrice.path("entities").path(1)).put("priceShape", "SCALAR");
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(scalarPlacePrice)).isInstanceOf(IllegalArgumentException.class);
		var missingOldMapping = definition();
		((ArrayNode)missingOldMapping.path("entities")).remove(0);
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(missingOldMapping)).isInstanceOf(IllegalArgumentException.class);
		var pretendTotal = definition();
		((ObjectNode)pretendTotal.path("containers").path(1)).putArray("totalFields").add("id");
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(pretendTotal)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void unknown_options_executable_paths_duplicates_and_unbounded_profiles_are_rejected() throws Exception {
		var unknown = definition();
		unknown.put("completeWhenMissing", true);
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(unknown)).isInstanceOf(IllegalArgumentException.class);
		var executable = definition();
		((ArrayNode)executable.path("containers")).add(mapper.readTree("""
			{"field":"menus.*","itemsField":"items","totalFields":["total"]}
			"""));
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(executable)).isInstanceOf(IllegalArgumentException.class);
		var duplicate = definition();
		((ArrayNode)duplicate.path("entities")).add(duplicate.path("entities").path(0));
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(duplicate)).isInstanceOf(IllegalArgumentException.class);
		var unknownCodec = definition();
		((ObjectNode)unknownCodec.path("entities").path(1)).put("priceShape", "EVAL");
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(unknownCodec)).isInstanceOf(IllegalArgumentException.class);
		var unbounded = definition();
		for (int i = 0; i < 16; i++) ((ArrayNode)unbounded.path("entities")).add(mapper.readTree(
			"{\"typename\":\"Entity" + i + "\",\"priceShape\":\"SCALAR\",\"imageShapes\":[\"STRING\"]}"));
		assertThatThrownBy(() -> MenuStructureProfile.fromJson(unbounded)).isInstanceOf(IllegalArgumentException.class);
	}
}
