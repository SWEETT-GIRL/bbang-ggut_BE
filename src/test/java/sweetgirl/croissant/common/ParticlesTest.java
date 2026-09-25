package sweetgirl.croissant.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParticlesTest {

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({"소금빵,이에요", "식빵,이에요", "케이크,예요", "휘낭시에,예요", "스콘,이에요", "타르트,예요"})
	@DisplayName("서술격 어미 — 받침이 있으면 이에요, 없으면 예요. 그냥 붙이면 '소금빵예요' 가 나간다")
	void copula(String kind, String expected) {
		assertThat(Particles.copula(kind)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({"소금빵,이", "케이크,가", "베이글,이", "쿠키,가"})
	@DisplayName("주격 조사 — 받침이 있으면 이, 없으면 가")
	void subject(String kind, String expected) {
		assertThat(Particles.subject(kind)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({"소금빵,과", "케이크,와", "베이글,과", "샌드위치,와"})
	@DisplayName("접속 조사 — 받침이 있으면 과, 없으면 와. '베이글와' 는 틀렸다")
	void conjunction(String kind, String expected) {
		assertThat(Particles.conjunction(kind)).isEqualTo(expected);
	}

	@Test
	@DisplayName("ㄹ 받침도 받침이다 — 베이글은 '과'. 예외가 있는 것은 으로/로 뿐이고 그건 지금 안 쓴다")
	void rieulIsJongseong() {
		assertThat(Particles.hasJongseong("베이글")).isTrue();
		assertThat(Particles.conjunction("베이글")).isEqualTo("과");
		assertThat(Particles.copula("베이글")).isEqualTo("이에요");
	}

	@ParameterizedTest
	@ValueSource(strings = {"MOF", "Cake", "1955", "빵1", "소금빵 "})
	@DisplayName("한글로 끝나지 않으면 조사를 정할 수 없다 — 부르는 쪽이 조사 없는 템플릿을 쓴다")
	void nonHangulEnding(String word) {
		assertThat(Particles.endsWithHangul(word)).isFalse();
		assertThat(Particles.hasJongseong(word)).isFalse();
	}

	@Test
	@DisplayName("null · 빈 문자열에도 터지지 않는다")
	void nullSafe() {
		assertThat(Particles.endsWithHangul(null)).isFalse();
		assertThat(Particles.endsWithHangul("")).isFalse();
		assertThat(Particles.hasJongseong(null)).isFalse();
	}

	@Test
	@DisplayName("운영 대표 종류 85개는 2026-09-25 현재 전부 한글로 끝난다 — 표본으로 고정한다")
	void productionKindsEndWithHangul() {
		assertThat(new String[] {"소금빵", "케이크", "쿠키", "샌드위치", "휘낭시에", "타르트", "식빵", "바게트",
			"스콘", "에그타르트", "마들렌", "도넛", "샌드베이글", "아이스크림", "크로크무슈"})
			.allSatisfy(kind -> assertThat(Particles.endsWithHangul(kind)).isTrue());
	}
}
