package sweetgirl.croissant.retention.policy;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.breadlog.dto.response.BreadRecordActivityResponse;

/**
 * 첫 주 메시지 선택만 수행한다. 스케줄러·FCM·우편함과 연결하지 않는다.
 * 발송 전 동의, 지원 앱 버전, 빈도 제한과 최신 활동을 별도로 재검증해야 한다.
 */
@Component
public class RetentionMessagePolicy {

	public Optional<RetentionMessage> select(
			RetentionPolicyContext context, BreadRecordActivityResponse activity, LocalDate today) {
		if (!today.equals(activity.asOfDate()) || !"Asia/Seoul".equals(activity.timeZone())
			|| !BreadRecordActivityResponse.POLICY_VERSION.equals(activity.policyVersion())) {
			return Optional.empty();
		}
		return switch (context.daySinceFirstSeen()) {
			case 1 -> firstDay(context, activity);
			case 3 -> thirdDay(context, activity);
			case 5 -> fifthDay(context, activity);
			default -> Optional.empty();
		};
	}

	private Optional<RetentionMessage> firstDay(RetentionPolicyContext context, BreadRecordActivityResponse activity) {
		if (activity.recordedToday()) {
			return Optional.empty();
		}
		if (activity.currentStreakDays() > 0) {
			return streak(activity);
		}
		if (activity.normalRecordCount() > 0) {
			return Optional.empty();
		}
		boolean first = !context.hasEverRecorded() && activity.activeRecordCount() == 0;
		return Optional.of(new RetentionMessage(
			first ? RetentionMessageKey.FIRST_RECORD : RetentionMessageKey.FIRST_NORMAL_RECORD,
			first ? "오늘 먹은 빵 있어? 🥐" : "이번엔 네가 먹은 빵을 남겨볼까? 🍞",
			first ? "첫 빵로그를 남기고, 오늘부터 기록 1일 차!" : "빵로그를 남기면 네 빵탑에 빵이 하나 더 쌓여!",
			RetentionDestination.RECORD_CREATE, null, null));
	}

	private Optional<RetentionMessage> thirdDay(RetentionPolicyContext context, BreadRecordActivityResponse activity) {
		if (!context.guest() && activity.normalRecordCount() > 0 && context.rankingEligible()
			&& !context.rankingAlreadyCreated() && !context.rankingMessageSent()) {
			return Optional.of(new RetentionMessage(RetentionMessageKey.MY_BREAD_RANKING,
				"먹어본 빵, 맛있었던 순서로 정리해봐 🏆", "내 빵순위에서 빵 종류별로 가게 순위를 매길 수 있어!",
				RetentionDestination.MY_BREAD_RANKING, null, null));
		}
		return context.recordedAfterD1Message() ? streak(activity) : Optional.empty();
	}

	private Optional<RetentionMessage> fifthDay(RetentionPolicyContext context, BreadRecordActivityResponse activity) {
		if (context.calendarMessageSent() || activity.normalRecordCount() == 0
			|| activity.latestNormalRecordId() == null || activity.latestNormalEatenDate() == null) {
			return Optional.empty();
		}
		return Optional.of(new RetentionMessage(RetentionMessageKey.RECORD_CALENDAR,
			"어떤 빵을 먹었는지 달력에서 확인해봐 📅", "날짜를 누르면 그날 남긴 빵로그를 다시 볼 수 있어!",
			RetentionDestination.RECORD_CALENDAR, null, activity.latestNormalEatenDate()));
	}

	private Optional<RetentionMessage> streak(BreadRecordActivityResponse activity) {
		if (activity.recordedToday() || activity.currentStreakDays() < 1
			|| activity.nextStreakDays() != activity.currentStreakDays() + 1) {
			return Optional.empty();
		}
		return Optional.of(new RetentionMessage(RetentionMessageKey.STREAK_CONTINUE,
			"오늘 남기면 " + activity.nextStreakDays() + "일 연속이야! 🔥",
			"오늘 먹은 빵 있어? 빵로그로 남기고 기록을 이어가 보자!",
			RetentionDestination.RECORD_CREATE, activity.nextStreakDays(), null));
	}
}
