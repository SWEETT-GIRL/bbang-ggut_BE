package sweetgirl.croissant.reward.service;

import java.util.List;
import java.util.Optional;

import sweetgirl.croissant.reward.entity.MemberAttendanceDay;

/**
 * 출석 7일판의 지금 자리. 어제와 오늘의 출석 행만 보면 정해진다.
 *
 * <p>하루는 쉬어도 된다 — 어제 안 찍었어도 그제 찍었으면 그제 칸에 이어진다(11/3: 출석자 60% 가 하루로 끝나 복구 길을 열었다).
 * 이틀 빠지면 1 일째로 돌아가고, 7 일을 채운 다음 날도 1 일째다(8 일째는 없다).
 *
 * @param doneToday    오늘 출석했는가
 * @param stampedDays  지금 판에 찍힌 칸 수. 오늘 안 했으면 어제까지 이어진 칸(끊겼거나 어제가 7 일째면 0)
 * @param nextBoardDay 다음에 찍힐 칸. 오늘 안 했으면 오늘 찍을 칸, 했으면 내일 찍을 칸
 */
public record AttendanceBoard(boolean doneToday, int stampedDays, int nextBoardDay) {

	static final int BOARD_SIZE = 7;

	public static AttendanceBoard of(Optional<MemberAttendanceDay> yesterday, Optional<MemberAttendanceDay> today) {
		return of(Optional.empty(), yesterday, today);
	}

	/** 어제가 비어 있으면 그제 칸에 이어진다(하루 유예). 그제도 비어 있으면 끊긴 것이다. */
	public static AttendanceBoard of(
			Optional<MemberAttendanceDay> dayBeforeYesterday,
			Optional<MemberAttendanceDay> yesterday,
			Optional<MemberAttendanceDay> today) {
		if (today.isPresent()) {
			int boardDay = today.get().getBoardDay();
			return new AttendanceBoard(true, boardDay, following(boardDay));
		}
		int carried = yesterday.or(() -> dayBeforeYesterday)
			.map(MemberAttendanceDay::getBoardDay)
			.filter(boardDay -> boardDay < BOARD_SIZE)
			.orElse(0);
		return new AttendanceBoard(false, carried, carried + 1);
	}

	/** 그 칸의 보상. {@code rewards} 는 1 일째부터 7 일째까지다. */
	public static int rewardOf(int boardDay, List<Integer> rewards) {
		return rewards.get(boardDay - 1);
	}

	/** 그 칸 다음 날 찍힐 칸. */
	public static int following(int boardDay) {
		return boardDay >= BOARD_SIZE ? 1 : boardDay + 1;
	}
}
