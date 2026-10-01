package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.dto.projection.RankingRowProjection;
import sweetgirl.croissant.breadlog.dto.projection.VerifiedRegion;
import sweetgirl.croissant.breadlog.repository.BreadRecordRankingRepository;
import sweetgirl.croissant.breadlog.repository.MemberRankingRegionRepository;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.BoardRow;
import sweetgirl.croissant.breadranking.repository.BreadBoardJdbcRepository.CategoryVotersRow;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.breadranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.breadranking.repository.RankingSnapshotJdbcRepository.RankedSubject;
import sweetgirl.croissant.menu.enums.MenuKind;

@ExtendWith(MockitoExtension.class)
class RankingSnapshotCommandServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
	private static final LocalDate YESTERDAY = LocalDate.of(2026, 9, 30);

	@Mock
	private RankingSnapshotJdbcRepository snapshotRepository;
	@Mock
	private BreadRecordRankingRepository rankingRepository;
	@Mock
	private MemberRankingRegionRepository regionRepository;
	@Mock
	private BreadBoardJdbcRepository boardRepository;
	@Mock
	private BreadCategoryRepository categoryRepository;

	@InjectMocks
	private RankingSnapshotCommandService service;

	@Test
	@DisplayName("다른 인스턴스가 돌고 있으면(락 실패) 아무것도 안 하고 -1")
	void skipsWhenLocked() {
		given(snapshotRepository.tryLock(RankingSnapshotCommandService.SNAPSHOT_LOCK_KEY)).willReturn(false);

		assertThat(service.snapshot(TODAY)).isEqualTo(-1);
		verify(snapshotRepository, never()).deleteByDate(any());
	}

	@Test
	@DisplayName("어제 날짜로 — 사람 4판 전국 + 내 지역 판은 인증된 구마다, 빵 판은 열린 카테고리만, 14일 지난 건 지운다")
	void snapshotsAllBoards() {
		given(snapshotRepository.tryLock(anyLong())).willReturn(true);
		given(snapshotRepository.insertAll(any(), anyString(), anyLong(), anyString(), any()))
			.willAnswer(invocation -> ((List<?>)invocation.getArgument(4)).size());
		given(regionRepository.findVerifiedRegions()).willReturn(List.of(new VerifiedRegion("서울", "동대문구")));
		given(rankingRepository.findRecordRanking(any(), any(), any(), isNull(), eq(100))).willReturn(rows(7L, 8L));
		given(rankingRepository.findRegionRanking(isNull(), eq(100))).willReturn(rows(7L));
		given(rankingRepository.findShareRanking(isNull(), eq(100))).willReturn(rows(8L));
		BreadCategory salt = category(1L, MenuKind.소금빵);
		BreadCategory bagel = category(2L, MenuKind.베이글);
		given(boardRepository.findCategoriesByVoters(1)).willReturn(List.of(
			new CategoryVotersRow(1L, 5, 5), new CategoryVotersRow(2L, 2, 2)));
		given(categoryRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(salt, bagel));
		given(boardRepository.findBoard(1L, "소금빵", 100)).willReturn(List.of(
			new BoardRow(10L, 3, LocalDateTime.of(2026, 9, 30, 12, 0), 0, 0, null, 5),
			new BoardRow(20L, 2, LocalDateTime.of(2026, 9, 29, 12, 0), 0, 0, null, 5)));
		given(boardRepository.findBoard(2L, "베이글", 100)).willReturn(List.of(
			new BoardRow(30L, 2, LocalDateTime.of(2026, 9, 30, 12, 0), 0, 0, null, 2)));

		int saved = service.snapshot(TODAY);

		verify(snapshotRepository).deleteByDate(YESTERDAY);
		// 주간 판은 스냅샷 날짜(9/30 수)가 속한 주의 월요일부터
		verify(rankingRepository).findRecordRanking(LocalDateTime.of(2026, 9, 28, 0, 0), null, null, null, 100);
		verify(rankingRepository).findRecordRanking(LocalDateTime.of(2026, 9, 28, 0, 0), "서울", "동대문구", null, 100);
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("WEEKLY"), eq(0L), eq(""), any());
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("WEEKLY"), eq(0L), eq("서울_동대문구"), any());
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("RECORDS"), eq(0L), eq("서울_동대문구"), any());
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("REGIONS"), eq(0L), eq(""), any());
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("SHARE"), eq(0L), eq(""), any());
		verify(snapshotRepository, never()).insertAll(any(), eq("REGIONS"), anyLong(), eq("서울_동대문구"), any());
		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("BREAD"), eq(1L), eq(""), argThat(
			(List<RankedSubject> list) -> list.size() == 2 && list.get(0).subjectId() == 10L && list.get(0).rank() == 1
				&& list.get(1).subjectId() == 20L && list.get(1).rank() == 2));
		verify(snapshotRepository, never()).insertAll(any(), eq("BREAD"), eq(2L), anyString(), any());
		verify(snapshotRepository).deleteBefore(TODAY.minusDays(14));
		// 전국 4판(2+2+1+1) + 구 2판(2+2) + 빵 판 2
		assertThat(saved).isEqualTo(12);
	}

	@Test
	@DisplayName("상위 100 밖 줄은 남기지 않는다")
	void dropsBeyondTop() {
		given(snapshotRepository.tryLock(anyLong())).willReturn(true);
		given(snapshotRepository.insertAll(any(), anyString(), anyLong(), anyString(), any()))
			.willAnswer(invocation -> ((List<?>)invocation.getArgument(4)).size());
		given(rankingRepository.findRecordRanking(any(), any(), any(), isNull(), anyInt()))
			.willReturn(List.of(row(7L, 1), row(8L, 101)));

		service.snapshot(TODAY);

		verify(snapshotRepository).insertAll(eq(YESTERDAY), eq("RECORDS"), eq(0L), eq(""),
			argThat((List<RankedSubject> list) -> list.size() == 1 && list.get(0).subjectId() == 7L));
	}

	private static List<RankingRowProjection> rows(Long... memberIds) {
		List<RankingRowProjection> rows = new java.util.ArrayList<>();
		for (int index = 0; index < memberIds.length; index++) {
			rows.add(row(memberIds[index], index + 1));
		}
		return rows;
	}

	private static RankingRowProjection row(Long memberId, long rank) {
		return new RankingRowProjection() {
			public Long getMemberId() {
				return memberId;
			}

			public String getNickname() {
				return "n" + memberId;
			}

			public String getSelectedCharacter() {
				return null;
			}

			public Long getScore() {
				return 10L;
			}

			public Long getRank() {
				return rank;
			}

			public Long getScoreFirstRank() {
				return rank;
			}
		};
	}

	private static BreadCategory category(Long id, MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", id);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}
}
