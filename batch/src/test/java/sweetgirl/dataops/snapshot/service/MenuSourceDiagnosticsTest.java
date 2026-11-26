package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.*;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MenuSourceDiagnosticsTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final NaverPlaceParser parser = new NaverPlaceParser();
	private final ResponseDiagnostics diagnostics = new ResponseDiagnostics(mapper, parser,
		new BusinessHoursParser(), new MenuEvidenceParser(mapper));
	private static final String ROOT = "placeDetail({\"input\":{\"id\":\"123\"}})";
	private ObjectNode payload() throws Exception {
		try (var input = getClass().getResourceAsStream("/response-agent/baemin-only.json")) {
			return (ObjectNode)mapper.readTree(input);
		}
	}
	private ObjectNode root(ObjectNode payload) { return (ObjectNode)payload.path("ROOT_QUERY").path(ROOT); }
	private ResponseDiagnostics.Report inspect(ObjectNode payload) {
		return diagnostics.inspect(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload, null,
			"sample", "123", OffsetDateTime.now(), 1L));
	}
	@Test void null_naver_list_allows_verified_baemin_without_model_call() throws Exception {
		var payload = payload(); var report = inspect(payload);
		assertThat(report.assessment()).isEqualTo("PARTIAL");
		assertThat(report.menuAllowed()).isFalse();
		assertThat(report.baeminAllowed()).isTrue();
		assertThat(report.modelEligible()).isFalse();
		assertThat(parser.parse(payload).baeminMenus()).extracting(ParsedMenu::name).containsExactly("소금빵", "통밀빵");
	}
	@Test void unrelated_cache_menus_are_never_read() throws Exception {
		var payload = payload();
		payload.set("PlaceDetail_BaeminMenu:other", mapper.readTree("{\"__typename\":\"PlaceDetail_BaeminMenu\",\"id\":\"other\",\"name\":\"다른 가게\",\"price\":\"9999\"}"));
		assertThat(parser.parse(payload).baeminMenus()).hasSize(2);
		assertThat(inspect(payload).baeminAllowed()).isTrue();
	}
	@Test void missing_field_is_distinct_from_explicit_null() throws Exception {
		var payload = payload(); root(payload).remove("placeMenus");
		var report = inspect(payload);
		assertThat(report.modelEligible()).isTrue();
		assertThat(report.held()).isTrue();
		assertThat(report.issues()).extracting(ResponseDiagnostics.Issue::code).contains("MENU_CONTAINER_MISSING");
		assertThat(report.baeminAllowed()).isFalse();
	}
	@Test void unfamiliar_path_is_reported_even_with_an_old_null_field() throws Exception {
		var payload = payload(); root(payload).set("menuCatalog", mapper.readTree("{\"items\":[],\"total\":0}"));
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("MENU_CONTAINER_UNRECOGNIZED");
		assertThat(inspect(payload).modelEligible()).isTrue();
	}
	@Test void explicit_empty_lists_do_not_request_model_analysis() throws Exception {
		var payload = payload();
		root(payload).set("placeMenus", mapper.readTree("{\"items\":[],\"total\":0}"));
		var baemin = (ObjectNode)root(payload).path("baemin");
		baemin.putArray("menus"); baemin.putArray("menuGroups");
		var report = inspect(payload);
		assertThat(report.assessment()).isEqualTo("READY");
		assertThat(report.menuAllowed()).isTrue(); assertThat(report.baeminAllowed()).isTrue();
		assertThat(report.modelEligible()).isFalse();
		assertThat(parser.parse(payload).baeminMenus()).isEmpty();
	}
	@Test void null_optional_source_is_not_a_schema_change() throws Exception {
		var payload = payload(); root(payload).putNull("baemin");
		assertThat(inspect(payload).baeminAllowed()).isFalse();
		assertThat(inspect(payload).modelEligible()).isFalse();
		assertThat(inspect(payload).held()).isFalse();
	}
	@Test void null_baemin_items_preserves_naver_authority_without_a_model_call() throws Exception {
		var payload = payload(); root(payload).set("placeMenus", mapper.readTree("{\"items\":[],\"total\":0}"));
		((ObjectNode)root(payload).path("baemin")).putNull("menus");
		var report = inspect(payload);
		assertThat(report.assessment()).isEqualTo("PARTIAL");
		assertThat(report.menuAllowed()).isTrue(); assertThat(report.baeminAllowed()).isFalse();
		assertThat(report.modelEligible()).isFalse();
	}
	@Test void broken_reference_duplicate_identity_and_wrong_store_are_held() throws Exception {
		var payload = payload(); payload.remove("PlaceDetail_BaeminMenu:a");
		assertThat(inspect(payload).baeminAllowed()).isFalse();
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("UNRESOLVED_REFERENCE");
		payload = payload(); ((ObjectNode)payload.path("PlaceDetail_BaeminMenu:b")).put("id", "a");
		assertThat(inspect(payload).baeminAllowed()).isFalse();
		payload = payload(); root(payload).put("id", "999");
		assertThat(inspect(payload).baeminAllowed()).isFalse();
	}
	@Test void changed_baemin_type_or_missing_items_goes_to_analysis() throws Exception {
		var payload = payload(); ((ObjectNode)payload.path("PlaceDetail_BaeminMenu:a")).putObject("price").put("amount", 3500);
		assertThat(inspect(payload).modelEligible()).isTrue(); assertThat(inspect(payload).held()).isTrue();
		payload = payload(); ((ObjectNode)root(payload).path("baemin")).remove("menus");
		assertThat(inspect(payload).modelEligible()).isTrue();
	}
}
