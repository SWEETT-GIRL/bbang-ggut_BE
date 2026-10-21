package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.*;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ResponseDiagnosticsTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final ResponseDiagnostics diagnostics = new ResponseDiagnostics(mapper, new NaverPlaceParser(),
		new BusinessHoursParser(), new MenuEvidenceParser(mapper));
	static final String ROOT = "placeDetail({\"input\":{\"id\":\"123\"}})";
	ObjectNode payload() {
		var payload = mapper.createObjectNode();
		payload.set("PlaceDetailBase:123", mapper.readTree("{\"id\":\"123\",\"name\":\"빵집\"}"));
		payload.putObject("ROOT_QUERY").set(ROOT, mapper.readTree("""
			{"placeMenus":{"items":[{"__ref":"PlaceMenuItem:a"}],"menuCount":1},
			 "newBusinessHours":[{"businessHours":[{"day":"매일","businessHours":{"start":"09:00","end":"18:00"}}]}]}
			"""));
		payload.set("PlaceMenuItem:a", mapper.readTree("""
			{"id":"a","__typename":"PlaceMenuItem","name":"빵","price":{"displayText":"5,300원"},"images":[{"url":"image"}]}
			"""));
		return payload;
	}
	ResponseDiagnostics.Report inspect(ObjectNode payload) {
		return diagnostics.inspect(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload, null, "capture", "123", OffsetDateTime.now(), 1L));
	}
	@Test void supported_format_and_value_changes_do_not_raise_incidents() {
		var payload = payload();
		assertThat(inspect(payload).issues()).isEmpty();
		((ObjectNode)payload.path("PlaceMenuItem:a").path("price")).put("displayText", "6,500원");
		((ObjectNode)payload.path("PlaceDetailBase:123")).put("visitorReviewsTotal", 120);
		assertThat(inspect(payload).issues()).isEmpty();
	}
	@Test void price_type_change_holds_entire_observation() {
		var payload = payload();
		((ObjectNode)payload.path("PlaceMenuItem:a")).put("price", "5300");
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("PRICE_SHAPE_UNSUPPORTED");
	}
	@Test void renamed_entity_and_unsupported_day_are_detected_without_throwing() {
		var payload = payload();
		((ObjectNode)payload.path("PlaceMenuItem:a")).put("__typename", "NextMenuItem");
		((ObjectNode)payload.path("ROOT_QUERY").path(ROOT).path("newBusinessHours").path(0).path("businessHours").path(0)).put("day", "평일");
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("MENU_ENTITY_UNSUPPORTED", "DAY_UNSUPPORTED");
	}
	@Test void partial_or_filtered_menu_list_is_not_proof_of_absence() {
		var payload = payload();
		var root = (ObjectNode)payload.path("ROOT_QUERY").path(ROOT);
		root.set("placeMenus({\"page\":2})", root.remove("placeMenus"));
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("MENU_INCOMPLETE");
	}
	@Test void null_menu_restricts_only_menu_without_becoming_a_model_analysis_case() {
		var payload = payload();
		((ObjectNode)payload.path("ROOT_QUERY").path(ROOT)).putNull("placeMenus");
		payload.remove("PlaceMenuItem:a");
		var report = inspect(payload);
		assertThat(report.issues()).extracting(ResponseDiagnostics.Issue::code).containsExactly("MENU_INCOMPLETE");
		assertThat(report.modelEligible()).isFalse();
		assertThat(report.held()).isFalse();
		assertThat(report.menuAllowed()).isFalse();
		assertThat(report.assessment()).isEqualTo("PARTIAL");
	}
	@Test void menu_incomplete_with_another_issue_holds_the_entire_observation() {
		var payload = payload();
		((ObjectNode)payload.path("ROOT_QUERY").path(ROOT).path("placeMenus")).put("menuCount", 99);
		((ObjectNode)payload.path("PlaceMenuItem:a")).put("price", "5300");
		var report = inspect(payload);
		assertThat(report.issues()).extracting(ResponseDiagnostics.Issue::code).contains("MENU_INCOMPLETE", "PRICE_SHAPE_UNSUPPORTED");
		assertThat(report.held()).isTrue();
		assertThat(report.menuAllowed()).isFalse();
		assertThat(report.assessment()).isEqualTo("HELD");
	}
	@Test void explicit_absence_and_missing_name_are_distinct() {
		var missing = payload();
		((ObjectNode)missing.path("PlaceDetailBase:123")).remove("name");
		assertThat(inspect(missing).issues()).extracting(ResponseDiagnostics.Issue::code).contains("REQUIRED_FIELD_UNREADABLE").doesNotContain("EXPLICIT_PAGE_ABSENT");
		((ObjectNode)missing.path("ROOT_QUERY")).putNull(ROOT);
		assertThat(inspect(missing).issues()).extracting(ResponseDiagnostics.Issue::code).containsExactly("EXPLICIT_PAGE_ABSENT");
	}
	@Test void unresolved_ref_and_other_target_are_held() {
		var payload = payload();
		payload.remove("PlaceMenuItem:a");
		assertThat(inspect(payload).held()).isTrue();
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("UNRESOLVED_REFERENCE");
		((ObjectNode)payload.path("PlaceDetailBase:123")).put("id", "999");
		assertThat(inspect(payload).issues()).extracting(ResponseDiagnostics.Issue::code).contains("IDENTITY_UNKNOWN");
	}
	@Test void invalid_observed_clock_is_held_on_live_and_saved_snapshot_diagnostics() {
		for (var observedAt : java.util.List.of(OffsetDateTime.now().plusDays(1), OffsetDateTime.parse("1969-12-31T00:00:00Z"))) {
			var report = diagnostics.inspect(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload(), null,
				"clock", "123", observedAt, null));
			assertThat(report.held()).isTrue();
			assertThat(report.targetVerified()).isFalse();
			assertThat(report.menuAllowed()).isFalse();
		}
	}

	@Test void candidate_cannot_repair_identity_and_menu_count_by_mutating_the_evidence_input() {
		var original=payload();
		((ObjectNode)original.path("PlaceDetailBase:123")).put("id","999");
		((ObjectNode)original.path("ROOT_QUERY").path(ROOT).path("placeMenus")).put("menuCount",99);
		var before=original.deepCopy();
		var mutating=new NaverPlaceParser() {
			@Override public ParsedPlace parse(tools.jackson.databind.JsonNode raw) {
				((ObjectNode)raw.path("PlaceDetailBase:123")).put("id","123");
				((ObjectNode)raw.path("ROOT_QUERY").path(ROOT).path("placeMenus")).put("menuCount",1);
				return super.parse(raw);
			}
		};
		var protectedDiagnostics=new ResponseDiagnostics(mapper,mutating,new BusinessHoursParser(),new MenuEvidenceParser(mapper));
		var result=protectedDiagnostics.inspect(new SnapshotSubmitRequest(1L,SyncSource.NAVER,original,null,"capture","123",OffsetDateTime.now(),1L));
		assertThat(result.assessment()).isEqualTo("HELD");
		assertThat(result.targetVerified()).isFalse();
		assertThat(result.issues()).extracting(ResponseDiagnostics.Issue::code).contains("PARSER_INPUT_MUTATION");
		assertThat(original).isEqualTo(before);
	}

	@Test void parser_may_normalize_its_own_copy_without_changing_the_callers_input() {
		var copying=new NaverPlaceParser() {
			@Override public ParsedPlace parse(tools.jackson.databind.JsonNode raw) {
				var ownCopy=raw.deepCopy();
				((ObjectNode)ownCopy).put("internal-normalization",true);
				return super.parse(ownCopy);
			}
		};
		var original=payload();var before=original.deepCopy();
		var protectedDiagnostics=new ResponseDiagnostics(mapper,copying,new BusinessHoursParser(),new MenuEvidenceParser(mapper));
		var result=protectedDiagnostics.inspect(new SnapshotSubmitRequest(1L,SyncSource.NAVER,original,null,"capture","123",OffsetDateTime.now(),1L));
		assertThat(result.assessment()).isEqualTo("READY");
		assertThat(original).isEqualTo(before);
	}

	@Test void mutation_then_exception_still_preserves_the_caller_input() {
		var mutating=new NaverPlaceParser() {
			@Override public ParsedPlace parse(tools.jackson.databind.JsonNode raw) {
				((ObjectNode)raw).remove("PlaceDetailBase:123");
				throw new IllegalArgumentException("parse failed after mutation");
			}
		};
		var original=payload();var before=original.deepCopy();
		assertThatThrownBy(()->ResponseDiagnostics.parsePreservingInput(mutating,original)).hasMessage("PARSER_INPUT_MUTATION");
		assertThat(original).isEqualTo(before);
	}

	@Test void signal_hours_parser_cannot_mutate_the_shared_raw_input() {
		var mutating=new NaverPlaceParser() {
			@Override public String parseBusinessHoursRaw(tools.jackson.databind.JsonNode raw) {
				((ObjectNode)raw.path("PlaceDetailBase:123")).put("id","999");
				return super.parseBusinessHoursRaw(raw);
			}
		};
		var original=payload();var before=original.deepCopy();
		assertThatThrownBy(()->new StoreSignalParser(mutating,new BusinessHoursParser()).parse(original)).hasMessage("PARSER_INPUT_MUTATION");
		assertThat(original).isEqualTo(before);
	}

}
