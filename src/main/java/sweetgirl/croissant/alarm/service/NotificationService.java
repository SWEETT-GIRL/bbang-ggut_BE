package sweetgirl.croissant.alarm.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

/**
 * 알림의 적재·조회. 종류가 무엇이든 한 알림함에 쌓이고 한 목록으로 나간다.
 *
 * <p>적재는 항상 둘로 나뉜다 — 알릴 내용 한 벌({@link Notification})과 그것을 받는 사람의
 * 우편함 줄({@link NotificationRecipient}). 공지는 앞이 하나이고 뒤가 회원 수만큼이며,
 * 활동 알림은 둘 다 하나다.
 *
 * <p>푸시는 여기서 보내지 않는다. FCM 응답을 기다리는 동안 이 트랜잭션이 DB 커넥션을 잡고 있게
 * 되기 때문이다. 커밋이 끝난 뒤 별도로 나간다.
 *
 * <p>구버전 앱을 위해 "저장 알림만" 보는 길이 따로 열려 있다. 스토어에 깔린 옛 앱은 응답에서
 * actorNickname·targetTitle을 꺼내 문장을 조립하는데, 공지에는 그 재료가 없어서 빈칸투성이
 * 문장이 뜬다. 그 앱들이 다 올라오면 by-type 경로를 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

	private static final int MAX_PAGE_SIZE = 50;

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notificationRepository;
	private final NotificationRecipientRepository recipientRepository;
	private final NotificationRecorder notificationRecorder;
	private final ApplicationEventPublisher eventPublisher;
	private final CourseRepository courseRepository;
	private final MemberRepository memberRepository;
	private final CharacterFaceReader characterFaceReader;

	// ── 조회 ────────────────────────────────────────────────────────────────

	public NotificationListResponse getMine(Long memberId, int page, int size) {
		return toListResponse(recipientRepository.findMine(memberId, pageable(page, size)));
	}

	/** 구버전 앱 전용 — 저장 알림만. */
	public NotificationListResponse getMineByType(Long memberId, NotificationType type,
			int page, int size) {
		return toListResponse(
			recipientRepository.findMineByType(memberId, type, pageable(page, size)));
	}

	public UnreadCountResponse getUnreadCount(Long memberId) {
		return new UnreadCountResponse(recipientRepository.countUnread(memberId));
	}

	public UnreadCountResponse getUnreadCountByType(Long memberId, NotificationType type) {
		return new UnreadCountResponse(recipientRepository.countUnreadByType(memberId, type));
	}

	// ── 읽음 ────────────────────────────────────────────────────────────────

	/**
	 * 읽음 처리. 남의 알림 id를 섞어 보내도 member로 걸러져 조용히 무시된다 —
	 * 남의 알림을 읽음 처리하려 한 것이 의미 있는 실패는 아니라서 예외로 만들지 않는다.
	 */
	@Transactional
	public void markAsRead(Long memberId, CourseNotificationReadRequest request) {
		LocalDateTime now = LocalDateTime.now();
		if (request == null || request.isAll()) {
			recipientRepository.markAllAsRead(memberId, now);
			return;
		}
		List<NotificationRecipient> picked = recipientRepository.findByMemberIdAndIdIn(memberId,
			request.getNotificationIds());
		picked.forEach(recipient -> recipient.markAsRead(now));
		// 알림함에서 묶인 줄의 대표 id 하나로 읽음이 오면 같은 묶음(같은 날 · 종류 · 대상)도 같이 읽는다
		for (NotificationRecipient recipient : picked) {
			Notification notification = recipient.getNotification();
			if (notification.getType().isCommunityActivity() && recipient.getNotifiedAt() != null) {
				LocalDate day = recipient.getNotifiedAt().toLocalDate();
				recipientRepository.markGroupAsRead(memberId, notification.getType(), notification.getTargetType(),
					notification.getTargetId(), day.atStartOfDay(), day.plusDays(1).atStartOfDay(), now);
			}
		}
	}

	/**
	 * 구버전 앱의 전체 읽음. 그 앱이 보지도 못하는 공지까지 읽음으로 만들면,
	 * 나중에 업데이트했을 때 못 본 공지가 이미 읽은 것으로 남는다.
	 */
	@Transactional
	public void markAsReadByType(Long memberId, NotificationType type,
			CourseNotificationReadRequest request) {
		LocalDateTime now = LocalDateTime.now();
		if (request == null || request.isAll()) {
			recipientRepository.markAllAsReadByType(memberId, type, now);
			return;
		}
		recipientRepository.findByMemberIdAndIdIn(memberId, request.getNotificationIds()).stream()
			.filter(recipient -> recipient.getNotification().getType() == type)
			.forEach(recipient -> recipient.markAsRead(now));
	}

	// ── 적재 ────────────────────────────────────────────────────────────────

	/**
	 * "누가 내 빵투어를 저장했다"를 쌓는다. 같은 사람이 같은 코스를 다시 저장했으면 새 줄을
	 * 만들지 않고 시각만 갱신해 맨 위로 올린다.
	 *
	 * <p>자기 코스 저장과 비공개 코스 저장은 {@code SavedCourseCommandService}가 이미 막으므로
	 * 여기서 다시 거르지 않는다. 다만 적재는 저장하기와 다른 트랜잭션이라, 그 사이 코스나 회원이
	 * 사라졌을 수 있어 조회 실패는 조용히 넘긴다 — 알림 하나 때문에 로그를 시끄럽게 할 일이 아니다.
	 */
	@Transactional
	public void recordCourseSaved(Long courseId, Long actorId, Long ownerId) {
		LocalDateTime now = LocalDateTime.now();

		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId).orElse(null);
		String title = course == null ? null : course.getTitle();

		notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			actorId, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, courseId)
			.ifPresentOrElse(
				existing -> renewCourseSaved(existing, ownerId, title, now),
				() -> createCourseSaved(course, actorId, ownerId, now));
	}

	private void renewCourseSaved(Notification notification, Long ownerId, String title,
			LocalDateTime now) {
		notification.refreshTargetTitle(title);
		recipientRepository.findByNotificationIdAndMemberId(notification.getId(), ownerId)
			.ifPresent(recipient -> recipient.renew(now));
	}

	private void createCourseSaved(Course course, Long actorId, Long ownerId, LocalDateTime now) {
		Member actor = memberRepository.findByIdAndDeletedAtIsNull(actorId).orElse(null);
		Member owner = memberRepository.findByIdAndDeletedAtIsNull(ownerId).orElse(null);
		if (course == null || actor == null || owner == null) {
			log.info("빵투어 저장 알림 skip — 대상이 사라졌다 [actorId={}, ownerId={}]", actorId, ownerId);
			return;
		}
		Notification notification = Notification.courseSaved(actor, course.getId(), course.getTitle());
		Notification recorded = notificationRecorder.recordFor(notification, owner, now);
		eventPublisher.publishEvent(PushRequestedEvent.to(recorded.getId(), owner.getId()));
	}

	/**
	 * 전체 공지. 내용은 한 줄이고 우편함만 사람 수만큼 늘어난다.
	 *
	 * <p>예전에는 내용까지 사람 수만큼 복사됐다. 문구를 고치려면 수만 행을 UPDATE 해야 했고,
	 * "이 공지"라고 가리킬 id가 없어 발송 결과를 묶어 볼 수도 없었다.
	 */
	@Transactional
	public NoticeSendResponse sendNotice(NoticeSendRequest request) {
		LocalDateTime now = LocalDateTime.now();

		NotificationChannel channel = request.channelOrDefault();
		Notification notification = Notification.announcement(
			NotificationType.NOTICE, channel, request.getTitle(), request.getBody(),
			request.getLink());

		NotificationRecorder.Broadcast broadcast = notificationRecorder.recordForAllMembers(notification, now);

		// 알림함에는 어느 쪽이든 쌓인다. 울릴지 말지만 여기서 갈린다.
		if (channel == NotificationChannel.PUSH) {
			eventPublisher.publishEvent(
				PushRequestedEvent.toEveryone(broadcast.notification().getId()));
		}

		log.info("공지 발송 [notificationId={}, title={}, recipients={}, channel={}]",
			broadcast.notification().getId(), request.getTitle(), broadcast.recipients(), channel);
		return new NoticeSendResponse(broadcast.recipients(), channel);
	}

	private Pageable pageable(int page, int size) {
		return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
	}

	private NotificationListResponse toListResponse(Slice<NotificationRecipient> slice) {
		List<NotificationResponse> rows = slice.getContent().stream().map(NotificationResponse::from).toList();
		return new NotificationListResponse(withFaces(groupCommunityRows(rows)), slice.hasNext());
	}

	/**
	 * 사람 사이 활동 알림은 같은 날 · 같은 종류 · 같은 대상이 이어지면 한 줄로 묶는다("OO님 외 2명이 좋아해요").
	 * 댓글 · 멘션은 하나씩이라 묶지 않는다(대상이 댓글 id 라 어차피 안 겹친다). 페이지 경계는 넘지 않는다.
	 */
	static List<NotificationResponse> groupCommunityRows(List<NotificationResponse> rows) {
		List<NotificationResponse> result = new ArrayList<>();
		int index = 0;
		while (index < rows.size()) {
			NotificationResponse head = rows.get(index);
			int end = index + 1;
			if (head.type().isCommunityActivity() && head.targetType() != NotificationTargetType.COMMENT) {
				while (end < rows.size() && sameGroup(head, rows.get(end))) {
					end++;
				}
			}
			result.add(end - index == 1 ? head : head.merged(rows.subList(index, end)));
			index = end;
		}
		return result;
	}

	private static boolean sameGroup(NotificationResponse head, NotificationResponse other) {
		return head.type() == other.type()
			&& head.targetType() == other.targetType()
			&& java.util.Objects.equals(head.targetId(), other.targetId())
			&& head.notifiedAt() != null && other.notifiedAt() != null
			&& head.notifiedAt().toLocalDate().equals(other.notifiedAt().toLocalDate());
	}

	/** 얼굴은 한 번에 읽는다 — 줄마다 쿼리하지 않는다. */
	private List<NotificationResponse> withFaces(List<NotificationResponse> rows) {
		List<Long> memberIds = rows.stream()
			.flatMap(row -> row.actors().stream())
			.map(NotificationResponse.ActorResponse::memberId)
			.filter(java.util.Objects::nonNull)
			.distinct()
			.toList();
		if (memberIds.isEmpty()) {
			return rows;
		}
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(memberIds);
		return rows.stream()
			.map(row -> row.withActors(row.actors().stream()
				.map(face -> new NotificationResponse.ActorResponse(face.memberId(), face.nickname(),
					face.memberId() == null ? null
						: faces.getOrDefault(face.memberId(), CharacterFace.NONE).characterCode()))
				.toList()))
			.toList();
	}
}
