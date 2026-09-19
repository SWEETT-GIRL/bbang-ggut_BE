package sweetgirl.croissant.personalization.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

class BbtiTypeTest {

	@Test
	@DisplayName("16타입은 4글자 코드뿐이다 — 화면 이름은 두지 않는다(#760)")
	void sixteenCodesOnly() {
		assertThat(BbtiType.values()).hasSize(16)
			.allSatisfy(type -> assertThat(type.code()).matches("[SP][FC][RL][ON]"));
	}

	@Test
	@DisplayName("저장된 코드로 타입을 찾고, 규칙 밖 코드는 서버 약속이 깨진 것이라 예외다")
	void fromCode() {
		assertThat(BbtiType.fromCode("SCRO")).isEqualTo(BbtiType.SCRO);
		assertThatThrownBy(() -> BbtiType.fromCode("XXXX"))
			.isInstanceOf(PersonalizationException.class)
			.hasFieldOrPropertyWithValue("errorCode", PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE);
	}
}
