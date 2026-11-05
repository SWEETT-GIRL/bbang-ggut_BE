package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.reward.entity.MemberAttendanceDay;

@DisplayName("AttendanceBoard — 하루 유예")
class AttendanceBoardTest {

	private Optional<MemberAttendanceDay> stamped(int boardDay) {
		MemberAttendanceDay day = mock(MemberAttendanceDay.class);
		given(day.getBoardDay()).willReturn(boardDay);
		return Optional.of(day);
	}

	@Test
	@DisplayName("어제 찍었으면 어제 칸에 이어진다 — 그제는 보지 않는다")
	void continuesFromYesterday() {
		AttendanceBoard board = AttendanceBoard.of(stamped(1), stamped(3), Optional.empty());

		assertThat(board.doneToday()).isFalse();
		assertThat(board.stampedDays()).isEqualTo(3);
		assertThat(board.nextBoardDay()).isEqualTo(4);
	}

	@Test
	@DisplayName("어제 안 찍었어도 그제 찍었으면 그제 칸에 이어진다")
	void oneDayGrace() {
		AttendanceBoard board = AttendanceBoard.of(stamped(3), Optional.empty(), Optional.empty());

		assertThat(board.stampedDays()).isEqualTo(3);
		assertThat(board.nextBoardDay()).isEqualTo(4);
	}

	@Test
	@DisplayName("이틀 빠지면 1 일째다")
	void twoDaysBreak() {
		AttendanceBoard board = AttendanceBoard.of(Optional.empty(), Optional.empty(), Optional.empty());

		assertThat(board.stampedDays()).isZero();
		assertThat(board.nextBoardDay()).isEqualTo(1);
	}

	@Test
	@DisplayName("그제 7 일째를 채웠고 어제 쉬었으면 새 판 1 일째다")
	void gracedAfterFullBoardStartsOver() {
		AttendanceBoard board = AttendanceBoard.of(stamped(7), Optional.empty(), Optional.empty());

		assertThat(board.stampedDays()).isZero();
		assertThat(board.nextBoardDay()).isEqualTo(1);
	}

	@Test
	@DisplayName("오늘 찍었으면 오늘 칸과 내일 칸이다 — 어제 · 그제는 상관없다")
	void doneToday() {
		AttendanceBoard board = AttendanceBoard.of(Optional.empty(), Optional.empty(), stamped(7));

		assertThat(board.doneToday()).isTrue();
		assertThat(board.stampedDays()).isEqualTo(7);
		assertThat(board.nextBoardDay()).isEqualTo(1);
	}
}
