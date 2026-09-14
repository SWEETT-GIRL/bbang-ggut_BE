package sweetgirl.croissant.recommendation.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sweetgirl.croissant.personalization.entity.UserTasteProfile;

/**
 * 후보 SQL 에 넘기는 사용자 취향(#661): 4축(없으면 null) · 태그 가중치(0~1, 12종) · 상위 빵 종류.
 * 프로필이 없거나 축이 없으면 축 항이 0 이 되고, 태그·종류가 비면 그 항도 0 이라 결과는 인기 prior 순이다.
 * 취향 폴백(취향 후보 3곳 미만)은 {@link #empty()} 로 다시 조회한다 — 취향 항을 전부 뺀다.
 */
public record TasteQuery(
		Double taste,
		Double texture,
		Double filling,
		Double vibe,
		Map<String, Double> tags,
		List<String> topKinds) {

	private static final TasteQuery EMPTY = new TasteQuery(null, null, null, null, Map.of(), List.of());

	public static TasteQuery empty() {
		return EMPTY;
	}

	public static TasteQuery of(UserTasteProfile profile) {
		if (profile == null) {
			return EMPTY;
		}
		Map<String, Double> tags = profile.getTags() == null ? Map.of() : profile.getTags().entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		List<String> topKinds = profile.getTopKinds() == null ? List.of() : List.of(profile.getTopKinds());
		if (!profile.hasAxis()) {
			return new TasteQuery(null, null, null, null, tags, topKinds);
		}
		return new TasteQuery(profile.getAxis().getTaste().doubleValue(), profile.getAxis().getTexture().doubleValue(),
			profile.getAxis().getFilling().doubleValue(), profile.getAxis().getVibe().doubleValue(), tags, topKinds);
	}

	public boolean hasAxis() {
		return taste != null && texture != null && filling != null && vibe != null;
	}
}
