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

/**
 * 빵로그 태그에 붙은 평가 칩 하나 — 식감 또는 맛. 값은 enum 이름이다.
 * 같음은 종류·값으로만 본다(순서가 달라도 같은 칩) — 표의 PK(tag_id, chip_type, chip)와 같은 기준.
 */
@Embeddable
@Getter
@EqualsAndHashCode(of = {"chipType", "chip"})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TagChip {

	@Enumerated(EnumType.STRING)
	@Column(name = "chip_type", nullable = false, length = 10)
	private ChipType chipType;

	@Column(name = "chip", nullable = false, length = 20)
	private String chip;

	/** 사용자가 고른 순서(종류별 0부터). 앱이 첫 번째를 대표로 보여 준다. 순서 칸이 생기기 전 행은 0 이라 enum 순서로 가른다. */
	@Column(name = "chip_order", nullable = false)
	private int position;

	private TagChip(ChipType chipType, String chip, int position) {
		this.chipType = chipType;
		this.chip = chip;
		this.position = position;
	}

	public static TagChip texture(TextureChip texture, int position) {
		return new TagChip(ChipType.TEXTURE, texture.name(), position);
	}

	public static TagChip taste(TasteChip taste, int position) {
		return new TagChip(ChipType.TASTE, taste.name(), position);
	}
}
