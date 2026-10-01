package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;

class BreadRecordTagTest {

	@Test
	@DisplayName("평가를 넣으면 칩은 중복 없이 기본 순서로 돌려준다")
	void rateKeepsChipsOrderedAndDistinct() {
		BreadRecordTag tag = tag();

		tag.rate(true, List.of(TextureChip.쫀득, TextureChip.바삭, TextureChip.쫀득), List.of(TasteChip.짭짤, TasteChip.고소),
			"겉은 바삭");

		assertThat(tag.hasRating()).isTrue();
		assertThat(tag.textures()).containsExactly(TextureChip.바삭, TextureChip.쫀득);
		assertThat(tag.tastes()).containsExactly(TasteChip.고소, TasteChip.짭짤);
		assertThat(tag.rating()).get().extracting(BreadRecordTag.Rating::memo).isEqualTo("겉은 바삭");
	}

	@Test
	@DisplayName("아무것도 안 남긴 태그는 평가가 없다")
	void noRatingWhenNothingGiven() {
		BreadRecordTag tag = tag();

		assertThat(tag.hasRating()).isFalse();
		assertThat(tag.rating()).isEmpty();
		assertThat(tag.textures()).isEmpty();
	}

	@Test
	@DisplayName("하트는 또 먹을래요를 아직 안 정했을 때만 on 으로 — 평가가 off 로 둔 건 안 건드린다")
	void markAgainOnlyWhenUndecided() {
		BreadRecordTag undecided = tag();
		BreadRecordTag declined = tag();
		declined.rate(false, null, null, null);

		undecided.markAgain();
		declined.markAgain();

		assertThat(undecided.getAgain()).isTrue();
		assertThat(declined.getAgain()).isFalse();
	}

	@Test
	@DisplayName("빈 평가를 다시 넣으면 이전 칩·메모가 지워진다")
	void rateWithEmptyClearsPrevious() {
		BreadRecordTag tag = tag();
		tag.rate(true, List.of(TextureChip.바삭), List.of(TasteChip.고소), "메모");

		tag.rate(null, null, null, null);

		assertThat(tag.hasRating()).isFalse();
	}

	private static BreadRecordTag tag() {
		return BreadRecordTag.builder().name("소금빵").breadCategory("소금빵").positionX(0.5).positionY(0.5).build();
	}
}
