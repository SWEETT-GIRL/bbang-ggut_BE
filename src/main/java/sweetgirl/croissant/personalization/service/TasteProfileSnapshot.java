package sweetgirl.croissant.personalization.service;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;

/**
 * 계산기가 낸 프로필 값 한 벌. 엔티티에 쓰기 전에 지금 값과 비교해 "추천이 읽는 값이 바뀌었나"(version+1) 와 "유형·카운트만
 * 바뀌었나"(version 유지)를 가른다.
 */
public record TasteProfileSnapshot(TasteAxis axis, String typeCode, TasteTypeSource typeSource,
		Map<String, Double> tags, String[] topKinds, TasteUserType userType, int strongStoreCount,
		int breadSignalCount) {

	/** 추천이 읽는 값 — 축 · 타입 · 출처 · 태그 · 종류. 이 중 하나라도 다르면 recompute(version+1). */
	public boolean sameTasteAs(UserTasteProfile profile) {
		return Objects.equals(axis, profile.getAxis())
			&& Objects.equals(typeCode, profile.getTypeCode())
			&& Objects.equals(typeSource, profile.getTypeSource())
			&& Objects.equals(tags, profile.getTags() != null ? profile.getTags() : Map.of())
			&& Arrays.equals(topKinds, profile.getTopKinds());
	}

	/** 유형 · 카운트 — 추천은 안 읽지만 컨텍스트(칩 상태)와 타입 카드가 읽는다. */
	public boolean sameStatsAs(UserTasteProfile profile) {
		return Objects.equals(userType, profile.getUserType())
			&& strongStoreCount == profile.getStrongStoreCount()
			&& breadSignalCount == profile.getBreadSignalCount();
	}
}
