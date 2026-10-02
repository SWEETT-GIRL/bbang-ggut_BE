package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;

class BreadRatingChipQueryServiceTest {

	private final BreadRatingChipQueryService service = new BreadRatingChipQueryService();

	@Test
	@DisplayName("소금빵은 빵 그룹 순서 — 겉바속촉이 맨 앞, 짭짤이 맨 앞")
	void breadGroupOrder() {
		BreadRatingChipsResponse response = service.getChips("소금빵");

		assertThat(response.breadGroup()).isEqualTo("빵");
		assertThat(response.textures()).hasSize(TextureChip.values().length);
		assertThat(response.textures().get(0)).isEqualTo(new Chip("겉바속촉", "겉바속촉"));
		assertThat(response.tastes().get(0)).isEqualTo(new Chip("짭짤", "짭짤"));
		assertThat(response.textures()).extracting(Chip::code).contains("속꽉참");
		assertThat(response.textures()).extracting(Chip::label).contains("속 꽉참");
	}

	@Test
	@DisplayName("빙수는 시원이 둘째로 온다")
	void iceGroupOrder() {
		BreadRatingChipsResponse response = service.getChips("빙수");

		assertThat(response.breadGroup()).isEqualTo("빙수_아이스");
		assertThat(response.textures().get(1).code()).isEqualTo("시원");
	}

	@Test
	@DisplayName("빵·기타·모르는 이름·없음은 대분류 없이 기본 순서")
	void defaultOrder() {
		for (String kind : new String[] {"빵", "기타", "없는빵", "", null}) {
			BreadRatingChipsResponse response = service.getChips(kind);

			assertThat(response.breadGroup()).as(String.valueOf(kind)).isNull();
			assertThat(response.textures()).extracting(Chip::code).containsExactly(
				java.util.Arrays.stream(TextureChip.values()).map(Enum::name).toArray(String[]::new));
			assertThat(response.tastes()).extracting(Chip::code).containsExactly(
				java.util.Arrays.stream(TasteChip.values()).map(Enum::name).toArray(String[]::new));
		}
	}
}
