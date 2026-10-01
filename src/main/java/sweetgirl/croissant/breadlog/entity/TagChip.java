package sweetgirl.croissant.breadlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.breadlog.enums.ChipType;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;

/** 빵로그 태그에 붙은 평가 칩 하나 — 식감 또는 맛. 값은 enum 이름이다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TagChip {

	@Enumerated(EnumType.STRING)
	@Column(name = "chip_type", nullable = false, length = 10)
	private ChipType chipType;

	@Column(name = "chip", nullable = false, length = 20)
	private String chip;

	private TagChip(ChipType chipType, String chip) {
		this.chipType = chipType;
		this.chip = chip;
	}

	public static TagChip texture(TextureChip texture) {
		return new TagChip(ChipType.TEXTURE, texture.name());
	}

	public static TagChip taste(TasteChip taste) {
		return new TagChip(ChipType.TASTE, taste.name());
	}
}
