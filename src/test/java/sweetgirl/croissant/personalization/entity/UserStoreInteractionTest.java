package sweetgirl.croissant.personalization.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

class UserStoreInteractionTest {

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 9, 6, 15, 0);

	@Test
	@DisplayName("사건 시각과 적재 시각을 따로 갖는다")
	void of_keepsOccurredAtSeparateFromCreatedAt() {
		UserStoreInteraction saved = UserStoreInteraction.of(7L, 1042L, null, "SAVE", null, null, OCCURRED_AT);

		assertThat(saved.getOccurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(saved.getCreatedAt()).isAfterOrEqualTo(OCCURRED_AT);
		assertThat(saved.isBreadLevel()).isFalse();
	}

	@Test
	@DisplayName("기록의 빵은 가게와 메뉴를 함께 가리키는 빵 단위 신호다")
	void of_recordBread_isBreadLevel() {
		UserStoreInteraction recordBread = UserStoreInteraction.of(
			7L, 1042L, 555L, "RECORD_BREAD", null, null, OCCURRED_AT);

		assertThat(recordBread.getStoreId()).isEqualTo(1042L);
		assertThat(recordBread.getMenuId()).isEqualTo(555L);
		assertThat(recordBread.isBreadLevel()).isTrue();
	}

	@Test
	@DisplayName("가게도 메뉴도 없는 신호는 만들 수 없다")
	void of_rejectsSignalWithoutTarget() {
		assertThatThrownBy(() -> UserStoreInteraction.of(7L, null, null, "SAVE", null, null, OCCURRED_AT))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.INTERACTION_TARGET_REQUIRED));
	}
}
