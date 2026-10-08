package sweetgirl.croissant.retention.policy;

import java.time.LocalDate;

/** 내부 정책 평가 결과. 동의·발송 제한을 통과했다는 의미가 아니며 그대로 전송하면 안 된다. */
public record RetentionMessage(
		RetentionMessageKey key,
		String title,
		String body,
		RetentionDestination destination,
		Integer nextStreakDays,
		LocalDate calendarDate) {
}
