package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordRankingResponse;
import sweetgirl.croissant.ranking.service.RankChanges;

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

		BreadRecordRankingResponse response = BreadRecordRankingService.assemble(rows, ME, false, 1, null, null,
			RankChanges.unknown());

		assertThat(response.entries()).extracting(BreadRecordRankingResponse.Entry::nickname).containsExactly("호떡");
		assertThat(response.me().rank()).isEqualTo(3L);
		assertThat(response.me().next().gap()).isEqualTo(4); // 4 - 1 + 1
		assertThat(response.me().next().rank()).isEqualTo(1); // 4점 무리 전체를 넘는다
	}

	@Test
	@DisplayName("지난주 전국 주간 1 위였던 사람의 줄에만 왕관이 붙는다 — 이번 판의 등수와는 상관없다")
	void crowned() {
		List<RankingRowProjection> rows = List.of(
			row(3L, "호떡", 4, 1, 1),
			row(2L, "두쫀쿠", 3, 2, 2));

		BreadRecordRankingResponse response = BreadRecordRankingService.assemble(rows, ME, false, 100, null, null,
			RankChanges.unknown(), java.util.Set.of(2L));

		assertThat(response.entries()).extracting(BreadRecordRankingResponse.Entry::crowned)
			.containsExactly(false, true);
	}

	@Test
	@DisplayName("점수가 0이면 순위 밖, 1위면 다음 목표가 없다, 게스트는 내 자리가 없다")
	void edgeCases() {
		List<RankingRowProjection> rows = List.of(row(ME, "앙버터", 5, 1, 1));

		assertThat(
			BreadRecordRankingService.assemble(rows, ME, false, 100, null, null, RankChanges.unknown()).me().next())
			.isNull();
		assertThat(BreadRecordRankingService.assemble(List.of(), ME, false, 100, null, null, RankChanges.unknown()).me()
			.rank()).isNull();
		assertThat(BreadRecordRankingService.assemble(rows, ME, true, 100, null, null, RankChanges.unknown()).me())
			.isNull();
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

	@Test
	@DisplayName("어제 스냅샷이 있으면 줄마다 변동과 NEW 를 붙이고, 없으면 둘 다 null 이다")
	void rankChangesAttached() {
		List<RankingRowProjection> rows = List.of(row(3L, "호떡", 4, 1, 1), row(ME, "앙버터", 1, 2, 2));
		RankChanges known = RankChanges.of(java.util.Map.of(3L, 2));

		BreadRecordRankingResponse withChanges = BreadRecordRankingService.assemble(rows, ME, false, 10, null, null,
			known);
		BreadRecordRankingResponse unknown = BreadRecordRankingService.assemble(rows, ME, false, 10, null, null,
			RankChanges.unknown());

		assertThat(withChanges.entries().get(0).rankChange()).isEqualTo(1); // 2위 → 1위
		assertThat(withChanges.entries().get(0).isNew()).isFalse();
		assertThat(withChanges.entries().get(1).rankChange()).isNull();
		assertThat(withChanges.entries().get(1).isNew()).isTrue();
		assertThat(unknown.entries().get(0).rankChange()).isNull();
		assertThat(unknown.entries().get(0).isNew()).isNull();
	}
}
