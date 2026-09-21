package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.TasteUserType;

class TasteProfileSnapshotTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 4, 0);

	@Test
	@DisplayName("추정 종류가 바뀌면 추천이 읽는 값이 바뀐 것이다 — version 을 올린다. 같으면 그대로")
	void inferredKindsAreTasteValues() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, NOW);
		profile.recompute(null, null, null, Map.of(), null, new String[] {"바게트"}, TasteUserType.BROWSER, 1, 0, NOW);

		TasteProfileSnapshot same = new TasteProfileSnapshot(null, null, null, Map.of(), null, new String[] {"바게트"},
			TasteUserType.BROWSER, 1, 0);
		TasteProfileSnapshot changed = new TasteProfileSnapshot(null, null, null, Map.of(), null,
			new String[] {"바게트", "소금빵"}, TasteUserType.BROWSER, 1, 0);

		assertThat(same.sameTasteAs(profile)).isTrue();
		assertThat(changed.sameTasteAs(profile)).isFalse();
	}

	@Test
	@DisplayName("새 프로필의 추정 종류는 빈 배열이다 — 계산기가 낸 빈 배열과 같게 본다(쓸데없이 version 이 오르지 않게)")
	void emptyProfileHasEmptyInferredKinds() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, NOW);

		assertThat(profile.getInferredKinds()).isEmpty();
		assertThat(new TasteProfileSnapshot(null, null, null, Map.of(), null, new String[0], null, 0, 0)
			.sameTasteAs(profile)).isTrue();
	}
}
