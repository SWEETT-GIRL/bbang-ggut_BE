package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion;
import sweetgirl.croissant.breadlog.repository.BreadRecordRankingRepository;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.ranking.snapshot.RankedSubject;
import sweetgirl.croissant.ranking.snapshot.SnapshotBoard;

@ExtendWith(MockitoExtension.class)
class PeopleRankingSnapshotSourceTest {

	private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);

	@Mock
	private BreadRecordRankingRepository rankingRepository;
	@Mock
	private MemberRankingRegionRepository regionRepository;

	@InjectMocks
	private PeopleRankingSnapshotSource source;

	@Test
	@DisplayName("4판 전국 + WEEKLY·RECORDS 는 인증된 구마다 한 판씩 — 줄 읽기는 미뤄 둔다")
	void boardsPerRegion() {
		given(regionRepository.findVerifiedRegions())
			.willReturn(List.of(new VerifiedRegion("서울", "동대문구"), new VerifiedRegion("경기", "수원시")));

		List<SnapshotBoard> boards = source.boards(SNAPSHOT_DATE);

		assertThat(boards).hasSize(8);
		assertThat(boards).extracting(SnapshotBoard::region)
			.containsExactly("", "서울_동대문구", "경기_수원시", "", "서울_동대문구", "경기_수원시", "", "");
		assertThat(boards).extracting(SnapshotBoard::board)
			.containsExactly("WEEKLY", "WEEKLY", "WEEKLY", "RECORDS", "RECORDS", "RECORDS", "REGIONS", "SHARE");
		verify(rankingRepository, org.mockito.Mockito.never()).findRecordRanking(any(), any(), any(), any(), anyInt());
	}

	@Test
	@DisplayName("주간 판은 스냅샷 날짜가 속한 주의 월요일부터, 100위 밖은 버린다")
	void weeklyRowsUseWeekOfSnapshotDate() {
		given(regionRepository.findVerifiedRegions()).willReturn(List.of());
		given(rankingRepository.findRecordRanking(LocalDateTime.of(2026, 9, 28, 0, 0), null, null, null, 100))
			.willReturn(List.of(row(7L, 1), row(8L, 101)));

		List<RankedSubject> rows = source.boards(SNAPSHOT_DATE).get(0).rows().get();

		assertThat(rows).containsExactly(new RankedSubject(7L, 1));
	}

	@Test
	@DisplayName("내 지역 판은 시·도와 시·군·구를 함께 넘긴다")
	void localRowsPassRegion() {
		given(regionRepository.findVerifiedRegions()).willReturn(List.of(new VerifiedRegion("서울", "동대문구")));
		given(rankingRepository.findRecordRanking(isNull(), any(), any(), isNull(), anyInt()))
			.willReturn(new ArrayList<>());

		List<SnapshotBoard> boards = source.boards(SNAPSHOT_DATE);
		boards.get(3).rows().get(); // RECORDS 지역 판

		verify(rankingRepository).findRecordRanking(null, "서울", "동대문구", null, 100);
	}

	private static RankingRowProjection row(Long memberId, long rank) {
		return new RankingRowProjection() {
			public Long getMemberId() {
				return memberId;
			}

			public String getNickname() {
				return "n";
			}

			public String getSelectedCharacter() {
				return null;
			}

			public Long getScore() {
				return 1L;
			}

			public Long getRank() {
				return rank;
			}

			public Long getScoreFirstRank() {
				return rank;
			}
		};
	}
}
