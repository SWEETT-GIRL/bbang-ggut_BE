package sweetgirl.croissant.personalization.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.TasteLabelVector;
import sweetgirl.croissant.personalization.enums.InteractionType;

/**
 * 프로필 계산이 읽는 신호 한 줄 — user_store_interaction 행에 그 가게의 store_profile 축·태그와 (빵 신호면) 종류 이름을 붙인
 * 것. 가게 프로필이 없거나 축이 비면 storeAxis 는 null 이고, 그 줄은 축 계산에서 빠진다(태그·종류·카운트에는 남는다).
 *
 * @param kind RECORD_BREAD 의 menu → menu_category.name. store_profile.kinds 와 같은 어휘라 겹침 계산에 쓸 수 있다
 * @param storeSignatureKinds 그 가게의 대표 종류(store_profile.signature_kinds). 추정 종류의 재료. 없으면 빈 목록
 * @param storeLabels 그 가게의 취향 라벨 아홉 값(store_recommendation_feature). 회원 라벨의 재료다 — 검사를 푼 회원이
 *        사실상 없어서, 라벨을 가진 회원은 이 값을 가중 평균해 만든 사람들뿐이다. 적재 전 가게는 null
 */
public record TasteSignal(InteractionType type, Long storeId, Long menuId, Boolean converted, LocalDateTime occurredAt,
		TasteAxis storeAxis, Map<String, Double> storeTags, String kind, List<String> storeSignatureKinds,
		TasteLabelVector storeLabels) {

	/** 라벨 없는 신호 — 라벨 적재 전 가게이거나 라벨을 쓰지 않는 픽스처. 없는 것이 정상 상태다. */
	public TasteSignal(InteractionType type, Long storeId, Long menuId, Boolean converted, LocalDateTime occurredAt,
			TasteAxis storeAxis, Map<String, Double> storeTags, String kind, List<String> storeSignatureKinds) {
		this(type, storeId, menuId, converted, occurredAt, storeAxis, storeTags, kind, storeSignatureKinds, null);
	}

	public boolean hasStoreAxis() {
		return storeAxis != null && storeAxis.isComplete();
	}

	public boolean hasStoreTags() {
		return storeTags != null && !storeTags.isEmpty();
	}

	/** 라벨 평균에 쓸 수 있는 줄인가 — 아홉이 다 있어야 한다. 일부만 있으면 그 줄을 빼고 나머지로 평균한다. */
	public boolean hasStoreLabels() {
		return storeLabels != null && storeLabels.isComplete();
	}
}
