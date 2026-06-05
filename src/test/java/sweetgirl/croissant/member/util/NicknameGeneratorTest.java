package sweetgirl.croissant.member.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class NicknameGeneratorTest {

	private static final int TOTAL_COMBINATIONS = 4;

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private NicknameGenerator nicknameGenerator;

	@Test
	void generate_returnsAdjectiveNounNickname() {
		given(memberRepository.findAllNicknames()).willReturn(List.of());
		nicknameGenerator.init();

		String nickname = nicknameGenerator.generate();

		assertThat(nickname.split(" ")).hasSize(2);
	}

	@Test
	void generate_throwsNicknameException_whenQueueIsExhausted() {
		given(memberRepository.findAllNicknames()).willReturn(List.of());
		nicknameGenerator.init();

		for (int i = 0; i < TOTAL_COMBINATIONS; i++) {
			nicknameGenerator.generate();
		}

		assertThatThrownBy(() -> nicknameGenerator.generate())
			.isInstanceOf(NicknameException.class);
	}

	@Test
	void init_excludesAlreadyUsedNicknames() {
		given(memberRepository.findAllNicknames()).willReturn(List.of("달콤한 망고시루"));
		nicknameGenerator.init();

		for (int i = 0; i < TOTAL_COMBINATIONS - 1; i++) {
			nicknameGenerator.generate();
		}

		assertThatThrownBy(() -> nicknameGenerator.generate())
			.isInstanceOf(NicknameException.class);
	}
}
