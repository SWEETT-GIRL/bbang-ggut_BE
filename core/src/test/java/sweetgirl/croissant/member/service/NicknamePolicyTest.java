package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.moderation.service.ForbiddenWordFilter;

@ExtendWith(MockitoExtension.class)
@DisplayName("NicknamePolicy")
class NicknamePolicyTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private ForbiddenWordFilter forbiddenWordFilter;

	@InjectMocks
	private NicknamePolicy nicknamePolicy;

	@Test
	@DisplayName("한글 · 영문 · 숫자 2~10자만 된다 — 공백 · 기호 · 1자 · 11자는 안 된다")
	void wellFormed() {
		assertThat(nicknamePolicy.isWellFormed("빵순이")).isTrue();
		assertThat(nicknamePolicy.isWellFormed("Bread99")).isTrue();
		assertThat(nicknamePolicy.isWellFormed("가나다라마바사아자차")).isTrue();
		assertThat(nicknamePolicy.isWellFormed("빵")).isFalse();
		assertThat(nicknamePolicy.isWellFormed("가나다라마바사아자차카")).isFalse();
		assertThat(nicknamePolicy.isWellFormed("빵 순이")).isFalse();
		assertThat(nicknamePolicy.isWellFormed("빵순이!")).isFalse();
		assertThat(nicknamePolicy.isWellFormed(null)).isFalse();
	}

	@Test
	@DisplayName("validate 는 형식 → 금칙어 → 중복 순으로 첫 문제를 던진다")
	void validateOrder() {
		assertThatThrownBy(() -> nicknamePolicy.validate("빵 순이"))
			.isInstanceOf(NicknameException.class)
			.extracting("errorCode").isEqualTo(NicknameErrorCode.NICKNAME_FORMAT_INVALID);

		given(forbiddenWordFilter.containsForbidden("나쁜말")).willReturn(true);
		assertThatThrownBy(() -> nicknamePolicy.validate("나쁜말"))
			.isInstanceOf(NicknameException.class)
			.extracting("errorCode").isEqualTo(NicknameErrorCode.NICKNAME_FORBIDDEN);

		given(forbiddenWordFilter.containsForbidden("Bread")).willReturn(false);
		given(memberRepository.existsByNicknameNormalizedAndDeletedAtIsNull("bread")).willReturn(true);
		assertThatThrownBy(() -> nicknamePolicy.validate("Bread"))
			.isInstanceOf(NicknameException.class)
			.extracting("errorCode").isEqualTo(NicknameErrorCode.NICKNAME_TAKEN);

		given(forbiddenWordFilter.containsForbidden("빵순이")).willReturn(false);
		given(memberRepository.existsByNicknameNormalizedAndDeletedAtIsNull("빵순이")).willReturn(false);
		assertThatCode(() -> nicknamePolicy.validate("빵순이")).doesNotThrowAnyException();
	}
}
