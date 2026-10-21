package sweetgirl.croissant.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.moderation.entity.ForbiddenWord;
import sweetgirl.croissant.moderation.enums.ForbiddenWordKind;
import sweetgirl.croissant.moderation.repository.ForbiddenWordRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForbiddenWordFilter")
class ForbiddenWordFilterTest {

	@Mock
	private ForbiddenWordRepository forbiddenWordRepository;

	@InjectMocks
	private ForbiddenWordFilter forbiddenWordFilter;

	private void givenWords() {
		given(forbiddenWordRepository.findAll()).willReturn(List.of(
			ForbiddenWord.of("시발", ForbiddenWordKind.BLOCK),
			ForbiddenWord.of("Fuck", ForbiddenWordKind.BLOCK),
			ForbiddenWord.of("시발점", ForbiddenWordKind.ALLOW)));
	}

	@Test
	@DisplayName("기호 · 공백 · 대소문자로 우회해도 걸린다")
	void catchesEvasion() {
		givenWords();

		assertThat(forbiddenWordFilter.containsForbidden("시.발 진짜")).isTrue();
		assertThat(forbiddenWordFilter.containsForbidden("시 발")).isTrue();
		assertThat(forbiddenWordFilter.containsForbidden("what the FUCK")).isTrue();
	}

	@Test
	@DisplayName("허용어(시발점) 안에서만 나오면 통과하고, 허용어 밖에 또 있으면 막는다")
	void allowList() {
		givenWords();

		assertThat(forbiddenWordFilter.containsForbidden("여기가 빵의 시발점이야")).isFalse();
		assertThat(forbiddenWordFilter.containsForbidden("시발점 시발")).isTrue();
	}

	@Test
	@DisplayName("빈 문장은 괜찮고, 단어는 한 번만 읽으며 reload 뒤 다시 읽는다")
	void loadsOnceUntilReload() {
		givenWords();

		assertThat(forbiddenWordFilter.containsForbidden(null)).isFalse();
		assertThat(forbiddenWordFilter.containsForbidden("   ")).isFalse();
		forbiddenWordFilter.containsForbidden("소금빵");
		forbiddenWordFilter.containsForbidden("크루아상");
		then(forbiddenWordRepository).should().findAll();

		forbiddenWordFilter.reload();
		forbiddenWordFilter.containsForbidden("소금빵");
		then(forbiddenWordRepository).should(org.mockito.Mockito.times(2)).findAll();
	}
}
