package sweetgirl.croissant.member.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.ArrayList;
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

	// 테스트 classpath adjectives.txt(2개, '달콤한'은 일부러 두 줄) × nouns.txt(2개) = 조합 4개
	private static final int TOTAL_COMBINATIONS = 4;
	private static final List<String> ALL_COMBINATIONS = List.of(
		"달콤한망고시루", "달콤한두쫀쿠", "신난망고시루", "신난두쫀쿠");

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private NicknameGenerator nicknameGenerator;

	@Test
	void generate_returnsAdjectiveNounNickname() {
		given(memberRepository.findAllNicknames()).willReturn(List.of());
		nicknameGenerator.init();

		String nickname = nicknameGenerator.generate();

		// 띄어쓰기 없이 붙이고 10자 안이다(새 닉네임 규칙)
		assertThat(nickname).matches("^[가-힣A-Za-z0-9]{2,10}$");
	}

	@Test
	void generate_throwsNicknameException_whenAllCombinationsAreInUse() {
		given(memberRepository.findAllNicknames())
			.willReturn(List.of())
			.willReturn(ALL_COMBINATIONS);
		nicknameGenerator.init();

		for (int i = 0; i < TOTAL_COMBINATIONS; i++) {
			nicknameGenerator.generate();
		}

		assertThatThrownBy(() -> nicknameGenerator.generate())
			.isInstanceOf(NicknameException.class);
	}

	@Test
	void generate_rebuildsQueueAndSucceeds_whenFreedCombinationsExistAfterExhaustion() {
		given(memberRepository.findAllNicknames())
			.willReturn(List.of("달콤한망고시루"))
			.willReturn(List.of());
		nicknameGenerator.init();

		for (int i = 0; i < TOTAL_COMBINATIONS - 1; i++) {
			nicknameGenerator.generate();
		}

		assertThat(nicknameGenerator.generate()).isNotNull();
	}

	@Test
	void generate_neverRepeatsNickname_whenWordFileHasDuplicateLines() {
		given(memberRepository.findAllNicknames()).willReturn(List.of());
		nicknameGenerator.init();

		List<String> generated = new ArrayList<>();
		for (int i = 0; i < TOTAL_COMBINATIONS; i++) {
			generated.add(nicknameGenerator.generate());
		}

		assertThat(generated).containsExactlyInAnyOrderElementsOf(ALL_COMBINATIONS);
	}

	@Test
	void init_excludesAlreadyUsedNicknames() {
		// 옛 닉네임은 띄어쓰기가 있다 — 정규화해서 비교하므로 "달콤한망고시루" 도 막힌다
		given(memberRepository.findAllNicknames()).willReturn(List.of("달콤한 망고시루"));
		nicknameGenerator.init();

		List<String> generated = List.of(
			nicknameGenerator.generate(),
			nicknameGenerator.generate(),
			nicknameGenerator.generate());

		assertThat(generated).doesNotContain("달콤한망고시루");
	}
}
