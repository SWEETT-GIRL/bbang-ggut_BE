package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.alarm.dto.response.NotificationResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse.ActorResponse;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;

@DisplayName("알림함 묶음")
class NotificationGroupingTest {

	private NotificationResponse row(long id, NotificationType type, NotificationTargetType targetType, Long targetId,
			Long actorId, LocalDateTime at, boolean read) {
		return new NotificationResponse(id, type, read, at, actorId, "회원" + actorId, targetType, targetId, "밀도",
			null, null, null, "TODAY", List.of(new ActorResponse(actorId, "회원" + actorId, null)), 1);
	}

	@Test
	@DisplayName("같은 날 · 같은 종류 · 같은 대상이 이어지면 한 줄로, 얼굴은 2개까지 · 인원은 전부 · 읽음은 전부 읽었을 때만")
	void groupsConsecutiveLikes() {
		LocalDateTime at = LocalDateTime.of(2026, 10, 22, 10, 0);
		List<NotificationResponse> rows = List.of(
			row(1, NotificationType.RECORD_LIKE, NotificationTargetType.RECORD, 10L, 11L, at, true),
			row(2, NotificationType.RECORD_LIKE, NotificationTargetType.RECORD, 10L, 12L, at.minusHours(1), false),
			row(3, NotificationType.RECORD_LIKE, NotificationTargetType.RECORD, 10L, 13L, at.minusHours(2), true),
			row(4, NotificationType.RECORD_LIKE, NotificationTargetType.RECORD, 10L, 14L, at.minusDays(1), true),
			row(5, NotificationType.RECORD_COMMENT, NotificationTargetType.COMMENT, 100L, 11L, at, false),
			row(6, NotificationType.RECORD_COMMENT, NotificationTargetType.COMMENT, 101L, 12L, at, false));

		List<NotificationResponse> grouped = NotificationService.groupCommunityRows(rows);

		assertThat(grouped).extracting(NotificationResponse::notificationId).containsExactly(1L, 4L, 5L, 6L);
		NotificationResponse likes = grouped.get(0);
		assertThat(likes.actorCount()).isEqualTo(3);
		assertThat(likes.actors()).extracting(ActorResponse::memberId).containsExactly(11L, 12L);
		assertThat(likes.read()).isFalse();
		assertThat(grouped.get(1).actorCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("그룹 라벨은 오늘 / 이번 주(7일) / 그 전")
	void groupLabel() {
		LocalDate today = LocalDate.of(2026, 10, 22);
		assertThat(NotificationResponse.groupOf(today.atTime(9, 0), today)).isEqualTo("TODAY");
		assertThat(NotificationResponse.groupOf(today.minusDays(6).atTime(9, 0), today)).isEqualTo("THIS_WEEK");
		assertThat(NotificationResponse.groupOf(today.minusDays(7).atTime(9, 0), today)).isEqualTo("EARLIER");
		assertThat(NotificationResponse.groupOf(null, today)).isEqualTo("EARLIER");
	}
}
