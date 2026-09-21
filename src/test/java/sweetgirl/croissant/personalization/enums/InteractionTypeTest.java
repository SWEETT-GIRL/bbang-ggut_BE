package sweetgirl.croissant.personalization.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InteractionTypeTest {

	@Test
	@DisplayName("가중치는 설계 §4.2 · §4.4 그대로 — 기록 2, 저장 1, 계획·담기 1.5, 조회 0(부호는 전환으로), 철회는 음수")
	void weightsFollowDesign() {
		assertThat(InteractionType.RECORD_BREAD.getWeight()).isEqualTo(2.0);
		assertThat(InteractionType.RECORD_STORE.getWeight()).isEqualTo(2.0);
		assertThat(InteractionType.SAVE.getWeight()).isEqualTo(1.0);
		assertThat(InteractionType.PLAN.getWeight()).isEqualTo(1.5);
		assertThat(InteractionType.COURSE_ADD.getWeight()).isEqualTo(1.5);
		assertThat(InteractionType.STORE_VIEW.getWeight()).isZero();
		assertThat(InteractionType.UNSAVE.getWeight()).isEqualTo(-1.0);
		assertThat(InteractionType.COURSE_REMOVE.getWeight()).isEqualTo(-1.0);
		assertThat(InteractionType.RECO_DISMISSED.getWeight()).isEqualTo(-0.3);
		assertThat(InteractionType.RECO_UNCHECK.getWeight()).isEqualTo(-1.0);
	}

	@Test
	@DisplayName("음의 신호 판정은 가중치 부호를 따른다 — 이름은 24자 컬럼에 들어간다")
	void negativeAndColumnLength() {
		for (InteractionType type : InteractionType.values()) {
			assertThat(type.isNegative()).isEqualTo(type.getWeight() < 0);
			assertThat(type.name().length()).isLessThanOrEqualTo(24);
		}
	}
}
