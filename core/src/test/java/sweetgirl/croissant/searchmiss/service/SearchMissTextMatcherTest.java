package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchMissTextMatcherTest {

	@ParameterizedTest
	@ValueSource(strings = {"ㅋㅋㅋ", "ㅂ ㅏ ㅇ", "1234", "빵", "아아아", " "})
	@DisplayName("자모만·숫자만·한 글자·같은 글자 반복은 쓰레기 입력이다")
	void isJunk_true(String keyword) {
		assertThat(SearchMissTextMatcher.isJunk(keyword)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"런베뮤", "성수 크로플", "빵점"})
	@DisplayName("가게를 찾는 말은 쓰레기가 아니다")
	void isJunk_false(String keyword) {
		assertThat(SearchMissTextMatcher.isJunk(keyword)).isFalse();
	}

	@Test
	@DisplayName("null 은 쓰레기 입력이다")
	void isJunk_null() {
		assertThat(SearchMissTextMatcher.isJunk(null)).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"런던베이글뮤지엄 안국점, 런던베이글뮤지엄",
		"런던베이글뮤지엄(안국점), 런던베이글뮤지엄",
		"London Bagel 도산점, londonbagel",
		"빵점, 빵점",
		"안국점, 안국점"
	})
	@DisplayName("띄어 쓰거나 괄호로 붙인 지점명만 떼고 정규화한다")
	void baseName(String storeName, String expected) {
		assertThat(SearchMissTextMatcher.baseName(storeName)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({
		"런베뮤, 런던베이글뮤지엄 안국점",
		"런던베이글, 런던베이글뮤지엄",
		"런던베이글뮤지염, 런던베이글뮤지엄",
		"런던 베이글 뮤지엄, 런던베이글뮤지엄"
	})
	@DisplayName("줄임말·포함·오타는 관련 있는 말이다")
	void isRelated_true(String keyword, String storeName) {
		assertThat(SearchMissTextMatcher.isRelated(keyword, storeName)).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"소금빵, 런던베이글뮤지엄",
		// 줄임말은 첫 음절부터 — 이게 없으면 짧은 말이 긴 이름 아무 데나 흩어져 맞는다
		"베뮤, 런던베이글뮤지엄",
		"어니언, 런던베이글뮤지엄"
	})
	@DisplayName("관련 없는 말은 가게 이름과 묶지 않는다")
	void isRelated_false(String keyword, String storeName) {
		assertThat(SearchMissTextMatcher.isRelated(keyword, storeName)).isFalse();
	}

	@ParameterizedTest
	@CsvSource({
		"이웃집퉁퉁이, 이웃집통통이",
		"뚜쥬르, 뚜쥬루",
		"서심당, 성심당",
		"블랑젤리코팡, 블랑제리코팡",
		"파리바게트, 파리바게뜨",
		"투텀즈업, 투떰즈업"
	})
	@DisplayName("자모 단위로 재서 모음·자음 한두 개만 다르면 오타다")
	void isTypo_jamo(String keyword, String name) {
		assertThat(SearchMissTextMatcher.isTypo(keyword, name)).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		// 글자로는 둘 다 2개·1개 차이지만 자모로는 5개·3개 — 다른 가게다(운영 1회차 오매칭)
		"케익부티크, 잍아부티크",
		"떰즈비, 떰즈업",
		// 두 글자는 자모 하나만 달라도 다른 말이 되기 쉬워 오타로 보지 않는다
		"콜버, 콜바",
		"성심당, 성심당"
	})
	@DisplayName("글자가 통째로 다르거나 너무 짧으면 오타가 아니다")
	void isTypo_rejects(String keyword, String name) {
		assertThat(SearchMissTextMatcher.isTypo(keyword, name)).isFalse();
	}

	@Test
	@DisplayName("한글을 초성·중성·종성으로 쪼개고 한글이 아닌 글자는 그대로 둔다")
	void toJamo() {
		assertThat(SearchMissTextMatcher.toJamo("빵a1")).isEqualTo("ㅃㅏㅇa1");
		assertThat(SearchMissTextMatcher.toJamo("뚜쥬루")).isEqualTo("ㄸㅜㅈㅠㄹㅜ");
	}

	@Test
	@DisplayName("이름 앞부분과 오타 관계여도 관련 있는 말이다 — 뚜쥬르 ↔ 뚜쥬루 빵돌가마마을")
	void isRelated_prefixTypo() {
		assertThat(SearchMissTextMatcher.isRelated("뚜쥬르", "뚜쥬루 빵돌가마마을")).isTrue();
		assertThat(SearchMissTextMatcher.isRelated("떰즈비", "떰즈업")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"hohaho2514@gmail.com", "연락처 010-1234-5678", "01012345678"})
	@DisplayName("이메일·전화번호는 개인정보이자 쓰레기 입력이다")
	void personalInfo(String keyword) {
		assertThat(SearchMissTextMatcher.containsPersonalInfo(keyword)).isTrue();
		assertThat(SearchMissTextMatcher.isJunk(keyword)).isTrue();
	}

	@Test
	@DisplayName("리포트에 싣기 전에 이메일·전화번호를 가린다")
	void maskPersonalInfo() {
		assertThat(SearchMissTextMatcher.maskPersonalInfo("hohaho2514@gmail.com")).isEqualTo("ho***@gmail.com");
		assertThat(SearchMissTextMatcher.maskPersonalInfo("연락 010-1234-5678")).isEqualTo("연락 010-****-5678");
		assertThat(SearchMissTextMatcher.maskPersonalInfo("콜버")).isEqualTo("콜버");
	}

	@Test
	@DisplayName("이름들의 공통 앞부분을 브랜드로 뽑고, 두 글자보다 짧으면 비운다")
	void commonPrefix() {
		assertThat(SearchMissTextMatcher.commonPrefix(List.of("뚜쥬루빵돌가마마을", "뚜쥬루과자점"))).isEqualTo("뚜쥬루");
		assertThat(SearchMissTextMatcher.commonPrefix(List.of("pnb풍년제과", "경기떡집"))).isEmpty();
		assertThat(SearchMissTextMatcher.commonPrefix(List.of())).isEmpty();
	}

	@Test
	@DisplayName("교정 대상은 지점명만 뗀 원래 표기다")
	void displayBaseName() {
		assertThat(SearchMissTextMatcher.displayBaseName("콜드버터베이크샵 탄방점")).isEqualTo("콜드버터베이크샵");
		assertThat(SearchMissTextMatcher.displayBaseName("버터크림팩토리 bucfac")).isEqualTo("버터크림팩토리 bucfac");
		assertThat(SearchMissTextMatcher.displayBaseName("안국점")).isEqualTo("안국점");
	}

	@Test
	@DisplayName("한글이 한 글자라도 있는지 본다 — 랜덤 영문 판정에 쓴다")
	void hasHangul() {
		assertThat(SearchMissTextMatcher.hasHangul("hhkthk")).isFalse();
		assertThat(SearchMissTextMatcher.hasHangul("도쿄 팡멪")).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"런던베이글뮤지엄 잠실점, 런던베이글뮤지엄",
		"런던베이글뮤지엄, 런던 베이글 뮤지엄 도산점",
		"어니언 성수, 어니언"
	})
	@DisplayName("지점명을 떼고 같거나 포함하면 같은 가게 이름이다")
	void isSameStoreName_true(String left, String right) {
		assertThat(SearchMissTextMatcher.isSameStoreName(left, right)).isTrue();
	}

	@Test
	@DisplayName("다른 가게 이름은 같은 가게로 보지 않는다")
	void isSameStoreName_false() {
		assertThat(SearchMissTextMatcher.isSameStoreName("어니언", "런던베이글뮤지엄")).isFalse();
	}

	@Test
	@DisplayName("띄어쓰기가 달라도 글에 들어 있으면 찾는다")
	void appearsIn() {
		assertThat(SearchMissTextMatcher.appearsIn("런던베이글뮤지엄", "오늘은 런던 베이글 뮤지엄 다녀옴")).isTrue();
		assertThat(SearchMissTextMatcher.appearsIn("런던베이글뮤지엄", "런베뮤 웨이팅 후기")).isFalse();
	}
}
