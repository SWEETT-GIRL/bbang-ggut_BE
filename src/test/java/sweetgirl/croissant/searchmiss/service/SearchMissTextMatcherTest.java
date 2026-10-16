package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;

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

	@Test
	@DisplayName("짧은 말은 한 글자 오타까지만 허용한다")
	void isTypo_shortKeyword() {
		assertThat(SearchMissTextMatcher.isTypo("어니언", "어니온")).isTrue();
		assertThat(SearchMissTextMatcher.isTypo("어니언", "어나온")).isFalse();
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
