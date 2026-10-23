package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.member.entity.Member;

/**
 * 알림 한 줄.
 *
 * <p>문구를 누가 갖느냐가 타입마다 다르다. 저장·좋아요 같은 활동 알림은 재료(actorNickname,
 * targetTitle)만 내리고 앱이 조립한다 — 서버가 문장을 박아 내리면 앱에서 못 고친다.
 * 공지·제보 결과는 반대로 서버가 title·body를 그대로 준다. 그래야 앱 배포 없이 문구를 고칠 수 있다.
 *
 * <p>필드 이름은 옛 모양 그대로다. 저장 구조가 셋으로 갈렸어도 앱이 그것을 알 이유가 없다.
 * {@code notificationId}가 이제 우편함 줄의 id인 것도 앱에는 보이지 않는다 — 목록에서 받은 id를
 * 읽음 처리에 그대로 돌려주면 된다는 규칙이 그대로다.
 */
public record NotificationResponse(
		Long notificationId,
		NotificationType type,
		boolean read,
		LocalDateTime notifiedAt,

		/** 탈퇴했으면 null. 공지처럼 사람이 한 일이 아니면 애초에 없다 */
		Long actorId,
		String actorNickname,

		NotificationTargetType targetType,
		Long targetId,
		String targetTitle,

		/** 서버가 문구를 갖는 알림용. 활동 알림에는 없다 */
		String title,
		String body,
		String link,

		/** 알림함 묶음(2026-10-22): TODAY · THIS_WEEK · EARLIER(KST). 앱은 그룹 헤더만 그린다 */
		String group,
		/** 같은 날 · 같은 종류 · 같은 대상을 한 줄로 묶었을 때 얼굴 2개까지. 사람이 한 일이 아니면 빈 배열 */
		List<ActorResponse> actors,
		/** 묶인 인원. 묶이지 않은 줄은 1(사람이 한 일이 아니면 0) */
		int actorCount) {

	public static final String GROUP_TODAY = "TODAY";
	public static final String GROUP_THIS_WEEK = "THIS_WEEK";
	public static final String GROUP_EARLIER = "EARLIER";
	public static final int MAX_ACTORS = 2;

	public record ActorResponse(Long memberId, String nickname, String characterCode) {
	}

	/** 탈퇴한 사람의 행동도 일어난 일이다 — 줄을 없애는 대신 이름만 가린다. */
	private static final String WITHDRAWN_NICKNAME = "알 수 없음";

	public static NotificationResponse from(NotificationRecipient recipient) {
		Notification notification = recipient.getNotification();
		Member actor = notification.getActor();
		return new NotificationResponse(
			recipient.getId(),
			notification.getType(),
			recipient.isRead(),
			recipient.getNotifiedAt(),
			actorId(actor),
			actorNickname(actor),
			notification.getTargetType(),
			notification.getTargetId(),
			notification.getTargetTitle(),
			notification.getTitle(),
			notification.getBody(),
			notification.getDeepLink(),
			groupOf(recipient.getNotifiedAt(), LocalDate.now(KST)),
			actor == null ? List.of() : List.of(new ActorResponse(actorId(actor), actorNickname(actor), null)),
			actor == null ? 0 : 1);
	}

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 오늘 / 이번 주(최근 7일) / 그 전. */
	public static String groupOf(LocalDateTime notifiedAt, LocalDate today) {
		if (notifiedAt == null) {
			return GROUP_EARLIER;
		}
		LocalDate day = notifiedAt.toLocalDate();
		if (!day.isBefore(today)) {
			return GROUP_TODAY;
		}
		return day.isAfter(today.minusDays(7)) ? GROUP_THIS_WEEK : GROUP_EARLIER;
	}

	/** 같은 묶음에 든 줄들을 한 줄로 — 대표는 첫 줄, 얼굴은 2개까지, 읽음은 전부 읽었을 때만. */
	public NotificationResponse merged(List<NotificationResponse> group) {
		List<ActorResponse> faces = new ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		int count = 0;
		boolean allRead = true;
		for (NotificationResponse row : group) {
			allRead &= row.read();
			for (ActorResponse face : row.actors()) {
				if (face.memberId() == null || seen.add(face.memberId())) {
					count++;
					if (faces.size() < MAX_ACTORS) {
						faces.add(face);
					}
				}
			}
		}
		return new NotificationResponse(notificationId, type, allRead, notifiedAt, actorId, actorNickname, targetType,
			targetId, targetTitle, title, body, link, this.group, faces, count);
	}

	public NotificationResponse withActors(List<ActorResponse> replaced) {
		return new NotificationResponse(notificationId, type, read, notifiedAt, actorId, actorNickname, targetType,
			targetId, targetTitle, title, body, link, group, replaced, actorCount);
	}

	private static Long actorId(Member actor) {
		if (actor == null || actor.isDeleted()) {
			return null;
		}
		return actor.getId();
	}

	private static String actorNickname(Member actor) {
		if (actor == null) {
			return null;
		}
		return actor.isDeleted() ? WITHDRAWN_NICKNAME : actor.getNickname();
	}
}
