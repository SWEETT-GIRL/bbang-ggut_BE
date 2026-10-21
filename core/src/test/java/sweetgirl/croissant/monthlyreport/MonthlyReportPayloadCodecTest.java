package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportMonth;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;

class MonthlyReportPayloadCodecTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private final ObjectMapper mapper = new ObjectMapper();
	private final MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(mapper, FACTORY.getValidator());

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	void roundTripPreservesContract() {
		var report = codec.read(PAYLOAD, 123L, MONTH);
		assertThat(codec.read(codec.write(report, 123L, MONTH), 123L, MONTH)).isEqualTo(report);
		assertThat(report.activity().totalScore()).isEqualTo(72);
	}

	@Test
	void versionTwoStoresUnboundedPowerWithoutMaxScoreAndReadsBack() throws Exception {
		MonthlyReportResponse draft = codec.read(PAYLOAD, 123L, MONTH)
			.withStores(new MonthlyReportResponse.Stores(2000L, 0L, 0L, 0L));
		MonthlyReportResponse report = new MonthlyReportActivityPolicy().applyTo(draft, mapper.createObjectNode());
		String json = codec.write(report, 123L, MONTH);
		assertThat(mapper.readTree(json).at("/activity/abilities/explore").has("maxScore")).isFalse();
		assertThat(codec.read(json, 123L, MONTH)).isEqualTo(report);
		assertThat(report.activity().totalScore()).isEqualTo(2084);
		assertThat(report.activity().artwork()).isNull();
	}

	@ParameterizedTest
	@CsvSource({
		"/activity, abilities, null", "/activity, totalScore, null", "/activity, totalScore, 1",
		"/activity/abilities/record, score, -1", "/activity/abilities/record, maxScore, 1000",
		"/activity/abilities/record, score, 9007199254740992"
	})
	void versionTwoRejectsUncomputedPowerOrArtificialMaximum(String parent, String field, String json)
			throws Exception {
		MonthlyReportResponse report = new MonthlyReportActivityPolicy().applyTo(codec.read(PAYLOAD, 123L, MONTH),
			mapper.createObjectNode());
		ObjectNode payload = (ObjectNode)mapper.readTree(codec.write(report, 123L, MONTH));
		((ObjectNode)payload.at(parent)).set(field, mapper.readTree(json));
		assertInvalid(payload.toString());
	}

	@Test
	void versionOneStillRequiresMaximumForComputedAbilities() throws Exception {
		ObjectNode payload = MonthlyReportFixtures.payload();
		payload.withObject("/activity/abilities/record").remove("maxScore");
		assertInvalid(payload.toString());
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"null", "[]", "{\"소금빵\": null}", "{\"소금빵\": {\"recordId\": 1}}",
		"{\"소금빵\": {\"recordId\": 1, \"photoReferenceId\": \"invalid\"}}",
		"{\"소금빵\": {\"recordId\": 0, \"photoReferenceId\": \"c0c3913e-4531-40f1-8c66-309027a40611\"}}",
		"{\"소금빵\": {\"recordId\": \"1\", \"photoReferenceId\": \"c0c3913e-4531-40f1-8c66-309027a40611\"}}"
	})
	void rejectsMalformedPhotoSourcesInsteadOfFallingBackToOldPhoto(String sources) {
		assertThatThrownBy(() -> codec.readPhotoSources("{\"breadPhotoSources\":" + sources + "}"))
			.isInstanceOf(CustomException.class).hasFieldOrPropertyWithValue("errorCode",
				MonthlyReportErrorCode.INVALID_SNAPSHOT);
	}

	@Test
	void uncomputedAbilitiesAndScoreRemainNull() throws Exception {
		var payload = MonthlyReportFixtures.payload();
		payload.withObject("/activity").putNull("totalScore").putNull("abilities").remove("artwork");
		var report = codec.read(payload.toString(), 123L, MONTH);
		assertThat(report.activity().totalScore()).isNull();
		assertThat(report.activity().abilities()).isNull();
		assertThat(mapper.readTree(codec.write(report, 123L, MONTH)).at("/activity/totalScore").isNull()).isTrue();
		payload.withObject("/activity").remove("abilities");
		assertThat(codec.read(payload.toString(), 123L, MONTH).activity().abilities()).isNull();
	}

	@Test
	void zeroActivityPreservesNonzeroPreviousMonth() throws Exception {
		var payload = MonthlyReportFixtures.payload();
		payload.withObject("/summary").put("records", 0).put("breads", 0).put("stores", 0);
		payload.withObject("/stores").put("viewed", 0).put("saved", 0).put("planned", 0).put("courses", 0);
		payload.withObject("/comparison").put("currentRecords", 0);
		payload.putArray("breads");
		payload.putArray("towns");
		payload.withObject("/activity").put("type", "seed").put("totalScore", 0).remove("artwork");
		for (String ability : List.of("explore", "save", "plan", "record")) {
			payload.withObject("/activity/abilities/" + ability).put("score", 0);
		}
		var report = codec.read(payload.toString(), 123L, MONTH);
		assertThat(report.summary().records()).isZero();
		assertThat(report.comparison().previousRecords()).isEqualTo(9);
		assertThat(report.activity().totalScore()).isZero();
	}

	@Test
	void missingPreviousMonthRemainsExplicitNullWhileKnownZeroIsPreserved() throws Exception {
		ObjectNode payload = MonthlyReportFixtures.payload();
		payload.withObject("/comparison").putNull("previousRecords");
		MonthlyReportResponse report = codec.read(payload.toString(), 123L, MONTH);
		assertThat(report.comparison().previousRecords()).isNull();
		assertThat(report.comparison().currentRecords()).isEqualTo(12);
		assertThat(mapper.readTree(codec.write(report, 123L, MONTH)).at("/comparison/previousRecords").isNull())
			.isTrue();
		payload.withObject("/comparison").put("previousRecords", 0);
		assertThat(codec.read(payload.toString(), 123L, MONTH).comparison().previousRecords()).isZero();
		payload.withObject("/comparison").remove("previousRecords");
		assertInvalid(payload.toString());
	}

	@ParameterizedTest
	@CsvSource({
		"/summary, records, -1", "/summary, records, 1.5", "/summary, records, '\"12\"'",
		"/summary, records, 9007199254740992", "/summary, records, null",
		"/summary, records, true", "/stores, saved, null", "/comparison, previousRecords, -1",
		"/comparison, currentRecords, 11", "/activity, type, '\"invalid\"'",
		"/activity, totalScore, 71", "/activity/abilities/record, score, 26",
		"/activity/abilities/record, maxScore, 0", "/activity/abilities, save, null",
		"/activity/artwork, characterCode, '\"dog\"'", "/activity/artwork, planetCode, '\"mars\"'",
		"/activity/artwork, type, '\"save\"'", "/breads/0, count, 0", "/breads/0, name, 123"
	})
	void rejectsInvalidPayload(String parent, String field, String json) throws Exception {
		var payload = MonthlyReportFixtures.payload();
		((ObjectNode)payload.at(parent)).set(field, mapper.readTree(json));
		assertInvalid(payload.toString());
	}

	@Test
	void rejectsMissingRequiredFieldsAndWrongIdentity() throws Exception {
		var missingCount = MonthlyReportFixtures.payload();
		missingCount.withObject("/summary").remove("records");
		assertInvalid(missingCount.toString());
		var missingScore = MonthlyReportFixtures.payload();
		missingScore.withObject("/activity").remove("totalScore");
		assertInvalid(missingScore.toString());
		assertInvalid(PAYLOAD.replace("123", "124"));
		assertInvalid(PAYLOAD.replace(MONTH, "2025-11"));
		assertInvalid(PAYLOAD.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
		assertInvalid("null");
		assertInvalid("{}");
	}

	@Test
	void rejectsOverlongListsAndUnsafeAbilitySum() throws Exception {
		var payload = MonthlyReportFixtures.payload();
		var bread = payload.at("/breads/0").deepCopy();
		var breads = payload.putArray("breads");
		for (int i = 0; i < 101; i++) {
			breads.add(bread);
		}
		assertInvalid(payload.toString());
		payload = MonthlyReportFixtures.payload();
		var towns = payload.putArray("towns");
		for (int i = 0; i < 501; i++) {
			towns.addObject().put("name", "성수동");
		}
		assertInvalid(payload.toString());
		payload = MonthlyReportFixtures.payload();
		payload.withObject("/activity").putNull("totalScore");
		for (String ability : List.of("explore", "save", "plan", "record")) {
			payload.withObject("/activity/abilities/" + ability)
				.put("score", MonthlyReportResponse.MAX_SAFE_INTEGER)
				.put("maxScore", MonthlyReportResponse.MAX_SAFE_INTEGER);
		}
		assertInvalid(payload.toString());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"2026-1", "2026-00", "2026-13", "1999-12", "2100-01", "2026-01-01", " 2026-01"})
	void rejectsInvalidMonth(String month) {
		assertThatThrownBy(() -> MonthlyReportMonth.parse(month)).isInstanceOf(CustomException.class)
			.extracting("errorCode").isEqualTo(MonthlyReportErrorCode.INVALID_MONTH);
	}

	@Test
	void acceptsBoundaryMonthsAndDecemberRollover() {
		assertThat(MonthlyReportMonth.parse("2000-01").toString()).isEqualTo("2000-01");
		assertThat(MonthlyReportMonth.parse("2099-12").toString()).isEqualTo("2099-12");
		assertThat(MonthlyReportMonth.parse("2025-12").plusMonths(1).toString()).isEqualTo("2026-01");
	}

	private void assertInvalid(String payload) {
		assertThatThrownBy(() -> codec.read(payload, 123L, MONTH)).isInstanceOf(CustomException.class)
			.extracting("errorCode").isEqualTo(MonthlyReportErrorCode.INVALID_SNAPSHOT);
	}
}
