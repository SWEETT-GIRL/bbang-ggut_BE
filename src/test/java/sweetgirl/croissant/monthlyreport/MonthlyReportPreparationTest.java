package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAppearanceReader;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPreparation;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportFaceExpression;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService.Prepared;

class MonthlyReportPreparationTest {
	private final MonthlyReportAppearanceReader appearance = mock(MonthlyReportAppearanceReader.class);
	private final MonthlyReportGenerationService generation = mock(MonthlyReportGenerationService.class);
	private final MonthlyReportAssetQueryService assets = mock(MonthlyReportAssetQueryService.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final MonthlyReportPreparation preparation = new MonthlyReportPreparation(appearance, generation, assets,
		mapper);

	@Test
	void selectsAssetsUsingCalculatedTypeAndKeepsAppearanceMetadata() throws Exception {
		MonthlyReportResponse report = mapper.readValue(PAYLOAD, MonthlyReportResponse.class);
		Instant captured = Instant.now();
		when(appearance.createAppearanceDraft(123L, MONTH)).thenReturn(report);
		when(generation.prepare(eq(report), any()))
			.thenReturn(new Prepared(report, mapper.createObjectNode(), captured));
		when(assets.getRequiredAssetImageKey("v2", Kind.COVER, report.character().code(), "", MONTH.substring(5)))
			.thenReturn("covers/v2.png");
		when(assets.getRequiredAssetImageKey("v2", Kind.TYPE, report.character().code(), report.planet().code(),
			report.activity().type()))
			.thenReturn("types/v2.png");
		when(assets.getRequiredAssetImageKey("v2", Kind.FACE, report.character().code(), "",
			MonthlyReportFaceExpression.select(report.character().code(), YearMonth.parse(MONTH)).variant()))
			.thenReturn("faces/v2.png");
		Prepared result = preparation.prepare(123L, MONTH, "v2");
		assertThat(result.report().coverImageKey()).isEqualTo("covers/v2.png");
		assertThat(result.report().activity().artwork().imageKey()).isEqualTo("types/v2.png");
		assertThat(result.report().activity().artwork().type()).isEqualTo(report.activity().type());
		assertThat(result.report().character().faceImageKey()).isEqualTo("faces/v2.png");
		assertThat(result.report().character().imageKey()).isEqualTo(report.character().imageKey());
		assertThat(mapper.readValue(mapper.writeValueAsString(result.report()), MonthlyReportResponse.class))
			.isEqualTo(result.report());
		assertThat(result.metrics().path("appearance").path("capturedAt").asText()).isEqualTo(captured.toString());
		assertThat(result.generatedAt()).isEqualTo(captured);
	}

	@Test
	void preservesExistingAppExpressionOrder() {
		assertThat(MonthlyReportFaceExpression.select("cat", YearMonth.of(2026, 9)).variant()).isEqualTo("neutral");
		assertThat(MonthlyReportFaceExpression.select("cat", YearMonth.of(2026, 10)).variant()).isEqualTo("closed");
		assertThat(MonthlyReportFaceExpression.select("cat", YearMonth.of(2026, 11)).variant()).isEqualTo("smile");
		assertThat(MonthlyReportFaceExpression.select("cat", YearMonth.of(2026, 12)).variant()).isEqualTo("wink");
	}

	@Test
	void missingFaceBlocksPreparationBeforePublication() throws Exception {
		MonthlyReportResponse report = mapper.readValue(PAYLOAD, MonthlyReportResponse.class);
		when(appearance.createAppearanceDraft(123L, MONTH)).thenReturn(report);
		when(generation.prepare(eq(report), any()))
			.thenReturn(new Prepared(report, mapper.createObjectNode(), Instant.now()));
		when(assets.getRequiredAssetImageKey("v2", Kind.FACE, report.character().code(), "",
			MonthlyReportFaceExpression.select(report.character().code(), YearMonth.parse(MONTH)).variant()))
			.thenThrow(new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
		assertThatThrownBy(() -> preparation.prepare(123L, MONTH, "v2"))
			.isInstanceOf(CustomException.class);
	}

	@Test
	void missingAssetBlocksPreparation() throws Exception {
		MonthlyReportResponse report = mapper.readValue(PAYLOAD, MonthlyReportResponse.class);
		when(appearance.createAppearanceDraft(123L, MONTH)).thenReturn(report);
		when(generation.prepare(eq(report), any()))
			.thenReturn(new Prepared(report, mapper.createObjectNode(), Instant.now()));
		when(assets.getRequiredAssetImageKey("v2", Kind.COVER, report.character().code(), "", MONTH.substring(5)))
			.thenThrow(new CustomException(MonthlyReportErrorCode.ASSET_MISSING));
		assertThatThrownBy(() -> preparation.prepare(123L, MONTH, "v2")).isInstanceOf(CustomException.class);
	}
}
