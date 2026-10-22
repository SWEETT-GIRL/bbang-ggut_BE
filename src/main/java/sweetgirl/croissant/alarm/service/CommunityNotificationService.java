package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 팔로우 · 커뮤니티 활동 알림. 문장은 "회원님"(피그마 05).
 *
 * <p>같은 사람이 같은 대상에 같은 알림을 두 번 만들지 않는다 — 팔로우/언팔 · 좋아요/취소 · 공개/비공개를 반복해도 알림은 한 번.
 * DB 의 부분 유니크(uk_notification_actor_target)가 마지막 방어선이고, 여기서는 먼저 찾아보고 있으면 아무것도 안 한다.
 * 차단 관계는 부르는 쪽(팔로우 · 좋아요 · 댓글)이 이미 막아 여기까지 오지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityNotificationService {

	static final String FOLLOW_TITLE = "%s님이 회원님을 팔로우해요";
	static final String MUTUAL_TITLE = "%s님과 서로 팔로우해요";
	static final String LIKE_TITLE = "%s님이 회원님의 빵로그를 좋아해요";
	static final String COMMENT_TITLE = "%s님이 댓글을 남겼어요";
	static final String MENTION_TITLE = "%s님이 회원님을 언급했어요";
	static final String FOLLOWING_RECORD_TITLE = "%s님이 새 빵로그를 올렸어요";
	static final int COMMENT_PREVIEW_LENGTH = 40;

	private final NotificationRepository notificationRepository;
	private final NotificationRecorder notificationRecorder;
	private final ApplicationEventPublisher eventPublisher;
	private final MemberRepository memberRepository;
	private final MemberFollowRepository memberFollowRepository;

	public void notifyFollow(Member actor, Member target) {
		once(NotificationType.FOLLOW, actor, NotificationTargetType.MEMBER, actor.getId(), actor.getNickname(),
			FOLLOW_TITLE.formatted(actor.getNickname()), null, profileLink(actor.getId()), List.of(target));
	}

	/** 초대 코드 · 일행 합류로 서로 팔로우됐을 때 — 둘 다 받는다. */
	public void notifyMutualFollow(Member first, Member second) {
		once(NotificationType.INVITE_MUTUAL, first, NotificationTargetType.MEMBER, first.getId(), first.getNickname(),
			MUTUAL_TITLE.formatted(first.getNickname()), null, profileLink(first.getId()), List.of(second));
		once(NotificationType.INVITE_MUTUAL, second, NotificationTargetType.MEMBER, second.getId(),
			second.getNickname(), MUTUAL_TITLE.formatted(second.getNickname()), null, profileLink(second.getId()),
			List.of(first));
	}

	public void notifyRecordLike(Member actor, BreadRecord record) {
		if (record.getMember().getId().equals(actor.getId())) {
			return;
		}
		once(NotificationType.RECORD_LIKE, actor, NotificationTargetType.RECORD, record.getId(), record.getStoreName(),
			LIKE_TITLE.formatted(actor.getNickname()), null, recordLink(record.getId()), List.of(record.getMember()));
	}

	/** 댓글 하나에 알림 하나(묶지 않음). 내 빵로그에 내가 쓴 댓글은 알리지 않는다. */
	public void notifyRecordComment(BreadRecordComment comment) {
		Member author = comment.getRecord().getMember();
		if (author.getId().equals(comment.getMember().getId())) {
			return;
		}
		once(NotificationType.RECORD_COMMENT, comment.getMember(), NotificationTargetType.COMMENT, comment.getId(),
			comment.getRecord().getStoreName(), COMMENT_TITLE.formatted(comment.getMember().getNickname()),
			preview(comment.getContent()), commentLink(comment), List.of(author));
	}

	public void notifyMention(BreadRecordComment comment, Member mentioned) {
		if (mentioned.getId().equals(comment.getMember().getId())) {
			return;
		}
		once(NotificationType.COMMENT_MENTION, comment.getMember(), NotificationTargetType.COMMENT, comment.getId(),
			comment.getRecord().getStoreName(), MENTION_TITLE.formatted(comment.getMember().getNickname()),
			preview(comment.getContent()), commentLink(comment), List.of(mentioned));
	}

	/** 공개로 올린 빵로그를 팔로워 전원에게 — 기록당 한 번(공개/비공개 토글 반복 무시). */
	public void notifyFollowingNewRecord(BreadRecord record) {
		Member author = record.getMember();
		List<Long> followerIds = memberFollowRepository.findFollowerIds(author.getId());
		if (followerIds.isEmpty()) {
			return;
		}
		List<Member> followers = memberRepository.findAllById(followerIds).stream()
			.filter(member -> !member.isDeleted())
			.toList();
		once(NotificationType.FOLLOWING_NEW_RECORD, author, NotificationTargetType.RECORD, record.getId(),
			record.getStoreName(), FOLLOWING_RECORD_TITLE.formatted(author.getNickname()), record.getStoreName(),
			recordLink(record.getId()), followers);
	}

	private void once(NotificationType type, Member actor, NotificationTargetType targetType, Long targetId,
			String targetTitle, String title, String body, String deepLink, List<Member> recipients) {
		if (recipients.isEmpty() || notificationRepository
			.findByActorIdAndTypeAndTargetTypeAndTargetId(actor.getId(), type, targetType, targetId).isPresent()) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		Notification saved = notificationRecorder.recordForMembers(
			Notification.communityActivity(type, actor, targetType, targetId, targetTitle, title, body, deepLink),
			recipients, now);
		for (Member recipient : recipients) {
			eventPublisher.publishEvent(PushRequestedEvent.to(saved.getId(), recipient.getId()));
		}
	}

	static String preview(String content) {
		if (content == null) {
			return null;
		}
		return content.length() <= COMMENT_PREVIEW_LENGTH ? content
			: content.substring(0, COMMENT_PREVIEW_LENGTH) + "…";
	}

	private static String profileLink(Long memberId) {
		return "/profile/" + memberId;
	}

	private static String recordLink(Long recordId) {
		return "/bbanglog/post/" + recordId;
	}

	private static String commentLink(BreadRecordComment comment) {
		return recordLink(comment.getRecord().getId()) + "?comments=1&commentId=" + comment.getId();
	}
}
