package sweetgirl.croissant.personalization.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.InteractionType;

/**
 * 프로필 계산이 읽는 신호 한 줄 — user_store_interaction 행에 그 가게의 store_profile 축·태그와 (빵 신호면) 종류 이름을 붙인
 * 것. 가게 프로필이 없거나 축이 비면 storeAxis 는 null 이고, 그 줄은 축 계산에서 빠진다(태그·종류·카운트에는 남는다).
 *
 * @param kind RECORD_BREAD 의 menu → menu_category.name. store_profile.kinds 와 같은 어휘라 겹침 계산에 쓸 수 있다
 * @param storeSignatureKinds 그 가게의 대표 종류(store_profile.signature_kinds). 추정 종류의 재료. 없으면 빈 목록
 */
public record TasteSignal(InteractionType type, Long storeId, Long menuId, Boolean converted, LocalDateTime occurredAt,
		TasteAxis storeAxis, Map<String, Double> storeTags, String kind, List<String> storeSignatureKinds) {

	public boolean hasStoreAxis() {
		return storeAxis != null && storeAxis.isComplete();
	}

	public boolean hasStoreTags() {
		return storeTags != null && !storeTags.isEmpty();
	}
}
