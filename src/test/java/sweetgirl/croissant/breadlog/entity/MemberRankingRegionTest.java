package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberRankingRegionTest {

	private static final LocalDateTime FIRST = LocalDateTime.of(2026, 9, 29, 10, 0);

	@Test
	@DisplayName("처음 정하고 24시간 안에는 바로 바꿀 수 있다")
	void freeFixWithinFirstDay() {
		MemberRankingRegion region = MemberRankingRegion.first(1L, "서울", FIRST);

		assertThat(region.nextChangeableAt(FIRST.plusHours(23))).isNull();
	}

	@Test
	@DisplayName("처음 정하고 하루가 지나면 30일을 기다려야 한다")
	void cooldownAfterFirstDay() {
		MemberRankingRegion region = MemberRankingRegion.first(1L, "서울", FIRST);

		assertThat(region.nextChangeableAt(FIRST.plusDays(2))).isEqualTo(FIRST.plusDays(30));
		assertThat(region.nextChangeableAt(FIRST.plusDays(30))).isNull();
	}

	@Test
	@DisplayName("첫날 정정을 쓰고 나면 그때부터 30일이다")
	void cooldownAfterFreeFix() {
		MemberRankingRegion region = MemberRankingRegion.first(1L, "서울", FIRST);
		LocalDateTime fixedAt = FIRST.plusHours(1);

		region.change("경기", fixedAt);

		assertThat(region.getSido()).isEqualTo("경기");
		assertThat(region.getChangeCount()).isEqualTo(2);
		assertThat(region.nextChangeableAt(fixedAt.plusHours(1))).isEqualTo(fixedAt.plusDays(30));
	}
}
