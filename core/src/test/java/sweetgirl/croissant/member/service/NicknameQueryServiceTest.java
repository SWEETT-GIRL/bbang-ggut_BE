package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.dto.response.NicknameAvailabilityResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("NicknameQueryService")
class NicknameQueryServiceTest {

	@Mock
	private NicknamePolicy nicknamePolicy;

	@InjectMocks
	private NicknameQueryService nicknameQueryService;

	@Test
	@DisplayName("형식 · 금칙어 · 중복을 차례로 보고, 중복이면 두 자리 숫자를 붙인 빈 후보 3개를 준다")
	void checks() {
		given(nicknamePolicy.isWellFormed("빵 순이")).willReturn(false);
		assertThat(nicknameQueryService.checkAvailability("빵 순이").reason()).isEqualTo("FORMAT");

		given(nicknamePolicy.isWellFormed("나쁜말")).willReturn(true);
		given(nicknamePolicy.isForbidden("나쁜말")).willReturn(true);
		assertThat(nicknameQueryService.checkAvailability("나쁜말").reason()).isEqualTo("FORBIDDEN");

		given(nicknamePolicy.isWellFormed("빵순이")).willReturn(true);
		given(nicknamePolicy.isForbidden(anyString())).willReturn(false);
		given(nicknamePolicy.isTaken(anyString())).willAnswer(invocation -> "빵순이".equals(invocation.getArgument(0)));
		NicknameAvailabilityResponse taken = nicknameQueryService.checkAvailability(" 빵순이 ");
		assertThat(taken.available()).isFalse();
		assertThat(taken.reason()).isEqualTo("TAKEN");
		assertThat(taken.suggestions()).hasSize(3).allMatch(candidate -> candidate.matches("빵순이\\d{2}"))
			.doesNotHaveDuplicates();

		given(nicknamePolicy.isWellFormed("크루아상")).willReturn(true);
		assertThat(nicknameQueryService.checkAvailability("크루아상")).isEqualTo(NicknameAvailabilityResponse.ok());
	}

	@Test
	@DisplayName("10자 이름이 겹치면 앞을 잘라 숫자 자리를 만든다")
	void suggestionsFitMaxLength() {
		given(nicknamePolicy.isWellFormed("가나다라마바사아자차")).willReturn(true);
		given(nicknamePolicy.isForbidden(anyString())).willReturn(false);
		given(nicknamePolicy.isTaken(anyString()))
			.willAnswer(invocation -> "가나다라마바사아자차".equals(invocation.getArgument(0)));

		assertThat(nicknameQueryService.checkAvailability("가나다라마바사아자차").suggestions())
			.hasSize(3).allMatch(candidate -> candidate.matches("가나다라마바사아\\d{2}"));
	}
}
