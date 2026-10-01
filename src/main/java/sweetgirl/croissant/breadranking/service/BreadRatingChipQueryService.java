package sweetgirl.croissant.breadranking.service;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.stereotype.Service;

import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.menu.enums.MenuKind;

/** 빵 평가 칩 목록 — 코드 상수라 DB 를 보지 않는다. 빵 종류를 모르거나 대분류가 없으면 기본 순서. */
@Service
public class BreadRatingChipQueryService {

	public BreadRatingChipsResponse getChips(String menuKind) {
		Optional<BreadGroup> group = parseKind(menuKind).flatMap(BreadGroup::of);
		return new BreadRatingChipsResponse(
			group.map(Enum::name).orElse(null),
			group.map(BreadGroup::textureOrder).orElseGet(BreadGroup::defaultTextureOrder).stream()
				.map(Chip::of)
				.toList(),
			group.map(BreadGroup::tasteOrder).orElseGet(BreadGroup::defaultTasteOrder).stream()
				.map(Chip::of)
				.toList());
	}

	/** 태그의 breadCategory(MenuKind 이름)가 그대로 온다 — 모르는 이름이면 기본 순서로 물러선다 */
	private static Optional<MenuKind> parseKind(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return Arrays.stream(MenuKind.values())
			.filter(kind -> kind.name().equals(name.strip()))
			.findFirst();
	}
}
