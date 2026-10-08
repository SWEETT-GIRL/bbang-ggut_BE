package sweetgirl.croissant.retention.policy;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sweetgirl.croissant.breadlog.dto.response.BreadRecordActivityResponse;

class RetentionMessagePolicyTest {

	private final RetentionMessagePolicy policy = new RetentionMessagePolicy();
	private static final LocalDate TODAY = LocalDate.parse("2026-10-08");

	@Test
	void tutorialYesterdayUsesActualNextDayNotFirstDayCopy() {
		var message = policy.select(context(1, true, true, false, false), activity(false, 1, 1, 0), TODAY)
			.orElseThrow();
		assertThat(message.key()).isEqualTo(RetentionMessageKey.STREAK_CONTINUE);
		assertThat(message.title()).isEqualTo("오늘 남기면 2일 연속이야! 🔥");
		assertThat(message.nextStreakDays()).isEqualTo(2);
		assertThat(message.destination()).isEqualTo(RetentionDestination.RECORD_CREATE);
	}

	@Test
	void firstEverRecordAndDeletedHistoryHaveDifferentCopy() {
		var empty = activity(false, 0, 0, 0);
		assertThat(policy.select(context(1, true, false, false, false), empty, TODAY).orElseThrow().key())
			.isEqualTo(RetentionMessageKey.FIRST_RECORD);
		assertThat(policy.select(context(1, true, true, false, false), empty, TODAY).orElseThrow().key())
			.isEqualTo(RetentionMessageKey.FIRST_NORMAL_RECORD);
		assertThat(policy.select(context(1, true, false, false, false), activity(false, 0, 1, 0), TODAY)
			.orElseThrow().key()).isEqualTo(RetentionMessageKey.FIRST_NORMAL_RECORD);
	}

	@Test
	void alreadyRecordedTodaySkipsReminderButNotRankingOrCalendar() {
		var written = activity(true, 3, 4, 3);
		assertThat(policy.select(context(1, false, true, true, false), written, TODAY)).isEmpty();
		assertThat(policy.select(context(3, false, true, true, false), written, TODAY).orElseThrow().key())
			.isEqualTo(RetentionMessageKey.MY_BREAD_RANKING);
		assertThat(policy.select(context(5, false, true, false, false), written, TODAY).orElseThrow().key())
			.isEqualTo(RetentionMessageKey.RECORD_CALENDAR);
	}

	@Test
	void noRestartCampaignForPastNormalRecords() {
		assertThat(policy.select(context(1, false, true, false, false), activity(false, 0, 1, 1), TODAY)).isEmpty();
	}

	@Test
	void guestAndTutorialOnlyAndIneligibleStoresCannotReceiveRanking() {
		assertThat(policy.select(context(3, true, true, true, false), activity(false, 1, 2, 1), TODAY)).isEmpty();
		assertThat(policy.select(context(3, false, true, true, false), activity(false, 1, 1, 0), TODAY)).isEmpty();
		assertThat(policy.select(context(3, false, true, false, false), activity(false, 1, 2, 1), TODAY)).isEmpty();
	}

	@Test
	void thirdDayFallbackRequiresActualRecordAfterD1AndActiveStreak() {
		var context = context(3, false, true, false, true);
		assertThat(policy.select(context, activity(false, 2, 3, 2), TODAY).orElseThrow().key())
			.isEqualTo(RetentionMessageKey.STREAK_CONTINUE);
		assertThat(policy.select(context, activity(false, 0, 3, 2), TODAY)).isEmpty();
		assertThat(policy.select(context, activity(true, 3, 4, 3), TODAY)).isEmpty();
	}

	@Test
	void rankingAndCalendarAreNotRepeated() {
		var written = activity(false, 2, 3, 2);
		assertThat(policy.select(new RetentionPolicyContext(3, false, true, true, false, true, false, false),
			written, TODAY)).isEmpty();
		assertThat(policy.select(new RetentionPolicyContext(3, false, true, true, true, false, false, false),
			written, TODAY)).isEmpty();
		assertThat(policy.select(new RetentionPolicyContext(5, false, true, true, false, false, true, false),
			written, TODAY)).isEmpty();
	}

	@Test
	void calendarTargetsEatenDateAndRequiresNormalRecord() {
		var context = context(5, false, true, false, false);
		assertThat(policy.select(context, activity(false, 1, 1, 0), TODAY)).isEmpty();
		var message = policy.select(context, activity(false, 2, 3, 2), TODAY).orElseThrow();
		assertThat(message.calendarDate()).isEqualTo(TODAY.minusDays(10));
		assertThat(message.destination()).isEqualTo(RetentionDestination.RECORD_CALENDAR);
	}

	@Test
	void previousDaySnapshotCannotBeUsed() {
		assertThat(policy.select(context(1, true, true, false, false), activity(false, 1, 1, 0), TODAY.plusDays(1)))
			.isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 2, 4, 6, 7, 8, 30})
	void onlyScheduledOpportunitiesCanSelectMessage(int day) {
		assertThat(policy.select(context(day, false, true, true, true), activity(false, 2, 3, 2), TODAY)).isEmpty();
	}

	private RetentionPolicyContext context(int day, boolean guest, boolean ever, boolean ranking, boolean afterD1) {
		return new RetentionPolicyContext(day, guest, ever, ranking, false, false, false, afterD1);
	}

	private BreadRecordActivityResponse activity(boolean recordedToday, int streak, long active, long normal) {
		return new BreadRecordActivityResponse(TODAY, "Asia/Seoul", OffsetDateTime.parse("2026-10-08T19:30:00+09:00"),
			recordedToday, streak, recordedToday ? streak : streak + 1, active, normal,
			active > 0 ? TODAY.minusDays(1) : null, normal > 0 ? 10L : null,
			normal > 0 ? TODAY.minusDays(10) : null, BreadRecordActivityResponse.POLICY_VERSION);
	}
}
