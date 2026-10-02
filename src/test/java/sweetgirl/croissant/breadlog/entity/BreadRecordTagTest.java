package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;

class BreadRecordTagTest {

	@Test
	@DisplayName("평가를 넣으면 칩은 중복 없이 고른 순서로 돌려준다 — 같은 칩은 첫 자리만 남는다")
	void rateKeepsChipsOrderedAndDistinct() {
		BreadRecordTag tag = tag();

		tag.rate(true, List.of(TextureChip.쫀득, TextureChip.바삭, TextureChip.쫀득), List.of(TasteChip.짭짤, TasteChip.고소),
			"겉은 바삭");

		assertThat(tag.hasRating()).isTrue();
		assertThat(tag.textures()).containsExactly(TextureChip.쫀득, TextureChip.바삭);
		assertThat(tag.tastes()).containsExactly(TasteChip.짭짤, TasteChip.고소);
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

	@Test
	@DisplayName("쫄깃만 두었다가 바삭을 더해도 대표(첫 번째)는 쫄깃 — enum 순서(바삭이 앞)가 아니라 고른 순서다 (#842)")
	void rateKeepsChosenOrderWhenChipAdded() {
		BreadRecordTag tag = tag();
		tag.rate(true, List.of(TextureChip.쫄깃), null, null);

		tag.rate(true, List.of(TextureChip.쫄깃, TextureChip.바삭), List.of(TasteChip.짭짤, TasteChip.고소), null);

		assertThat(tag.textures()).containsExactly(TextureChip.쫄깃, TextureChip.바삭);
		assertThat(tag.tastes()).containsExactly(TasteChip.짭짤, TasteChip.고소);
		assertThat(tag.rating()).get().extracting(BreadRecordTag.Rating::textures)
			.isEqualTo(List.of(TextureChip.쫄깃, TextureChip.바삭));
	}

	@Test
	@DisplayName("순서 칸이 생기기 전 행(chip_order 전부 0)은 지금처럼 enum 순서로 돌려준다")
	@SuppressWarnings("unchecked")
	void legacyChipsWithoutOrderFallBackToEnumOrder() {
		BreadRecordTag tag = tag();
		Set<TagChip> chips = (Set<TagChip>)ReflectionTestUtils.getField(tag, "chips");
		chips.add(TagChip.texture(TextureChip.쫄깃, 0));
		chips.add(TagChip.texture(TextureChip.바삭, 0));
		chips.add(TagChip.taste(TasteChip.짭짤, 0));
		chips.add(TagChip.taste(TasteChip.고소, 0));

		assertThat(tag.textures()).containsExactly(TextureChip.바삭, TextureChip.쫄깃);
		assertThat(tag.tastes()).containsExactly(TasteChip.고소, TasteChip.짭짤);
	}

	private static BreadRecordTag tag() {
		return BreadRecordTag.builder().name("소금빵").breadCategory("소금빵").positionX(0.5).positionY(0.5).build();
	}

	@Test
	@DisplayName("칩 목록의 null 원소는 버린다 — 하나 때문에 저장이 깨지지 않는다")
	void rateIgnoresNullChips() {
		BreadRecordTag tag = tag();
		List<TextureChip> textures = new java.util.ArrayList<>();
		textures.add(null);
		textures.add(TextureChip.바삭);

		tag.rate(true, textures, java.util.Arrays.asList(TasteChip.고소, null), null);

		assertThat(tag.textures()).containsExactly(TextureChip.바삭);
		assertThat(tag.tastes()).containsExactly(TasteChip.고소);
	}
}
