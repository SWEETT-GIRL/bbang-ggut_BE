package sweetgirl.croissant.breadranking.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

class BreadGroupTest {

	@Test
	@DisplayName("빵·기타를 뺀 모든 MenuKind 가 대분류 하나에 정확히 한 번 들어간다")
	void everyKindBelongsToExactlyOneGroup() {
		for (MenuKind kind : MenuKind.values()) {
			long groups = Arrays.stream(BreadGroup.values()).filter(group -> group.getKinds().contains(kind)).count();
			if (MenuCategory.UNCATEGORIZED_KINDS.contains(kind)) {
				assertThat(groups).as(kind.name()).isZero();
				assertThat(BreadGroup.of(kind)).isEmpty();
			} else {
				assertThat(groups).as(kind.name()).isEqualTo(1);
				assertThat(BreadGroup.of(kind)).isPresent();
			}
		}
	}

	@Test
	@DisplayName("그룹의 칩 순서는 앞 6개 뒤에 나머지가 기본 순서로 — 전체가 빠짐없이 한 번씩")
	void chipOrderCoversAllChipsOnce() {
		for (BreadGroup group : BreadGroup.values()) {
			List<TextureChip> textures = group.textureOrder();
			List<TasteChip> tastes = group.tasteOrder();
			assertThat(textures).as(group.name()).hasSize(TextureChip.values().length).doesNotHaveDuplicates();
			assertThat(tastes).as(group.name()).hasSize(TasteChip.values().length).doesNotHaveDuplicates();
			assertThat(textures.subList(0, 6)).isEqualTo(group.getLeadingTextures());
			assertThat(tastes.subList(0, 6)).isEqualTo(group.getLeadingTastes());
		}
	}

	@Test
	@DisplayName("시원·사각사각은 빙수·아이스에서만 앞에 오고 다른 그룹에선 맨 뒤다")
	void coldChipsOnlyLeadInIceGroup() {
		assertThat(BreadGroup.빙수_아이스.textureOrder().subList(0, 6)).contains(TextureChip.시원, TextureChip.사각사각);
		List<TextureChip> bread = BreadGroup.빵.textureOrder();
		assertThat(bread.subList(bread.size() - 2, bread.size())).containsExactly(TextureChip.시원, TextureChip.사각사각);
		assertThat(BreadGroup.defaultTextureOrder()).containsExactly(TextureChip.values());
	}
}
