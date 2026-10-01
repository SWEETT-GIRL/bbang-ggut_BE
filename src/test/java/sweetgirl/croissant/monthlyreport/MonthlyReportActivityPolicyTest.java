package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Activity;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;

class MonthlyReportActivityPolicyTest {

	private final MonthlyReportActivityPolicy policy = new MonthlyReportActivityPolicy();

	@Test
	void usesApprovedWeightsAndDoesNotCapPowerAtOneThousand() {
		Activity activity = policy.calculate(80, 20, 10, 200);
		assertThat(activity.totalScore()).isEqualTo(1590);
		assertThat(activity.abilities().explore().score()).isEqualTo(80);
		assertThat(activity.abilities().save().score()).isEqualTo(60);
		assertThat(activity.abilities().plan().score()).isEqualTo(50);
		assertThat(activity.abilities().record().score()).isEqualTo(1400);
		assertThat(activity.abilities().values()).allSatisfy(ability -> assertThat(ability.maxScore()).isNull());
		assertThat(policy.calculate(0, 0, 0, 10).totalScore()).isEqualTo(70);
		assertThat(policy.calculate(0, 0, 0, 100).totalScore()).isEqualTo(700);
	}

	@ParameterizedTest
	@CsvSource({
		"0, 0, 0, 0, seed", "8, 0, 0, 1, explore", "0, 3, 0, 1, save", "0, 0, 2, 1, plan",
		"6, 2, 1, 1, record", "105, 35, 21, 15, record", "15, 5, 3, 0, plan", "3, 1, 0, 0, save"
	})
	void choosesWeightedContributionWithStableTieOrder(long viewed, long saved, long planned, long records,
			String expectedType) {
		assertThat(policy.calculate(viewed, saved, planned, records).type()).isEqualTo(expectedType);
	}

	@ParameterizedTest
	@CsvSource({
		"-1, 0, 0, 0", "0, -1, 0, 0", "0, 0, -1, 0", "0, 0, 0, -1",
		"0, 0, 0, 9223372036854775807", "9007199254740991, 1, 0, 0"
	})
	void rejectsInvalidCountsOrUnsafeTotalsWithoutClipping(long viewed, long saved, long planned, long records) {
		assertThatThrownBy(() -> policy.calculate(viewed, saved, planned, records))
			.isInstanceOf(CustomException.class)
			.hasFieldOrPropertyWithValue("errorCode", MonthlyReportErrorCode.INVALID_SNAPSHOT);
	}

	@Test
	void preservesSafeIntegerBoundary() {
		assertThat(policy.calculate(MonthlyReportResponse.MAX_SAFE_INTEGER, 0, 0, 0).totalScore())
			.isEqualTo(MonthlyReportResponse.MAX_SAFE_INTEGER);
	}

	@Test
	void freezesPolicyMetadataAndOnlyKeepsArtworkForCalculatedType() {
		ObjectMapper mapper = new ObjectMapper();
		try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
			MonthlyReportPayloadCodec codec = new MonthlyReportPayloadCodec(mapper, validation.getValidator());
			MonthlyReportResponse draft = codec.read(PAYLOAD, 123L, MONTH);
			ObjectNode metrics = mapper.createObjectNode();
			MonthlyReportResponse report = policy.applyTo(draft, metrics);
			assertThat(report.schemaVersion()).isEqualTo(2);
			assertThat(report.activity().abilities().record().score()).isEqualTo(84);
			assertThat(report.activity().artwork()).isEqualTo(draft.activity().artwork());
			assertThat(report.character()).isEqualTo(draft.character());
			assertThat(report.planet()).isEqualTo(draft.planet());
			assertThat(codec.read(codec.write(report, 123L, MONTH), 123L, MONTH)).isEqualTo(report);
			assertThat(metrics.at("/activityPolicy/version").asText()).isEqualTo("bread-power-v1");
			assertThat(metrics.at("/activityPolicy/weights/record").asLong()).isEqualTo(7);
			assertThat(metrics.at("/activityPolicy/contributions/record").asLong()).isEqualTo(84);
			assertThat(policy.applyTo(draft.withStores(new MonthlyReportResponse.Stores(1000L, 0L, 0L, 99L)),
				metrics).activity().artwork()).isNull();
			// 초안의 과거 점수와 원본 객체를 바꾸지 않는다. 코스는 추가 빵력으로 계산하지 않는다.
			assertThat(draft.activity().totalScore()).isEqualTo(72);
			assertThat(metrics.at("/activityPolicy/totalScore").asLong()).isEqualTo(1084);
		}
	}
}
