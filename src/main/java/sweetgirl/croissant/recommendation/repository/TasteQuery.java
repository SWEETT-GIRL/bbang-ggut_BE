package sweetgirl.croissant.recommendation.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.GenericKind;
import sweetgirl.croissant.personalization.enums.TasteTag;

/**
 * 후보 SQL 에 넘기는 사용자 취향(#661): 4축(없으면 null) · 태그 가중치(0~1, {@link TasteTag} 8종) · 상위 빵 종류.
 * 프로필이 없거나 축이 없으면 축 항이 0 이 되고, 태그·종류가 비면 그 항도 0 이라 결과는 인기 prior 순이다.
 * 취향 폴백(취향 후보 3곳 미만)은 {@link #empty()} 로 다시 조회한다 — 취향 항을 전부 뺀다.
 *
 * <p>강한 신호 가게 수는 SQL 에 넘기지 않는다 — 태그를 문구에 써도 되는지({@link #reasonTags(int)}) 가르는 데만 쓴다.
 * 추정 종류(inferredKinds, #762)도 SQL 에 넘기지 않는다 — 점수가 아니라 "{종류} 취향" 배지에만 쓴다(점수에 넣을지는 측정 뒤).
 */
public record TasteQuery(
		Double taste,
		Double texture,
		Double filling,
		Double vibe,
		Map<String, Double> tags,
		List<String> topKinds,
		int strongStoreCount,
		List<String> inferredKinds) {

	private static final TasteQuery EMPTY = new TasteQuery(null, null, null, null, Map.of(), List.of(), 0, List.of());

	public static TasteQuery empty() {
		return EMPTY;
	}

	public static TasteQuery of(UserTasteProfile profile) {
		if (profile == null) {
			return EMPTY;
		}
		// 사전이 바뀌면(09-19 v5) 재계산 전 프로필에 옛 키가 남는다 — 새 가게 태그에 없는 키라 태그 항을 0 쪽으로 끌고,
		// 이유 문장에는 없어진 태그 이름이 샌다. 지금 사전에 있는 키만 넘긴다.
		// "> 0" 은 SQL 가중 평균의 재료를 고르는 것이지 "도드라진 태그" 문턱이 아니다 — v5 에서는 거의 다 통과한다. 문구 쪽은
		// memberNotableTagAbove 로 거르고, 여기를 바꾸면 점수가 달라지므로 태그 비중 조정 때 같이 본다(#759 리뷰)
		Map<String, Double> tags = profile.getTags() == null ? Map.of() : profile.getTags().entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
			.filter(entry -> TasteTag.fromKey(entry.getKey()).isPresent())
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		// 계산기가 이미 일반 종류를 빼지만, 그 전에 계산된 프로필에는 남아 있을 수 있다 — 조회 입구에서 한 번 더 막는다
		List<String> topKinds = profile.getTopKinds() == null ? List.of() : Arrays.stream(profile.getTopKinds())
			.filter(kind -> !GenericKind.isGeneric(kind))
			.toList();
		List<String> inferredKinds = profile.getInferredKinds() == null ? List.of()
			: Arrays.stream(profile.getInferredKinds())
				.filter(kind -> !GenericKind.isGeneric(kind))
				.toList();
		if (!profile.hasAxis()) {
			return new TasteQuery(null, null, null, null, tags, topKinds, profile.getStrongStoreCount(), inferredKinds);
		}
		return new TasteQuery(profile.getAxis().getTaste().doubleValue(), profile.getAxis().getTexture().doubleValue(),
			profile.getAxis().getFilling().doubleValue(), profile.getAxis().getVibe().doubleValue(), tags, topKinds,
			profile.getStrongStoreCount(), inferredKinds);
	}

	/**
	 * 문구(배지 · 이유 문장)에 써도 되는 태그. 강한 신호 가게가 {@code minStrongStores} 곳 미만이면 비운다 — 회원 태그는 좋아한
	 * 가게들 태그의 가중 평균이라, 한두 곳이면 그 가게의 태그를 옮겨 적은 것일 뿐 "이 회원은 이런 곳을 좋아한다" 고 말할 근거가 안 된다.
	 * 점수의 태그 항은 이 판정과 상관없이 {@link #tags()} 를 그대로 쓴다.
	 */
	public Map<String, Double> reasonTags(int minStrongStores) {
		return strongStoreCount >= minStrongStores ? tags : Map.of();
	}

	public boolean hasAxis() {
		return taste != null && texture != null && filling != null && vibe != null;
	}
}
