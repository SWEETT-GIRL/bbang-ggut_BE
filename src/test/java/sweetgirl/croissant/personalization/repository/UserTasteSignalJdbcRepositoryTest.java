package sweetgirl.croissant.personalization.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTasteSignalJdbcRepositoryTest {

	@Test
	@DisplayName("태그는 관대하게 읽는다 — 숫자만 남기고 null · 문자열 · 중첩은 버린다(검사 제출이 dataops 데이터 품질에 묶이지 않게)")
	void parseTags_keepsNumbersOnly() {
		assertThat(UserTasteSignalJdbcRepository.parseTags(
			"{\"카페형\": 0.9, \"혼빵\": 1, \"노포\": null, \"신상\": \"x\", \"대형\": {\"a\": 1}}"))
			.containsOnly(java.util.Map.entry("카페형", 0.9), java.util.Map.entry("혼빵", 1.0));
		assertThat(UserTasteSignalJdbcRepository.parseTags(null)).isEmpty();
		assertThat(UserTasteSignalJdbcRepository.parseTags(" ")).isEmpty();
	}

	@Test
	@DisplayName("JSON 객체가 아니면 그 회원의 계산만 실패한다")
	void parseTags_rejectsNonObject() {
		assertThatThrownBy(() -> UserTasteSignalJdbcRepository.parseTags("[1, 2]"))
			.isInstanceOf(IllegalStateException.class);
	}
}
