package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberRankingRegionTest {

	private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 1, 10, 0);

	@Test
	@DisplayName("다른 곳에서 다시 인증하면 바로 바뀌고 인증 시각이 새로 찍힌다 — 잠금 없음")
	void reverifyElsewhere() {
		MemberRankingRegion region = MemberRankingRegion.verified(1L, "서울", "동대문구", FIRST);

		region.reverify("부산", "해운대구", FIRST.plusMinutes(5));

		assertThat(region.getSido()).isEqualTo("부산");
		assertThat(region.getSigungu()).isEqualTo("해운대구");
		assertThat(region.getVerifiedAt()).isEqualTo(FIRST.plusMinutes(5));
		assertThat(region.isVerified()).isTrue();
	}

	@Test
	@DisplayName("같은 곳을 다시 인증하면 인증 시각만 새로 찍힌다")
	void reverifySamePlace() {
		MemberRankingRegion region = MemberRankingRegion.verified(1L, "서울", "동대문구", FIRST);

		region.reverify("서울", "동대문구", FIRST.plusDays(3));

		assertThat(region.getSigungu()).isEqualTo("동대문구");
		assertThat(region.getVerifiedAt()).isEqualTo(FIRST.plusDays(3));
	}

	@Test
	@DisplayName("시·도만 고르던 때의 행은 시·군·구가 없어 인증한 것으로 치지 않는다")
	void legacyRowIsNotVerified() {
		MemberRankingRegion region = MemberRankingRegion.verified(1L, "서울", null, FIRST);

		assertThat(region.isVerified()).isFalse();
	}
}
