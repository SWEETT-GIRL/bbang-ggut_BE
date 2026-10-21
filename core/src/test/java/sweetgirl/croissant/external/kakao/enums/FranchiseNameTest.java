package sweetgirl.croissant.external.kakao.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FranchiseNameTest {

	@Test
	@DisplayName("커피니 프랜차이즈는 차단 대상이다")
	void matches_true_whenCoffeeniBranch() {
		assertThat(FranchiseName.matches("커피니 역삼점")).isTrue();
		assertThat(FranchiseName.matches("커피니")).isTrue();
	}

	@Test
	@DisplayName("핫브레드 프랜차이즈는 차단 대상이다")
	void matches_true_whenHotBreadBranch() {
		assertThat(FranchiseName.matches("핫브레드 강남점")).isTrue();
		assertThat(FranchiseName.matches("핫브레드")).isTrue();
	}

	@Test
	@DisplayName("개인 베이커리는 차단하지 않는다")
	void matches_false_whenIndependentBakery() {
		assertThat(FranchiseName.matches("동네빵집")).isFalse();
		assertThat(FranchiseName.matches("우리동네 베이커리")).isFalse();
	}
}
