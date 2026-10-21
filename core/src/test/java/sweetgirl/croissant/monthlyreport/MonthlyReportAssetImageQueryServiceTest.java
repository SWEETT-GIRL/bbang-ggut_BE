package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportAssetReplacement;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportAssetReplacementRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportAssetImageQueryService;

class MonthlyReportAssetImageQueryServiceTest {
	private final MonthlyReportAssetReplacementRepository repository = mock(
		MonthlyReportAssetReplacementRepository.class);
	private final MonthlyReportAssetImageQueryService service = new MonthlyReportAssetImageQueryService(repository);

	@Test
	void replacesFaceCoverAndTypeWithoutChangingPublishedValues() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode payload = (ObjectNode)mapper.readTree(MonthlyReportFixtures.PAYLOAD);
		payload.put("coverImageKey", "cover.png");
		((ObjectNode)payload.get("character")).put("faceImageKey", "face.png");
		((ObjectNode)payload.get("activity").get("artwork")).put("imageKey", "type.png");
		MonthlyReportResponse original = mapper.treeToValue(payload, MonthlyReportResponse.class);
		when(repository.findAllById(any())).thenReturn(List.of(
			MonthlyReportAssetReplacement.replace("cover.png", "cover.webp"),
			MonthlyReportAssetReplacement.replace("face.png", "face.webp"),
			MonthlyReportAssetReplacement.replace("type.png", "type.webp")));
		MonthlyReportResponse resolved = service.resolveReportImages(original);
		assertThat(resolved.coverImageKey()).isEqualTo("cover.webp");
		assertThat(resolved.character().faceImageKey()).isEqualTo("face.webp");
		assertThat(resolved.activity().artwork().imageKey()).isEqualTo("type.webp");
		assertThat(resolved.summary()).isEqualTo(original.summary());
		assertThat(resolved.activity().abilities()).isEqualTo(original.activity().abilities());
		assertThat(resolved.breads()).isEqualTo(original.breads());
		assertThat(resolved.character().code()).isEqualTo(original.character().code());
		assertThat(original.coverImageKey()).isEqualTo("cover.png");
		assertThat(original.character().faceImageKey()).isEqualTo("face.png");
	}

	@Test
	void missingAndLegacyImagesKeepTheirOriginalKeys() throws Exception {
		MonthlyReportResponse original = new ObjectMapper().readValue(MonthlyReportFixtures.PAYLOAD,
			MonthlyReportResponse.class);
		assertThat(service.resolveReportImages(original)).isEqualTo(original);
		assertThat(MonthlyReportAssetImageQueryService.replaceKey(Map.of(), null)).isNull();
	}

	@Test
	void emptyImageKeysDoNotQueryDatabase() {
		assertThat(service.getReplacementKeys(List.of())).isEmpty();
		verifyNoInteractions(repository);
	}
}
