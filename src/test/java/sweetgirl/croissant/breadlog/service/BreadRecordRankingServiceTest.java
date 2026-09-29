package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse;

class BreadRecordRankingServiceTest {

	private static final Long ME = 8L;

	@Test
	@DisplayName("이번 주는 월요일 0시부터다")
	void weekStartsMonday() {
		// 2026-09-29 는 화요일
		assertThat(BreadRecordRankingService.weekStart(LocalDate.of(2026, 9, 29)))
			.isEqualTo(LocalDateTime.of(2026, 9, 28, 0, 0));
		assertThat(BreadRecordRankingService.weekStart(LocalDate.of(2026, 9, 28)))
			.isEqualTo(LocalDateTime.of(2026, 9, 28, 0, 0));
	}

	@Test
	@DisplayName("내가 목록 밖이면 목록엔 안 넣고, 바로 위 사람의 점수 무리를 넘는 목표를 준다")
	void nextGoalBeyondLimit() {
		// 상위 1 명만 요청 — 쿼리는 1위, 내 바로 위 2위, 나 3위를 돌려준다. 1·2위는 같은 점수 무리
		List<RankingRowProjection> rows = List.of(
			row(3L, "호떡", 4, 1, 1),
			row(2L, "두쫀쿠", 4, 2, 1),
			row(ME, "앙버터", 1, 3, 3));

		BreadRecordRankingResponse response = BreadRecordRankingService.assemble(rows, ME, false, 1, null);

		assertThat(response.entries()).extracting(BreadRecordRankingResponse.Entry::nickname).containsExactly("호떡");
		assertThat(response.me().rank()).isEqualTo(3L);
		assertThat(response.me().next().gap()).isEqualTo(4); // 4 - 1 + 1
		assertThat(response.me().next().rank()).isEqualTo(1); // 4점 무리 전체를 넘는다
	}

	@Test
	@DisplayName("점수가 0이면 순위 밖, 1위면 다음 목표가 없다, 게스트는 내 자리가 없다")
	void edgeCases() {
		List<RankingRowProjection> rows = List.of(row(ME, "앙버터", 5, 1, 1));

		assertThat(BreadRecordRankingService.assemble(rows, ME, false, 100, null).me().next()).isNull();
		assertThat(BreadRecordRankingService.assemble(List.of(), ME, false, 100, null).me().rank()).isNull();
		assertThat(BreadRecordRankingService.assemble(rows, ME, true, 100, null).me()).isNull();
	}

	private static RankingRowProjection row(Long memberId, String nickname, long score, long rank, long firstRank) {
		return new RankingRowProjection() {
			public Long getMemberId() {
				return memberId;
			}

			public String getNickname() {
				return nickname;
			}

			public String getSelectedCharacter() {
				return null;
			}

			public Long getScore() {
				return score;
			}

			public Long getRank() {
				return rank;
			}

			public Long getScoreFirstRank() {
				return firstRank;
			}
		};
	}
}
